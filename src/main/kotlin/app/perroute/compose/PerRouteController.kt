package app.perroute.compose

import app.perroute.model.AppRoutes
import app.perroute.model.AppState
import app.perroute.model.ConnectionMode
import app.perroute.model.ProxyNode
import app.perroute.model.RoutingMode
import app.perroute.model.RouteRule
import app.perroute.model.ConnectionProfile
import app.perroute.parser.ParseResult
import app.perroute.persistence.AppStateStore
import app.perroute.service.AppDirectories
import app.perroute.service.AppProxyLauncher
import app.perroute.service.CoreInstaller
import app.perroute.service.ProcessDiscovery
import app.perroute.service.InstalledApplicationDiscovery
import app.perroute.service.ProxiFyreManager
import app.perroute.service.ServerLatencyProbe
import app.perroute.service.SecureUpdateService
import app.perroute.service.SubscriptionService
import app.perroute.service.XrayConfigGenerator
import app.perroute.service.RealTunnelProbe
import app.perroute.service.RoutePlanner
import app.perroute.service.TrafficHistoryStore
import app.perroute.service.QrImportService
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.net.NetworkInterface
import app.perroute.service.RouteRuleEvaluator
import app.perroute.util.Json
import app.perroute.backup.BackupCodec
import app.perroute.backup.BackupService
import app.perroute.gateway.AppGateway
import app.perroute.inspector.ConnectionInspector
import app.perroute.model.BandwidthPolicy
import app.perroute.quality.QualityScore
import app.perroute.routing.AppRouteMode
import app.perroute.routing.AutoCandidatePool
import app.perroute.routing.AutoSelection
import app.perroute.routing.AutoServerSelector
import app.perroute.routing.RouteHealthTracker
import app.perroute.runtime.QualityService
import app.perroute.runtime.RouteProbe
import app.perroute.runtime.RouteRecoveryManager
import app.perroute.runtime.RouteTarget
import java.util.function.Consumer
import java.util.prefs.Preferences

/** Which components are installed and whether the process is elevated. */
data class EnvironmentState(
    val coreReady: Boolean,
    val perAppReady: Boolean,
    val elevated: Boolean
)

/** The outcome of a user action and the sentence shown for it. */
data class ActionResult(val ok: Boolean, val message: String)

/** Live counters of one selected application. */
data class AppTraffic(
    val path: String,
    val downloadBytes: Long = 0,
    val uploadBytes: Long = 0,
    val downloadBytesPerSecond: Long = 0,
    val uploadBytesPerSecond: Long = 0,
    val active: Boolean = false
)

/** Live totals of the current session. */
data class TrafficSnapshot(
    val downloadBytesPerSecond: Long = 0,
    val uploadBytesPerSecond: Long = 0,
    val totalDownloadBytes: Long = 0,
    val totalUploadBytes: Long = 0,
    val connectedSeconds: Long = 0,
    val latencyMillis: Long? = null,
    val applications: List<AppTraffic> = emptyList()
)

/** One sample of the live speed graph. */
data class TrafficPoint(
    val downloadBytesPerSecond: Long,
    val uploadBytesPerSecond: Long,
    val atMillis: Long = System.currentTimeMillis()
)

