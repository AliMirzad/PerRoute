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

/** A server the running core is carrying traffic for, and the loopback port that leads to it. */
data class ActiveRoute(val node: ProxyNode, val main: Boolean, val applications: List<String>, val checkPort: Int)

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
class PerRouteController(
    private val state: AppState,
    private val store: AppStateStore,
    private val backend: ConnectionBackend = SystemConnectionBackend(),
    private val preferences: Preferences = Preferences.userNodeForPackage(PerRouteController::class.java)
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

    init {
        if (state.connectionMode() == ConnectionMode.TUN_COMPATIBILITY) {
            state.setConnectionMode(ConnectionMode.SYSTEM_PROXY)
            save()
        }
        if (deduplicateNodes()) save()
        runCatching { backend.restoreSystemProxy() }
            .onFailure { log("Could not restore stale system proxy settings: ${message(it)}") }
        backend.addLogListener(Consumer { line -> log(line) })
        backend.addRunningListener(Consumer { running ->
            if (!running && phase == ConnectionPhase.CONNECTED) {
                val stoppedMode = activeMode
                connectionReady = false
                activeMode = null
                phase = ConnectionPhase.DISCONNECTED
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
        recoverFromUnexpectedExit()
        Runtime.getRuntime().addShutdownHook(Thread({
            if (!closing) {
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
            state.setActiveProfileId("")
            state.setSelectedNodeKey("")
            state.setAdvancedRouting(false)
            state.setConnectionMode(ConnectionMode.SYSTEM_PROXY)
            state.setRoutingMode(RoutingMode.INCLUDE_SELECTED)
        }
        runCatching { preferences.clear() }
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
    fun routePlan(assumeAdvanced: Boolean = false): RoutePlanner.Plan = synchronized(state) {
        RoutePlanner.plan(
            state.nodes().toList(), state.selectedNodeKey(), state.appPaths().toList(),
            assumeAdvanced || state.advancedRouting(), LinkedHashMap(state.appRoutes()),
            LinkedHashMap(state.appRouteLabels()),
            if (assumeAdvanced) ConnectionMode.NATIVE_PER_APP else state.connectionMode()
        )
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
    fun saveApplicationSetup(paths: Collection<String>, routes: Map<String, String>, advanced: Boolean): ActionResult {
        editLock("app routes")?.let { return it }
        synchronized(state) {
            storeApplications(paths)
            storeRoutes(routes)
            state.setAdvancedRouting(advanced)
        }
        save()
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
        measureEndpoints(targets).also { rememberLatencies(it) }

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
                .mapKeys { it.key.key() }.also { rememberLatencies(it) }
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
                tasks += executor.submit { observeRoute(route.node.key(), route.checkPort) }
            }
            val servers = executor.submit<List<String>> { backend.systemDnsServers() }
            val resolver = executor.submit<String?> { backend.publicResolver() }

            tasks.forEach { runCatching { it.get(20, TimeUnit.SECONDS) } }
            dnsObservation = DnsObservation(
                runCatching { servers.get(15, TimeUnit.SECONDS) }.getOrDefault(emptyList()),
                runCatching { resolver.get(15, TimeUnit.SECONDS) }.getOrNull(),
                System.currentTimeMillis()
            )
            val failed = routes.count { routeChecks[it.node.key()]?.state == RouteCheckState.FAILED }
            when {
                routes.isEmpty() -> ActionResult(true, "Direct route checked. Connect to check your servers.")
                failed == 0 -> ActionResult(true, "${routes.size} ${if (routes.size == 1) "route" else "routes"} checked.")
                else -> ActionResult(false, "$failed of ${routes.size} routes failed the check.")
            }
        } finally {
            executor.shutdownNow()
        }
    }

    fun checkRoute(key: String): ActionResult {
        val port = if (key == DIRECT_ROUTE_KEY) null
            else activeRoutes.takeIf { isConnected() }?.firstOrNull { it.node.key() == key }?.checkPort
                ?: return ActionResult(false, "Connect to check this server.")
        observeRoute(key, port)
        val check = routeChecks[key]
        return if (check?.state == RouteCheckState.PASSED) ActionResult(true, "Check passed.")
        else ActionResult(false, "Check failed: ${check?.detail.orEmpty()}")
    }

    private fun observeRoute(key: String, socksPort: Int?) {
        val previous = routeChecks[key]
        setRouteCheck(key, (previous ?: RouteCheck()).copy(state = RouteCheckState.CHECKING))
        val started = System.nanoTime()
        val report = runCatching { backend.lookupPublicAddress(socksPort) }.getOrElse { RouteReport(error = message(it)) }
        val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
        setRouteCheck(key, if (report.ip != null) RouteCheck(RouteCheckState.PASSED, System.currentTimeMillis(), elapsed, report.ip, report.country)
            else RouteCheck(RouteCheckState.FAILED, System.currentTimeMillis(), detail = report.error ?: "No answer"))
    }

    /** A light tunnel check for every route right after the core starts. It is not repeated in the background. */
    private fun startConnectChecks(routes: List<ActiveRoute>) {
        routes.forEach { route ->
            setRouteCheck(route.node.key(), RouteCheck(RouteCheckState.CHECKING))
            Thread.ofVirtual().name("perroute-route-check").start {
                val startedAt = System.currentTimeMillis()
                val started = System.nanoTime()
                val health = runCatching { backend.checkTunnel(route.checkPort) }
                val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
                if (activeRoutes.none { it.node.key() == route.node.key() && it.checkPort == route.checkPort }) return@start
                val ok = health.getOrNull()?.success() == true
                val detail = health.getOrNull()?.detail() ?: message(health.exceptionOrNull() ?: IllegalStateException("No answer"))
                val observed = if (ok) RouteCheck(RouteCheckState.PASSED, System.currentTimeMillis(), elapsed, detail = detail)
                    else RouteCheck(RouteCheckState.FAILED, System.currentTimeMillis(), detail = detail)
                synchronized(routeCheckLock) {
                    // A check the user asked for in the meantime is newer and more detailed; keep it.
                    val current = routeChecks[route.node.key()]
                    if (current != null && current.state != RouteCheckState.CHECKING && current.atMillis >= startedAt) return@start
                    routeChecks = routeChecks + (route.node.key() to observed)
                }
                val name = cleanName(route.node.name())
                if (ok) log("Tunnel health ($name): $detail") else log("Tunnel health was inconclusive ($name): $detail")
            }
        }
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
                state.connectionMode(), state.routeRules(), state.advancedRouting(), LinkedHashMap(state.appRoutes()))
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

    private class PreparedConnection(val config: String, val tun: Boolean, val routes: List<ActiveRoute>)

    private fun missingRouteMessage(plan: RoutePlanner.Plan): String {
        val first = plan.missing().first()
        val app = displayName(first.applicationPath())
        val server = first.label().ifBlank { "its custom server" }
        val reason = "$app is assigned to $server, which is no longer in your server list."
        val more = if (plan.missing().size > 1) " ${plan.missing().size - 1} more ${if (plan.missing().size == 2) "app has" else "apps have"} the same problem." else ""
        return "$reason$more Open Apps and choose a replacement, or turn off Advanced routing to use the main server for every app."
    }

    /** Builds the whole config for a main server, re-applying every per-app assignment that is in effect. */
    private fun prepare(main: ProxyNode, mode: ConnectionMode, apps: List<String>): PreparedConnection {
        val plan = synchronized(state) {
            RoutePlanner.plan(state.nodes().toList(), main.key(), apps, state.advancedRouting(),
                LinkedHashMap(state.appRoutes()), LinkedHashMap(state.appRouteLabels()), mode)
        }
        check(plan.ready()) { missingRouteMessage(plan) }
        val tun = mode == ConnectionMode.TUN_COMPATIBILITY
        val perApp = mode == ConnectionMode.NATIVE_PER_APP
        val config = if (tun) configGenerator.generate(main, apps, state.routingMode())
            else configGenerator.generateApplicationProxy(main, if (perApp) apps else emptyList(), rules(),
                if (perApp) plan.applicationNodes() else emptyList())
        val customKeys = if (perApp) XrayConfigGenerator.customRouteKeys(main, apps, plan.applicationNodes()) else emptyList()
        val routes = plan.routes().map { route ->
            ActiveRoute(route.node(), route.main(), if (perApp) route.applications() else emptyList(),
                if (route.main()) AppProxyLauncher.SOCKS_PORT
                else XrayConfigGenerator.routeCheckPort(customKeys.indexOf(route.node().key())))
        }
        return PreparedConnection(config, tun, routes)
    }

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
        synchronized(routeCheckLock) { routeChecks = routeChecks.filterKeys { it == DIRECT_ROUTE_KEY } }
        if (!prepared.tun) startConnectChecks(prepared.routes)
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
            val prepared = prepare(node, mode, apps)
            val tun = prepared.tun
            validate(prepared)
            if (mode == ConnectionMode.NATIVE_PER_APP && killSwitch()) {
                backend.applyPerApp(apps)
            }
            backend.stop()
            backend.start(prepared.config, tun)
            if (!tun) {
                Thread.sleep(250)
                if (!backend.isRunning()) error("Xray stopped immediately after launch.")
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
        val apps = applications()
        val prepared: PreparedConnection
        val fallback: PreparedConnection
        try {
            fallback = prepare(previous, mode, apps)
            prepared = prepare(target, mode, apps)
            validate(prepared)
        } catch (invalid: Throwable) {
            return ActionResult(false, "Could not switch servers; the current connection was left untouched: ${message(invalid)}")
        }
        phase = ConnectionPhase.CONNECTING
        connectionReady = false
        state.setSelectedNodeKey(key)
        save()
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
            log("Switched to ${cleanName(target.name())}.")
            ActionResult(true, "Switched to ${cleanName(target.name())}.")
        } catch (switchError: Throwable) {
            state.setSelectedNodeKey(previous.key())
            save()
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
            while (telemetryGeneration.get() == generation) {
                try {
                    val counters = backend.readInboundCounters()
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

    private fun stopTelemetry() {
        markSession(false)
        telemetryGeneration.incrementAndGet()
        connectedAtMillis = 0L
        traffic = TrafficSnapshot()
        activeRoutes = emptyList()
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
                            if (desiredConnected && isConnected() &&
                                !runCatching { backend.checkTunnel(AppProxyLauncher.SOCKS_PORT).success() }.getOrDefault(false)) {
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
        phase = ConnectionPhase.DISCONNECTED
        history.close()
        saveExecutor.shutdown()
        runCatching { saveExecutor.awaitTermination(20, TimeUnit.SECONDS) }
        saveNow()
    }

    companion object {
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
