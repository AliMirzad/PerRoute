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

/**
 * A browser workspace as the screens show it: whether its route is running, the server it uses (null when that
 * server is gone), its loopback port and, when it cannot open, why.
 */
data class WorkspaceView(
    val workspace: app.perroute.workspace.Workspace,
    val running: Boolean,
    val runningSince: Long,
    val node: ProxyNode?,
    val port: Int,
    val problem: String
)

/** What can go into a recipe made from the current setup; the user picks what to share. */
data class RecipeDraft(
    val apps: List<app.perroute.recipe.Recipe.AppEntry>,
    val roles: List<app.perroute.recipe.Recipe.Role>,
    val rules: List<RouteRule>,
    val settings: Map<String, Boolean>
)

/**
 * A finished comparison of a live route (or the current server) with a candidate. [route] is the live route that
 * was compared, or null when the current server was tested on a core of its own because PerRoute was not connected.
 */
data class ShadowResult(
    val report: app.perroute.shadow.ShadowExperiment.Report?,
    val route: ActiveRoute?,
    val liveExit: String,
    val candidateExit: String,
    val error: String,
    val liveNode: ProxyNode? = null
)

/** One app on a server for a limited time; when [until] passes it returns to its usual route. */
data class TemporaryRoute(val application: String, val nodeKey: String, val startedAt: Long, val until: Long) {
    fun remainingMillis(now: Long = System.currentTimeMillis()): Long = (until - now).coerceAtLeast(0L)
}

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
class ImportPreview @JvmOverloads constructor(
    val source: String,
    val replaceSource: Boolean,
    val result: ParseResult,
    val candidates: List<ImportCandidate>,
    val removedCount: Int,
    /** The provider's response for a subscription: usage, expiry and title, recorded when the import is confirmed. */
    val fetched: app.perroute.service.SubscriptionService.Fetched? = null
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
        app.perroute.netstate.NetworkStateKeys.setPerRouteServer(app.perroute.service.WindowsSystemProxyManager.perRouteProxyServer())
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
            // The installer needs Administrator approval. Started through the Windows shell it shows the usual
            // prompt; a plain process start would fail. PerRoute closes afterwards so its files can be replaced,
            // and the installer keeps this version for a rollback.
            takeNetworkSnapshot(app.perroute.netstate.NetworkSnapshot.Reason.MANUAL)
            backend.shellOpen(installer, emptyList(), elevated = true)
            ActionResult(true, "The verified installer is starting. PerRoute closes so it can be updated.")
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

    // ---------------------------------------------------------------- temporary routes

    private val temporaryFile: Path get() = AppDirectories.dataDirectory().resolve("temporary-routes.json")
    @Volatile private var temporary: List<TemporaryRoute> = emptyList()
    private val temporaryLoaded = AtomicBoolean(false)

    /** Apps on a server for a limited time, soonest to end first. Ended ones are not listed. */
    fun temporaryRoutes(now: Long = System.currentTimeMillis()): List<TemporaryRoute> =
        loadedTemporary().filter { it.until > now }.sortedBy { it.until }

    fun temporaryRoute(path: String): TemporaryRoute? = temporaryRoutes().firstOrNull { AppRoutes.normalize(it.application) == AppRoutes.normalize(path) }

    private fun loadedTemporary(): List<TemporaryRoute> {
        if (temporaryLoaded.compareAndSet(false, true)) {
            temporary = runCatching {
                if (!Files.isRegularFile(temporaryFile)) return@runCatching emptyList()
                (Json.`object`(Files.readString(temporaryFile))["routes"] as? List<*>).orEmpty().mapNotNull { raw ->
                    val map = raw as? Map<*, *> ?: return@mapNotNull null
                    TemporaryRoute(map["app"]?.toString().orEmpty(), map["node"]?.toString().orEmpty(),
                        (map["startedAt"] as? Number)?.toLong() ?: 0L, (map["until"] as? Number)?.toLong() ?: 0L)
                }.filter { it.application.isNotBlank() && it.nodeKey.isNotBlank() }
            }.getOrDefault(emptyList())
        }
        return temporary
    }

    private fun saveTemporary() {
        runCatching {
            val routes = temporary.map { mapOf("app" to it.application, "node" to it.nodeKey, "startedAt" to it.startedAt, "until" to it.until) }
            if (routes.isEmpty()) Files.deleteIfExists(temporaryFile)
            else {
                Files.createDirectories(temporaryFile.parent)
                Files.writeString(temporaryFile, Json.stringify(mapOf("format" to 1, "routes" to routes)))
            }
        }
    }

    /** The temporary servers in effect for a connection, by normalized path. */
    private fun temporaryChoices(): Map<String, String> {
        val now = System.currentTimeMillis()
        return loadedTemporary().filter { it.until > now }.associate { AppRoutes.normalize(it.application) to it.nodeKey }
    }

    /**
     * Puts one app on [nodeKey] for [minutes], without touching its saved route, the app selection or any rule.
     * When the time is up it goes back to its usual route on its own. While connected only that app's route
     * changes; the other routes keep running.
     */
    @Synchronized
    fun setTemporaryRoute(path: String, nodeKey: String, minutes: Int): ActionResult {
        if (minutes < 1 || minutes > 24 * 60) return ActionResult(false, "Choose between 1 minute and 24 hours.")
        val key = AppRoutes.normalize(path)
        val selected = applications().firstOrNull { AppRoutes.normalize(it) == key }
            ?: return ActionResult(false, "Select ${displayName(path)} in Apps first; only selected apps can use a temporary server.")
        val mode = activeMode ?: state.connectionMode()
        if (mode != ConnectionMode.NATIVE_PER_APP) return ActionResult(false, "Temporary servers need Smart per-app mode, where each app has a route of its own.")
        if (phase == ConnectionPhase.CONNECTING || phase == ConnectionPhase.DISCONNECTING) return ActionResult(false, "Wait for the current connection operation to finish.")
        val node = nodes().firstOrNull { it.key() == nodeKey } ?: return ActionResult(false, "Server was not found.")
        val now = System.currentTimeMillis()
        val previous = loadedTemporary()
        temporary = previous.filter { AppRoutes.normalize(it.application) != key && it.until > now } + TemporaryRoute(selected, nodeKey, now, now + minutes * 60_000L)
        saveTemporary()
        val until = LocalTime.now().plusMinutes(minutes.toLong()).format(DateTimeFormatter.ofPattern("HH:mm"))
        val done = "${displayName(selected)} uses ${cleanName(node.name())} until $until, then returns to its usual route."
        if (!isConnected()) { log(done); return ActionResult(true, done) }
        return applyTemporaryChange(done) { temporary = previous; saveTemporary() }
    }

    /** Ends a temporary server early; the app goes back to its usual route at once. */
    @Synchronized
    fun cancelTemporaryRoute(path: String): ActionResult {
        val key = AppRoutes.normalize(path)
        val previous = loadedTemporary()
        if (previous.none { AppRoutes.normalize(it.application) == key }) return ActionResult(true, "${displayName(path)} is on its usual route.")
        temporary = previous.filter { AppRoutes.normalize(it.application) != key }
        saveTemporary()
        val done = "${displayName(path)} is back on its usual route."
        if (!isConnected()) { log(done); return ActionResult(true, done) }
        return applyTemporaryChange(done) { temporary = previous; saveTemporary() }
    }

    private fun applyTemporaryChange(done: String, revert: () -> Unit): ActionResult {
        if (phase != ConnectionPhase.CONNECTED) { revert(); return ActionResult(false, "Wait for the current connection operation to finish.") }
        val running = runningPrepared ?: run { revert(); return ActionResult(false, "Connect first.") }
        val main = selectedNode() ?: run { revert(); return ActionResult(false, "The active server was not found.") }
        val next = try {
            prepare(main, activeMode ?: state.connectionMode(), running.applications, running.autoChoices).also { validate(it) }
        } catch (invalid: Throwable) {
            revert()
            return ActionResult(false, "The temporary server could not be applied; nothing changed: ${message(invalid)}")
        }
        return applyLive(next, done, revert)
    }

    /** Ends temporary servers whose time is up. Runs for as long as PerRoute does. */
    private fun startTemporaryExpiry() {
        Thread.ofVirtual().name("perroute-temporary-routes").start {
            while (!closing) {
                try { Thread.sleep(5_000) } catch (_: InterruptedException) { break }
                val now = System.currentTimeMillis()
                val ended = loadedTemporary().filter { it.until <= now }
                val drifted = isConnected() && runningPrepared?.let { it.temporary != temporaryChoices() } == true
                if (ended.isEmpty() && !drifted) continue
                synchronized(this) {
                    if (ended.isNotEmpty()) {
                        temporary = temporary.filter { it.until > now }
                        saveTemporary()
                    }
                    val names = joinNames(ended.map { displayName(it.application) })
                    // Also catches a change that could not be applied earlier, so the core always ends up matching.
                    if (isConnected() && phase == ConnectionPhase.CONNECTED && runningPrepared?.let { it.temporary != temporaryChoices() } == true) {
                        val result = applyTemporaryChange(if (ended.isEmpty()) "Temporary servers updated." else "The temporary server of $names ended; back on the usual route.") { }
                        if (!result.ok) log("A temporary server ended but the route could not change yet: ${result.message}")
                    } else if (ended.isNotEmpty()) log("The temporary server of $names ended.")
                }
            }
        }
    }

    /** Starts the per-app engine; on untrusted networks local traffic also goes to the core so it can be blocked. */
    private fun applyPerAppEngine(apps: List<String>, prepared: PreparedConnection) {
        if (prepared.localBlocking) backend.applyPerApp(apps, false) else backend.applyPerApp(apps)
    }

    // ---------------------------------------------------------------- local network

    private val localPolicy by lazy { app.perroute.localnet.LocalNetworkPolicy(AppDirectories.dataDirectory().resolve("local-networks.json")) }
    @Volatile private var localNetworkCache: List<app.perroute.localnet.LocalNetwork> = emptyList()
    @Volatile private var localNetworkCacheAt = 0L
    /** The untrusted ranges the running core blocks, so a network change that alters them can be noticed. */
    @Volatile private var appliedLocalBlocking: List<String> = emptyList()

    /** The networks this computer is on, with their devices. Cached for half a minute unless [refresh]. */
    fun localNetworks(refresh: Boolean = false, resolveNames: Boolean = false): List<app.perroute.localnet.LocalNetwork> {
        val now = System.currentTimeMillis()
        if (!refresh && now - localNetworkCacheAt < 30_000 && localNetworkCacheAt > 0) return localNetworkCache
        val found = runCatching { backend.localNetworks(resolveNames) }.getOrDefault(emptyList())
        localNetworkCache = found
        localNetworkCacheAt = now
        return found
    }

    fun localNamesDirect(): Boolean = localPolicy.localNamesDirect()
    fun setLocalNamesDirect(value: Boolean): ActionResult {
        localPolicy.setLocalNamesDirect(value)
        return ActionResult(true, (if (value) "Local names such as printer.local now stay on your local network." else "Local names are routed like any other name.") + appliesNext())
    }

    fun networkTrusted(id: String): Boolean = localPolicy.trust(id) == app.perroute.localnet.LocalNetworkPolicy.Trust.TRUSTED
    fun untrustedNetworks(): Map<String, String> = localPolicy.untrusted()

    /**
     * On a network that is not trusted, routed apps cannot reach its devices (its router and DNS servers stay
     * reachable). Apps that are not routed are not affected.
     */
    fun setNetworkTrusted(id: String, label: String, trusted: Boolean): ActionResult {
        localPolicy.setTrust(id, label, if (trusted) app.perroute.localnet.LocalNetworkPolicy.Trust.TRUSTED else app.perroute.localnet.LocalNetworkPolicy.Trust.UNTRUSTED)
        return ActionResult(true, (if (trusted) "$label is trusted: routed apps can reach its devices." else "$label is not trusted: routed apps cannot reach its devices, except the router and DNS.") + appliesNext())
    }

    private fun appliesNext(): String = if (isConnected()) " It applies from the next connection." else ""

    private fun localNetworkRules(): app.perroute.localnet.LocalNetworkPolicy.Rules {
        val networks = if (localPolicy.untrusted().isEmpty()) emptyList() else localNetworks(refresh = true)
        return localPolicy.rules(networks).also { appliedLocalBlocking = it.blocked() }
    }

    // ---------------------------------------------------------------- network time machine

    private val networkHistory by lazy { app.perroute.netstate.NetworkHistoryStore(AppDirectories.dataDirectory().resolve("network-history.json")) }

    fun networkSnapshots(): List<app.perroute.netstate.NetworkSnapshot> = networkHistory.all()
    fun networkBaseline(): app.perroute.netstate.NetworkSnapshot? = networkHistory.baseline()

    /** The settings as they are right now, not stored. */
    fun currentNetworkState(): app.perroute.netstate.NetworkSnapshot =
        app.perroute.netstate.NetworkSnapshot(0, System.currentTimeMillis(), app.perroute.netstate.NetworkSnapshot.Reason.MANUAL,
            SecureUpdateService.CURRENT_VERSION, runCatching { backend.networkState() }.getOrDefault(emptyMap()))

    fun takeNetworkSnapshot(reason: app.perroute.netstate.NetworkSnapshot.Reason = app.perroute.netstate.NetworkSnapshot.Reason.MANUAL): app.perroute.netstate.NetworkSnapshot? {
        val values = runCatching { backend.networkState() }.getOrDefault(emptyMap())
        if (values.isEmpty()) return null
        return networkHistory.add(reason, SecureUpdateService.CURRENT_VERSION, values, System.currentTimeMillis())
    }

    /** Takes a snapshot in the background; used around connecting so it never slows a connection down. */
    private fun snapshotLater(reason: app.perroute.netstate.NetworkSnapshot.Reason, delayMillis: Long = 0) {
        if (closing) return
        Thread.ofVirtual().name("perroute-network-snapshot").start {
            if (delayMillis > 0) try { Thread.sleep(delayMillis) } catch (_: InterruptedException) { return@start }
            if (!closing) runCatching { takeNetworkSnapshot(reason) }
        }
    }

    /** What a safe restore would do now. */
    fun networkRestorePlan(): app.perroute.netstate.NetworkRestorePlan =
        app.perroute.netstate.NetworkRestorePlan.build(currentNetworkState(), networkHistory.baseline(), isConnected() || phase != ConnectionPhase.DISCONNECTED)

    /**
     * Undoes PerRoute's own leftovers and nothing else (see [app.perroute.netstate.NetworkRestorePlan]). A snapshot
     * of the settings before the restore is kept, so the comparison shows what was done.
     */
    @Synchronized
    fun restoreNetwork(): ActionResult {
        val plan = networkRestorePlan()
        plan.blocked()?.let { return ActionResult(false, it) }
        if (!plan.needed()) return ActionResult(true, "Nothing to restore: PerRoute has left nothing behind.")
        takeNetworkSnapshot(app.perroute.netstate.NetworkSnapshot.Reason.BEFORE_RESTORE)
        val done = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var needsAdmin = false
        for (step in plan.actions()) {
            val outcome = runCatching {
                when (step.action()) {
                    // Both go through the safe restore, which only writes the saved values while the proxy is still PerRoute's.
                    app.perroute.netstate.NetworkRestorePlan.Action.RESTORE_PROXY,
                    app.perroute.netstate.NetworkRestorePlan.Action.DISCARD_PROXY_COPY -> backend.restoreSystemProxy()
                    app.perroute.netstate.NetworkRestorePlan.Action.RESET_PROXY -> backend.resetSystemProxyLeftover(networkHistory.baseline()?.values())
                    app.perroute.netstate.NetworkRestorePlan.Action.REMOVE_RECOVERY_ENTRY -> backend.removeRecoveryEntry()
                    app.perroute.netstate.NetworkRestorePlan.Action.STOP_CORES -> backend.stopOrphan()
                    app.perroute.netstate.NetworkRestorePlan.Action.RESTORE_ENGINE -> {
                        if (!backend.elevated()) { needsAdmin = true; return@runCatching }
                        backend.restorePerApp()
                    }
                    app.perroute.netstate.NetworkRestorePlan.Action.STOP_LIMITED_ENGINE -> backend.stopPerAppSidecar()
                    app.perroute.netstate.NetworkRestorePlan.Action.CLEAR_SESSION -> Files.deleteIfExists(sessionMarker)
                    app.perroute.netstate.NetworkRestorePlan.Action.LEAVE -> {}
                }
            }
            if (outcome.isSuccess) done += step.title() else failed += "${step.title()}: ${message(outcome.exceptionOrNull()!!)}"
        }
        snapshotLater(app.perroute.netstate.NetworkSnapshot.Reason.AFTER_DISCONNECT, 500)
        val left = plan.leftAlone().size
        val note = if (left == 0) "" else " ${plural(left, "setting")} set by others ${if (left == 1) "was" else "were"} left as ${if (left == 1) "it was" else "they were"}."
        log("Network restore: ${done.size} ${if (done.size == 1) "step" else "steps"} done.$note")
        return when {
            failed.isNotEmpty() -> ActionResult(false, "Some steps failed: ${failed.joinToString(" | ")}")
            needsAdmin -> ActionResult(false, "Done, except the per-app engine: restoring it needs Administrator approval. Use Open recovery tool.$note")
            else -> ActionResult(true, "Restored. PerRoute's leftovers were removed.$note")
        }
    }

    // ---------------------------------------------------------------- safe update and rollback

    private val startupHealthStore by lazy { app.perroute.update.StartupHealth(AppDirectories.dataDirectory().resolve("startup-health.json")) }

    fun startupHealth(): app.perroute.update.StartupHealth.Status = startupHealthStore.read()

    /** Marks this start as working; called once the window has been up for a while and on a clean exit. */
    fun markStartupHealthy() = startupHealthStore.markHealthy(System.currentTimeMillis())

    /** The version the installer kept for a rollback, or null. */
    fun rollbackVersion(): String? = app.perroute.update.InstallLocation.current().flatMap { it.rollbackVersion() }.orElse(null)

    fun recoveryToolAvailable(): Boolean = app.perroute.update.InstallLocation.current().map { Files.isRegularFile(it.recoveryTool()) }.orElse(false)

    /**
     * Hands over to the recovery tool to put the previous version back. It needs Administrator approval, and
     * PerRoute closes afterwards (the caller exits), so its files can be replaced. Settings are kept.
     */
    fun rollBack(): ActionResult {
        val location = app.perroute.update.InstallLocation.current().orElse(null) ?: return ActionResult(false, "Rollback is available in the installed PerRoute only.")
        val version = location.rollbackVersion().orElse(null) ?: return ActionResult(false, "No previous version is kept on this computer.")
        if (!Files.isRegularFile(location.recoveryTool())) return ActionResult(false, "The recovery tool is missing. Reinstall PerRoute to restore it.")
        return try {
            backend.shellOpen(location.recoveryTool(), listOf("/rollback", "/wait", ProcessHandle.current().pid().toString()), elevated = true)
            log("Rolling back to $version.")
            ActionResult(true, "Going back to PerRoute $version. PerRoute closes now and opens again when the rollback is done.")
        } catch (error: Throwable) {
            ActionResult(false, message(error))
        }
    }

    fun openRecoveryTool(): ActionResult {
        val location = app.perroute.update.InstallLocation.current().orElse(null) ?: return ActionResult(false, "The recovery tool comes with the installed PerRoute.")
        if (!Files.isRegularFile(location.recoveryTool())) return ActionResult(false, "The recovery tool is missing. Reinstall PerRoute to restore it.")
        return runCatching { backend.shellOpen(location.recoveryTool(), emptyList(), elevated = false); ActionResult(true, "PerRoute Recovery opened.") }
            .getOrElse { ActionResult(false, message(it)) }
    }

    // ---------------------------------------------------------------- browser workspaces

    private val workspaceStore by lazy { app.perroute.workspace.WorkspaceStore(AppDirectories.dataDirectory().resolve("workspaces.json")) }
    /** Workspaces whose core is running, by id, with the server it was started for. */
    private val runningWorkspaces = java.util.concurrent.ConcurrentHashMap<String, RunningWorkspace>()

    private class RunningWorkspace(val tunnel: IsolatedTunnel, val nodeKey: String, val startedAt: Long, @Volatile var browser: Process?)

    fun workspaces(): List<WorkspaceView> = workspaceStore.all().map { workspace ->
        val running = runningWorkspaces[workspace.id]
        val alive = running?.tunnel?.alive() == true
        val node = workspaceNode(workspace)
        WorkspaceView(workspace, alive, running?.startedAt ?: 0L, node, workspacePort(workspace),
            when {
                !Files.isRegularFile(Path.of(workspace.browserPath)) -> "The browser was not found at ${workspace.browserPath}."
                node == null && !workspace.followsMain() -> "Its server was removed. Choose another one."
                node == null -> "Add a server first."
                running != null && !alive -> "Its connection stopped. Open it again to reconnect."
                else -> ""
            })
    }

    fun workspaceBrowsers(): List<app.perroute.workspace.WorkspaceBrowsers.Browser> = app.perroute.workspace.WorkspaceBrowsers.find(
        runCatching { installedApplications().map { it.path() } + runningApplications().map { it.path() } }.getOrDefault(emptyList()))

    fun workspacePort(workspace: app.perroute.workspace.Workspace): Int = WORKSPACE_PORT_BASE + workspace.slot

    /** The folder that holds a workspace's cookies, sign-ins and history. */
    fun workspaceProfile(workspace: app.perroute.workspace.Workspace): Path = AppDirectories.dataDirectory().resolve("workspaces").resolve(workspace.id)

    private fun workspaceNode(workspace: app.perroute.workspace.Workspace): ProxyNode? =
        if (workspace.followsMain()) selectedNode() else nodes().firstOrNull { it.key() == workspace.nodeKey }

    fun createWorkspace(name: String, browser: String, nodeKey: String): ActionResult {
        if (name.isBlank()) return ActionResult(false, "Give the workspace a name, such as Work or Personal.")
        if (!app.perroute.workspace.WorkspaceBrowsers.supported(browser)) return ActionResult(false, "Choose Chrome, Edge, Brave, Vivaldi or Chromium. Other browsers cannot take a separate route this way.")
        if (!Files.isRegularFile(Path.of(browser))) return ActionResult(false, "That browser was not found.")
        if (nodeKey.isNotBlank() && nodes().none { it.key() == nodeKey }) return ActionResult(false, "Server was not found.")
        if (workspaceStore.all().any { it.name.equals(name.trim(), ignoreCase = true) }) return ActionResult(false, "A workspace with that name exists already.")
        return try {
            val workspace = workspaceStore.create(name.trim(), Path.of(browser).toAbsolutePath().normalize().toString(), nodeKey, System.currentTimeMillis())
            log("Workspace ${workspace.name} created.")
            ActionResult(true, "${workspace.name} is ready. Open it to start its own browser window.")
        } catch (error: Throwable) { ActionResult(false, message(error)) }
    }

    /** Renames a workspace or moves it to another browser or server; an open one reconnects on the new server. */
    fun updateWorkspace(id: String, name: String, browser: String, nodeKey: String): ActionResult {
        val workspace = workspaceStore.find(id) ?: return ActionResult(false, "That workspace no longer exists.")
        if (name.isBlank()) return ActionResult(false, "Give the workspace a name.")
        if (!app.perroute.workspace.WorkspaceBrowsers.supported(browser) || !Files.isRegularFile(Path.of(browser))) return ActionResult(false, "Choose an installed Chromium browser.")
        if (nodeKey.isNotBlank() && nodes().none { it.key() == nodeKey }) return ActionResult(false, "Server was not found.")
        if (workspaceStore.all().any { it.id != id && it.name.equals(name.trim(), ignoreCase = true) }) return ActionResult(false, "A workspace with that name exists already.")
        val updated = app.perroute.workspace.Workspace(id, name.trim(), Path.of(browser).toAbsolutePath().normalize().toString(), nodeKey, workspace.slot, workspace.createdAt)
        workspaceStore.update(updated)
        val running = runningWorkspaces[id]
        if (running != null && running.tunnel.alive() && running.nodeKey != workspaceNode(updated)?.key()) {
            val restarted = startWorkspaceCore(updated)
            return if (restarted.ok) ActionResult(true, "${updated.name} now uses ${workspaceNode(updated)?.let { cleanName(it.name()) }}. Its open window keeps working.") else restarted
        }
        return ActionResult(true, "${updated.name} saved.")
    }

    /** Removes a workspace. Its profile folder (cookies, sign-ins, history) is only deleted when asked to. */
    fun removeWorkspace(id: String, deleteProfile: Boolean): ActionResult {
        val workspace = workspaceStore.find(id) ?: return ActionResult(false, "That workspace no longer exists.")
        stopWorkspace(id)
        val profile = workspaceProfile(workspace)
        if (deleteProfile && backend.profileInUse(profile)) return ActionResult(false, "Close the ${workspace.name} browser window first, then remove it.")
        workspaceStore.remove(id)
        if (deleteProfile) runCatching { deleteTree(profile) }.onFailure { return ActionResult(false, "${workspace.name} was removed, but its profile folder could not be deleted: ${message(it)}") }
        log("Workspace ${workspace.name} removed.")
        return ActionResult(true, "${workspace.name} was removed." + if (deleteProfile) " Its browsing data was deleted." else " Its browsing data is kept in ${profile.fileName}.")
    }

    /** Opens the workspace's browser on its own route. It works whether or not PerRoute is connected. */
    @Synchronized
    fun openWorkspace(id: String): ActionResult {
        val workspace = workspaceStore.find(id) ?: return ActionResult(false, "That workspace no longer exists.")
        if (!Files.isRegularFile(Path.of(workspace.browserPath))) return ActionResult(false, "The browser was not found at ${workspace.browserPath}.")
        val running = runningWorkspaces[id]
        val wanted = workspaceNode(workspace)?.key()
        if (running == null || !running.tunnel.alive() || running.nodeKey != wanted) startWorkspaceCore(workspace).let { if (!it.ok) return it }
        val profile = workspaceProfile(workspace)
        return try {
            Files.createDirectories(profile)
            val browser = backend.startProgram(app.perroute.workspace.WorkspaceBrowsers.command(workspace.browserPath, profile, workspacePort(workspace)))
            runningWorkspaces[id]?.browser = browser
            watchWorkspace(id, browser)
            ActionResult(true, "${workspace.name} opened on ${workspaceNode(workspace)?.let { cleanName(it.name()) }}.")
        } catch (error: Throwable) {
            ActionResult(false, "The browser could not be started: ${message(error)}")
        }
    }

    /** Stops a workspace's route. Its window stays open but has no connection until the workspace is opened again. */
    fun stopWorkspace(id: String): ActionResult {
        val running = runningWorkspaces.remove(id) ?: return ActionResult(true, "That workspace is not running.")
        runCatching { running.tunnel.close() }
        val name = workspaceStore.find(id)?.name ?: "The workspace"
        log("$name stopped. Its browser window has no connection until you open it again.")
        return ActionResult(true, "$name stopped. Its window has no connection until you open it again.")
    }

    private fun startWorkspaceCore(workspace: app.perroute.workspace.Workspace): ActionResult {
        val node = workspaceNode(workspace) ?: return ActionResult(false, if (workspace.followsMain()) "Add a server first." else "The workspace's server was removed. Choose another one.")
        runningWorkspaces.remove(workspace.id)?.let { runCatching { it.tunnel.close() } }
        val port = workspacePort(workspace)
        return try {
            val config = XrayConfigGenerator().generateIsolated(node, port, app.perroute.service.RealTunnelProbe.reservePort(), rules())
            val tunnel = backend.startIsolatedCore(config, port)
            Thread.sleep(300)
            if (!tunnel.alive()) {
                val reason = tunnel.lastLines().lastOrNull { it.contains("fail", true) || it.contains("error", true) } ?: "the core stopped at once"
                tunnel.close()
                return ActionResult(false, "${workspace.name} could not start its route: $reason")
            }
            runningWorkspaces[workspace.id] = RunningWorkspace(tunnel, node.key(), System.currentTimeMillis(), null)
            log("${workspace.name} routes through ${cleanName(node.name())} on its own port.")
            ActionResult(true, "${workspace.name} uses ${cleanName(node.name())}.")
        } catch (error: Throwable) {
            ActionResult(false, "${workspace.name} could not start its route: ${message(error)}")
        }
    }

    /** Stops the workspace's route once its browser has closed and its profile is no longer in use. */
    private fun watchWorkspace(id: String, browser: Process) {
        Thread.ofVirtual().name("perroute-workspace-watch").start {
            runCatching { browser.waitFor() }
            // A browser that was already open hands the window to the running one and exits at once, so the
            // profile lock, not the launcher process, says when the window really closed.
            val profile = workspaceStore.find(id)?.let(::workspaceProfile) ?: return@start
            while (!closing && runningWorkspaces[id]?.browser === browser && backend.profileInUse(profile)) {
                try { Thread.sleep(3_000) } catch (_: InterruptedException) { return@start }
            }
            if (!closing && runningWorkspaces[id]?.browser === browser) {
                runningWorkspaces.remove(id)?.let { runCatching { it.tunnel.close() } }
                log("${workspaceStore.find(id)?.name ?: "A workspace"} closed; its route stopped.")
            }
        }
    }

    /** Workspace windows left open across a PerRoute restart get their route back. */
    private fun reattachWorkspaces() {
        for (workspace in workspaceStore.all()) {
            if (closing) return
            if (!backend.profileInUse(workspaceProfile(workspace))) continue
            if (startWorkspaceCore(workspace).ok) {
                log("${workspace.name} was still open; its route was restored.")
                Thread.ofVirtual().start {
                    while (!closing && runningWorkspaces.containsKey(workspace.id) && backend.profileInUse(workspaceProfile(workspace))) {
                        try { Thread.sleep(3_000) } catch (_: InterruptedException) { return@start }
                    }
                    if (!closing && runningWorkspaces[workspace.id]?.browser == null) runningWorkspaces.remove(workspace.id)?.let { runCatching { it.tunnel.close() } }
                }
            }
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    // ---------------------------------------------------------------- routing recipes

    private val recipeSnapshotFile: Path get() = AppDirectories.dataDirectory().resolve("recipe-rollback.bin")

    /**
     * What a recipe made from the current setup would contain. Every distinct custom server becomes a numbered
     * slot; nothing about the server itself is included.
     */
    fun recipeDraft(): RecipeDraft = synchronized(state) {
        val advanced = state.advancedRouting()
        val roles = LinkedHashMap<String, String>()
        val apps = state.appPaths().map { path ->
            val key = AppRoutes.normalize(path)
            val policy = state.bandwidthPolicies()[key] ?: BandwidthPolicy.UNLIMITED
            val route = when {
                advanced && key in state.autoApps() -> app.perroute.recipe.Recipe.Route.AUTO
                advanced && state.appRoutes().containsKey(key) -> app.perroute.recipe.Recipe.Route.ROLE
                else -> app.perroute.recipe.Recipe.Route.MAIN
            }
            val role = if (route == app.perroute.recipe.Recipe.Route.ROLE) roles.getOrPut(state.appRoutes()[key]!!) { "server-${roles.size + 1}" } else ""
            app.perroute.recipe.Recipe.AppEntry(Path.of(path).fileName.toString(), displayName(path), route, role,
                if (route == app.perroute.recipe.Recipe.Route.AUTO) AutoCandidatePool.decode(state.autoPools()[key]).encode() else "",
                policy.downloadBitsPerSecond(), policy.uploadBitsPerSecond())
        }
        RecipeDraft(apps, roles.values.mapIndexed { index, id -> app.perroute.recipe.Recipe.Role(id, "Server ${index + 1}", "") },
            state.routeRules().filter { it.enabled() }, mapOf("perAppKillSwitch" to killSwitch(), "autoReconnect" to autoReconnect(),
                "autoFailover" to autoFailover(), "fastestOnConnect" to fastestOnConnect()))
    }

    /** Builds the recipe the user chose to share from [draft]. */
    fun buildRecipe(name: String, description: String, author: String, draft: RecipeDraft, includeApps: Boolean, includeSettings: Boolean,
                    roleHints: Map<String, String>, skippedRules: Set<String>): app.perroute.recipe.Recipe {
        val roles = if (includeApps) draft.roles.map { app.perroute.recipe.Recipe.Role(it.id(), roleHints[it.id()]?.takeIf { hint -> hint.isNotBlank() } ?: it.label(), "") } else emptyList()
        return app.perroute.recipe.RecipePlanner.export(name.ifBlank { "My routing recipe" }, description, author, "PerRoute ${SecureUpdateService.CURRENT_VERSION}",
            roles, if (includeApps) draft.apps else emptyList(), draft.rules, if (includeSettings) draft.settings else emptyMap(), skippedRules)
    }

    fun saveRecipe(recipe: app.perroute.recipe.Recipe, file: Path): ActionResult = try {
        Files.createDirectories(file.toAbsolutePath().parent)
        Files.writeString(file, app.perroute.recipe.RecipeCodec.toJson(recipe))
        log("Recipe ${recipe.name()} saved.")
        ActionResult(true, "Recipe saved to ${file.fileName}. It contains no servers, links or passwords.")
    } catch (error: Throwable) { ActionResult(false, "The recipe could not be saved: ${message(error)}") }

    /** Reads a recipe (file contents or shared text) and compares it with this setup. Nothing changes yet. */
    fun openRecipe(text: String): Pair<app.perroute.recipe.RecipePlanner.Plan?, String?> = try {
        val recipe = app.perroute.recipe.RecipeCodec.parse(text)
        val known = runCatching { installedApplications().map { it.path() } + runningApplications().map { it.path() } }.getOrDefault(emptyList())
        val modes = synchronized(state) {
            state.appPaths().associate { path ->
                val key = AppRoutes.normalize(path)
                key to when {
                    state.advancedRouting() && key in state.autoApps() -> "Auto"
                    state.advancedRouting() && state.appRoutes().containsKey(key) -> "Manual"
                    else -> "Main"
                }
            }
        }
        val preferences = mapOf("perAppKillSwitch" to killSwitch(), "autoReconnect" to autoReconnect(), "autoFailover" to autoFailover(), "fastestOnConnect" to fastestOnConnect())
        app.perroute.recipe.RecipePlanner.plan(recipe, rules(), applications(), known, modes, preferences) to null
    } catch (error: app.perroute.recipe.RecipeCodec.RecipeException) {
        null to error.message
    } catch (error: Throwable) {
        null to "The recipe could not be read: ${message(error)}"
    }

    /**
     * Applies a recipe the user reviewed. [roleServers] points every slot at one of the user's servers;
     * [appPaths] gives the path of each recipe app to set up (apps left out are skipped); [replace] lists apps that
     * are already routed differently and should follow the recipe anyway. Rules are added after the user's own.
     * A rollback snapshot is written first, so [undoRecipe] can put everything back.
     */
    fun applyRecipe(plan: app.perroute.recipe.RecipePlanner.Plan, roleServers: Map<String, String>, appPaths: Map<String, String>,
                    replace: Set<String>, applySettings: Boolean): ActionResult {
        editLock("routing")?.let { return it }
        val recipe = plan.recipe()
        val chosenApps = recipe.apps().filter { appPaths[it.executable()]?.isNotBlank() == true }
        for (entry in chosenApps) {
            if (entry.route() == app.perroute.recipe.Recipe.Route.ROLE) {
                val key = roleServers[entry.role()] ?: return ActionResult(false, "Choose a server for ${recipe.roles().firstOrNull { it.id() == entry.role() }?.label() ?: entry.role()}.")
                if (nodes().none { it.key() == key }) return ActionResult(false, "One of the chosen servers no longer exists.")
            }
            if (!Files.isRegularFile(Path.of(appPaths[entry.executable()]!!))) return ActionResult(false, "${entry.executable()} was not found at the chosen place.")
        }
        val before = synchronized(state) { store.serialize(state) }
        try { AppStateStore(recipeSnapshotFile).write(withPreferences(before, preferenceSnapshot())) }
        catch (error: Throwable) { return ActionResult(false, "A rollback snapshot could not be written, so nothing was changed: ${message(error)}") }
        var addedRules = 0
        var setUp = 0
        synchronized(state) {
            for (rulePlan in plan.rules()) {
                if (rulePlan.status() == app.perroute.recipe.RecipePlanner.RuleStatus.DUPLICATE) continue
                val entry = rulePlan.rule()
                val application = when {
                    entry.executable().isBlank() -> ""
                    else -> appPaths[entry.executable()] ?: rulePlan.applicationPath().ifBlank { null }
                } ?: continue
                state.routeRules().add(RouteRule("", app.perroute.recipe.RecipePlanner.ruleName(recipe, entry), application, entry.match(), entry.value(), entry.action(), true))
                addedRules++
            }
            var needsAdvanced = false
            for (entry in chosenApps) {
                val path = Path.of(appPaths[entry.executable()]!!).toAbsolutePath().normalize().toString()
                val key = AppRoutes.normalize(path)
                val already = state.appPaths().any { AppRoutes.normalize(it) == key }
                val configured = plan.apps().firstOrNull { it.app().executable() == entry.executable() }?.configured() == true
                if (!already) state.appPaths().add(path)
                if (configured && entry.executable() !in replace) continue
                when (entry.route()) {
                    app.perroute.recipe.Recipe.Route.MAIN -> { state.autoApps().remove(key); state.appRoutes().remove(key) }
                    app.perroute.recipe.Recipe.Route.ROLE -> {
                        val node = roleServers[entry.role()]!!
                        state.autoApps().remove(key)
                        state.appRoutes()[key] = node
                        state.nodes().firstOrNull { it.key() == node }?.let { state.appRouteLabels()[node] = cleanName(it.name()) }
                        needsAdvanced = true
                    }
                    app.perroute.recipe.Recipe.Route.AUTO -> {
                        state.autoApps().add(key)
                        val pool = AutoCandidatePool.decode(entry.pool())
                        if (pool.kind() == AutoCandidatePool.Kind.ALL) state.autoPools().remove(key) else state.autoPools()[key] = pool.encode()
                        needsAdvanced = true
                    }
                }
                if (entry.downloadBitsPerSecond() > 0 || entry.uploadBitsPerSecond() > 0) {
                    state.bandwidthPolicies()[key] = BandwidthPolicy(entry.downloadBitsPerSecond(), entry.uploadBitsPerSecond())
                }
                setUp++
            }
            if (needsAdvanced) state.setAdvancedRouting(true)
            state.appRouteLabels().keys.retainAll(state.appRoutes().values.toSet())
        }
        if (applySettings) plan.settings().forEach { setting ->
            when (setting.key()) {
                "perAppKillSwitch" -> setKillSwitch(setting.to())
                "autoReconnect" -> setAutoReconnect(setting.to())
                "autoFailover" -> setAutoFailover(setting.to())
                "fastestOnConnect" -> setFastestOnConnect(setting.to())
            }
        }
        autoRouting.clear()
        save()
        log("Recipe ${recipe.name()} applied: ${plural(addedRules, "rule")} added, ${plural(setUp, "app")} set up.")
        return ActionResult(true, "${recipe.name()} applied: ${plural(addedRules, "rule")} added, ${plural(setUp, "app")} set up. Undo recipe puts your previous setup back.")
    }

    fun hasRecipeSnapshot(): Boolean = Files.isRegularFile(recipeSnapshotFile)

    /** Puts the setup from before the last recipe back. */
    fun undoRecipe(): ActionResult {
        editLock("routing")?.let { return it }
        if (!hasRecipeSnapshot()) return ActionResult(false, "There is no recipe to undo.")
        return try {
            val properties = AppStateStore(recipeSnapshotFile).loadProperties()
            val previous = store.parse(properties)
            val prefs = properties.stringPropertyNames().filter { it.startsWith(ROLLBACK_PREFIX) }.associate { it.removePrefix(ROLLBACK_PREFIX) to properties.getProperty(it) }
            store.write(store.serialize(previous))
            synchronized(state) { state.replaceWith(previous) }
            applyPreferences(prefs)
            Files.deleteIfExists(recipeSnapshotFile)
            afterStateReplaced()
            log("The last recipe was undone.")
            ActionResult(true, "Your setup from before the recipe is back.")
        } catch (error: Throwable) {
            ActionResult(false, "The recipe could not be undone: ${message(error)}")
        }
    }

    /** Takes every rule a recipe added out again; apps and settings stay as they are. */
    fun removeRecipeRules(): ActionResult {
        editLock("routing rules")?.let { return it }
        val removed = synchronized(state) {
            val before = state.routeRules().size
            state.routeRules().removeIf { it.name().startsWith(app.perroute.recipe.RecipePlanner.RULE_PREFIX) }
            before - state.routeRules().size
        }
        save()
        return ActionResult(true, if (removed == 0) "No recipe rules to remove." else "${plural(removed, "recipe rule")} removed.")
    }

    // ---------------------------------------------------------------- gaming

    private val gamingResults by lazy { app.perroute.gaming.GamingResultStore(AppDirectories.dataDirectory().resolve("gaming-results.json")) }

    fun gamingScores(): Map<String, app.perroute.gaming.GamingScore> = gamingResults.all()
    fun gamingScore(key: String): app.perroute.gaming.GamingScore? = gamingResults.get(key)

    /**
     * Tests servers for games, one after another, each through a core of its own so the running connection is not
     * involved. [progress] gets each result as it arrives; [cancelled] stops between servers.
     */
    fun gamingTest(targets: List<ProxyNode>, cancelled: () -> Boolean = { false },
                   progress: (ProxyNode, app.perroute.gaming.GamingScore) -> Unit = { _, _ -> }): Map<String, app.perroute.gaming.GamingScore> {
        val results = LinkedHashMap<String, app.perroute.gaming.GamingScore>()
        for (node in targets) {
            if (cancelled() || closing) break
            val score = runCatching { gamingTestOne(node) }.getOrElse {
                app.perroute.gaming.GamingScore(0, "Unavailable", false, -1, -1, -1, 100.0, -1, 100.0, System.currentTimeMillis(), message(it))
            }
            gamingResults.put(node.key(), score)
            results[node.key()] = score
            progress(node, score)
        }
        if (results.isNotEmpty()) log("Gaming test finished for ${plural(results.size, "server")}.")
        return results
    }

    private fun gamingTestOne(node: ProxyNode): app.perroute.gaming.GamingScore {
        val port = app.perroute.service.RealTunnelProbe.reservePort()
        backend.startIsolatedCore(XrayConfigGenerator().generateProbe(node, port, app.perroute.service.RealTunnelProbe.reservePort()), port).use { tunnel ->
            // The usual tunnel check first: it waits for the core to listen and proves the server answers at all.
            val health = backend.checkTunnel(port)
            if (!tunnel.alive()) return unavailable("Xray did not accept this server.")
            if (!health.success()) return unavailable(health.detail())
            return app.perroute.gaming.GamingScore.of(app.perroute.gaming.GamingProbe().measure(port), System.currentTimeMillis())
        }
    }

    private fun unavailable(reason: String) = app.perroute.gaming.GamingScore(0, "Unavailable", false, -1, -1, -1, 100.0, -1, 100.0, System.currentTimeMillis(), reason)

    // ---------------------------------------------------------------- shadow routing

    /**
     * Destinations worth comparing for a route: the HTTPS sites its apps were seen using in this session (the
     * inspector's observations, kept in memory only), then common sites, five at most.
     */
    fun shadowDestinations(routeId: String?): List<String> {
        val route = routeId?.let { id -> activeRoutes.firstOrNull { it.id == id } }
        val apps = route?.applications?.map { AppRoutes.normalize(it) }?.toSet().orEmpty()
        val seen = inspector.snapshot(System.currentTimeMillis())
            .filter { apps.isEmpty() || AppRoutes.normalize(it.applicationPath()) in apps }
            .filter { it.destinationPort() == 443 }
            .mapNotNull { observation -> observation.sniffedDomain().ifBlank { observation.destinationHost() }.takeIf { host -> host.contains('.') && !host.matches(Regex("[0-9.]+|.*:.*")) } }
            .groupingBy { it.lowercase(Locale.ROOT) }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        return (seen.take(4) + DEFAULT_SHADOW_DESTINATIONS).distinct().take(5)
    }

    /**
     * Compares a route with a candidate server, side by side, without touching the route. While connected the live
     * side is the route's own port; otherwise the route's server is tested on a core of its own as well.
     */
    fun runShadowExperiment(routeId: String?, candidateKey: String, destinations: List<String>, cancelled: () -> Boolean = { false }): ShadowResult {
        val candidate = nodes().firstOrNull { it.key() == candidateKey } ?: return ShadowResult(null, null, "", "", "Server was not found.")
        val route = routeId?.let { id -> activeRoutes.takeIf { isConnected() }?.firstOrNull { it.id == id } }
        val liveNode = route?.node ?: routeId?.let { id -> nodes().firstOrNull { it.key() == id } } ?: selectedNode()
            ?: return ShadowResult(null, null, "", "", "Choose a server to compare with.")
        if (liveNode.key() == candidate.key() && route == null) return ShadowResult(null, null, "", "", "Pick a different server to compare with.")
        val hosts = destinations.ifEmpty { shadowDestinations(routeId) }
        val timer = app.perroute.shadow.ShadowExperiment.Timer { port, host -> app.perroute.service.TunnelHttp.timeHead(app.perroute.service.TunnelHttp.Route.socks(port), host, 6_000) }
        val opened = mutableListOf<IsolatedTunnel>()
        return try {
            fun isolated(node: ProxyNode): Int {
                val port = app.perroute.service.RealTunnelProbe.reservePort()
                opened += backend.startIsolatedCore(XrayConfigGenerator().generateProbe(node, port, app.perroute.service.RealTunnelProbe.reservePort()), port)
                // Waits until the core listens; a failure shows up in the comparison itself.
                backend.checkTunnel(port)
                return port
            }
            val livePort = route?.checkPort ?: isolated(liveNode)
            val candidatePort = isolated(candidate)
            val report = app.perroute.shadow.ShadowExperiment.run(timer, livePort, candidatePort, hosts, app.perroute.shadow.ShadowExperiment.ROUNDS, cancelled)
            val (liveExit, candidateExit) = runCatching { backend.lookupPublicAddress(livePort) to backend.lookupPublicAddress(candidatePort) }.getOrDefault(RouteReport() to RouteReport())
            log("Compared ${cleanName(liveNode.name())} with ${cleanName(candidate.name())}: ${report.verdict().outcome().headline().lowercase()}. The live route was not changed.")
            ShadowResult(report, route, describeExit(liveExit), describeExit(candidateExit), "", liveNode)
        } catch (error: Throwable) {
            ShadowResult(null, route, "", "", "The comparison could not run: ${message(error)}", liveNode)
        } finally {
            opened.forEach { runCatching { it.close() } }
        }
    }

    private fun describeExit(report: RouteReport): String =
        report.ip?.let { ip -> ip + (report.country?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") } ?: ""

    // ---------------------------------------------------------------- subscription intelligence

    private val insights by lazy { app.perroute.subscription.SubscriptionInsightStore(AppDirectories.dataDirectory().resolve("subscription-insights.json")) }

    fun subscriptionInsight(url: String): app.perroute.subscription.SubscriptionInsight? = insights.get(url)

    fun subscriptionHealth(url: String): app.perroute.subscription.SubscriptionHealth.Report =
        app.perroute.subscription.SubscriptionHealth.analyze(insights.get(url), System.currentTimeMillis())

    /** The worst report across all subscriptions, for a badge on the Servers screen. */
    fun subscriptionAttention(): Int = subscriptions().count { subscriptionHealth(it).severity() >= app.perroute.subscription.SubscriptionHealth.Severity.WARNING }

    /** Records a successful update: what the provider reported and how the server list changed. */
    private fun recordSubscription(url: String, fetched: app.perroute.service.SubscriptionService.Fetched, previous: List<ProxyNode>) {
        runCatching {
            val now = System.currentTimeMillis()
            val incoming = fetched.result().nodes()
            val identities = incoming.map { it.identity() }
            val within = identities.size - identities.toSet().size
            val (elsewhere, shared) = synchronized(state) {
                val others = state.nodes().filter { node -> !state.nodeSources()[node.key()].equals(url, ignoreCase = true) }
                val otherIdentities = others.mapTo(hashSetOf()) { it.identity() }
                val otherEndpoints = others.mapTo(hashSetOf()) { "${it.address().lowercase(Locale.ROOT)}:${it.port()}" }
                identities.toSet().count { it in otherIdentities } to
                    incoming.distinctBy { it.identity() }.count { it.identity() !in otherIdentities && "${it.address().lowercase(Locale.ROOT)}:${it.port()}" in otherEndpoints }
            }
            val change = if (previous.isEmpty() && (insights.get(url)?.lastSuccessAt() ?: 0L) == 0L) null
                else app.perroute.subscription.SubscriptionChange.between(previous.map { app.perroute.subscription.SubscriptionChange.Entry(it.identity(), cleanName(it.name())) },
                    incoming.distinctBy { it.identity() }.map { app.perroute.subscription.SubscriptionChange.Entry(it.identity(), cleanName(it.name())) }, now)
            val current = insights.get(url) ?: app.perroute.subscription.SubscriptionInsight.empty(now)
            insights.put(url, current.succeeded(now, fetched.title(), fetched.userInfo(), fetched.updateIntervalHours(),
                incoming.distinctBy { it.identity() }.size, within, elsewhere, shared, change))
            if (change?.unexpected() == true) log("${sourceLabel(url)} changed a lot in this update (${change.summary()}). Check that the link still belongs to your provider.")
            fetched.userInfo().takeIf { it.hasExpiry() && it.expireAtMillis() <= now }?.let { log("${sourceLabel(url)} reports that it has expired.") }
        }
    }

    private fun recordSubscriptionFailure(url: String, error: String) {
        runCatching {
            val now = System.currentTimeMillis()
            insights.put(url, (insights.get(url) ?: app.perroute.subscription.SubscriptionInsight.empty(now)).failed(now, error))
        }
    }

    private fun serversOf(url: String): List<ProxyNode> = synchronized(state) {
        state.nodes().filter { node -> state.nodeSources()[node.key()].equals(url, ignoreCase = true) }
    }

    // ---------------------------------------------------------------- connection doctor and safety center

    /** The core's recent lines, in memory only, so the Doctor can say why a server refuses connections. */
    private val coreLogTail = java.util.concurrent.ConcurrentLinkedDeque<String>()

    private fun rememberCoreLine(line: String) {
        coreLogTail.addLast(line)
        while (coreLogTail.size > 300) coreLogTail.pollFirst()
    }

    /**
     * Looks for the reason something does not connect, from the outside in: this computer's own connection, its
     * DNS and clock, PerRoute's parts, the server, the tunnel, and finally the app and the destination. Nothing is
     * changed; a finding that can be fixed carries a [Repair] the user can choose to run.
     */
    fun diagnose(appPath: String?, destination: String?): HealthReport {
        val findings = mutableListOf<HealthFinding>()
        val connected = isConnected()
        val mode = activeMode ?: state.connectionMode()
        val now = System.currentTimeMillis()
        val target = appPath?.takeIf { it.isNotBlank() }
        val appName = healthAppName(target)

        val direct = runCatching { backend.lookupPublicAddress(null) }.getOrElse { RouteReport(error = message(it)) }
        val offline = direct.ip == null
        findings += if (offline) HealthFinding("network", "This computer", CheckStatus.FAIL, "Your own internet connection does not work",
            "Even without the tunnel, a public test site did not answer (${direct.error ?: "no answer"}). Check Wi-Fi or the cable and your router first; nothing in PerRoute can work until this does.")
        else HealthFinding("network", "This computer", CheckStatus.PASS, "Your internet connection works", "Public address ${direct.ip}${direct.country?.let { " ($it)" } ?: ""}.")

        findings += if (!runCatching { backend.systemResolves("www.cloudflare.com") }.getOrDefault(false))
            HealthFinding("dns", "This computer", if (offline) CheckStatus.SKIPPED else CheckStatus.FAIL, "Windows cannot look up names",
                "Names such as www.cloudflare.com do not resolve through your DNS servers (${backend.systemDnsServers().joinToString(", ").ifBlank { "unknown" }}). Servers given by name cannot be reached either.")
        else HealthFinding("dns", "This computer", CheckStatus.PASS, "Name lookups work", "Windows resolves names through ${backend.systemDnsServers().joinToString(", ").ifBlank { "its DNS servers" }}.")

        if (!offline) {
            val serverTime = runCatching { backend.directServerTime() }.getOrNull()
            if (serverTime != null) {
                val skew = kotlin.math.abs(now - serverTime) / 1000
                findings += when {
                    skew > 90 -> HealthFinding("clock", "This computer", CheckStatus.FAIL, "Your clock is ${formatSkew(skew)} off",
                        "VMess servers refuse connections when the clock differs by more than 90 seconds, and secure connections can fail too. Turn on Set time automatically in Windows settings.")
                    skew > 30 -> HealthFinding("clock", "This computer", CheckStatus.WARN, "Your clock is ${formatSkew(skew)} off",
                        "Close to the 90 seconds some servers accept. Turn on Set time automatically in Windows settings.")
                    else -> HealthFinding("clock", "This computer", CheckStatus.PASS, "Your clock is correct", "Within $skew seconds of a public time source.")
                }
            }
        }

        val environment = environment()
        findings += if (!environment.coreReady) HealthFinding("core", "PerRoute", CheckStatus.FAIL, "The connection core is not installed",
            "Xray is missing, so no connection can start.", Repair.OPEN_COMPONENTS)
        else HealthFinding("core", "PerRoute", CheckStatus.PASS, "The connection core is installed", "")
        if (mode == ConnectionMode.NATIVE_PER_APP) {
            findings += if (!environment.perAppReady) HealthFinding("engine", "PerRoute", CheckStatus.FAIL, "The per-app engine is not installed",
                "Smart per-app needs it to send the selected apps to the tunnel. Install PerRoute with the Full Installer.", Repair.OPEN_COMPONENTS)
            else HealthFinding("engine", "PerRoute", CheckStatus.PASS, "The per-app engine is installed", "")
        }

        when {
            desiredConnected && !connected && phase == ConnectionPhase.DISCONNECTED -> findings += HealthFinding("connection", "Connection", CheckStatus.FAIL,
                "The connection stopped", "You connected, but the connection is not running now${if (autoReconnect()) " and is being retried" else ""}.", Repair.RECONNECT)
            !connected -> findings += HealthFinding("connection", "Connection", CheckStatus.INFO, "Not connected",
                "Routed apps only use the tunnel while PerRoute is connected." + if (killSwitch() && mode == ConnectionMode.NATIVE_PER_APP) " The kill switch keeps the selected apps offline until then." else "")
            else -> findings += HealthFinding("connection", "Connection", CheckStatus.PASS, "Connected", "Mode: ${modeLabel(mode)}.")
        }

        if (connected && mode == ConnectionMode.SYSTEM_PROXY) {
            findings += if (!runCatching { backend.perRouteProxyActive() }.getOrDefault(true))
                HealthFinding("proxy", "Windows", CheckStatus.FAIL, "Another program changed the Windows proxy",
                    "Apps that follow Windows proxy settings no longer go through PerRoute. A VPN or another proxy tool may have replaced the setting.", Repair.REAPPLY_SYSTEM_PROXY)
            else HealthFinding("proxy", "Windows", CheckStatus.PASS, "The Windows proxy points to PerRoute", "")
        }
        if (!connected) {
            val plan = runCatching { networkRestorePlan() }.getOrNull()
            if (plan != null && plan.needed()) findings += HealthFinding("leftovers", "Windows", CheckStatus.WARN, "PerRoute left settings behind",
                plan.actions().joinToString(" ") { it.title() + "." }, Repair.RESTORE_NETWORK)
        }

        // The route the app (or the main connection) takes, and its server.
        val route = when {
            target != null && connected -> activeRoutes.firstOrNull { r -> r.applications.any { AppRoutes.normalize(it) == AppRoutes.normalize(target) } }
            connected -> activeRoutes.firstOrNull { it.main }
            else -> null
        }
        val node = route?.node ?: target?.let { routePlan().nodeFor(it) } ?: selectedNode()
        if (node == null) {
            findings += HealthFinding("server", "Server", CheckStatus.FAIL, "There is no server", "Add a server in Servers first.")
        } else if (!offline) {
            val name = cleanName(node.name())
            if (node.protocol() != app.perroute.model.Protocol.XRAY_PROFILE) {
                val reach = latencyProbe.probe(node, 3_000)
                findings += if (!reach.reachable()) HealthFinding("server", "Server", CheckStatus.FAIL, "$name does not answer",
                    "Its address ${node.address()}:${node.port()} cannot be reached (${reach.detail()}). The server may be down or blocked from your network.",
                    if (route?.main != false) Repair.SWITCH_SERVER else null)
                else HealthFinding("server", "Server", CheckStatus.PASS, "$name is reachable", "Its port answered in ${reach.milliseconds()} ms.")
            }
            val source = nodeSource(node.key())
            if (source != MANUAL_SOURCE && source != IMPORTED_SOURCE) {
                val health = subscriptionHealth(source)
                if (health.severity() == app.perroute.subscription.SubscriptionHealth.Severity.PROBLEM) findings += HealthFinding("subscription", "Server",
                    CheckStatus.FAIL, "Its subscription: ${health.status().lowercase()}", health.findings().first().detail(), Repair.UPDATE_SUBSCRIPTIONS)
                else if (health.severity() == app.perroute.subscription.SubscriptionHealth.Severity.WARNING) findings += HealthFinding("subscription", "Server",
                    CheckStatus.WARN, "Its subscription: ${health.status().lowercase()}", health.findings().first().detail(), Repair.UPDATE_SUBSCRIPTIONS)
            }
            if (route != null) {
                val tunnel = runCatching { backend.checkTunnel(route.checkPort) }.getOrElse { app.perroute.service.ProxyHealthCheck.Result(false, message(it)) }
                if (tunnel.success()) findings += HealthFinding("tunnel", "Tunnel", CheckStatus.PASS, "The tunnel through $name works", tunnel.detail())
                else {
                    val hint = CoreLogHints.analyze(coreLogTail.toList().takeLast(120))
                    findings += HealthFinding("tunnel", "Tunnel", CheckStatus.FAIL, hint?.title ?: "The tunnel through $name does not work",
                        (hint?.detail ?: "The server is reachable but does not carry traffic: ${tunnel.detail()}") + " Other routes are not affected.",
                        if (route.main) Repair.SWITCH_SERVER else Repair.RECONNECT)
                }
            }
        }

        if (target != null) {
            val selected = applications().any { AppRoutes.normalize(it) == AppRoutes.normalize(target) }
            when {
                mode != ConnectionMode.NATIVE_PER_APP -> findings += HealthFinding("app", "App", CheckStatus.INFO, "$appName follows the ${modeLabel(mode)} mode",
                    if (mode == ConnectionMode.SYSTEM_PROXY) "It only uses the tunnel if it follows the Windows proxy settings. Many games and some apps do not; use Smart per-app for them."
                    else "It only uses the tunnel if it is set to the local proxy ports itself.")
                !selected -> findings += HealthFinding("app", "App", CheckStatus.FAIL, "$appName is not selected",
                    "Only selected apps use the tunnel in Smart per-app. Select it in Apps and save.", Repair.OPEN_APPS)
                else -> {
                    val running = processIds(target).isNotEmpty()
                    findings += HealthFinding("app", "App", if (running) CheckStatus.PASS else CheckStatus.INFO, if (running) "$appName is selected and running" else "$appName is selected but not running",
                        if (running && connected && connectedAtMillis > 0) "If it was already open before you connected, connections it opened earlier may still use the old route. Restart it to be sure." else "")
                    if (!connected && killSwitch()) findings += HealthFinding("killswitch", "App", CheckStatus.WARN, "The kill switch keeps $appName offline",
                        "This is intended while PerRoute is disconnected. Connect, or turn the kill switch off.", Repair.RECONNECT)
                    temporaryRoute(target)?.let { temporary ->
                        findings += HealthFinding("temporary", "App", CheckStatus.INFO, "$appName is on a temporary server",
                            "${nodes().firstOrNull { it.key() == temporary.nodeKey }?.let { cleanName(it.name()) } ?: "A server"} until ${formatClockTime(temporary.until)}.")
                    }
                }
            }
        }

        if (!destination.isNullOrBlank()) {
            val host = destination.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':')
            val verdict = evaluateRoute(target.orEmpty(), host, 443)
            val rule = verdict.rule()
            findings += when {
                rule != null && rule.action() == RouteRule.Action.BLOCK -> HealthFinding("rule", "Rules", CheckStatus.FAIL, "A rule blocks $host",
                    "Rule \"${ruleDisplayName(rule)}\" blocks it for ${if (rule.applicationPath().isBlank()) "every app" else healthAppName(rule.applicationPath())}. Change or disable the rule in Rules.")
                rule != null && rule.action() == RouteRule.Action.DIRECT -> HealthFinding("rule", "Rules", CheckStatus.INFO, "$host goes direct",
                    "Rule \"${ruleDisplayName(rule)}\" sends it around the tunnel. If the site only works through the tunnel, change that rule.")
                else -> HealthFinding("rule", "Rules", CheckStatus.PASS, "No rule stands in the way of $host", "It takes the app's route.")
            }
        }
        return HealthReport(findings, now, target?.let { appName } ?: "Your connection")
    }

    /**
     * Checks that protection works as configured, with real tests where they are possible: each route's exit
     * address against your own, where name lookups go, the kill switch and the per-app engine, and the rules
     * loaded in the core. What can only be predicted is labelled as such.
     */
    fun safetyCheck(): HealthReport {
        val findings = mutableListOf<HealthFinding>()
        val now = System.currentTimeMillis()
        val connected = isConnected()
        val mode = activeMode ?: state.connectionMode()
        runCatching { checkRoutes() }
        val checks = routeChecks
        val direct = checks[DIRECT_ROUTE_KEY]?.publicIp
        if (!connected) findings += HealthFinding("exit", "Exit address", CheckStatus.SKIPPED, "Connect to test the routes", "Exit addresses can only be observed through a running connection.")
        else for (route in activeRoutes) {
            val check = checks[route.id]
            val name = cleanName(route.node.name())
            findings += when {
                check?.state != RouteCheckState.PASSED -> HealthFinding("exit:${route.id}", "Exit address", CheckStatus.FAIL, "$name could not be tested", check?.detail?.ifBlank { null } ?: "No answer through this route.")
                direct != null && check.publicIp == direct -> HealthFinding("exit:${route.id}", "Exit address", CheckStatus.FAIL, "$name leaves from your own address",
                    "Traffic on this route appears with $direct, the same as without the tunnel. The server may forward traffic directly, or a rule sends the test site direct.")
                else -> HealthFinding("exit:${route.id}", "Exit address", CheckStatus.PASS, "$name hides your address",
                    "Seen as ${check.publicIp}${check.country?.let { " ($it)" } ?: ""}" + (direct?.let { "; your own address is $it." } ?: "."))
            }
        }

        val dns = dnsObservation
        findings += when (mode) {
            ConnectionMode.NATIVE_PER_APP -> HealthFinding("dns", "Name lookups", CheckStatus.WARN, "Name lookups use your normal DNS",
                "Windows looks up names for the selected apps itself, through ${dns?.systemServers?.joinToString(", ")?.ifBlank { null } ?: "your DNS servers"}" +
                    (dns?.publicResolver?.let { ", which reaches the internet as $it" } ?: "") +
                    ". The traffic goes through the tunnel, but your DNS provider can see which names are looked up. Turn on secure DNS (DNS over HTTPS) in Windows or in the browser to hide them.")
            ConnectionMode.SYSTEM_PROXY -> HealthFinding("dns", "Name lookups", CheckStatus.INFO, "Browsers send names to the proxy",
                "Apps that use the Windows proxy hand the site name to PerRoute, so it is looked up on the far side. Other apps look names up with your normal DNS.")
            else -> HealthFinding("dns", "Name lookups", CheckStatus.INFO, "Depends on each app", "Apps set to the SOCKS port can send names to the proxy; apps set to the HTTP port always do.")
        }

        if (mode == ConnectionMode.NATIVE_PER_APP) {
            val engine = runCatching { backend.networkState() }.getOrDefault(emptyMap())
            val redirecting = engine[app.perroute.netstate.NetworkStateKeys.ENGINE_SERVICE] == app.perroute.netstate.NetworkStateKeys.RUNNING ||
                engine[app.perroute.netstate.NetworkStateKeys.ENGINE_LIMITED] == app.perroute.netstate.NetworkStateKeys.RUNNING
            val ours = engine[app.perroute.netstate.NetworkStateKeys.ENGINE_CONFIG] == app.perroute.netstate.NetworkStateKeys.CONFIG_PERROUTE ||
                engine[app.perroute.netstate.NetworkStateKeys.ENGINE_LIMITED] == app.perroute.netstate.NetworkStateKeys.RUNNING
            val count = engine[app.perroute.netstate.NetworkStateKeys.ENGINE_APPS]?.toIntOrNull()
            if (engine.isNotEmpty()) findings += when {
                connected && (!redirecting || !ours) -> HealthFinding("engine", "Per-app engine", CheckStatus.FAIL, "The per-app engine is not redirecting",
                    "PerRoute is connected, but the engine is not running with PerRoute's app list, so the selected apps may connect directly.", Repair.RECONNECT)
                connected && count != null && engine[app.perroute.netstate.NetworkStateKeys.ENGINE_LIMITED] != app.perroute.netstate.NetworkStateKeys.RUNNING && count != applications().size ->
                    HealthFinding("engine", "Per-app engine", CheckStatus.WARN, "The engine redirects ${plural(count, "app")}, not ${applications().size}",
                        "Its app list differs from your selection. Reconnect to bring them in line.", Repair.RECONNECT)
                connected -> HealthFinding("engine", "Per-app engine", CheckStatus.PASS, "The selected apps are redirected", "The engine uses PerRoute's list of ${plural(applications().size, "app")}.")
                else -> HealthFinding("engine", "Per-app engine", CheckStatus.INFO, "Not connected", if (redirecting && ours) "The kill switch holds the selected apps." else "The selected apps connect directly until you connect.")
            }
            findings += if (killSwitch()) HealthFinding("killswitch", "Kill switch", CheckStatus.PASS, "The kill switch is on",
                "If the tunnel stops, the selected apps stay offline instead of using your direct connection." +
                    if (trafficGateway()) " The traffic gateway fails closed as well." else "")
            else HealthFinding("killswitch", "Kill switch", CheckStatus.WARN, "The kill switch is off",
                "If the tunnel stops unexpectedly, the selected apps fall back to your direct connection. Turn it on in Settings > Automation to keep them offline instead.")
        }

        val loaded = loadedRuleIds()
        val enabledRules = rules().filter { it.enabled() }
        if (loaded != null && enabledRules.isNotEmpty()) {
            val inUse = synchronized(state) { state.appPaths().map { AppRoutes.normalize(it) }.toSet() }
            val missing = enabledRules.filter { it.id() !in loaded && (it.applicationPath().isBlank() || AppRoutes.normalize(it.applicationPath()) in inUse) }
            findings += if (missing.isEmpty()) HealthFinding("rules", "Rules", CheckStatus.PASS, "Every rule is loaded in the running core", "${plural(enabledRules.size, "rule")} checked.")
            else HealthFinding("rules", "Rules", CheckStatus.FAIL, "${plural(missing.size, "rule")} not loaded",
                "${joinNames(missing.map { ruleDisplayName(it) })} ${if (missing.size == 1) "is" else "are"} missing from the running core. Reconnect to load them.", Repair.RECONNECT)
        } else if (enabledRules.isNotEmpty()) findings += HealthFinding("rules", "Rules", CheckStatus.SKIPPED, "Connect to check the loaded rules", "Which rules the core uses can only be read while connected.")
        RuleAnalysis.shadowed(rules()).forEach { item ->
            findings += HealthFinding("shadow:${item.rule.id()}", "Rules", CheckStatus.WARN, "\"${ruleDisplayName(item.rule)}\" never applies",
                "\"${ruleDisplayName(item.by)}\" comes first and already decides every destination it lists. Remove it or move it up in Rules.")
        }

        val untrusted = localPolicy.untrusted()
        if (untrusted.isNotEmpty()) {
            val blocked = localPolicy.rules(localNetworks()).blocked()
            findings += if (blocked.isEmpty()) HealthFinding("local", "Local network", CheckStatus.PASS, "You are on a trusted network", "Routed apps can reach local devices.")
            else if (connected && appliedLocalBlocking == blocked) HealthFinding("local", "Local network", CheckStatus.PASS, "Local devices are blocked for routed apps",
                "This network is not trusted. Only its router and DNS servers are reachable from routed apps.")
            else HealthFinding("local", "Local network", CheckStatus.WARN, "Local protection applies after reconnecting", "This network is not trusted, but the running connection was started for another network.", Repair.RECONNECT)
        }

        findings += HealthFinding("loopback", "Local ports", CheckStatus.INFO, "Local ports are on this computer only",
            "PerRoute's ports listen on 127.0.0.1, so other devices cannot use them. On a computer shared by several signed-in users, other users can.")
        workspaces().filter { it.running }.forEach { view ->
            findings += HealthFinding("workspace:${view.workspace.id}", "Workspaces", CheckStatus.PASS, "${view.workspace.name} has its own route",
                "Its browser uses port ${view.port} through ${view.node?.let { cleanName(it.name()) } ?: "its server"}; if that stops, the window has no connection rather than a direct one.")
        }
        return HealthReport(findings, now, "Routing safety")
    }

    /** Runs one repair the user confirmed. */
    fun repair(repair: Repair): ActionResult = when (repair) {
        Repair.RECONNECT -> { if (isConnected() || phase != ConnectionPhase.DISCONNECTED) disconnect(); connect() }
        Repair.RESTORE_NETWORK -> restoreNetwork()
        Repair.REAPPLY_SYSTEM_PROXY -> if (!isConnected() || activeMode != ConnectionMode.SYSTEM_PROXY) ActionResult(false, "Connect in System proxy mode first.")
            else runCatching { backend.applySystemProxy(); ActionResult(true, "The Windows proxy points to PerRoute again.") }.getOrElse { ActionResult(false, message(it)) }
        Repair.SWITCH_SERVER -> switchToFastest()
        Repair.STOP_LEFTOVER_CORES -> runCatching { backend.stopOrphan(); ActionResult(true, "Leftover cores were stopped.") }.getOrElse { ActionResult(false, message(it)) }
        Repair.UPDATE_SUBSCRIPTIONS -> updateAllSubscriptions()
        Repair.TURN_OFF_KILL_SWITCH -> { setKillSwitch(false); ActionResult(true, "The kill switch is off. The selected apps use your direct connection while PerRoute is disconnected.") }
        Repair.OPEN_COMPONENTS, Repair.OPEN_APPS -> ActionResult(true, "")
    }

    private fun formatSkew(seconds: Long): String = when {
        seconds < 120 -> "$seconds seconds"
        seconds < 7200 -> "${seconds / 60} minutes"
        else -> "${seconds / 3600} hours"
    }

    private fun formatClockTime(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))

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

    /**
     * Applies [next] to the running core when its route layout differs from what runs: an app that gets or loses
     * a route of its own (a temporary server), or routes that merge or split. Routes that exist on both sides keep
     * their outbound and their open connections; only routes that appear or go away are added or removed. Returns
     * false, changing nothing, when that is not possible (embedded profiles, balancers, a different app list or a
     * core without the control API); the caller then restarts the core.
     */
    @Synchronized
    private fun applyReconfiguration(next: PreparedConnection): Boolean {
        val current = runningPrepared ?: return false
        val config = runningConfig ?: return false
        if (!connectionReady || current.tun || next.tun || current.gateway != next.gateway || !current.runtimeControl || !next.runtimeControl) return false
        if (current.applications != next.applications) return false
        val running = XrayConfigGenerator.applicationRouteTargets(config)
        val planned = XrayConfigGenerator.applicationRouteTargets(next.config)
        // A route that exists on both sides, on the same server, keeps the outbound the core already has.
        val keep = HashMap<String, String>()
        for (route in next.routes) {
            val before = current.routes.firstOrNull { it.id == route.id && it.node.key() == route.node.key() } ?: continue
            val plannedTag = planned[route.ruleTag] ?: return false
            val liveTag = running[before.ruleTag] ?: return false
            keep[plannedTag] = liveTag
        }
        val plan = XrayConfigGenerator.runtimePlan(config, next.config, keep) ?: return false
        backend.validate(plan.config(), false)
        backend.applyRuntimePlan(plan)
        val previous = activeRoutes
        runningConfig = plan.config()
        runningPrepared = next
        activeRoutes = next.routes
        // Routes that stayed keep their health history; new ones start fresh and old ones stop being watched.
        previous.filter { old -> next.routes.none { it.id == old.id } }.forEach { recovery.remove(it.id) }
        next.routes.forEach { route ->
            val before = previous.firstOrNull { it.id == route.id }
            if (before == null || before.checkPort != route.checkPort || before.node.key() != route.node.key()) {
                recovery.replace(RouteTarget(route.id, cleanName(route.node.name()), route.node.key(), route.checkPort))
                synchronized(routeCheckLock) { routeChecks = routeChecks + (route.id to RouteCheck(RouteCheckState.CHECKING)) }
            }
        }
        synchronized(routeCheckLock) { routeChecks = routeChecks.filterKeys { key -> key == DIRECT_ROUTE_KEY || next.routes.any { it.id == key } } }
        startAutoLoop()
        return true
    }

    /** Moves the running core to [next] with as little disruption as possible: in place when it can, else a restart. */
    private fun applyLive(next: PreparedConnection, done: String, revert: () -> Unit): ActionResult {
        val fallback = runningPrepared ?: return ActionResult(false, "Connect first.")
        val inPlace = runCatching { applyReconfiguration(next) }
        if (inPlace.getOrNull() == true) {
            stateListeners.forEach { it(true) }
            log("$done Other routes kept running.")
            return ActionResult(true, done)
        }
        inPlace.exceptionOrNull()?.let { log("The change could not be made in place (${message(it)}); restarting the core.") }
        return restartCore(next, fallback, activeMode ?: state.connectionMode(), done, revert)
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
        runCatching { gamingResults.retain(live) }
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
        runCatching { Files.deleteIfExists(recipeSnapshotFile) }
        runCatching { gamingResults.clear() }
        runCatching { insights.clear() }
        runCatching { localPolicy.clear() }
        temporary = emptyList()
        temporaryLoaded.set(true)
        saveTemporary()
        // Workspace windows lose their route; their browsing data stays in its folders until removed by hand.
        runningWorkspaces.keys.toList().forEach { stopWorkspace(it) }
        runCatching { workspaceStore.clear() }
        localNetworkCache = emptyList()
        localNetworkCacheAt = 0L
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
                if (assumeAdvanced) ConnectionMode.NATIVE_PER_APP else state.connectionMode(), choices,
                runningPrepared?.temporary ?: temporaryChoices()
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
        val normalized = url.trim()
        return try {
            val previous = serversOf(normalized)
            val fetched = subscriptionService.fetchDetailed(normalized)
            val result = fetched.result()
            if (result.nodes().isEmpty()) return parseMessage(result)
            storeSubscription(result, normalized)
            recordSubscription(normalized, fetched, previous)
            parseMessage(result)
        } catch (e: Throwable) {
            if (subscriptions().any { it.equals(normalized, ignoreCase = true) }) recordSubscriptionFailure(normalized, message(e))
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
                val previous = serversOf(url)
                val fetched = subscriptionService.fetchDetailed(url)
                val result = fetched.result()
                result.warnings().forEach { log("Warning: $it") }
                if (result.nodes().isEmpty()) {
                    failures += "${sourceLabel(url)}: no valid servers in the response; kept the existing ones"
                    recordSubscriptionFailure(url, "The response had no valid servers.")
                } else {
                    merge(result, url, replaceSource = true)
                    recordSubscription(url, fetched, previous)
                    updated += result.nodes().size
                }
            } catch (error: Throwable) {
                failures += "${sourceLabel(url)}: ${message(error)}"
                recordSubscriptionFailure(url, message(error))
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
        runCatching { insights.remove(url) }
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

    private fun preview(result: ParseResult, source: String, replaceSource: Boolean,
                        fetched: app.perroute.service.SubscriptionService.Fetched? = null): ImportOutcome {
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
            ImportOutcome(ImportPreview(source, replaceSource, result, candidates, previous.count { it !in incoming }, fetched))
        }
    }

    /** Fetches and parses without saving anything, so the user can review servers and parser warnings. */
    fun previewSubscription(url: String): ImportOutcome {
        if (url.isBlank()) return ImportOutcome(error = "Enter a subscription URL.")
        return try {
            val fetched = subscriptionService.fetchDetailed(url.trim())
            preview(fetched.result(), url.trim(), replaceSource = true, fetched)
        } catch (error: Throwable) {
            if (subscriptions().any { it.equals(url.trim(), ignoreCase = true) }) recordSubscriptionFailure(url.trim(), message(error))
            ImportOutcome(error = message(error))
        }
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
            if (preview.replaceSource) {
                val previous = serversOf(preview.source)
                storeSubscription(preview.result, preview.source)
                preview.fetched?.let { recordSubscription(preview.source, it, previous) }
            }
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
        val runtimeControl: Boolean = false,
        /** Temporary servers baked into this config, by normalized path. */
        val temporary: Map<String, String> = emptyMap(),
        /** Whether the per-app engine must also send local-network traffic to the core (untrusted networks). */
        val localBlocking: Boolean = false
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
        val temporaries = if (mode == ConnectionMode.NATIVE_PER_APP) temporaryChoices().filterKeys { key -> apps.any { AppRoutes.normalize(it) == key } } else emptyMap()
        val plan = synchronized(state) {
            RoutePlanner.plan(state.nodes().toList(), main.key(), apps, state.advancedRouting(),
                LinkedHashMap(state.appRoutes()), LinkedHashMap(state.appRouteLabels()), mode, autoChoices, temporaries)
        }
        check(plan.ready()) { missingRouteMessage(plan) }
        val tun = mode == ConnectionMode.TUN_COMPATIBILITY
        val perApp = mode == ConnectionMode.NATIVE_PER_APP
        val gateway = perApp && trafficGateway()
        // The core's control API is only enabled for features that change routes in place. A temporary server is
        // one of them, so it can begin and end without interrupting anyone else. Once on, it stays on for the
        // rest of the session, so the end of the last temporary server does not need a restart either.
        val runtimeControl = !tun && (routeMonitoring() || hasAutoApps() || (perApp && temporaries.isNotEmpty()) ||
            (connectionReady && runningPrepared?.runtimeControl == true))
        val local = localNetworkRules()
        val generator = XrayConfigGenerator(if (gateway) XrayConfigGenerator.GATEWAY_UPSTREAM_PORT_BASE else XrayConfigGenerator.PER_APP_PORT_BASE, runtimeControl)
            .withLocalNetwork(local.localNamesDirect(), local.allowed(), local.blocked())
        val configNodes = if (!perApp) emptyList() else plan.applicationNodes().mapIndexed { index, node ->
            val route = plan.routeFor(apps[index])
            when {
                node == null -> null
                route?.temporary() == true -> temporarySlot(apps[index], node)
                route?.auto() == true -> autoSlot(apps[index], node)
                else -> node
            }
        }
        val config = if (tun) configGenerator.generate(main, apps, state.routingMode())
            else generator.generateApplicationProxy(main, if (perApp) apps else emptyList(), rules(), configNodes)
        val customKeys = if (perApp) XrayConfigGenerator.customRouteKeys(main, apps, configNodes) else emptyList()
        val routes = plan.routes().map { route ->
            val slot = when {
                route.temporary() -> temporarySlotKey(route.applications().first())
                route.auto() -> autoSlotKey(route.applications().first())
                else -> route.node().key()
            }
            val index = customKeys.indexOf(slot)
            ActiveRoute(route.node(), route.main(), if (perApp) route.applications() else emptyList(),
                if (route.main()) AppProxyLauncher.SOCKS_PORT else XrayConfigGenerator.routeCheckPort(index),
                id = route.id(), auto = route.auto(),
                ruleTag = XrayConfigGenerator.applicationRouteRuleTag(if (route.main()) -1 else index))
        }
        return PreparedConnection(config, tun, routes, gateway, apps, if (perApp) autoChoices else emptyMap(), plan, runtimeControl,
            temporaries, perApp && local.blocking())
    }

    private fun autoSlotKey(application: String) = "auto-slot:" + AppRoutes.normalize(application)

    private fun autoSlot(application: String, node: ProxyNode) = ProxyNode(autoSlotKey(application), node.name(), node.protocol(),
        node.address(), node.port(), node.credential(), node.options(), node.sourceLink())

    private fun temporarySlotKey(application: String) = "temp-slot:" + AppRoutes.normalize(application)

    private fun temporarySlot(application: String, node: ProxyNode) = ProxyNode(temporarySlotKey(application), node.name(), node.protocol(),
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
                applyPerAppEngine(apps, prepared)
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
                    applyPerAppEngine(apps, prepared)
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
            snapshotLater(app.perroute.netstate.NetworkSnapshot.Reason.CONNECTED, 2_500)
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
        snapshotLater(app.perroute.netstate.NetworkSnapshot.Reason.AFTER_DISCONNECT, 800)
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
                            // Moving between a trusted and an untrusted network changes what local traffic may do,
                            // and that is part of the running config.
                            if (localPolicy.untrusted().isNotEmpty()) {
                                val blocked = localPolicy.rules(localNetworks(refresh = true)).blocked()
                                if (blocked != appliedLocalBlocking) {
                                    if (autoReconnect()) { log("You joined a network with a different trust setting; reconnecting to apply it."); backend.stop() }
                                    else log("You joined a network with a different trust setting. Reconnect to apply it.")
                                    return@start
                                }
                            }
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
        // Workspace windows that stay open lose their route with PerRoute: they fail closed, never direct.
        runningWorkspaces.values.forEach { runCatching { it.tunnel.close() } }
        runningWorkspaces.clear()
        runCatching { markStartupHealthy() }
        runCatching { quality.close() }
        history.close()
        saveExecutor.shutdown()
        runCatching { saveExecutor.awaitTermination(20, TimeUnit.SECONDS) }
        saveNow()
    }

    // Kotlin runs initializers in the order they are written, so the 3.5 background work starts here, after
    // every property above has been set up.
    init {
        backend.addLogListener(Consumer { line -> rememberCoreLine(line) })
        startTemporaryExpiry()
        // The settings as PerRoute finds them, after any crash recovery: the baseline for a later restore.
        snapshotLater(app.perroute.netstate.NetworkSnapshot.Reason.STARTUP, 4_000)
        Thread.ofVirtual().name("perroute-workspace-reattach").start { runCatching { reattachWorkspaces() } }
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
        /** First loopback port of the browser workspaces; each has a fixed one, clear of every connection port. */
        val WORKSPACE_PORT_BASE = 19700 + app.perroute.service.PortOffset.VALUE
        /** Sites a comparison uses when the route's apps were not seen visiting any. */
        val DEFAULT_SHADOW_DESTINATIONS = listOf("www.google.com", "www.cloudflare.com", "www.microsoft.com", "github.com", "www.wikipedia.org")

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