/** Where the connection is in its life cycle. Routing settings are editable only in DISCONNECTED. */
enum class ConnectionPhase { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

/** The public address seen through one route, or why it could not be read. */
data class RouteReport(val ip: String? = null, val country: String? = null, val error: String? = null)

/** The states a route check can be in. */
enum class RouteCheckState { NOT_CHECKED, CHECKING, PASSED, FAILED }

/** The last thing actually observed on one route. Nothing here is inferred from another route. */
data class RouteCheck(
    val state: RouteCheckState = RouteCheckState.NOT_CHECKED,
    val atMillis: Long = 0L,
    val latencyMillis: Long? = null,
    val publicIp: String? = null,
    val country: String? = null,
    val detail: String = ""
) {
    fun stale(now: Long = System.currentTimeMillis()): Boolean =
        (state == RouteCheckState.PASSED || state == RouteCheckState.FAILED) &&
            now - atMillis > PerRouteController.ROUTE_CHECK_STALE_MILLIS
}

/**
 * A server the running core is carrying traffic for, and the loopback port that leads to it. [id] is the
 * server key for the main route and shared custom routes, and `auto:<path>` for an application in Auto mode,
 * which always has a route of its own. [ruleTag] names the core rule that sends the route's applications out.
 */
data class ActiveRoute(
    val node: ProxyNode,
    val main: Boolean,
    val applications: List<String>,
    val checkPort: Int,
    val id: String = node.key(),
    val auto: Boolean = false,
    val ruleTag: String = XrayConfigGenerator.applicationRouteRuleTag(-1)
)

/** Whether a per-app limit is being enforced right now, and why not when it is not. */
enum class BandwidthState { UNLIMITED, ENFORCED, THROTTLING, PENDING, NOT_ENFORCED }

data class BandwidthStatus(val policy: BandwidthPolicy, val state: BandwidthState, val detail: String,
                           val downloadBytesPerSecond: Long = 0, val uploadBytesPerSecond: Long = 0)

/** The health of one live route as the recovery manager sees it, with its timeline. */
data class RouteHealthView(val route: ActiveRoute, val health: RouteHealthTracker.Snapshot?, val monitoring: Boolean)

/** A restore that has been read and checked, waiting for the user's decision. Nothing has changed yet. */
class RestoreRequest(val file: Path, val preview: BackupService.RestorePreview)

/** What Windows resolves with. It describes the machine, not any single route. */
data class DnsObservation(val systemServers: List<String>, val publicResolver: String?, val atMillis: Long)

/** A rule that a preset adds. */
data class ScenarioRule(
    val name: String,
    val type: RouteRule.MatchType,
    val value: String,
    val action: RouteRule.Action
)

data class Scenario(
    val id: String,
    val title: String,
    val summary: String,
    val highlights: List<String>,
    val killSwitch: Boolean,
    val autoReconnect: Boolean,
    val autoFailover: Boolean,
    val fastestOnConnect: Boolean,
    val rules: List<ScenarioRule>
)

/** One automation switch before and after applying a preset. */
data class SettingChange(val label: String, val from: Boolean, val to: Boolean)

/** Everything applying a preset would do, computed without changing anything. */
data class ScenarioPreview(
    val scenario: Scenario,
    val settings: List<SettingChange>,
    val addedRules: List<ScenarioRule>,
    val removedRules: List<RouteRule>,
    val alreadyActive: Boolean
)

/** A parsed server and whether an identical one is already saved. */
data class ImportCandidate(val node: ProxyNode, val alreadySaved: Boolean)

/** Parsed servers waiting for the user to confirm. Nothing is stored until [PerRouteController.commitImport]. */
class ImportPreview(
    val source: String,
    val replaceSource: Boolean,
    val result: ParseResult,
    val candidates: List<ImportCandidate>,
    val removedCount: Int
) {
    val warnings: List<String> get() = result.warnings()
    val newCount: Int get() = candidates.count { !it.alreadySaved }
}

/** Either a preview to confirm or the reason nothing could be imported. */
data class ImportOutcome(val preview: ImportPreview? = null, val error: String? = null)

/** Owns the saved state and the connection. Every screen goes through it; it is the only place that decides whether a change is allowed. */
class PerRouteController @JvmOverloads constructor(
    private val state: AppState,
    private val store: AppStateStore,
    private val backend: ConnectionBackend = SystemConnectionBackend(),
    private val preferences: Preferences = Preferences.userNodeForPackage(PerRouteController::class.java),
    recoveryPolicy: RouteHealthTracker.Policy = RouteHealthTracker.Policy.DEFAULT,
    private val autoIntervalMillis: Long = AUTO_INTERVAL_MILLIS
) : AutoCloseable {
    private val subscriptionService by lazy { SubscriptionService() }
    private val coreInstaller by lazy { CoreInstaller() }
    private val processDiscovery = Any()
    private val installedApplicationDiscovery = Any()
    private val configGenerator by lazy { XrayConfigGenerator() }
    private val latencyProbe by lazy { ServerLatencyProbe() }
    private val realTunnelProbe by lazy { RealTunnelProbe() }
    private val qrImport by lazy { QrImportService() }
    private val history by lazy { TrafficHistoryStore(AppDirectories.dataDirectory().resolve("traffic-history.json")) }
    private val updateService by lazy { SecureUpdateService() }
    private val proxiFyre by lazy { ProxiFyreManager() }
    private val logListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val stateListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    @Volatile private var activeMode: ConnectionMode? = null
    @Volatile private var connectionReady = false
    @Volatile private var phase = ConnectionPhase.DISCONNECTED
    @Volatile private var traffic = TrafficSnapshot()
    @Volatile private var connectedAtMillis = 0L
    private val telemetryGeneration = AtomicLong()
    @Volatile private var availableUpdate: SecureUpdateService.UpdateInfo? = null
    private val updateDownloading = AtomicBoolean(false)
    private val reconnectRunning = AtomicBoolean(false)
    private val trafficPoints = CopyOnWriteArrayList<TrafficPoint>()
    @Volatile private var desiredConnected = false
    @Volatile private var closing = false
    @Volatile private var updateProgress = 0
    @Volatile private var updateDownloadMessage = ""
    @Volatile private var downloadedUpdate: Path? = null
    @Volatile private var runningAppCache = emptyList<ProcessDiscovery.RunningApplication>()
    @Volatile private var runningAppCacheAt = 0L
    @Volatile private var installedAppCache = emptyList<InstalledApplicationDiscovery.InstalledApplication>()
    @Volatile private var installedAppCacheAt = 0L
    @Volatile private var latencyCache = emptyMap<String, Long>()
    @Volatile private var speedCache = emptyMap<String, Long>()
    @Volatile private var activeRoutes = emptyList<ActiveRoute>()
    @Volatile private var routeChecks = emptyMap<String, RouteCheck>()
    @Volatile private var dnsObservation: DnsObservation? = null
    private val routeCheckLock = Any()
    private val savePending = AtomicBoolean(false)
    private val saveExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "perroute-state-writer").apply { isDaemon = true }
    }

    // ---- Version 3: quality, Auto routing, route recovery, traffic gateway, inspector and backups.
    private val quality by lazy { QualityService(AppDirectories.dataDirectory().resolve("quality-history.json")) }
    private val autoRouting by lazy { AutoRouting(quality) }
    private val inspector = ConnectionInspector()
    private val recovery = RouteRecoveryManager(probe = ::probeRoute, onResult = ::onRouteResult, policy = recoveryPolicy)
    /** The config the running core corresponds to, including outbounds renamed by runtime changes. */
    @Volatile private var runningConfig: String? = null
    @Volatile private var runningPrepared: PreparedConnection? = null
    @Volatile private var gatewayActive = false
    private val autoLoopGeneration = AtomicLong()
    /** Failovers are at least one full recovery cycle apart (about a minute by default), which prevents flapping. */
    private val failoverIntervalMillis = recoveryPolicy.backoffMaxMillis() * 2

    init {
        if (state.connectionMode() == ConnectionMode.TUN_COMPATIBILITY) {
            state.setConnectionMode(ConnectionMode.SYSTEM_PROXY)
            save()
        }
        if (deduplicateNodes()) save()
        initializeVersionThreeDefaults()
        runCatching { backend.restoreSystemProxy() }
            .onFailure { log("Could not restore stale system proxy settings: ${message(it)}") }
        backend.addLogListener(Consumer { line -> log(line) })
        // The inspector reads the core's own per-connection lines; they are kept in memory only.
        backend.addLogListener(Consumer { line -> if (connectionReady) inspector.onLogLine(line, System.currentTimeMillis()) })
        backend.addGatewayListener(Consumer { connection -> inspector.onGatewayConnection(connection, System.currentTimeMillis()) })
        backend.addRunningListener(Consumer { running ->
            if (!running && phase == ConnectionPhase.CONNECTED) {
                val stoppedMode = activeMode
                connectionReady = false
                activeMode = null
                phase = ConnectionPhase.DISCONNECTED
                runCatching { backend.stopGateway() }
                stopTelemetry()
                if (stoppedMode == ConnectionMode.NATIVE_PER_APP) {
                    if (!killSwitch()) Thread.ofVirtual().name("perroute-sidecar-cleanup").start {
                        runCatching { backend.restorePerApp() }
                    }
                }
                if (stoppedMode == ConnectionMode.SYSTEM_PROXY) {
                    Thread.ofVirtual().name("perroute-system-proxy-cleanup").start {
                        runCatching { backend.restoreSystemProxy() }
                    }
                }
                log("The connection core stopped.")
                stateListeners.forEach { it(false) }
                scheduleReconnect()
            }
        })
        if (updateFeedUrl().isNotBlank()) {
            Thread.ofVirtual().name("perroute-update-check").start {
                val result = checkForUpdate()
                if (result.ok && availableUpdateVersion() != null) log(result.message)
            }
        }
        startNetworkMonitor()
        startQualityMeasurements()
        recoverFromUnexpectedExit()
        Runtime.getRuntime().addShutdownHook(Thread({
            if (!closing) {
                runCatching { backend.stopGateway() }
                runCatching { backend.stop() }
                runCatching { backend.restoreSystemProxy() }
                runCatching { if (!killSwitch()) backend.stopPerAppSidecar() }
                runCatching { Files.deleteIfExists(sessionMarker) }
            }
        }, "perroute-shutdown"))
    }

    private val sessionMarker: Path get() = AppDirectories.dataDirectory().resolve("session.active")

    private fun recoverFromUnexpectedExit() {
        val unclean = Files.exists(sessionMarker)
        runCatching { Files.deleteIfExists(sessionMarker) }
        if (unclean) log("PerRoute did not shut down cleanly last time. Network settings were restored.")
        Thread.ofVirtual().name("perroute-crash-recovery").start {
            runCatching { backend.stopOrphan() }
            if (unclean && !killSwitch()) runCatching { backend.restorePerApp() }
        }
    }

    private fun markSession(active: Boolean) {
        runCatching {
            if (active) Files.writeString(sessionMarker, System.currentTimeMillis().toString())
            else Files.deleteIfExists(sessionMarker)
        }
    }

    private var startupProblem: String? = null
    fun reportStartupProblem(message: String) { startupProblem = message; log(message) }
    fun takeStartupProblem(): String? = startupProblem.also { startupProblem = null }

    fun nodes(): List<ProxyNode> = synchronized(state) { state.nodes().toList() }
    fun applications(): List<String> = synchronized(state) { state.appPaths().toList() }
    fun subscriptions(): List<String> = synchronized(state) { state.subscriptionUrls().toList() }
    fun nodeSource(key: String): String = synchronized(state) { state.nodeSources()[key] ?: IMPORTED_SOURCE }
    fun isDarkTheme(): Boolean = preferences.getBoolean("darkTheme", true)
    fun setDarkTheme(value: Boolean) { preferences.putBoolean("darkTheme", value) }
    /**
     * The interface design, kept apart from the theme and every other preference. Nothing saved means
     * the New design, on a fresh install and after an upgrade alike; anything unrecognised also reads as New.
     */
    fun uiStyle(): String = if (preferences.get(PREF_UI_STYLE, UI_STYLE_NEW) == UI_STYLE_SIMPLE) UI_STYLE_SIMPLE else UI_STYLE_NEW
    fun setUiStyle(value: String) { preferences.put(PREF_UI_STYLE, if (value == UI_STYLE_SIMPLE) UI_STYLE_SIMPLE else UI_STYLE_NEW) }
    fun selectedNodeKey(): String = state.selectedNodeKey()
    fun connectionMode(): ConnectionMode = state.connectionMode()
    fun routingMode(): RoutingMode = state.routingMode()
    fun isConnected(): Boolean = connectionReady && backend.isRunning()
    fun connectionPhase(): ConnectionPhase = phase
    fun trafficSnapshot(): TrafficSnapshot = traffic
    fun trafficPoints(): List<TrafficPoint> = trafficPoints.toList()
    fun trafficHistory(): TrafficHistoryStore.Snapshot = history.snapshot()
    fun trafficReport(applicationPath: String = ""): TrafficHistoryStore.Report = history.report(applicationPath)
    fun clearTrafficHistory() { history.clear(); trafficPoints.clear() }
    fun autoFailover(): Boolean = preferences.getBoolean("autoFailover", true)
    fun setAutoFailover(value: Boolean) = preferences.putBoolean("autoFailover", value)
    fun fastestOnConnect(): Boolean = preferences.getBoolean("fastestOnConnect", false)
    fun setFastestOnConnect(value: Boolean) = preferences.putBoolean("fastestOnConnect", value)
    fun autoSpeedTest(): Boolean = preferences.getBoolean("autoSpeedTest", true)
    fun setAutoSpeedTest(value: Boolean) = preferences.putBoolean("autoSpeedTest", value)
    fun autoReconnect(): Boolean = preferences.getBoolean("autoReconnect", true)
    fun setAutoReconnect(value: Boolean) = preferences.putBoolean("autoReconnect", value)
    fun killSwitch(): Boolean = preferences.getBoolean("perAppKillSwitch", false)
    fun setKillSwitch(value: Boolean) {
        preferences.putBoolean("perAppKillSwitch", value)
        if (!value && !isConnected()) Thread.ofVirtual().start { runCatching { backend.restorePerApp() } }
    }
    fun updateFeedUrl(): String = preferences.get("updateFeedUrl", "")
    fun setUpdateFeedUrl(value: String) = preferences.put("updateFeedUrl", value.trim())
    fun availableUpdateVersion(): String? = availableUpdate?.takeIf { it.newer() }?.version()
    fun isUpdateDownloading(): Boolean = updateDownloading.get()
    fun updateDownloadProgress(): Int = updateProgress
    fun updateDownloadMessage(): String = updateDownloadMessage
    fun downloadedUpdatePath(): Path? = downloadedUpdate?.takeIf(Files::isRegularFile)

    fun checkForUpdate(): ActionResult {
        val feed = updateFeedUrl()
        if (feed.isBlank()) return ActionResult(false, "Enter your signed HTTPS update feed URL first.")
        return try {
            val update = updateService.check(feed)
            availableUpdate = update.takeIf { it.newer() }
            if (update.newer()) ActionResult(true, "PerRoute ${update.version()} is available.")
            else ActionResult(true, "PerRoute ${SecureUpdateService.CURRENT_VERSION} is up to date.")
        } catch (error: Throwable) {
            availableUpdate = null
            ActionResult(false, message(error))
        }
    }

    fun downloadAvailableUpdate(progress: (Int) -> Unit): ActionResult {
        val update = availableUpdate ?: return ActionResult(false, "Check for a new version first.")
        return try {
            val path = updateService.download(update, Consumer(progress))
            downloadedUpdate = path
            ActionResult(true, "Verified update downloaded to ${path.parent}.")
        } catch (error: Throwable) {
            ActionResult(false, message(error))
        }
    }

    fun downloadAvailableUpdateInBackground(): ActionResult {
        val update = availableUpdate ?: return ActionResult(false, "Check for a new version first.")
        if (!updateDownloading.compareAndSet(false, true)) return ActionResult(true, "The update is already downloading.")
        updateProgress = 0
        updateDownloadMessage = "Downloading PerRoute ${update.version()}…"
        Thread.ofVirtual().name("perroute-update-download").start {
            val result = downloadAvailableUpdate { value -> updateProgress = value }
            updateDownloadMessage = result.message
            updateDownloading.set(false)
            log(result.message)
        }
        return ActionResult(true, "Update download started in the background.")
    }

    fun launchDownloadedUpdate(): ActionResult {
        val installer = downloadedUpdatePath()
            ?: return ActionResult(false, "Download and verify an update first.")
        return try {
            ProcessBuilder(installer.toString()).start()
            ActionResult(true, "Verified installer started.")
        } catch (error: Throwable) {
            ActionResult(false, message(error))
        }
    }

    // ============================================================================ version 3

    /**
     * New automatic behavior is opt-in for installations that ran PerRoute 2: background quality checks, route
     * monitoring and the traffic gateway start switched off there and on for a fresh installation. The choice
     * is written once, so it never changes by itself later.
     */
    private fun initializeVersionThreeDefaults() {
        if (preferences.getBoolean(PREF_V3_INITIALIZED, false)) return
        val fresh = !synchronized(state) { state.migratedFromV2() }
        preferences.putBoolean(PREF_QUALITY_BACKGROUND, fresh)
        preferences.putBoolean(PREF_ROUTE_MONITORING, fresh)
        preferences.putBoolean(PREF_TRAFFIC_GATEWAY, fresh)
        preferences.putBoolean(PREF_V3_INITIALIZED, true)
        if (!fresh) log("Your PerRoute 2 setup was kept as it was. New automatic features stay off until you turn them on in Settings > Routing.")
    }

    fun qualityBackground(): Boolean = preferences.getBoolean(PREF_QUALITY_BACKGROUND, true)
    fun setQualityBackground(value: Boolean) {
        preferences.putBoolean(PREF_QUALITY_BACKGROUND, value)
        if (value) quality.requestPass()
    }
    fun routeMonitoring(): Boolean = preferences.getBoolean(PREF_ROUTE_MONITORING, true)
    fun setRouteMonitoring(value: Boolean) {
        preferences.putBoolean(PREF_ROUTE_MONITORING, value)
        recovery.setContinuous(value)
    }
    fun trafficGateway(): Boolean = preferences.getBoolean(PREF_TRAFFIC_GATEWAY, true)
    /** The gateway sits in the data path, so it can only be switched while disconnected. */
    fun setTrafficGateway(value: Boolean): ActionResult {
        editLock("the traffic gateway")?.let { return it }
        preferences.putBoolean(PREF_TRAFFIC_GATEWAY, value)
        return ActionResult(true, if (value) "The traffic gateway will carry per-app traffic from the next connection. Bandwidth limits and connection details become available."
            else "The traffic gateway is off. Per-app traffic goes straight to the core; bandwidth limits are not enforced.")
    }
    fun autoSwitchMargin(): Int = preferences.getInt(PREF_AUTO_MARGIN, 10).coerceIn(3, 40)
    fun setAutoSwitchMargin(value: Int) = preferences.putInt(PREF_AUTO_MARGIN, value.coerceIn(3, 40))
    fun autoCooldownMinutes(): Int = preferences.getInt(PREF_AUTO_COOLDOWN, 10).coerceIn(1, 120)
    fun setAutoCooldownMinutes(value: Int) = preferences.putInt(PREF_AUTO_COOLDOWN, value.coerceIn(1, 120))
    private fun autoPolicy() = AutoServerSelector.Policy(autoSwitchMargin(), autoCooldownMinutes() * 60_000L, failoverIntervalMillis, 0.25)

    // ---------------------------------------------------------------- quality

    /** Adds one measured sample to a server's quality history. */
    fun recordQualitySample(key: String, sample: app.perroute.quality.QualitySample) {
        when (sample.kind()) {
            app.perroute.quality.QualitySample.Kind.REACHABILITY -> quality.recordReachability(key, sample.latencyMillis())
            app.perroute.quality.QualitySample.Kind.TUNNEL -> quality.recordTunnel(key, sample.success(), sample.latencyMillis(), sample.detail())
            app.perroute.quality.QualitySample.Kind.RECOVERY -> quality.recordRecovery(key)
        }
    }

    fun qualityScore(key: String): QualityScore = quality.score(key)
    fun qualityScores(): Map<String, QualityScore> = quality.scores(nodes().map { it.key() })
    fun qualityLastPass(): Long = quality.lastPassAt

    private fun startQualityMeasurements() {
        quality.startBackground(
            enabled = { !closing && (qualityBackground() || hasAutoApps()) },
            targets = ::qualityTargets,
            verifyCandidates = { if (hasAutoApps()) autoCandidateNodes() else emptyList() },
            ping = { node -> latencyProbe.probe(node, 2_000).let { if (it.reachable()) it.milliseconds() else -1L } },
            verify = { node -> realTunnelProbe.probe(node).let { it.reachable() to it.milliseconds() } }
        )
    }

    /** Servers worth measuring, most useful first: Auto candidates, servers in use, favorites, then the stalest. */
    private fun qualityTargets(): List<ProxyNode> {
        val all = synchronized(state) { state.nodes().filter { it.key() !in state.hiddenNodeKeys() } }
        val candidates = autoCandidateNodes().mapTo(hashSetOf()) { it.key() }
        val inUse = activeRoutes.mapTo(hashSetOf()) { it.node.key() } + listOfNotNull(selectedNode()?.key())
        val favorites = synchronized(state) { HashSet(state.favoriteNodeKeys()) }
        return all.sortedWith(compareByDescending<ProxyNode> { it.key() in candidates }
            .thenByDescending { it.key() in inUse }
            .thenByDescending { it.key() in favorites }
            .thenBy { quality.lastMeasured(it.key()) })
    }

    private fun autoCandidateNodes(): List<ProxyNode> = synchronized(state) {
        if (!state.advancedRouting()) return@synchronized emptyList()
        val pools = state.autoApps().map { AutoCandidatePool.decode(state.autoPools()[it]) }.distinct()
        state.nodes().filter { node ->
            pools.any { it.includes(node.key(), node.key() in state.favoriteNodeKeys(), state.nodeTags()[node.key()]) }
        }
    }

    // ---------------------------------------------------------------- Auto routing

    private fun hasAutoApps(): Boolean = synchronized(state) { state.advancedRouting() && state.autoApps().isNotEmpty() }

    fun appRouteMode(path: String): AppRouteMode = synchronized(state) {
        val key = AppRoutes.normalize(path)
        when {
            key in state.autoApps() -> AppRouteMode.AUTO
            state.appRoutes().containsKey(key) -> AppRouteMode.MANUAL
            else -> AppRouteMode.MAIN
        }
    }

    fun autoApps(): Set<String> = synchronized(state) { LinkedHashSet(state.autoApps()) }
    fun autoPools(): Map<String, String> = synchronized(state) { LinkedHashMap(state.autoPools()) }
    fun autoPool(path: String): AutoCandidatePool = synchronized(state) { AutoCandidatePool.decode(state.autoPools()[AppRoutes.normalize(path)]) }

    /** The last decision Auto made for an application, or a fresh one computed now (and not applied). */
    fun autoDecision(path: String): AutoDecisionView? {
        val key = AppRoutes.normalize(path)
        autoRouting.last(key)?.let { return it }
        if (key !in autoApps()) return null
        val decision = decideAuto(key, emptySet())
        return AutoDecisionView(decision, System.currentTimeMillis(), applied = false)
    }

    /**
     * What Auto would choose for an application with [pool], from the current scores and its saved choice.
     * Used by the Apps screen before anything is saved; nothing is stored.
     */
    fun previewAutoDecision(path: String, pool: AutoCandidatePool): AutoServerSelector.Decision {
        val key = AppRoutes.normalize(path)
        if (isConnected()) autoRouting.last(key)?.takeIf { pool == autoPool(path) }?.let { return it.decision }
        return synchronized(state) {
            autoRouting.decide(pool, state.autoSelections()[key], state.nodes().toList(), HashSet(state.favoriteNodeKeys()),
                LinkedHashMap(state.nodeTags()), state.nodes().firstOrNull { it.key() == state.selectedNodeKey() } ?: state.nodes().firstOrNull(),
                failingNodeKeys(), autoPolicy())
        }
    }

    private fun decideAuto(key: String, failing: Set<String>): AutoServerSelector.Decision = synchronized(state) {
        autoRouting.decide(
            AutoCandidatePool.decode(state.autoPools()[key]), state.autoSelections()[key], state.nodes().toList(),
            HashSet(state.favoriteNodeKeys()), LinkedHashMap(state.nodeTags()),
            state.nodes().firstOrNull { it.key() == state.selectedNodeKey() } ?: state.nodes().firstOrNull(), failing, autoPolicy()
        )
    }

    /**
     * The server each application in Auto mode uses for a connection, by normalized path. With [persist] the
     * decisions become the saved selections (used when connecting); without it the saved selections are shown
     * as they are and only applications without one get a preview.
     */
    private fun autoChoices(persist: Boolean): Map<String, String> {
        val (keys, effective) = synchronized(state) {
            state.appPaths().map { AppRoutes.normalize(it) }.filter { it in state.autoApps() } to
                (state.advancedRouting() && state.connectionMode() == ConnectionMode.NATIVE_PER_APP)
        }
        if (!effective || keys.isEmpty()) return emptyMap()
        val now = System.currentTimeMillis()
        val result = LinkedHashMap<String, String>()
        for (key in keys) {
            val saved = synchronized(state) { state.autoSelections()[key]?.takeIf { selection -> state.nodes().any { it.key() == selection.nodeKey() } } }
            if (!persist && saved != null) { result[key] = saved.nodeKey(); continue }
            val decision = decideAuto(key, emptySet())
            result[key] = decision.nodeKey() ?: ""
            if (persist) {
                autoRouting.remember(key, decision, applied = decision.hasServer())
                if (decision.hasServer() && saved?.nodeKey() != decision.nodeKey()) {
                    synchronized(state) { state.autoSelections()[key] = AutoSelection(decision.nodeKey(), now) }
                    save()
                }
            }
        }
        return result
    }

    private fun failingNodeKeys(): Set<String> {
        val health = recovery.snapshot()
        return activeRoutes.filter { route -> health[route.id]?.affected() == true }.mapTo(hashSetOf()) { it.node.key() }
    }

    /**
     * Re-decides one application in Auto mode while connected and, when its server should change, moves only
     * that application's route. Every other route keeps running untouched. Manual and Main applications are
     * never changed here.
     */
    private fun reevaluateAuto(application: String, routeFailed: Boolean) {
        if (!isConnected() || closing) return
        val key = AppRoutes.normalize(application)
        val route = activeRoutes.firstOrNull { it.auto && AppRoutes.normalize(it.applications.first()) == key } ?: return
        val failing = failingNodeKeys() + if (routeFailed) setOf(route.node.key()) else emptySet()
        val decision = decideAuto(key, failing)
        if (!decision.hasServer() || decision.nodeKey() == route.node.key()) {
            autoRouting.remember(key, decision, applied = true)
            return
        }
        val target = nodes().firstOrNull { it.key() == decision.nodeKey() } ?: return
        val prepared = runningPrepared ?: return
        val main = selectedNode() ?: return
        val outcome = runCatching {
            val next = prepare(main, activeMode ?: state.connectionMode(), prepared.applications, prepared.autoChoices + (key to target.key()))
            applyRouteChange(route.id, next)
        }
        if (outcome.getOrNull() == true) {
            synchronized(state) { state.autoSelections()[key] = AutoSelection(target.key(), System.currentTimeMillis()) }
            save()
            autoRouting.remember(key, decision, applied = true)
            recovery.replace(RouteTarget(route.id, cleanName(target.name()), target.key(), route.checkPort))
            recovery.note(route.id, RouteHealthTracker.EventType.FAILOVER, if (decision.reason() == AutoServerSelector.Reason.FAILOVER) "Failed over" else "Server changed",
                "Now using ${cleanName(target.name())}. Other routes were not restarted.")
            log("Auto moved ${displayName(application)} to ${cleanName(target.name())} (${decision.summary()}). Other routes were not touched.")
        } else {
            val reason = outcome.exceptionOrNull()?.let { message(it) } ?: "this route can only change with a reconnect"
            autoRouting.remember(key, decision, applied = false, note = "Not applied: $reason")
            log("Auto would move ${displayName(application)} to ${cleanName(target.name())}, but $reason. It keeps its current server.")
        }
    }

    private fun startAutoLoop() {
        val generation = autoLoopGeneration.incrementAndGet()
        if (activeRoutes.none { it.auto }) return
        Thread.ofVirtual().name("perroute-auto-routing").start {
            while (autoLoopGeneration.get() == generation && !closing) {
                try { Thread.sleep(autoIntervalMillis) } catch (_: InterruptedException) { break }
                if (autoLoopGeneration.get() != generation || !isConnected()) break
                val health = recovery.snapshot()
                activeRoutes.filter { it.auto }.forEach { route ->
                    runCatching { reevaluateAuto(route.applications.first(), health[route.id]?.state == RouteHealthTracker.State.FAILED) }
                }
            }
        }
    }

    // ---------------------------------------------------------------- bandwidth

    fun bandwidthPolicies(): Map<String, BandwidthPolicy> = synchronized(state) { LinkedHashMap(state.bandwidthPolicies()) }
    fun bandwidthPolicy(path: String): BandwidthPolicy =
        synchronized(state) { state.bandwidthPolicies()[AppRoutes.normalize(path)] } ?: BandwidthPolicy.UNLIMITED

    /** Limits can be changed at any time; while the gateway carries the app, the new limit applies at once. */
    fun setBandwidthPolicy(path: String, policy: BandwidthPolicy): ActionResult {
        val key = AppRoutes.normalize(path)
        if (key.isBlank()) return ActionResult(false, "Choose an application first.")
        synchronized(state) { if (policy.unlimited()) state.bandwidthPolicies().remove(key) else state.bandwidthPolicies()[key] = policy }
        save()
        val connectedPath = runningPrepared?.applications?.firstOrNull { AppRoutes.normalize(it) == key }
        if (gatewayActive && connectedPath != null) {
            runCatching { backend.setGatewayLimits(connectedPath, policy.downloadBytesPerSecond(), policy.uploadBytesPerSecond()) }
                .onFailure { return ActionResult(false, "The limit was saved but could not be applied: ${message(it)}") }
        }
        val status = bandwidthStatus(path)
        return ActionResult(true, if (policy.unlimited()) "${displayName(path)} is unlimited." else "Limit saved. ${status.detail}")
    }

    fun bandwidthStatus(path: String): BandwidthStatus {
        val policy = bandwidthPolicy(path)
        val key = AppRoutes.normalize(path)
        val usage = traffic.applications.firstOrNull { AppRoutes.normalize(it.path) == key }
        val down = usage?.downloadBytesPerSecond ?: 0L
        val up = usage?.uploadBytesPerSecond ?: 0L
        if (policy.unlimited()) return BandwidthStatus(policy, BandwidthState.UNLIMITED, "No limit.", down, up)
        if (!isConnected()) return BandwidthStatus(policy, BandwidthState.PENDING, "Enforced while connected in Smart per-app mode.", down, up)
        if (activeMode != ConnectionMode.NATIVE_PER_APP) {
            return BandwidthStatus(policy, BandwidthState.NOT_ENFORCED, "Not enforced: limits apply in Smart per-app mode only.", down, up)
        }
        if (!gatewayActive) {
            return BandwidthStatus(policy, BandwidthState.NOT_ENFORCED, "Not enforced: the traffic gateway is off. Turn it on in Settings > Routing, then reconnect.", down, up)
        }
        if (runningPrepared?.applications?.none { AppRoutes.normalize(it) == key } != false) {
            return BandwidthStatus(policy, BandwidthState.PENDING, "Applies after this app is selected and you reconnect.", down, up)
        }
        val busy = (policy.downloadBytesPerSecond() > 0 && down >= policy.downloadBytesPerSecond() * 0.9) ||
            (policy.uploadBytesPerSecond() > 0 && up >= policy.uploadBytesPerSecond() * 0.9)
        return if (busy) BandwidthStatus(policy, BandwidthState.THROTTLING, "Throttling: the app is using its full limit.", down, up)
        else BandwidthStatus(policy, BandwidthState.ENFORCED, "Enforced by the traffic gateway for all of the app's connections.", down, up)
    }

    private fun startGateway(prepared: PreparedConnection) {
        val routes = prepared.applications.mapIndexed { index, path ->
            AppGateway.Route(path, index, XrayConfigGenerator.perAppPort(index), XrayConfigGenerator.gatewayUpstreamPort(index))
        }
        backend.startGateway(routes)
        prepared.applications.forEach { path ->
            val policy = bandwidthPolicy(path)
            backend.setGatewayLimits(path, policy.downloadBytesPerSecond(), policy.uploadBytesPerSecond())
        }
        gatewayActive = true
        inspector.gatewayActive(true)
    }

    // ---------------------------------------------------------------- route recovery

    /** Every live route with its own health and recovery timeline. */
    fun routeHealth(): List<RouteHealthView> {
        val health = recovery.snapshot()
        val monitoring = routeMonitoring()
        return activeRoutes.map { RouteHealthView(it, health[it.id], monitoring) }
    }

    /** Checks one route again right away; a failed route gets a fresh set of attempts. Other routes are not touched. */
    fun retryRoute(routeId: String): ActionResult {
        if (!isConnected()) return ActionResult(false, "Connect to check this route.")
        return if (recovery.retryNow(routeId)) ActionResult(true, "Checking this route again.")
        else ActionResult(false, "That route is no longer in use.")
    }

    private fun probeRoute(target: RouteTarget): RouteProbe {
        val startedAt = System.currentTimeMillis()
        val started = System.nanoTime()
        val result = backend.checkTunnel(target.checkPort)
        val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
        return RouteProbe(result.success(), if (result.success()) elapsed else -1L, result.detail(), startedAt)
    }

    private fun onRouteResult(target: RouteTarget, result: RouteProbe, action: RouteHealthTracker.Action, health: RouteHealthTracker.Snapshot) {
        val route = activeRoutes.firstOrNull { it.id == target.id && it.checkPort == target.checkPort && it.node.key() == target.nodeKey } ?: return
        quality.recordTunnel(target.nodeKey, result.ok, result.latencyMillis, result.detail)
        val now = System.currentTimeMillis()
        val first = synchronized(routeCheckLock) {
            val current = routeChecks[target.id]
            // A check the user asked for in the meantime is newer and more detailed; keep it.
            if (current != null && current.state != RouteCheckState.CHECKING && current.atMillis >= result.startedAt) return@synchronized false
            val observed = if (result.ok) RouteCheck(RouteCheckState.PASSED, now, result.latencyMillis,
                    current?.publicIp?.takeIf { current.state == RouteCheckState.PASSED },
                    current?.country?.takeIf { current.state == RouteCheckState.PASSED }, result.detail)
                else RouteCheck(RouteCheckState.FAILED, now, detail = result.detail)
            routeChecks = routeChecks + (target.id to observed)
            current == null || current.state == RouteCheckState.CHECKING
        }
        if (first) {
            if (result.ok) log("Tunnel health (${target.name}): ${result.detail}") else log("Tunnel health was inconclusive (${target.name}): ${result.detail}")
        }
        when (action) {
            RouteHealthTracker.Action.REFRESH_ROUTE -> Thread.ofVirtual().name("perroute-route-refresh").start { refreshRoute(route) }
            RouteHealthTracker.Action.EXHAUSTED -> {
                log("${target.name} did not recover after ${health.maxAttempts()} attempts. It is checked again every few minutes; other routes are not affected.")
                if (route.auto) Thread.ofVirtual().name("perroute-auto-failover").start { reevaluateAuto(route.applications.first(), routeFailed = true) }
            }
            RouteHealthTracker.Action.RECOVERED -> {
                quality.recordRecovery(target.nodeKey)
                log("${target.name} recovered on its own route.")
            }
            RouteHealthTracker.Action.NONE -> {}
        }
    }

    /** Rebuilds one route's outbound in the running core, which drops its stuck connections and nothing else. */
    private fun refreshRoute(route: ActiveRoute) {
        val prepared = runningPrepared ?: return
        val outcome = runCatching { applyRouteChange(route.id, prepared) }
        if (outcome.getOrNull() == true) log("${cleanName(route.node.name())}: the route was rebuilt; other routes kept running.")
        else log("${cleanName(route.node.name())} keeps retrying; its route cannot be rebuilt without a reconnect" +
            (outcome.exceptionOrNull()?.let { " (${message(it)})" } ?: "."))
    }

    /**
     * Applies [next] to the running core by replacing only the outbound of route [routeId]: the new outbound is
     * added, every rule is swapped in one step, then the old outbound is removed. Other routes keep their
     * outbound, their tag and their open connections. Returns false, changing nothing, when the two configs
     * differ in anything else (another route, an embedded profile, balancers), because that needs a restart.
     */
    @Synchronized
    private fun applyRouteChange(routeId: String, next: PreparedConnection): Boolean {
        val current = runningPrepared ?: return false
        val config = runningConfig ?: return false
        if (!connectionReady || current.tun || next.tun || current.gateway != next.gateway || !current.runtimeControl) return false
        if (current.routes.size != next.routes.size || current.applications != next.applications) return false
        for ((before, after) in current.routes.zip(next.routes)) {
            if (before.main != after.main || before.auto != after.auto || before.ruleTag != after.ruleTag || before.applications != after.applications) return false
            if (before.id != routeId && before.id != after.id) return false
        }
        val swapped = current.routes.firstOrNull { it.id == routeId } ?: return false
        val running = XrayConfigGenerator.applicationRouteTargets(config)
        val planned = XrayConfigGenerator.applicationRouteTargets(next.config)
        var candidate = next.config
        for (route in next.routes) {
            val base = planned[route.ruleTag] ?: return false
            val live = running[route.ruleTag] ?: return false
            if (route.ruleTag != swapped.ruleTag && base != live) candidate = XrayConfigGenerator.renameOutbound(candidate, base, live)
        }
        val oldTag = running[swapped.ruleTag] ?: return false
        val base = planned[swapped.ruleTag] ?: return false
        val newTag = if (oldTag == base) "$base~1" else base
        candidate = XrayConfigGenerator.renameOutbound(candidate, base, newTag)
        // Nothing but the replaced outbound may differ, or rules could point at outbounds the core does not have.
        if (outboundTags(candidate) - newTag != outboundTags(config) - oldTag) return false
        val change = XrayConfigGenerator.runtimeChange(candidate, newTag) ?: return false
        backend.validate(candidate, false)
        backend.applyRuntimeChange(change, newTag, oldTag, candidate)
        runningConfig = candidate
        runningPrepared = next
        activeRoutes = next.routes
        return true
    }

    private fun outboundTags(config: String): Set<String> = runCatching {
        (Json.`object`(config)["outbounds"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.get("tag") as? String }.toSet()
    }.getOrDefault(emptySet())

    // ---------------------------------------------------------------- connection inspector

    /** Whether connections can be inspected right now: only Smart per-app has per-app inbounds to observe. */
    fun inspectorAvailable(): Boolean = isConnected() && activeMode == ConnectionMode.NATIVE_PER_APP

    fun inspectorUsesGateway(): Boolean = gatewayActive

    fun inspectorRows(): List<InspectorRow> {
        val prepared = runningPrepared ?: return emptyList()
        if (!inspectorAvailable()) return emptyList()
        val config = runningConfig.orEmpty()
        val targets = runCatching { XrayConfigGenerator.applicationRouteTargets(config) }.getOrDefault(emptyMap())
        val routeTags = activeRoutes.mapNotNull { route -> targets[route.ruleTag]?.let { route.id to it } }.toMap()
        val context = InspectorContext(prepared.applications, activeRoutes, routeTags, rules(), prepared.plan, routeChecks) { serverName(it) }
        return InspectorView.rows(inspector.snapshot(System.currentTimeMillis()), context)
    }

    /** Running instances of an executable, for the inspector's details. Process identity is observed, not stored. */
    fun processIds(path: String): List<Long> = runCatching {
        ProcessHandle.allProcesses()
            .filter { handle -> handle.info().command().map { it.equals(path, ignoreCase = true) }.orElse(false) }
            .limit(16).map { it.pid() }.toList()
    }.getOrDefault(emptyList())

    // ---------------------------------------------------------------- backup and restore

    private val restoreSnapshotFile: Path get() = AppDirectories.dataDirectory().resolve("restore-rollback.bin")

    private fun preferenceSnapshot(): Map<String, String> =
        BACKUP_PREFERENCES.keys.mapNotNull { key -> preferences.get(key, null)?.let { key to it } }.toMap()

    private fun applyPreferences(values: Map<String, String>) {
        values.forEach { (key, value) ->
            when (BACKUP_PREFERENCES[key]) {
                PreferenceType.BOOLEAN -> if (value == "true" || value == "false") {
                    if (key == "perAppKillSwitch") setKillSwitch(value.toBoolean()) else preferences.putBoolean(key, value.toBoolean())
                }
                PreferenceType.INT -> value.toIntOrNull()?.let { preferences.putInt(key, it) }
                PreferenceType.URL -> if (value.isBlank() || value.startsWith("https://", ignoreCase = true)) preferences.put(key, value.trim())
                null -> {}
            }
        }
        runCatching { preferences.flush() }
    }

    /** Writes a backup of the chosen sections. Server credentials are inside, so encryption is recommended. */
    fun exportBackup(file: Path, sections: Set<BackupService.Section>, password: CharArray?): ActionResult = try {
        val bytes = synchronized(state) {
            BackupService.export(state, preferenceSnapshot(), sections, password, "PerRoute ${SecureUpdateService.CURRENT_VERSION}", System.currentTimeMillis())
        }
        Files.createDirectories(file.toAbsolutePath().parent)
        val temporary = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.write(temporary, bytes)
        Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        log("Backup saved (${sections.size} ${if (sections.size == 1) "section" else "sections"}, ${if (password?.isNotEmpty() == true) "encrypted" else "not encrypted"}).")
        ActionResult(true, "Backup saved to ${file.fileName}.")
    } catch (error: Throwable) {
        ActionResult(false, "The backup could not be saved: ${message(error)}")
    } finally {
        password?.fill('\u0000')
    }

    /** Reads and checks a backup and describes what restoring it would change. Nothing is changed. */
    fun openBackup(file: Path, password: CharArray?): Pair<RestoreRequest?, String?> = try {
        val bytes = Files.readAllBytes(file)
        val known = runCatching { installedApplications().map { it.path() } + runningApplications().map { it.path() } }.getOrDefault(emptyList())
        val current = synchronized(state) { store.parse(store.serialize(state)) }
        RestoreRequest(file, BackupService.preview(bytes, password, current, BackupService::executableExists, known)) to null
    } catch (error: BackupCodec.BackupException) {
        null to error.message
    } catch (error: Throwable) {
        null to "The backup could not be opened: ${message(error)}"
    } finally {
        password?.fill('\u0000')
    }

    /**
     * Restores a backup in one step. A rollback snapshot of the current state and preferences is written first;
     * the new state is then written atomically and only afterwards replaces the state in memory. If anything
     * fails, memory, the state file and the preferences are put back, so a half-restored setup never remains.
     */
    fun restoreBackup(request: RestoreRequest, mode: BackupService.Mode, remap: Map<String, String>): ActionResult {
        editLock("settings")?.let { return it }
        val currentBytes = synchronized(state) { store.serialize(state) }
        val preferencesBefore = preferenceSnapshot()
        val plan = try {
            BackupService.plan(request.preview, store.parse(currentBytes), preferencesBefore, mode, remap, BackupService::executableExists)
        } catch (error: Throwable) {
            return ActionResult(false, "The backup could not be applied; nothing was changed: ${message(error)}")
        }
        try {
            AppStateStore(restoreSnapshotFile).write(withPreferences(currentBytes, preferencesBefore))
        } catch (error: Throwable) {
            return ActionResult(false, "A rollback snapshot could not be written, so nothing was restored: ${message(error)}")
        }
        return try {
            store.write(store.serialize(plan.state()))
            synchronized(state) { state.replaceWith(plan.state()) }
            applyPreferences(plan.preferences())
            afterStateReplaced()
            log("A backup was restored (${mode.name.lowercase()}). ${plan.summary().joinToString(" ")}")
            ActionResult(true, "Backup restored. ${plan.summary().joinToString(" ")}")
        } catch (error: Throwable) {
            runCatching {
                synchronized(state) { state.replaceWith(store.parse(currentBytes)) }
                store.write(currentBytes)
                applyPreferences(preferencesBefore)
            }
            afterStateReplaced()
            ActionResult(false, "The restore failed and your previous settings were put back: ${message(error)}")
        }
    }

    fun hasRestoreSnapshot(): Boolean = Files.isRegularFile(restoreSnapshotFile)

    /** Puts back the state and preferences from before the last restore. */
    fun undoRestore(): ActionResult {
        editLock("settings")?.let { return it }
        if (!hasRestoreSnapshot()) return ActionResult(false, "There is no restore to undo.")
        return try {
            val properties = AppStateStore(restoreSnapshotFile).loadProperties()
            val previous = store.parse(properties)
            val prefs = properties.stringPropertyNames().filter { it.startsWith(ROLLBACK_PREFIX) }
                .associate { it.removePrefix(ROLLBACK_PREFIX) to properties.getProperty(it) }
            store.write(store.serialize(previous))
            synchronized(state) { state.replaceWith(previous) }
            applyPreferences(prefs)
            Files.deleteIfExists(restoreSnapshotFile)
            afterStateReplaced()
            log("The last restore was undone.")
            ActionResult(true, "Your settings from before the restore are back.")
        } catch (error: Throwable) {
            ActionResult(false, "The restore could not be undone: ${message(error)}")
        }
    }

    private fun withPreferences(stateBytes: ByteArray, values: Map<String, String>): ByteArray {
        val properties = java.util.Properties()
        properties.load(java.io.ByteArrayInputStream(stateBytes))
        values.forEach { (key, value) -> properties.setProperty(ROLLBACK_PREFIX + key, value) }
        val out = java.io.ByteArrayOutputStream()
        properties.store(out, "PerRoute rollback snapshot")
        return out.toByteArray()
    }

    private fun afterStateReplaced() {
        val live = nodes().mapTo(hashSetOf()) { it.key() }
        latencyCache = latencyCache.filterKeys { it in live }
        speedCache = speedCache.filterKeys { it in live }
        quality.retain(live)
        autoRouting.clear()
        if (deduplicateNodes()) save()
    }

    fun onLog(listener: (String) -> Unit) { logListeners += listener }
    fun onConnectionChanged(listener: (Boolean) -> Unit) { stateListeners += listener }

    fun environment(): EnvironmentState = try {
        EnvironmentState(
            coreReady = backend.coreReady(),
            perAppReady = backend.perAppReady(),
            elevated = backend.elevated()
        )
    } catch (_: Throwable) {
        EnvironmentState(false, false, false)
    }

    /** Routing choices are baked into the running config, so they only change while fully disconnected. */
    private fun editLock(subject: String): ActionResult? =
        if (phase != ConnectionPhase.DISCONNECTED || isConnected()) ActionResult(false, "Disconnect to change $subject.")
        else null

    fun selectNode(key: String) {
        state.setSelectedNodeKey(key)
        save()
    }

    fun removeNode(key: String): ActionResult {
        editLock("the server list")?.let { return it }
        val orphaned: Int
        synchronized(state) {
            if (!state.nodes().removeIf { it.key() == key }) return ActionResult(false, "Server was not found.")
            state.nodeSources().remove(key)
            state.favoriteNodeKeys().remove(key)
            state.hiddenNodeKeys().remove(key)
            state.nodeTags().remove(key)
            if (state.selectedNodeKey() == key) {
                state.setSelectedNodeKey(state.nodes().firstOrNull()?.key() ?: "")
            }
            // Assignments to this server are kept on purpose: the application must not quietly move
            // to the main server or to the direct route. They show up as needing a replacement.
            orphaned = state.appRoutes().values.count { it == key }
        }
        save()
        return ActionResult(true, if (orphaned == 0) "Server removed."
            else "Server removed. $orphaned app ${if (orphaned == 1) "assignment needs" else "assignments need"} a replacement server.")
    }

    fun setMode(mode: ConnectionMode) {
        if (phase != ConnectionPhase.DISCONNECTED || isConnected()) return
        if (state.connectionMode() == ConnectionMode.NATIVE_PER_APP && mode != ConnectionMode.NATIVE_PER_APP) {
            Thread.ofVirtual().name("perroute-sidecar-cleanup").start { runCatching { backend.restorePerApp() } }
        }
        state.setConnectionMode(mode)
        save()
    }

    fun setRoutingMode(mode: RoutingMode) {
        state.setRoutingMode(mode)
        save()
    }

    /**
     * Returns PerRoute to the state of a fresh installation: servers, subscriptions, selected apps, app
     * routes, rules, profiles, traffic history and every preference. Refused while a connection exists,
     * because the running core was built from the data that is about to disappear.
     */
    fun resetAll(): ActionResult {
        if (phase != ConnectionPhase.DISCONNECTED || isConnected()) return ActionResult(false, "Disconnect before resetting PerRoute.")
        synchronized(state) {
            state.nodes().clear()
            state.appPaths().clear()
            state.subscriptionUrls().clear()
            state.nodeSources().clear()
            state.favoriteNodeKeys().clear()
            state.hiddenNodeKeys().clear()
            state.nodeTags().clear()
            state.routeRules().clear()
            state.profiles().clear()
            state.appRoutes().clear()
            state.appRouteLabels().clear()
            state.autoApps().clear()
            state.autoPools().clear()
            state.autoSelections().clear()
            state.bandwidthPolicies().clear()
            state.setActiveProfileId("")
            state.setSelectedNodeKey("")
            state.setAdvancedRouting(false)
            state.setConnectionMode(ConnectionMode.SYSTEM_PROXY)
            state.setRoutingMode(RoutingMode.INCLUDE_SELECTED)
            state.setMigratedFromV2(false)
        }
        runCatching { preferences.clear() }
        initializeVersionThreeDefaults()
        runCatching { quality.clear() }
        autoRouting.clear()
        runCatching { Files.deleteIfExists(restoreSnapshotFile) }
        latencyCache = emptyMap()
        speedCache = emptyMap()
        dnsObservation = null
        availableUpdate = null
        updateDownloadMessage = ""
        synchronized(routeCheckLock) { routeChecks = emptyMap() }
        history.clear()
        trafficPoints.clear()
        // The last generated config still holds server credentials, so it goes as well.
        runCatching { Files.deleteIfExists(AppDirectories.dataDirectory().resolve("current-config.json")) }
        // The kill switch is off again after a reset; release anything it was still holding back.
        Thread.ofVirtual().name("perroute-reset-cleanup").start { runCatching { backend.restorePerApp() } }
        saveNow()
        log("PerRoute was reset to its initial state.")
        return ActionResult(true, "PerRoute was reset. Servers, apps, rules, profiles, history and settings were cleared.")
    }

    fun advancedRouting(): Boolean = synchronized(state) { state.advancedRouting() }

    /** Turning this off never deletes an assignment; it only stops applying them. */
    fun setAdvancedRouting(enabled: Boolean): ActionResult {
        editLock("routing settings")?.let { return it }
        synchronized(state) { state.setAdvancedRouting(enabled) }
        save()
        return ActionResult(true, if (enabled) "Advanced routing is on. Choose servers per app in Apps."
            else "Advanced routing is off. All selected apps use the main server; saved assignments are kept.")
    }

    /** Saved custom servers by normalized application path, whether or not they are currently applied. */
    fun appRoutes(): Map<String, String> = synchronized(state) { LinkedHashMap(state.appRoutes()) }
    fun appRouteLabel(nodeKey: String): String = synchronized(state) { state.appRouteLabels()[nodeKey] ?: "" }

    /**
     * The routes a connection would use right now. With [assumeAdvanced] the saved assignments are
     * resolved even while they are inactive, which is how the UI finds ones that need a replacement.
     */
    fun routePlan(assumeAdvanced: Boolean = false): RoutePlanner.Plan {
        // While connected the plan shows what is running; otherwise what Auto would pick next.
        val choices = runningPrepared?.autoChoices ?: if (assumeAdvanced) previewAutoChoices() else autoChoices(persist = false)
        return synchronized(state) {
            RoutePlanner.plan(
                state.nodes().toList(), state.selectedNodeKey(), state.appPaths().toList(),
                assumeAdvanced || state.advancedRouting(), LinkedHashMap(state.appRoutes()),
                LinkedHashMap(state.appRouteLabels()),
                if (assumeAdvanced) ConnectionMode.NATIVE_PER_APP else state.connectionMode(), choices
            )
        }
    }

    /** Auto choices as if Advanced routing were on in Smart per-app, for finding problems while it is off. */
    private fun previewAutoChoices(): Map<String, String> {
        val keys = synchronized(state) { state.appPaths().map { AppRoutes.normalize(it) }.filter { it in state.autoApps() } }
        return keys.associateWith { key ->
            synchronized(state) { state.autoSelections()[key]?.nodeKey()?.takeIf { saved -> state.nodes().any { it.key() == saved } } }
                ?: decideAuto(key, emptySet()).nodeKey().orEmpty()
        }
    }

    fun activeRoutes(): List<ActiveRoute> = activeRoutes
    fun routeChecks(): Map<String, RouteCheck> = routeChecks
    fun dnsObservation(): DnsObservation? = dnsObservation

    fun addApplication(path: String): ActionResult {
        editLock("the selected apps")?.let { return it }
        val normalized = Path.of(path).toAbsolutePath().normalize().toString()
        if (!Files.isRegularFile(Path.of(normalized))) return ActionResult(false, "That application was not found.")
        synchronized(state) {
            if (state.appPaths().none { it.equals(normalized, ignoreCase = true) }) state.appPaths().add(normalized)
        }
        save()
        return ActionResult(true, "Application added.")
    }

    fun replaceApplications(paths: Collection<String>): ActionResult {
        editLock("the selected apps")?.let { return it }
        synchronized(state) { storeApplications(paths) }
        save()
        return ActionResult(true, "Selection saved.")
    }

    /** The Apps screen saves its whole draft in one step: selection, assignments and the Advanced flag. */
    fun saveApplicationSetup(paths: Collection<String>, routes: Map<String, String>, advanced: Boolean): ActionResult =
        saveApplicationSetup(paths, routes, advanced, autoApps(), autoPools())

    /**
     * Also saves which applications are in Auto mode and their candidate pools. An application's Manual
     * choice in [routes] is kept while it is in Auto, so switching back restores it.
     */
    fun saveApplicationSetup(paths: Collection<String>, routes: Map<String, String>, advanced: Boolean,
                             autoApps: Set<String>, pools: Map<String, String>): ActionResult {
        editLock("app routes")?.let { return it }
        val newlyAuto: Boolean
        synchronized(state) {
            storeApplications(paths)
            storeRoutes(routes)
            val auto = autoApps.map { AppRoutes.normalize(it) }.filter { it.isNotBlank() }.toCollection(LinkedHashSet())
            newlyAuto = (auto - state.autoApps()).isNotEmpty()
            state.autoApps().clear()
            state.autoApps().addAll(auto)
            state.autoPools().clear()
            pools.forEach { (path, text) ->
                val key = AppRoutes.normalize(path)
                val pool = AutoCandidatePool.decode(text)
                if (key.isNotBlank() && pool.kind() != AutoCandidatePool.Kind.ALL) state.autoPools()[key] = pool.encode()
            }
            state.autoSelections().keys.retainAll(auto)
            state.setAdvancedRouting(advanced)
        }
        autoRouting.forget(autoApps())
        save()
        if (newlyAuto && advanced) quality.requestPass()
        return ActionResult(true, "Changes saved.")
    }

    private fun storeApplications(paths: Collection<String>) {
        state.appPaths().clear()
        paths.map { Path.of(it).toAbsolutePath().normalize().toString() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .filter { Files.isRegularFile(Path.of(it)) }
            .forEach(state.appPaths()::add)
    }

    private fun storeRoutes(routes: Map<String, String>) {
        val normalized = AppRoutes.normalized(routes)
        state.appRoutes().clear()
        state.appRoutes().putAll(normalized)
        val names = state.nodes().associate { it.key() to cleanName(it.name()) }
        normalized.values.forEach { key -> names[key]?.let { state.appRouteLabels()[key] = it } }
        state.appRouteLabels().keys.retainAll(normalized.values.toSet())
    }

    fun removeApplication(path: String): ActionResult {
        editLock("the selected apps")?.let { return it }
        synchronized(state) { state.appPaths().removeIf { it.equals(path, ignoreCase = true) } }
        save()
        return ActionResult(true, "Application removed.")
    }

    fun runningApplications(forceRefresh: Boolean = false): List<ProcessDiscovery.RunningApplication> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && now - runningAppCacheAt < 3_000 && runningAppCache.isNotEmpty()) {
            return runningAppCache
        }
        return synchronized(processDiscovery) {
            val refreshedAt = System.currentTimeMillis()
            if (!forceRefresh && refreshedAt - runningAppCacheAt < 3_000 && runningAppCache.isNotEmpty()) {
                runningAppCache
            } else {
                backend.runningApplications().filter(::isUserFacingApplication).also {
                    runningAppCache = it
                    runningAppCacheAt = refreshedAt
                }
            }
        }
    }

    fun installedApplications(forceRefresh: Boolean = false): List<InstalledApplicationDiscovery.InstalledApplication> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && now - installedAppCacheAt < 300_000 && installedAppCache.isNotEmpty()) {
            return installedAppCache
        }
        return synchronized(installedApplicationDiscovery) {
            val refreshedAt = System.currentTimeMillis()
            if (!forceRefresh && refreshedAt - installedAppCacheAt < 300_000 && installedAppCache.isNotEmpty()) {
                installedAppCache
            } else {
                backend.installedApplications().filter { isUserFacingApplication(it.name(), it.path()) }.also {
                    installedAppCache = it
                    installedAppCacheAt = refreshedAt
                }
            }
        }
    }

    fun fetchSubscription(url: String): ActionResult {
        if (url.isBlank()) return ActionResult(false, "Enter a subscription URL.")
        editLock("subscriptions")?.let { return it }
        return try {
            val normalized = url.trim()
            val result = subscriptionService.fetch(normalized)
            if (result.nodes().isEmpty()) return parseMessage(result)
            storeSubscription(result, normalized)
            parseMessage(result)
        } catch (e: Throwable) {
            ActionResult(false, message(e))
        }
    }

    private fun storeSubscription(result: ParseResult, url: String) {
        merge(result, url, replaceSource = true)
        synchronized(state) {
            if (state.subscriptionUrls().none { it.equals(url, ignoreCase = true) }) state.subscriptionUrls().add(url)
        }
        save()
    }

    fun updateAllSubscriptions(): ActionResult {
        editLock("subscriptions")?.let { return it }
        val urls = subscriptions()
        if (urls.isEmpty()) return ActionResult(false, "No saved subscriptions to update.")
        var updated = 0
        val failures = mutableListOf<String>()
        urls.forEach { url ->
            try {
                val result = subscriptionService.fetch(url)
                result.warnings().forEach { log("Warning: $it") }
                if (result.nodes().isEmpty()) {
                    failures += "${sourceLabel(url)}: no valid servers in the response; kept the existing ones"
                } else {
                    merge(result, url, replaceSource = true)
                    updated += result.nodes().size
                }
            } catch (error: Throwable) {
                failures += "${sourceLabel(url)}: ${message(error)}"
            }
        }
        save()
        val unresolved = routePlan(assumeAdvanced = true).missing().size
        val suffix = if (unresolved == 0) "" else " $unresolved app ${if (unresolved == 1) "assignment needs" else "assignments need"} a replacement server."
        return if (failures.isEmpty()) ActionResult(true, "Updated $updated servers from ${urls.size} subscriptions.$suffix")
        else ActionResult(false, "Updated $updated servers. Failed: ${failures.joinToString(" | ")}$suffix")
    }

    fun removeSubscription(url: String): ActionResult {
        if (url.isBlank() || url == MANUAL_SOURCE || url == IMPORTED_SOURCE) {
            return ActionResult(false, "Select a saved subscription first.")
        }
        editLock("subscriptions")?.let { return it }
        var removedNodes = 0
        synchronized(state) {
            if (!state.subscriptionUrls().removeIf { it.equals(url, ignoreCase = true) }) {
                return ActionResult(false, "Subscription was not found.")
            }
            val keys = state.nodeSources().filterValues { it.equals(url, ignoreCase = true) }.keys.toSet()
            removedNodes = keys.size
            state.nodes().removeIf { it.key() in keys }
            keys.forEach { key ->
                state.nodeSources().remove(key)
                state.favoriteNodeKeys().remove(key)
                state.hiddenNodeKeys().remove(key)
                state.nodeTags().remove(key)
            }
            if (state.selectedNodeKey() in keys) {
                state.setSelectedNodeKey(state.nodes().firstOrNull()?.key() ?: "")
            }
        }
        save()
        return ActionResult(true, "Subscription removed with $removedNodes servers.")
    }

    fun cachedLatencies(): Map<String, Long> = latencyCache

    private fun rememberLatencies(measured: Map<String, Long>) {
        val live = synchronized(state) { state.nodes().mapTo(hashSetOf()) { it.key() } }
        latencyCache = (latencyCache + measured).filterKeys { it in live }
        speedCache = speedCache.filterKeys { it in live }
    }

    fun pingServers(targets: List<ProxyNode>): Map<String, Long> =
        measureEndpoints(targets).also { measured ->
            rememberLatencies(measured)
            measured.forEach { (key, value) -> quality.recordReachability(key, value) }
        }

    private fun measureEndpoints(targets: List<ProxyNode>): Map<String, Long> {
        if (targets.isEmpty()) return emptyMap()
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        return try {
            val tasks = targets.map { node ->
                Callable {
                    val result = latencyProbe.probe(node)
                    node.key() to if (result.reachable()) result.milliseconds() else -1L
                }
            }
            val futures = executor.invokeAll(tasks, 3, TimeUnit.SECONDS)
            targets.indices.associate { index ->
                val value = runCatching {
                    if (futures[index].isCancelled) -1L else futures[index].get().second
                }.getOrDefault(-1L)
                targets[index].key() to value
            }
        } finally {
            executor.shutdownNow()
        }
    }

    fun realTestServers(targets: List<ProxyNode>): Map<String, Long> {
        if (targets.isEmpty()) return emptyMap()
        val executor = Executors.newFixedThreadPool(minOf(3, targets.size), Thread.ofVirtual().factory())
        return try {
            val futures = targets.associateWith { node -> executor.submit<Long> {
                val result = realTunnelProbe.probe(node)
                if (result.reachable()) result.milliseconds() else -1L
            } }
            futures.mapValues { (_, future) -> runCatching { future.get(35, TimeUnit.SECONDS) }.getOrDefault(-1L) }
                .mapKeys { it.key.key() }.also { measured ->
                    rememberLatencies(measured)
                    measured.forEach { (key, value) -> quality.recordTunnel(key, value >= 0, value, if (value >= 0) "" else "Real tunnel test failed") }
                }
        } finally { executor.shutdownNow() }
    }

    fun cachedSpeeds(): Map<String, Long> = speedCache

    fun speedTestServer(node: ProxyNode): Long {
        val result = realTunnelProbe.speed(node)
        if (!result.success()) log("Speed test failed for ${cleanName(node.name())}: ${result.detail()}")
        val speed = if (result.success()) result.bytesPerSecond() else -1L
        speedCache = speedCache + (node.key() to speed)
        return speed
    }

    private fun setRouteCheck(key: String, check: RouteCheck) {
        synchronized(routeCheckLock) { routeChecks = routeChecks + (key to check) }
    }

    /**
     * One request per route, each through that route's own loopback port. A route is only reported as
     * passed when its own request came back; the main server's result is never reused for another one.
     */
    fun checkRoutes(): ActionResult {
        val routes = if (isConnected()) activeRoutes else emptyList()
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        return try {
            val tasks = mutableListOf<java.util.concurrent.Future<*>>()
            tasks += executor.submit { observeRoute(DIRECT_ROUTE_KEY, null) }
            routes.forEach { route ->
                tasks += executor.submit { observeRoute(route.id, route.checkPort, route.node.key()) }
            }
            val servers = executor.submit<List<String>> { backend.systemDnsServers() }
            val resolver = executor.submit<String?> { backend.publicResolver() }

            tasks.forEach { runCatching { it.get(20, TimeUnit.SECONDS) } }
            dnsObservation = DnsObservation(
                runCatching { servers.get(15, TimeUnit.SECONDS) }.getOrDefault(emptyList()),
                runCatching { resolver.get(15, TimeUnit.SECONDS) }.getOrNull(),
                System.currentTimeMillis()
            )
            val failed = routes.count { routeChecks[it.id]?.state == RouteCheckState.FAILED }
            when {
                routes.isEmpty() -> ActionResult(true, "Direct route checked. Connect to check your servers.")
                failed == 0 -> ActionResult(true, "${routes.size} ${if (routes.size == 1) "route" else "routes"} checked.")
                else -> ActionResult(false, "$failed of ${routes.size} routes failed the check.")
            }
        } finally {
            executor.shutdownNow()
        }
    }

    /** [key] is a route id: a server key for the main and shared routes, `auto:<path>` for an Auto app, or the direct route. */
    fun checkRoute(key: String): ActionResult {
        val route = activeRoutes.takeIf { isConnected() }?.firstOrNull { it.id == key }
        val port = if (key == DIRECT_ROUTE_KEY) null
            else route?.checkPort ?: return ActionResult(false, "Connect to check this server.")
        observeRoute(key, port, route?.node?.key())
        val check = routeChecks[key]
        return if (check?.state == RouteCheckState.PASSED) ActionResult(true, "Check passed.")
        else ActionResult(false, "Check failed: ${check?.detail.orEmpty()}")
    }

    private fun observeRoute(key: String, socksPort: Int?, nodeKey: String? = null) {
        val previous = routeChecks[key]
        setRouteCheck(key, (previous ?: RouteCheck()).copy(state = RouteCheckState.CHECKING))
        val started = System.nanoTime()
        val report = runCatching { backend.lookupPublicAddress(socksPort) }.getOrElse { RouteReport(error = message(it)) }
        val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
        setRouteCheck(key, if (report.ip != null) RouteCheck(RouteCheckState.PASSED, System.currentTimeMillis(), elapsed, report.ip, report.country)
            else RouteCheck(RouteCheckState.FAILED, System.currentTimeMillis(), detail = report.error ?: "No answer"))
        // A lookup through a server's own port is an end-to-end use of its tunnel.
        if (nodeKey != null) quality.recordTunnel(nodeKey, report.ip != null, elapsed, report.error.orEmpty())
    }

    fun loadedRuleIds(): Set<String>? {
        if (!isConnected()) return null
        return runCatching {
            val root = Json.`object`(Files.readString(AppDirectories.dataDirectory().resolve("current-config.json")))
            val routing = root["routing"] as? Map<*, *>
            (routing?.get("rules") as? List<*>).orEmpty()
                .mapNotNull { XrayConfigGenerator.customRuleId((it as? Map<*, *>)?.get("ruleTag") as? String) }
                .toHashSet()
        }.getOrDefault(emptySet())
    }

    fun evaluateRoute(application: String, destination: String, port: Int): RouteRuleEvaluator.Verdict =
        RouteRuleEvaluator.evaluate(rules(), applications(), application, destination, port)

    fun activeScenarioId(): String {
        val names = rules().map { it.name() }
        return SCENARIOS.firstOrNull { scenario -> names.any { it.startsWith(scenarioPrefix(scenario)) } }?.id ?: ""
    }

    fun previewScenario(id: String): ScenarioPreview? {
        val scenario = SCENARIOS.firstOrNull { it.id == id } ?: return null
        val current = rules().filter { isScenarioRule(it) }
        val settings = listOf(
            SettingChange("Per-app kill switch", killSwitch(), scenario.killSwitch),
            SettingChange("Auto reconnect", autoReconnect(), scenario.autoReconnect),
            SettingChange("Main server failover", autoFailover(), scenario.autoFailover),
            SettingChange("Fastest main server on connect", fastestOnConnect(), scenario.fastestOnConnect)
        )
        val alreadyActive = activeScenarioId() == id
        return ScenarioPreview(scenario, settings, scenario.rules,
            if (alreadyActive) emptyList() else current, alreadyActive)
    }

    fun applyScenario(id: String): ActionResult {
        if (isConnected() || phase != ConnectionPhase.DISCONNECTED) return ActionResult(false, "Disconnect before changing presets.")
        val scenario = SCENARIOS.firstOrNull { it.id == id } ?: return ActionResult(false, "Preset was not found.")
        synchronized(state) {
            state.routeRules().removeIf { it.name().startsWith(SCENARIO_RULE_PREFIX) }
            state.routeRules().addAll(0, scenario.rules.map {
                RouteRule("", scenarioPrefix(scenario) + it.name, "", it.type, it.value, it.action, true)
            })
        }
        setAutoReconnect(scenario.autoReconnect)
        setAutoFailover(scenario.autoFailover)
        setFastestOnConnect(scenario.fastestOnConnect)
        setKillSwitch(scenario.killSwitch)
        save()
        return ActionResult(true, "${scenario.title} preset applied.")
    }

    fun clearScenario(): ActionResult {
        if (isConnected() || phase != ConnectionPhase.DISCONNECTED) return ActionResult(false, "Disconnect before changing presets.")
        synchronized(state) { state.routeRules().removeIf { it.name().startsWith(SCENARIO_RULE_PREFIX) } }
        save()
        return ActionResult(true, "Preset rules removed. Automation settings were left as they are.")
    }

    fun switchToFastest(): ActionResult {
        val fastest = fastestNode(excludeKey = if (nodes().size > 1) selectedNodeKey() else null)
            ?: return ActionResult(false, "No reachable server was found.")
        return switchNode(fastest.first.key())
    }

    private fun fastestNode(excludeKey: String? = null): Pair<ProxyNode, Long>? {
        val candidates = nodes().filter { excludeKey == null || it.key() != excludeKey }
        if (candidates.isEmpty()) return null
        val tcp = pingServers(candidates)
        val finalists = candidates.filter { (tcp[it.key()] ?: -1L) >= 0L }
            .sortedBy { tcp[it.key()] ?: Long.MAX_VALUE }.take(8)
        val latencies = realTestServers(finalists)
        return finalists.mapNotNull { node ->
            latencies[node.key()]?.takeIf { it >= 0L }?.let { node to it }
        }.minByOrNull { it.second }
    }

    fun pasteLinks(content: String): ActionResult {
        if (content.isBlank()) return ActionResult(false, "Paste one or more share links.")
        return try {
            val result = subscriptionService.parsePasted(content)
            merge(result, MANUAL_SOURCE, replaceSource = false)
            save()
            parseMessage(result)
        } catch (e: Throwable) {
            ActionResult(false, message(e))
        }
    }

    private fun preview(result: ParseResult, source: String, replaceSource: Boolean): ImportOutcome {
        if (result.nodes().isEmpty()) {
            return ImportOutcome(error = listOfNotNull("No valid servers were found.", result.warnings().firstOrNull()).joinToString(" "))
        }
        return synchronized(state) {
            val replaced = if (replaceSource) state.nodeSources().filterValues { it.equals(source, ignoreCase = true) }.keys else emptySet()
            val kept = state.nodes().filter { it.key() !in replaced }.mapTo(hashSetOf()) { it.identity() }
            val previous = state.nodes().filter { it.key() in replaced }.mapTo(hashSetOf()) { it.identity() }
            val seen = hashSetOf<String>()
            val candidates = result.nodes().filter { seen.add(it.identity()) }
                .map { ImportCandidate(it, it.identity() in kept || it.identity() in previous) }
            val incoming = candidates.mapTo(hashSetOf()) { it.node.identity() }
            ImportOutcome(ImportPreview(source, replaceSource, result, candidates, previous.count { it !in incoming }))
        }
    }

    /** Fetches and parses without saving anything, so the user can review servers and parser warnings. */
    fun previewSubscription(url: String): ImportOutcome {
        if (url.isBlank()) return ImportOutcome(error = "Enter a subscription URL.")
        return try { preview(subscriptionService.fetch(url.trim()), url.trim(), replaceSource = true) }
        catch (error: Throwable) { ImportOutcome(error = message(error)) }
    }

    fun previewPasted(content: String): ImportOutcome {
        if (content.isBlank()) return ImportOutcome(error = "Paste one or more share links.")
        return try { preview(subscriptionService.parsePasted(content), MANUAL_SOURCE, replaceSource = false) }
        catch (error: Throwable) { ImportOutcome(error = message(error)) }
    }

    fun previewQrImage(path: Path): ImportOutcome = try { previewPasted(qrImport.decode(path)) }
    catch (error: Throwable) { ImportOutcome(error = message(error)) }
    fun previewQrClipboard(): ImportOutcome = try { previewPasted(qrImport.decodeClipboard()) }
    catch (error: Throwable) { ImportOutcome(error = message(error)) }
    fun previewQrCamera(): ImportOutcome = try { previewPasted(qrImport.decodeCamera()) }
    catch (error: Throwable) { ImportOutcome(error = message(error)) }

    fun commitImport(preview: ImportPreview): ActionResult {
        if (preview.replaceSource) editLock("subscriptions")?.let { return it }
        return try {
            if (preview.replaceSource) storeSubscription(preview.result, preview.source)
            else { merge(preview.result, preview.source, replaceSource = false); save() }
            parseMessage(preview.result)
        } catch (error: Throwable) {
            ActionResult(false, message(error))
        }
    }

    fun importQrImage(path: Path): ActionResult = try { pasteLinks(qrImport.decode(path)) }
    catch (error: Throwable) { ActionResult(false, message(error)) }
    fun importQrClipboard(): ActionResult = try { pasteLinks(qrImport.decodeClipboard()) }
    catch (error: Throwable) { ActionResult(false, message(error)) }
    fun importQrCamera(): ActionResult = try { pasteLinks(qrImport.decodeCamera()) }
    catch (error: Throwable) { ActionResult(false, message(error)) }

    fun rules(): List<RouteRule> = synchronized(state) { state.routeRules().toList() }

    fun addRule(name: String, app: String, type: RouteRule.MatchType, value: String, action: RouteRule.Action): ActionResult {
        editLock("routing rules")?.let { return it }
        if (value.isBlank()) return ActionResult(false, "Enter a domain, IP/CIDR, or port.")
        return try {
            synchronized(state) { state.routeRules().add(RouteRule("", name.ifBlank { defaultRuleName(type) }, app, type, value, action, true)) }
            save(); ActionResult(true, "Rule added.")
        } catch (error: Throwable) { ActionResult(false, message(error)) }
    }

    /** Keeps the rule's identity and position, so the inspector and first-match order are unaffected. */
    fun updateRule(id: String, name: String, app: String, type: RouteRule.MatchType, value: String, action: RouteRule.Action): ActionResult {
        editLock("routing rules")?.let { return it }
        if (value.isBlank()) return ActionResult(false, "Enter a domain, IP/CIDR, or port.")
        synchronized(state) {
            val index = state.routeRules().indexOfFirst { it.id() == id }
            if (index < 0) return ActionResult(false, "Rule was not found.")
            val current = state.routeRules()[index]
            if (isScenarioRule(current)) return ActionResult(false, "Preset rules cannot be edited. Remove the preset or add your own rule.")
            state.routeRules()[index] = RouteRule(id, name.ifBlank { defaultRuleName(type) }, app, type, value, action, current.enabled())
        }
        save()
        return ActionResult(true, "Rule saved.")
    }

    /** Moves one of the user's rules up (-1) or down (+1). Preset rules stay above them. */
    fun moveRule(id: String, offset: Int): ActionResult {
        editLock("routing rules")?.let { return it }
        synchronized(state) {
            val rules = state.routeRules()
            val index = rules.indexOfFirst { it.id() == id }
            if (index < 0) return ActionResult(false, "Rule was not found.")
            if (isScenarioRule(rules[index])) return ActionResult(false, "Preset rules keep their place above your own rules.")
            val target = index + offset
            if (target !in rules.indices || isScenarioRule(rules[target])) return ActionResult(false, "The rule is already at that end of the list.")
            java.util.Collections.swap(rules, index, target)
        }
        save()
        return ActionResult(true, "Rule order updated.")
    }

    fun removeRule(id: String): ActionResult {
        editLock("routing rules")?.let { return it }
        synchronized(state) { state.routeRules().removeIf { it.id() == id } }
        save()
        return ActionResult(true, "Rule removed.")
    }

    fun setRuleEnabled(id: String, enabled: Boolean): ActionResult {
        editLock("routing rules")?.let { return it }
        synchronized(state) {
            val index = state.routeRules().indexOfFirst { it.id() == id }
            if (index >= 0) state.routeRules()[index] = state.routeRules()[index].let {
                RouteRule(it.id(), it.name(), it.applicationPath(), it.matchType(), it.value(), it.action(), enabled)
            }
        }
        save()
        return ActionResult(true, if (enabled) "Rule enabled." else "Rule disabled.")
    }

    fun profiles(): List<ConnectionProfile> = synchronized(state) { state.profiles().toList() }
    fun activeProfileId(): String = synchronized(state) { state.activeProfileId() }
    fun saveProfile(name: String): ActionResult {
        if (name.isBlank()) return ActionResult(false, "Enter a profile name.")
        synchronized(state) {
            state.profiles().removeIf { it.name().equals(name.trim(), true) }
            val profile = ConnectionProfile("", name.trim(), state.appPaths(), state.selectedNodeKey(),
                state.connectionMode(), state.routeRules(), state.advancedRouting(), LinkedHashMap(state.appRoutes()),
                LinkedHashSet(state.autoApps()))
            state.profiles().add(profile); state.setActiveProfileId(profile.id())
        }
        save(); return ActionResult(true, "Profile saved.")
    }
    fun applyProfile(id: String): ActionResult {
        if (isConnected() || phase != ConnectionPhase.DISCONNECTED) return ActionResult(false, "Disconnect before changing profiles.")
        val profile = synchronized(state) { state.profiles().firstOrNull { it.id() == id } }
            ?: return ActionResult(false, "Profile was not found.")
        synchronized(state) {
            state.appPaths().clear(); state.appPaths().addAll(profile.applicationPaths().filter { Files.isRegularFile(Path.of(it)) })
            if (state.nodes().any { it.key() == profile.nodeKey() }) state.setSelectedNodeKey(profile.nodeKey())
            state.setConnectionMode(profile.mode())
            state.routeRules().clear(); state.routeRules().addAll(profile.rules())
            state.setAdvancedRouting(profile.advancedRouting())
            // A profile saved before Advanced routing existed says nothing about assignments, so the
            // ones saved now are left in place (inactive, because that profile turns Advanced off).
            if (profile.advancedRouting() || profile.appRoutes().isNotEmpty()) storeRoutes(profile.appRoutes())
            // A profile with Advanced routing describes every app's mode, Auto included.
            if (profile.advancedRouting()) {
                state.autoApps().clear()
                state.autoApps().addAll(profile.autoApps())
                state.autoSelections().keys.retainAll(profile.autoApps())
            }
            state.setActiveProfileId(profile.id())
        }
        save()
        val unresolved = routePlan().missing().size
        return ActionResult(true, "${profile.name()} profile applied." +
            if (unresolved == 0) "" else " $unresolved app ${if (unresolved == 1) "assignment needs" else "assignments need"} a replacement server.")
    }
    fun removeProfile(id: String) { synchronized(state) { state.profiles().removeIf { it.id() == id }; if (state.activeProfileId() == id) state.setActiveProfileId("") }; save() }

    fun isFavorite(key: String): Boolean = synchronized(state) { key in state.favoriteNodeKeys() }
    fun toggleFavorite(key: String) { synchronized(state) { if (!state.favoriteNodeKeys().add(key)) state.favoriteNodeKeys().remove(key) }; save() }
    fun nodeTag(key: String): String = synchronized(state) { state.nodeTags()[key] ?: "" }
    fun setNodeTag(key: String, tag: String) { synchronized(state) { if (tag.isBlank()) state.nodeTags().remove(key) else state.nodeTags()[key] = tag.trim() }; save() }
    fun isNodeHidden(key: String): Boolean = synchronized(state) { key in state.hiddenNodeKeys() }
    fun setNodeHidden(key: String, hidden: Boolean) { synchronized(state) { if (hidden) state.hiddenNodeKeys().add(key) else state.hiddenNodeKeys().remove(key) }; save() }

    private fun parseMessage(result: ParseResult): ActionResult {
        result.warnings().forEach { log("Warning: $it") }
        val count = result.nodes().size
        if (count == 0) return ActionResult(false, listOfNotNull("No valid servers were found.", result.warnings().firstOrNull()).joinToString(" "))
        log("$count servers were added or updated.")
        return ActionResult(true, "$count servers are ready.")
    }

    private fun merge(result: ParseResult, source: String, replaceSource: Boolean) {
        synchronized(state) {
            var incoming = result.nodes()
            var staleKeys = emptySet<String>()
            if (replaceSource) {
                staleKeys = state.nodeSources().filterValues { it.equals(source, ignoreCase = true) }.keys.toSet()
                // A refreshed server keeps its previous key, which is what favorites, tags and
                // per-app assignments are stored against.
                val previousKeys = state.nodes().filter { it.key() in staleKeys }.associate { it.identity() to it.key() }
                incoming = incoming.map { node ->
                    val previous = previousKeys[node.identity()]
                    if (previous == null || previous == node.key()) node
                    else ProxyNode(previous, node.name(), node.protocol(), node.address(), node.port(),
                        node.credential(), node.options(), node.sourceLink())
                }
                state.nodes().removeIf { it.key() in staleKeys }
                staleKeys.forEach { state.nodeSources().remove(it) }
            }
            val merged = linkedMapOf<String, ProxyNode>()
            state.nodes().forEach { merged.putIfAbsent(it.identity(), it) }
            incoming.forEach { node ->
                if (!merged.containsKey(node.identity())) {
                    merged[node.identity()] = node
                    state.nodeSources()[node.key()] = source
                }
            }
            state.nodes().clear()
            state.nodes().addAll(merged.values)
            val liveKeys = state.nodes().mapTo(hashSetOf()) { it.key() }
            state.nodeSources().keys.removeIf { it !in liveKeys }
            staleKeys.filter { it !in liveKeys }.forEach { key ->
                state.favoriteNodeKeys().remove(key)
                state.hiddenNodeKeys().remove(key)
                state.nodeTags().remove(key)
            }
            if (state.selectedNodeKey().isBlank() || state.nodes().none { it.key() == state.selectedNodeKey() }) {
                state.setSelectedNodeKey(state.nodes().firstOrNull()?.key() ?: "")
            }
            val dropped = state.appRoutes().values.filter { it in staleKeys && it !in liveKeys }.distinct()
            if (dropped.isNotEmpty()) {
                log("${dropped.size} assigned ${if (dropped.size == 1) "server is" else "servers are"} no longer in ${sourceLabel(source)}. " +
                    "The affected apps keep their assignment until you choose a replacement in Apps.")
            }
        }
    }

    private fun deduplicateNodes(): Boolean = synchronized(state) {
        val unique = linkedMapOf<String, ProxyNode>()
        val replacements = mutableMapOf<String, String>()
        state.nodes().forEach { node ->
            val kept = unique.putIfAbsent(node.identity(), node)
            if (kept != null) replacements[node.key()] = kept.key()
        }
        if (replacements.isEmpty()) return@synchronized false
        state.nodes().clear()
        state.nodes().addAll(unique.values)
        replacements.forEach { (removed, kept) ->
            state.nodeSources().remove(removed)
            if (state.favoriteNodeKeys().remove(removed)) state.favoriteNodeKeys().add(kept)
            if (state.hiddenNodeKeys().remove(removed)) state.hiddenNodeKeys().add(kept)
            state.nodeTags().remove(removed)?.let { tag -> state.nodeTags().putIfAbsent(kept, tag) }
            state.appRouteLabels().remove(removed)?.let { label -> state.appRouteLabels().putIfAbsent(kept, label) }
        }
        state.appRoutes().replaceAll { _, key -> replacements[key] ?: key }
        state.autoSelections().replaceAll { _, selection -> replacements[selection.nodeKey()]?.let { AutoSelection(it, selection.changedAt()) } ?: selection }
        replacements[state.selectedNodeKey()]?.let(state::setSelectedNodeKey)
        true
    }

    fun installCore(progress: (String) -> Unit): ActionResult = try {
        coreInstaller.install(Consumer { text ->
            progress(text)
            log(text)
        })
        ActionResult(true, "Xray and Wintun are installed.")
    } catch (e: Throwable) {
        ActionResult(false, message(e))
    }

    fun installPerApp(progress: (String) -> Unit): ActionResult = try {
        proxiFyre.downloadAndInstall(Consumer { text ->
            progress(text)
            log(text)
        })
        ActionResult(true, "The per-app engine is installed.")
    } catch (e: Throwable) {
        ActionResult(false, message(e))
    }

    private class PreparedConnection(
        val config: String,
        val tun: Boolean,
        val routes: List<ActiveRoute>,
        val gateway: Boolean = false,
        val applications: List<String> = emptyList(),
        val autoChoices: Map<String, String> = emptyMap(),
        val plan: RoutePlanner.Plan,
        val runtimeControl: Boolean = false
    )

    private fun missingRouteMessage(plan: RoutePlanner.Plan): String {
        val first = plan.missing().first()
        val app = displayName(first.applicationPath())
        if (first.problem() == RoutePlanner.Problem.NO_SERVER) {
            return "$app is in Auto mode, but no server can be chosen for it: there is no measured server in its pool and no main server. " +
                "Add a server or choose Main or Manual for it in Apps. It is not released to the direct route."
        }
        val server = first.label().ifBlank { "its custom server" }
        val reason = "$app is assigned to $server, which is no longer in your server list."
        val more = if (plan.missing().size > 1) " ${plan.missing().size - 1} more ${if (plan.missing().size == 2) "app has" else "apps have"} the same problem." else ""
        return "$reason$more Open Apps and choose a replacement, or turn off Advanced routing to use the main server for every app."
    }

    /**
     * Builds the whole config for a main server, re-applying every per-app assignment that is in effect. Each
     * application in Auto mode gets a route of its own (a copy of its server under a key nobody else uses), so
     * its server can later be replaced in the running core without touching any other application.
     */
    private fun prepare(main: ProxyNode, mode: ConnectionMode, apps: List<String>,
                        autoChoices: Map<String, String> = autoChoices(persist = false)): PreparedConnection {
        val plan = synchronized(state) {
            RoutePlanner.plan(state.nodes().toList(), main.key(), apps, state.advancedRouting(),
                LinkedHashMap(state.appRoutes()), LinkedHashMap(state.appRouteLabels()), mode, autoChoices)
        }
        check(plan.ready()) { missingRouteMessage(plan) }
        val tun = mode == ConnectionMode.TUN_COMPATIBILITY
        val perApp = mode == ConnectionMode.NATIVE_PER_APP
        val gateway = perApp && trafficGateway()
        // The core's control API is only enabled for features that change routes in place.
        val runtimeControl = !tun && (routeMonitoring() || hasAutoApps())
        val generator = XrayConfigGenerator(if (gateway) XrayConfigGenerator.GATEWAY_UPSTREAM_PORT_BASE else XrayConfigGenerator.PER_APP_PORT_BASE, runtimeControl)
        val configNodes = if (!perApp) emptyList() else plan.applicationNodes().mapIndexed { index, node ->
            if (node != null && plan.routeFor(apps[index])?.auto() == true) autoSlot(apps[index], node) else node
        }
        val config = if (tun) configGenerator.generate(main, apps, state.routingMode())
            else generator.generateApplicationProxy(main, if (perApp) apps else emptyList(), rules(), configNodes)
        val customKeys = if (perApp) XrayConfigGenerator.customRouteKeys(main, apps, configNodes) else emptyList()
        val routes = plan.routes().map { route ->
            val index = customKeys.indexOf(if (route.auto()) autoSlotKey(route.applications().first()) else route.node().key())
            ActiveRoute(route.node(), route.main(), if (perApp) route.applications() else emptyList(),
                if (route.main()) AppProxyLauncher.SOCKS_PORT else XrayConfigGenerator.routeCheckPort(index),
                id = route.id(), auto = route.auto(),
                ruleTag = XrayConfigGenerator.applicationRouteRuleTag(if (route.main()) -1 else index))
        }
        return PreparedConnection(config, tun, routes, gateway, apps, if (perApp) autoChoices else emptyMap(), plan, runtimeControl)
    }

    private fun autoSlotKey(application: String) = "auto-slot:" + AppRoutes.normalize(application)

    private fun autoSlot(application: String, node: ProxyNode) = ProxyNode(autoSlotKey(application), node.name(), node.protocol(),
        node.address(), node.port(), node.credential(), node.options(), node.sourceLink())

    /**
     * Has Xray check the whole config before anything running is touched. When several servers share
     * the config and Xray rejects it, each server is checked on its own so the message can name the
     * one that is at fault instead of quoting an internal outbound tag.
     */
    private fun validate(prepared: PreparedConnection) {
        try {
            backend.validate(prepared.config, prepared.tun)
        } catch (rejected: Throwable) {
            if (prepared.tun || prepared.routes.size < 2) throw rejected
            for (route in prepared.routes) {
                val alone = runCatching { backend.validate(configGenerator.generateApplicationProxy(route.node), false) }.exceptionOrNull() ?: continue
                val reason = message(alone).substringAfterLast(" > ")
                val role = if (route.main) "the main server"
                    else "the custom server of ${route.applications.joinToString(", ") { displayName(it) }}"
                error("Xray does not accept ${cleanName(route.node.name())}, $role: $reason. Choose a different server for it.")
            }
            throw rejected
        }
    }

    private fun activate(prepared: PreparedConnection) {
        activeRoutes = prepared.routes
        runningConfig = prepared.config
        runningPrepared = prepared
        inspector.clear()
        synchronized(routeCheckLock) {
            routeChecks = routeChecks.filterKeys { it == DIRECT_ROUTE_KEY } +
                if (prepared.tun) emptyMap() else prepared.routes.associate { it.id to RouteCheck(RouteCheckState.CHECKING) }
        }
        if (prepared.tun) recovery.stopWatching()
        else recovery.start(prepared.routes.map { RouteTarget(it.id, cleanName(it.node.name()), it.node.key(), it.checkPort) }, routeMonitoring())
        startAutoLoop()
    }

    @Synchronized
    fun connect(): ActionResult {
        if (isConnected()) return ActionResult(true, "Already connected.")
        if (phase == ConnectionPhase.CONNECTING || phase == ConnectionPhase.DISCONNECTING) {
            return ActionResult(false, "A connection operation is already in progress.")
        }
        val mode = state.connectionMode()
        val apps = applications()
        var node = selectedNode() ?: return ActionResult(false, "Select a server first.")
        if ((mode == ConnectionMode.NATIVE_PER_APP || mode == ConnectionMode.TUN_COMPATIBILITY) && apps.isEmpty()) {
            return ActionResult(false, "Select at least one application for Smart per-app.")
        }
        // Applications in Auto mode are decided now, from the latest scores, and the choices are saved.
        val autoChoices = autoChoices(persist = true)
        val plan = routePlan()
        if (!plan.ready()) return ActionResult(false, missingRouteMessage(plan))
        if (!backend.coreReady()) return ActionResult(false, "Xray is not installed. Install it from Settings.")
        if (mode == ConnectionMode.NATIVE_PER_APP && !backend.perAppReady()) {
            return ActionResult(false, "Smart per-app needs the bundled Windows driver. Reinstall PerRoute with the Full Installer and approve its single UAC prompt, or choose Local proxy/System proxy.")
        }
        if (mode == ConnectionMode.TUN_COMPATIBILITY && !backend.elevated()) {
            return ActionResult(false, "TUN mode requires Administrator privileges.")
        }
        phase = ConnectionPhase.CONNECTING
        connectionReady = false
        return try {
            if (fastestOnConnect()) {
                fastestNode()?.let { fastest ->
                    node = fastest.first
                    state.setSelectedNodeKey(node.key())
                    save()
                    log("Fastest server selected: ${cleanName(node.name())} (${fastest.second} ms).")
                }
            }
            // A new fastest main server can change what an Auto app without measurements falls back to.
            val prepared = prepare(node, mode, apps, if (node.key() == plan.main()?.key()) autoChoices else autoChoices(persist = true))
            val tun = prepared.tun
            validate(prepared)
            if (mode == ConnectionMode.NATIVE_PER_APP && killSwitch()) {
                backend.applyPerApp(apps)
            }
            backend.stopGateway()
            backend.stop()
            backend.start(prepared.config, tun)
            if (!tun) {
                Thread.sleep(250)
                if (!backend.isRunning()) error("Xray stopped immediately after launch.")
                // The gateway listens where the per-app driver sends each app, and forwards to the app's own
                // inbound. It must be up before the driver starts, or the apps would point at a closed port.
                if (prepared.gateway) startGateway(prepared)
                if (mode == ConnectionMode.SYSTEM_PROXY) {
                    backend.applySystemProxy()
                } else if (mode == ConnectionMode.NATIVE_PER_APP && !killSwitch()) {
                    backend.applyPerApp(apps)
                }
            }
            activeMode = mode
            connectionReady = true
            phase = ConnectionPhase.CONNECTED
            desiredConnected = true
            markSession(true)
            activate(prepared)
            startTelemetry(mode, apps)
            stateListeners.forEach { it(true) }
            val customs = prepared.routes.count { !it.main }
            log("Connected to ${cleanName(node.name())}" + if (customs == 0) "." else " with $customs custom ${if (customs == 1) "server" else "servers"}.")
            ActionResult(true, if (mode == ConnectionMode.BROWSER_PROXY)
                "Local proxy ready: SOCKS5 127.0.0.1:${AppProxyLauncher.SOCKS_PORT} • HTTP 127.0.0.1:${AppProxyLauncher.HTTP_PORT}"
            else "Connected.")
        } catch (e: Throwable) {
            if (mode == ConnectionMode.NATIVE_PER_APP && !killSwitch()) runCatching { backend.restorePerApp() }
            if (mode == ConnectionMode.SYSTEM_PROXY) runCatching { backend.restoreSystemProxy() }
            runCatching { backend.stopGateway() }
            backend.stop()
            activeMode = null
            connectionReady = false
            phase = ConnectionPhase.DISCONNECTED
            stopTelemetry()
            stateListeners.forEach { it(false) }
            ActionResult(false, message(e))
        }
    }

    /**
     * Changes the main server. While connected the shared core is restarted with a config that still
     * carries every custom server, so applications on custom servers are interrupted briefly as well.
     */
    @Synchronized
    fun switchNode(key: String): ActionResult {
        val target = nodes().firstOrNull { it.key() == key }
            ?: return ActionResult(false, "Server was not found.")
        if (!isConnected()) {
            if (phase != ConnectionPhase.DISCONNECTED) return ActionResult(false, "Wait for the current connection operation to finish.")
            selectNode(key)
            return ActionResult(true, "Main server selected.")
        }
        if (state.selectedNodeKey() == key) return ActionResult(true, "This server is already the main server.")
        if (phase != ConnectionPhase.CONNECTED) return ActionResult(false, "Wait for the current connection operation to finish.")

        val previous = selectedNode() ?: return ActionResult(false, "The active server was not found.")
        val mode = activeMode ?: state.connectionMode()
        val running = runningPrepared
        val apps = running?.applications ?: applications()
        val choices = running?.autoChoices ?: autoChoices(persist = false)
        val prepared: PreparedConnection
        val fallback: PreparedConnection
        try {
            fallback = prepare(previous, mode, apps, choices)
            prepared = prepare(target, mode, apps, choices)
            validate(prepared)
        } catch (invalid: Throwable) {
            return ActionResult(false, "Could not switch servers; the current connection was left untouched: ${message(invalid)}")
        }
        // In place first: only the main route's outbound is replaced, so custom and Auto routes keep their
        // connections. When that is not possible (embedded profiles, a server that is also a custom route),
        // the core is restarted as before, with the previous connection restored if the restart fails.
        val previousRouteId = activeRoutes.firstOrNull { it.main }?.id ?: previous.key()
        val inPlace = runCatching { applyRouteChange(previousRouteId, prepared) }
        if (inPlace.getOrNull() == true) {
            state.setSelectedNodeKey(key)
            save()
            val main = activeRoutes.first { it.main }
            recovery.remove(previousRouteId)
            recovery.replace(RouteTarget(main.id, cleanName(target.name()), target.key(), main.checkPort))
            synchronized(routeCheckLock) { routeChecks = routeChecks - previousRouteId + (main.id to RouteCheck(RouteCheckState.CHECKING)) }
            stateListeners.forEach { it(true) }
            log("Switched to ${cleanName(target.name())}. Custom and Auto routes kept running.")
            return ActionResult(true, "Switched to ${cleanName(target.name())}.")
        }
        inPlace.exceptionOrNull()?.let { log("The main server could not be replaced in place (${message(it)}); restarting the core.") }
        state.setSelectedNodeKey(key)
        save()
        return restartCore(prepared, fallback, mode, "Switched to ${cleanName(target.name())}.") {
            state.setSelectedNodeKey(previous.key())
            save()
        }
    }

    /**
     * Moves one live route to another server without disconnecting, so a route that failed can be fixed where
     * it is. The main route changes the main server; a custom route moves every app assigned to it; an Auto
     * route is pinned to the server until Auto decides again. Only that route's outbound is replaced when the
     * core allows it; otherwise the core is restarted, with the previous connection restored if that fails.
     */
    @Synchronized
    fun changeRouteServer(routeId: String, key: String): ActionResult {
        if (!isConnected()) return ActionResult(false, "Connect to change the server of a route.")
        if (phase != ConnectionPhase.CONNECTED) return ActionResult(false, "Wait for the current connection operation to finish.")
        val route = activeRoutes.firstOrNull { it.id == routeId } ?: return ActionResult(false, "That route is no longer in use.")
        if (route.main) return switchNode(key)
        val target = nodes().firstOrNull { it.key() == key } ?: return ActionResult(false, "Server was not found.")
        val name = cleanName(target.name())
        if (route.node.key() == key) return ActionResult(true, "This route already uses $name.")
        val running = runningPrepared ?: return ActionResult(false, "Connect to change the server of a route.")
        val main = selectedNode() ?: return ActionResult(false, "The active server was not found.")
        if (route.applications.isEmpty()) return ActionResult(false, "This route carries no apps.")
        val mode = activeMode ?: state.connectionMode()
        val apps = route.applications.map { AppRoutes.normalize(it) }
        val who = joinNames(route.applications.map(::displayName))

        // The new choice is saved first so the config is built from it; [revert] puts the previous one back.
        var choices = running.autoChoices
        val revert: () -> Unit
        if (route.auto) {
            val app = apps.first()
            val before = synchronized(state) { state.autoSelections()[app] }
            synchronized(state) { state.autoSelections()[app] = AutoSelection(key, System.currentTimeMillis()) }
            choices = choices + (app to key)
            revert = { synchronized(state) { if (before == null) state.autoSelections().remove(app) else state.autoSelections()[app] = before } }
        } else {
            val (routes, labels) = synchronized(state) { LinkedHashMap(state.appRoutes()) to LinkedHashMap(state.appRouteLabels()) }
            synchronized(state) {
                apps.forEach { state.appRoutes()[it] = key }
                state.appRouteLabels()[key] = name
                state.appRouteLabels().keys.retainAll(state.appRoutes().values.toSet())
            }
            revert = {
                synchronized(state) {
                    state.appRoutes().clear(); state.appRoutes().putAll(routes)
                    state.appRouteLabels().clear(); state.appRouteLabels().putAll(labels)
                }
            }
        }
        val prepared: PreparedConnection
        try {
            prepared = prepare(main, mode, running.applications, choices)
            validate(prepared)
        } catch (invalid: Throwable) {
            revert()
            return ActionResult(false, "Could not change the server; the current connection was left untouched: ${message(invalid)}")
        }
        save()
        val inPlace = runCatching { applyRouteChange(route.id, prepared) }
        if (inPlace.getOrNull() == true) {
            val moved = activeRoutes.firstOrNull { it.ruleTag == route.ruleTag } ?: activeRoutes.first { it.node.key() == key }
            recovery.remove(route.id)
            recovery.replace(RouteTarget(moved.id, name, target.key(), moved.checkPort))
            recovery.note(moved.id, RouteHealthTracker.EventType.FAILOVER, "Server changed", "Now using $name. Other routes were not restarted.")
            synchronized(routeCheckLock) { routeChecks = routeChecks - route.id + (moved.id to RouteCheck(RouteCheckState.CHECKING)) }
            stateListeners.forEach { it(true) }
            log("Moved $who to $name. Other routes kept running.")
            return ActionResult(true, "$who now ${if (route.applications.size == 1) "uses" else "use"} $name.")
        }
        inPlace.exceptionOrNull()?.let { log("The route of $who could not be changed in place (${message(it)}); restarting the core.") }
        return restartCore(prepared, running, mode, "Moved $who to $name.") { revert(); save() }
    }

    /**
     * Restarts the running core with [prepared]. When that fails, [revert] undoes the saved choice and
     * [fallback] is started again; when that fails too, the connection is stopped.
     */
    private fun restartCore(prepared: PreparedConnection, fallback: PreparedConnection, mode: ConnectionMode,
                            done: String, revert: () -> Unit): ActionResult {
        phase = ConnectionPhase.CONNECTING
        connectionReady = false
        return try {
            backend.stop()
            backend.start(prepared.config, prepared.tun)
            Thread.sleep(250)
            if (!backend.isRunning()) error("Xray stopped while switching servers.")
            activeMode = mode
            connectionReady = true
            phase = ConnectionPhase.CONNECTED
            activate(prepared)
            stateListeners.forEach { it(true) }
            log(done)
            ActionResult(true, done)
        } catch (switchError: Throwable) {
            revert()
            val restored = runCatching {
                backend.stop()
                backend.start(fallback.config, fallback.tun)
                Thread.sleep(250)
                check(backend.isRunning()) { "Previous server could not be restored." }
            }.isSuccess
            if (restored) {
                activeMode = mode
                connectionReady = true
                phase = ConnectionPhase.CONNECTED
                activate(fallback)
                stateListeners.forEach { it(true) }
                ActionResult(false, "Could not switch servers; the previous connection was restored: ${message(switchError)}")
            } else {
                if (mode == ConnectionMode.NATIVE_PER_APP && !killSwitch()) runCatching { backend.restorePerApp() }
                if (mode == ConnectionMode.SYSTEM_PROXY) runCatching { backend.restoreSystemProxy() }
                runCatching { backend.stopGateway() }
                backend.stop()
                activeMode = null
                connectionReady = false
                phase = ConnectionPhase.DISCONNECTED
                stopTelemetry()
                stateListeners.forEach { it(false) }
                ActionResult(false, "Server switch failed and the connection was stopped: ${message(switchError)}")
            }
        }
    }

    @Synchronized
    fun disconnect(): ActionResult {
        desiredConnected = false
        if (phase == ConnectionPhase.DISCONNECTED && !backend.isRunning()) {
            return ActionResult(true, "Already disconnected.")
        }
        phase = ConnectionPhase.DISCONNECTING
        val disconnectMode = activeMode ?: state.connectionMode()
        val cleanupError = runCatching {
            if (disconnectMode == ConnectionMode.NATIVE_PER_APP) {
                if (!killSwitch()) backend.restorePerApp()
            } else backend.stopPerAppSidecar()
            backend.restoreSystemProxy()
        }.exceptionOrNull()
        backend.stop()
        runCatching { backend.stopGateway() }
        activeMode = null
        connectionReady = false
        phase = ConnectionPhase.DISCONNECTED
        stopTelemetry()
        stateListeners.forEach { it(false) }
        return if (cleanupError == null) {
            log("Disconnected.")
            ActionResult(true, "Disconnected.")
        } else {
            ActionResult(false, "Disconnected, but cleanup was incomplete: ${message(cleanupError)}")
        }
    }

    private fun selectedNode(): ProxyNode? {
        val list = nodes()
        return list.firstOrNull { it.key() == state.selectedNodeKey() } ?: list.firstOrNull()
    }

    private fun startTelemetry(mode: ConnectionMode, apps: List<String>) {
        val generation = telemetryGeneration.incrementAndGet()
        connectedAtMillis = System.currentTimeMillis()
        traffic = TrafficSnapshot(applications = apps.map(::AppTraffic))
        Thread.ofVirtual().name("perroute-telemetry").start {
            var previous = emptyMap<String, Long>()
            var previousAt = System.nanoTime()
            var latency: Long? = null
            var latencyTick = 0
            var failedLatencyChecks = 0
            val lastActive = mutableMapOf<Int, Long>()
            var qualityTick = 0
            while (telemetryGeneration.get() == generation) {
                try {
                    // With the gateway the per-app byte counts are already in this process, exact and free;
                    // without it they are read from the core's statistics API.
                    val counters = if (gatewayActive) gatewayCountersAsInbound(apps) else backend.readInboundCounters()
                    val nowNanos = System.nanoTime()
                    val elapsed = ((nowNanos - previousAt) / 1_000_000_000.0).coerceAtLeast(.2)
                    val nowMillis = System.currentTimeMillis()
                    fun value(tag: String, direction: String): Long =
                        counters["inbound>>>$tag>>>traffic>>>$direction"] ?: 0L
                    fun delta(tag: String, direction: String): Long =
                        (value(tag, direction) - (previous["inbound>>>$tag>>>traffic>>>$direction"] ?: value(tag, direction))).coerceAtLeast(0L)

                    val appTraffic = apps.mapIndexed { index, path ->
                        val tag = XrayConfigGenerator.perAppTag(index)
                        val downDelta = delta(tag, "downlink")
                        val upDelta = delta(tag, "uplink")
                        history.record(path, downDelta, upDelta)
                        if (downDelta + upDelta > 0) lastActive[index] = nowMillis
                        AppTraffic(
                            path = path,
                            downloadBytes = value(tag, "downlink"),
                            uploadBytes = value(tag, "uplink"),
                            downloadBytesPerSecond = (downDelta / elapsed).toLong(),
                            uploadBytesPerSecond = (upDelta / elapsed).toLong(),
                            active = nowMillis - (lastActive[index] ?: 0L) <= 5_000L
                        )
                    }
                    // Every byte enters through exactly one inbound. Per-app mode counts the per-app
                    // inbounds, the other modes the shared ones; the two sets are never added together,
                    // and the route-check inbounds are left out of both.
                    val countedTags = if (mode == ConnectionMode.NATIVE_PER_APP) {
                        apps.indices.map(XrayConfigGenerator::perAppTag)
                    } else listOf("perroute-socks", "perroute-http")
                    traffic = TrafficSnapshot(
                        downloadBytesPerSecond = (countedTags.sumOf { delta(it, "downlink") } / elapsed).toLong(),
                        uploadBytesPerSecond = (countedTags.sumOf { delta(it, "uplink") } / elapsed).toLong(),
                        totalDownloadBytes = countedTags.sumOf { value(it, "downlink") },
                        totalUploadBytes = countedTags.sumOf { value(it, "uplink") },
                        connectedSeconds = ((nowMillis - connectedAtMillis) / 1_000L).coerceAtLeast(0L),
                        latencyMillis = latency,
                        applications = appTraffic
                    )
                    trafficPoints += TrafficPoint(traffic.downloadBytesPerSecond, traffic.uploadBytesPerSecond)
                    while (trafficPoints.size > 90) trafficPoints.removeAt(0)
                    previous = counters
                    previousAt = nowNanos

                    // Only the main server is watched and failed over. Custom servers are checked on
                    // connect and on request; they are never switched automatically.
                    if (++latencyTick >= 5) {
                        latencyTick = 0
                        val current = selectedNode()
                        val result = current?.let { latencyProbe.probe(it, 2_000) }
                        // Every twelfth check (once a minute) also counts as quality evidence for the main server.
                        if (current != null && result != null && ++qualityTick % 12 == 0) {
                            quality.recordReachability(current.key(), if (result.reachable()) result.milliseconds() else -1L)
                        }
                        if (result?.reachable() == true) {
                            latency = result.milliseconds()
                            failedLatencyChecks = 0
                        } else {
                            latency = null
                            failedLatencyChecks++
                            if (failedLatencyChecks >= 3 && autoFailover() && telemetryGeneration.get() == generation) {
                                failedLatencyChecks = 0
                                log("The main server stopped responding. Looking for the fastest fallback.")
                                val fallback = fastestNode(excludeKey = current?.key())
                                if (fallback != null) {
                                    val resultSwitch = switchNode(fallback.first.key())
                                    if (resultSwitch.ok) log("Auto failover selected ${cleanName(fallback.first.name())} (${fallback.second} ms).")
                                    else log("Auto failover could not switch: ${resultSwitch.message}")
                                    previous = emptyMap()
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
                try { Thread.sleep(1_000) } catch (_: InterruptedException) { break }
            }
        }
    }

    /** The gateway's per-app totals in the same shape as the core's inbound counters. */
    private fun gatewayCountersAsInbound(apps: List<String>): Map<String, Long> {
        val counters = backend.gatewayCounters()
        val byPath = counters.mapKeys { AppRoutes.normalize(it.key) }
        val result = HashMap<String, Long>()
        apps.forEachIndexed { index, path ->
            val app = byPath[AppRoutes.normalize(path)] ?: return@forEachIndexed
            val tag = XrayConfigGenerator.perAppTag(index)
            result["inbound>>>$tag>>>traffic>>>downlink"] = app.downloadBytes()
            result["inbound>>>$tag>>>traffic>>>uplink"] = app.uploadBytes()
        }
        return result
    }

    private fun stopTelemetry() {
        markSession(false)
        telemetryGeneration.incrementAndGet()
        autoLoopGeneration.incrementAndGet()
        connectedAtMillis = 0L
        traffic = TrafficSnapshot()
        activeRoutes = emptyList()
        runningConfig = null
        runningPrepared = null
        gatewayActive = false
        inspector.gatewayActive(false)
        inspector.clear()
        recovery.stopWatching()
        synchronized(routeCheckLock) { routeChecks = routeChecks.filterKeys { it == DIRECT_ROUTE_KEY } }
    }

    private fun scheduleReconnect() {
        if (!desiredConnected || !autoReconnect() || closing || !reconnectRunning.compareAndSet(false, true)) return
        Thread.ofVirtual().name("perroute-auto-reconnect").start {
            try {
                var delayMillis = 1_500L
                while (desiredConnected && autoReconnect() && !closing) {
                    try { Thread.sleep(delayMillis) } catch (_: InterruptedException) { break }
                    if (isConnected()) break
                    val result = connect()
                    if (result.ok && isConnected()) { log("Connection restored automatically."); break }
                    log("Auto reconnect is waiting: ${result.message}")
                    delayMillis = (delayMillis * 2).coerceAtMost(20_000L)
                }
            } finally { reconnectRunning.set(false) }
        }
    }

    private fun startNetworkMonitor() {
        Thread.ofVirtual().name("perroute-network-monitor").start {
            var previous = networkSignature()
            while (!closing) {
                try { Thread.sleep(4_000) } catch (_: InterruptedException) { break }
                val current = networkSignature()
                if (current != previous) {
                    previous = current
                    if (desiredConnected && isConnected()) {
                        Thread.ofVirtual().name("perroute-network-recheck").start {
                            try { Thread.sleep(1_500) } catch (_: InterruptedException) { return@start }
                            if (!desiredConnected || !isConnected()) return@start
                            if (routeMonitoring() && activeRoutes.size > 1) {
                                // Every route is checked and recovered on its own. Only when none of them
                                // answers is the core itself assumed stuck and restarted.
                                val routes = activeRoutes
                                routes.forEach { recovery.retryNow(it.id) }
                                val anyAlive = routes.any { runCatching { backend.checkTunnel(it.checkPort).success() }.getOrDefault(false) }
                                if (!anyAlive && desiredConnected && isConnected()) {
                                    log("Network changed and no route responds; reconnecting.")
                                    backend.stop()
                                }
                            } else if (!runCatching { backend.checkTunnel(AppProxyLauncher.SOCKS_PORT).success() }.getOrDefault(false)) {
                                log("Network changed and the tunnel no longer responds; reconnecting.")
                                backend.stop()
                            }
                        }
                    } else scheduleReconnect()
                }
            }
        }
    }

    private fun networkSignature(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { network -> network.inetAddresses.toList().map { "${network.name}:${it.hostAddress}" } }
            .sorted().joinToString("|")
    }.getOrDefault("")

    private fun save() {
        if (!savePending.compareAndSet(false, true)) return
        runCatching {
            saveExecutor.execute {
                try { Thread.sleep(150) } catch (_: InterruptedException) { }
                savePending.set(false)
                saveNow()
            }
        }.onFailure { savePending.set(false); saveNow() }
    }

    private fun saveNow() {
        runCatching { store.write(synchronized(state) { store.serialize(state) }) }
            .onFailure { log("Could not save settings: ${message(it)}") }
    }

    private fun log(text: String) {
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        logListeners.forEach { it("[$time] $text") }
    }

    @Synchronized
    override fun close() {
        closing = true
        desiredConnected = false
        phase = ConnectionPhase.DISCONNECTING
        val closingMode = activeMode ?: state.connectionMode()
        runCatching {
            if (closingMode == ConnectionMode.NATIVE_PER_APP) {
                if (!killSwitch()) backend.restorePerApp()
            } else backend.stopPerAppSidecar()
            backend.restoreSystemProxy()
        }
        activeMode = null
        connectionReady = false
        stopTelemetry()
        backend.stop()
        runCatching { backend.stopGateway() }
        phase = ConnectionPhase.DISCONNECTED
        recovery.close()
        runCatching { quality.close() }
        history.close()
        saveExecutor.shutdown()
        runCatching { saveExecutor.awaitTermination(20, TimeUnit.SECONDS) }
        saveNow()
    }

    /** How a backed-up preference is validated before it is written back. */
    private enum class PreferenceType { BOOLEAN, INT, URL }

    companion object {
        private const val PREF_V3_INITIALIZED = "v3.initialized"
        private const val PREF_QUALITY_BACKGROUND = "v3.qualityBackground"
        private const val PREF_ROUTE_MONITORING = "v3.routeMonitoring"
        private const val PREF_TRAFFIC_GATEWAY = "v3.trafficGateway"
        private const val PREF_AUTO_MARGIN = "v3.autoSwitchMargin"
        private const val PREF_AUTO_COOLDOWN = "v3.autoCooldownMinutes"
        private const val ROLLBACK_PREFIX = "rollback.pref."
        /** The interface design. A per-computer appearance choice, so backups do not carry it. */
        const val PREF_UI_STYLE = "ui.style"
        const val UI_STYLE_NEW = "new"
        const val UI_STYLE_SIMPLE = "simple"
        /** How often applications in Auto mode are re-decided while connected. */
        const val AUTO_INTERVAL_MILLIS = 60_000L

        /** The preferences a backup carries. Anything else (window state, caches) stays on this computer. */
        private val BACKUP_PREFERENCES = linkedMapOf(
            "darkTheme" to PreferenceType.BOOLEAN,
            "autoFailover" to PreferenceType.BOOLEAN,
            "fastestOnConnect" to PreferenceType.BOOLEAN,
            "autoSpeedTest" to PreferenceType.BOOLEAN,
            "autoReconnect" to PreferenceType.BOOLEAN,
            "perAppKillSwitch" to PreferenceType.BOOLEAN,
            "updateFeedUrl" to PreferenceType.URL,
            PREF_QUALITY_BACKGROUND to PreferenceType.BOOLEAN,
            PREF_ROUTE_MONITORING to PreferenceType.BOOLEAN,
            PREF_TRAFFIC_GATEWAY to PreferenceType.BOOLEAN,
            PREF_AUTO_MARGIN to PreferenceType.INT,
            PREF_AUTO_COOLDOWN to PreferenceType.INT
        )

        const val MANUAL_SOURCE = "__manual__"
        const val IMPORTED_SOURCE = "__imported__"
        const val DIRECT_ROUTE_KEY = "__direct__"
        const val ROUTE_CHECK_STALE_MILLIS = 5 * 60_000L
        const val SCENARIO_RULE_PREFIX = "Scenario · "
        private fun scenarioPrefix(scenario: Scenario) = "$SCENARIO_RULE_PREFIX${scenario.title} · "
        fun isScenarioRule(rule: RouteRule): Boolean = rule.name().startsWith(SCENARIO_RULE_PREFIX)
        fun ruleDisplayName(rule: RouteRule): String =
            if (isScenarioRule(rule)) rule.name().removePrefix(SCENARIO_RULE_PREFIX).substringAfter(" · ") else rule.name()
        private fun defaultRuleName(type: RouteRule.MatchType) = when (type) {
            RouteRule.MatchType.DOMAIN -> "Domain rule"
            RouteRule.MatchType.IP -> "IP rule"
            RouteRule.MatchType.PORT -> "Port rule"
        }

        val SCENARIOS = listOf(
            Scenario(
                id = "work", title = "Work",
                summary = "Stay connected and never fall back to an unprotected route.",
                highlights = listOf("Kill switch on", "Auto reconnect and failover", "Work tools always proxied", "Peer-to-peer ports blocked"),
                killSwitch = true, autoReconnect = true, autoFailover = true, fastestOnConnect = false,
                rules = listOf(
                    ScenarioRule("Work tools via proxy", RouteRule.MatchType.DOMAIN,
                        "slack.com, zoom.us, teams.microsoft.com, github.com, githubusercontent.com, atlassian.net, notion.so",
                        RouteRule.Action.PROXY),
                    ScenarioRule("Block peer-to-peer ports", RouteRule.MatchType.PORT, "6881-6889", RouteRule.Action.BLOCK)
                )
            ),
            Scenario(
                id = "gaming", title = "Gaming",
                summary = "Lowest latency first; large downloads skip the tunnel.",
                highlights = listOf("Fastest main server on connect", "Auto reconnect and failover", "Game downloads stay direct", "Kill switch off"),
                killSwitch = false, autoReconnect = true, autoFailover = true, fastestOnConnect = true,
                rules = listOf(
                    ScenarioRule("Game downloads direct", RouteRule.MatchType.DOMAIN,
                        "steamcontent.com, download.epicgames.com, epicgames-download1.akamaized.net, blzddist1-a.akamaihd.net, origin-a.akamaihd.net",
                        RouteRule.Action.DIRECT)
                )
            ),
            Scenario(
                id = "streaming", title = "Streaming",
                summary = "Keep one stable exit address so sessions are not interrupted.",
                highlights = listOf("Keeps your main server", "Kill switch on", "Auto reconnect and failover", "Streaming sites always proxied"),
                killSwitch = true, autoReconnect = true, autoFailover = true, fastestOnConnect = false,
                rules = listOf(
                    ScenarioRule("Streaming via proxy", RouteRule.MatchType.DOMAIN,
                        "netflix.com, nflxvideo.net, youtube.com, googlevideo.com, twitch.tv, ttvnw.net, spotify.com, scdn.co",
                        RouteRule.Action.PROXY)
                )
            )
        )
        fun dataDirectory(): Path = AppDirectories.dataDirectory()

        fun sourceLabel(source: String): String = when (source) {
            MANUAL_SOURCE -> "Manual"
            IMPORTED_SOURCE -> "Imported"
            else -> runCatching {
                val uri = java.net.URI.create(source)
                uri.host?.removePrefix("www.") ?: source
            }.getOrDefault(source)
        }

        fun displayName(applicationPath: String): String =
            runCatching { Path.of(applicationPath).fileName.toString() }.getOrDefault(applicationPath)
                .let { if (it.endsWith(".exe", ignoreCase = true)) it.dropLast(4) else it }

        private fun isUserFacingApplication(app: ProcessDiscovery.RunningApplication): Boolean {
            return isUserFacingApplication(app.name(), app.path())
        }

        private fun isUserFacingApplication(rawName: String, rawPath: String): Boolean {
            val name = rawName.lowercase(Locale.ROOT)
            val path = rawPath.lowercase(Locale.ROOT)
            val windows = System.getenv("SystemRoot")?.lowercase(Locale.ROOT)
            if (windows != null && path.startsWith("$windows\\")) return false
            if ("\\common files\\" in path) return false
            if (name in setOf("perroute.exe", "xray.exe", "proxifyre.exe", "java.exe", "javaw.exe")) return false
            return listOf("crashpad", "updater", "update.exe", "service.exe", "helper.exe", "notify.exe").none { it in name }
        }

        private val CONTROL_CHARACTERS = Regex("[\\u0000-\\u001f\\ufe0f]")
        private val WHITESPACE_RUN = Regex("\\s+")
        fun cleanName(value: String): String = value
            .replace("?", "")
            .replace(CONTROL_CHARACTERS, "")
            .replace(WHITESPACE_RUN, " ")
            .trim()

        private fun message(error: Throwable): String = error.message?.takeIf { it.isNotBlank() }
            ?: error.javaClass.simpleName
    }
}
