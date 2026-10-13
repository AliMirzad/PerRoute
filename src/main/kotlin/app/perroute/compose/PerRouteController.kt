package app.perroute.compose

import app.perroute.model.AppState
import app.perroute.model.ConnectionMode
import app.perroute.model.ProxyNode
import app.perroute.model.RoutingMode
import app.perroute.model.RouteRule
import app.perroute.model.ConnectionProfile
import app.perroute.parser.ParseResult
import app.perroute.persistence.AppStateStore
import app.perroute.service.AdminCheck
import app.perroute.service.AppDirectories
import app.perroute.service.AppProxyLauncher
import app.perroute.service.CoreInstaller
import app.perroute.service.ProcessDiscovery
import app.perroute.service.InstalledApplicationDiscovery
import app.perroute.service.ProxiFyreManager
import app.perroute.service.ProxyHealthCheck
import app.perroute.service.ServerLatencyProbe
import app.perroute.service.SecureUpdateService
import app.perroute.service.SubscriptionService
import app.perroute.service.XrayConfigGenerator
import app.perroute.service.XrayProcessManager
import app.perroute.service.XrayStatsReader
import app.perroute.service.RealTunnelProbe
import app.perroute.service.TrafficHistoryStore
import app.perroute.service.QrImportService
import app.perroute.service.WindowsSystemProxyManager
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
import java.net.InetAddress
import app.perroute.service.RouteRuleEvaluator
import app.perroute.service.TunnelHttp
import app.perroute.util.Json
import java.util.function.Consumer
import java.util.prefs.Preferences

data class EnvironmentState(
    val coreReady: Boolean,
    val perAppReady: Boolean,
    val elevated: Boolean
)

data class ActionResult(val ok: Boolean, val message: String)

data class AppTraffic(
    val path: String,
    val downloadBytes: Long = 0,
    val uploadBytes: Long = 0,
    val downloadBytesPerSecond: Long = 0,
    val uploadBytesPerSecond: Long = 0,
    val active: Boolean = false
)

data class TrafficSnapshot(
    val downloadBytesPerSecond: Long = 0,
    val uploadBytesPerSecond: Long = 0,
    val totalDownloadBytes: Long = 0,
    val totalUploadBytes: Long = 0,
    val connectedSeconds: Long = 0,
    val latencyMillis: Long? = null,
    val applications: List<AppTraffic> = emptyList()
)

data class TrafficPoint(
    val downloadBytesPerSecond: Long,
    val uploadBytesPerSecond: Long,
    val atMillis: Long = System.currentTimeMillis()
)

enum class ConnectionPhase { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

data class RouteReport(val ip: String? = null, val country: String? = null, val error: String? = null)

data class RouteInspection(
    val direct: RouteReport,
    val tunnel: RouteReport?,
    val systemDnsServers: List<String>,
    val publicResolver: String?,
    val mode: ConnectionMode,
    val killSwitch: Boolean
) {
    val tunnelExposesDirectIp: Boolean
        get() = tunnel?.ip != null && tunnel.ip == direct.ip
}

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

class PerRouteController(
    private val state: AppState,
    private val store: AppStateStore
) : AutoCloseable {
    private val subscriptionService by lazy { SubscriptionService() }
    private val coreInstaller by lazy { CoreInstaller() }
    private val processDiscovery by lazy { ProcessDiscovery() }
    private val installedApplicationDiscovery by lazy { InstalledApplicationDiscovery() }
    private val xray by lazy { XrayProcessManager() }
    private val statsReader by lazy { XrayStatsReader() }
    private val configGenerator by lazy { XrayConfigGenerator() }
    private val healthCheck by lazy { ProxyHealthCheck() }
    private val latencyProbe by lazy { ServerLatencyProbe() }
    private val realTunnelProbe by lazy { RealTunnelProbe() }
    private val qrImport by lazy { QrImportService() }
    private val history by lazy { TrafficHistoryStore(AppDirectories.dataDirectory().resolve("traffic-history.json")) }
    private val updateService by lazy { SecureUpdateService() }
    private val proxiFyre by lazy { ProxiFyreManager() }
    private val systemProxy by lazy { WindowsSystemProxyManager(AppDirectories.dataDirectory()) }
    private val logListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val stateListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val preferences = Preferences.userNodeForPackage(PerRouteController::class.java)
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
        runCatching { systemProxy.restore() }
            .onFailure { log("Could not restore stale system proxy settings: ${message(it)}") }
        xray.addLogListener(Consumer { line -> log(line) })
        xray.addStateListener(Consumer { running ->
            if (!running && phase == ConnectionPhase.CONNECTED) {
                val stoppedMode = activeMode
                connectionReady = false
                activeMode = null
                phase = ConnectionPhase.DISCONNECTED
                stopTelemetry()
                if (stoppedMode == ConnectionMode.NATIVE_PER_APP) {
                    if (!killSwitch()) Thread.ofVirtual().name("perroute-sidecar-cleanup").start {
                        runCatching { proxiFyre.stopAndRestore() }
                    }
                }
                if (stoppedMode == ConnectionMode.SYSTEM_PROXY) {
                    Thread.ofVirtual().name("perroute-system-proxy-cleanup").start {
                        runCatching { systemProxy.restore() }
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
                runCatching { xray.stop() }
                runCatching { systemProxy.restore() }
                runCatching { if (!killSwitch()) proxiFyre.stopManagedSidecar() }
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
            runCatching { xray.stopOrphan() }
            if (unclean && !killSwitch()) runCatching { proxiFyre.stopAndRestore() }
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
    fun isConnected(): Boolean = connectionReady && xray.isRunning
    fun connectionPhase(): ConnectionPhase = phase
    fun trafficSnapshot(): TrafficSnapshot = traffic
    fun trafficPoints(): List<TrafficPoint> = trafficPoints.toList()
    fun trafficHistory(): TrafficHistoryStore.Snapshot = history.snapshot()
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
        if (!value && !isConnected()) Thread.ofVirtual().start { runCatching { proxiFyre.stopAndRestore() } }
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
            coreReady = runCatching { coreInstaller.inspect().ready() }.getOrDefault(false),
            perAppReady = runCatching { proxiFyre.inspect().ready() }.getOrDefault(false),
            elevated = runCatching { AdminCheck.isElevated() }.getOrDefault(false)
        )
    } catch (_: Throwable) {
        EnvironmentState(false, false, false)
    }

    fun selectNode(key: String) {
        state.setSelectedNodeKey(key)
        save()
    }

    fun removeNode(key: String) {
        synchronized(state) {
            state.nodes().removeIf { it.key() == key }
            state.nodeSources().remove(key)
            state.favoriteNodeKeys().remove(key)
            state.hiddenNodeKeys().remove(key)
            state.nodeTags().remove(key)
            if (state.selectedNodeKey() == key) {
                state.setSelectedNodeKey(state.nodes().firstOrNull()?.key() ?: "")
            }
        }
        save()
    }

    fun setMode(mode: ConnectionMode) {
        if (phase != ConnectionPhase.DISCONNECTED || isConnected()) return
        if (state.connectionMode() == ConnectionMode.NATIVE_PER_APP && mode != ConnectionMode.NATIVE_PER_APP) {
            Thread.ofVirtual().name("perroute-sidecar-cleanup").start { runCatching { proxiFyre.stopAndRestore() } }
        }
        state.setConnectionMode(mode)
        save()
    }

    fun setRoutingMode(mode: RoutingMode) {
        state.setRoutingMode(mode)
        save()
    }

    fun addApplication(path: String) {
        val normalized = Path.of(path).toAbsolutePath().normalize().toString()
        if (!Files.isRegularFile(Path.of(normalized))) return
        synchronized(state) {
            if (state.appPaths().none { it.equals(normalized, ignoreCase = true) }) state.appPaths().add(normalized)
        }
        save()
    }

    fun replaceApplications(paths: Collection<String>) {
        synchronized(state) {
            state.appPaths().clear()
            paths.map { Path.of(it).toAbsolutePath().normalize().toString() }
                .distinctBy { it.lowercase(Locale.ROOT) }
                .filter { Files.isRegularFile(Path.of(it)) }
                .forEach(state.appPaths()::add)
        }
        save()
    }

    fun removeApplication(path: String) {
        synchronized(state) { state.appPaths().removeIf { it.equals(path, ignoreCase = true) } }
        save()
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
                processDiscovery.list().filter(::isUserFacingApplication).also {
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
                installedApplicationDiscovery.list().filter { isUserFacingApplication(it.name(), it.path()) }.also {
                    installedAppCache = it
                    installedAppCacheAt = refreshedAt
                }
            }
        }
    }

    fun fetchSubscription(url: String): ActionResult {
        if (url.isBlank()) return ActionResult(false, "Enter a subscription URL.")
        return try {
            val normalized = url.trim()
            val result = subscriptionService.fetch(normalized)
            if (result.nodes().isEmpty()) return parseMessage(result)
            merge(result, normalized, replaceSource = true)
            synchronized(state) {
                if (state.subscriptionUrls().none { it.equals(normalized, ignoreCase = true) }) {
                    state.subscriptionUrls().add(normalized)
                }
            }
            save()
            parseMessage(result)
        } catch (e: Throwable) {
            ActionResult(false, message(e))
        }
    }

    fun updateAllSubscriptions(): ActionResult {
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
        return if (failures.isEmpty()) ActionResult(true, "Updated $updated servers from ${urls.size} subscriptions.")
        else ActionResult(false, "Updated $updated servers. Failed: ${failures.joinToString(" | ")}")
    }

    fun removeSubscription(url: String): ActionResult {
        if (url.isBlank() || url == MANUAL_SOURCE || url == IMPORTED_SOURCE) {
            return ActionResult(false, "Select a saved subscription first.")
        }
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

    fun inspectRoutes(): RouteInspection {
        val connected = isConnected()
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        return try {
            val direct = executor.submit<RouteReport> { traceRoute(TunnelHttp.Route.direct()) }
            val tunnel = if (connected) executor.submit<RouteReport> {
                traceRoute(TunnelHttp.Route.socks(AppProxyLauncher.SOCKS_PORT))
            } else null
            val servers = executor.submit<List<String>> { systemDnsServers() }
            val resolver = executor.submit<String?> {
                runCatching { InetAddress.getByName("whoami.akamai.net").hostAddress }.getOrNull()
            }
            fun <T> await(future: java.util.concurrent.Future<T>, fallback: T): T =
                runCatching { future.get(15, TimeUnit.SECONDS) }.getOrDefault(fallback)
            RouteInspection(
                direct = await(direct, RouteReport(error = "The lookup timed out.")),
                tunnel = tunnel?.let { await(it, RouteReport(error = "The lookup timed out.")) },
                systemDnsServers = await(servers, emptyList()),
                publicResolver = await(resolver, null),
                mode = activeMode ?: state.connectionMode(),
                killSwitch = killSwitch()
            )
        } finally {
            executor.shutdownNow()
        }
    }

    private fun traceRoute(route: TunnelHttp.Route): RouteReport {
        val trace = runCatching { TunnelHttp.getText(route, "www.cloudflare.com", "/cdn-cgi/trace", 6_000) }
        trace.getOrNull()?.let { body ->
            val fields = body.lines().mapNotNull { line ->
                line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() }
            }.toMap()
            fields["ip"]?.takeIf { it.isNotBlank() }?.let { return RouteReport(ip = it, country = fields["loc"]) }
        }
        val plain = runCatching { TunnelHttp.getText(route, "api.ipify.org", "/", 6_000).trim() }
        plain.getOrNull()?.takeIf { it.isNotBlank() && it.length <= 45 }?.let { return RouteReport(ip = it) }
        return RouteReport(error = message(plain.exceptionOrNull() ?: trace.exceptionOrNull() ?: IllegalStateException("No answer")))
    }

    private fun systemDnsServers(): List<String> = runCatching {
        val environment = java.util.Hashtable<String, String>()
        environment["java.naming.factory.initial"] = "com.sun.jndi.dns.DnsContextFactory"
        val context = javax.naming.directory.InitialDirContext(environment)
        try {
            (context.environment["java.naming.provider.url"] as? String).orEmpty()
                .split(' ').map { it.removePrefix("dns://").trim('/', ' ') }.filter { it.isNotBlank() }.distinct()
        } finally {
            context.close()
        }
    }.getOrDefault(emptyList())

    fun loadedRuleIds(): Set<String>? {
        if (!isConnected()) return null
        return runCatching {
            val root = Json.`object`(Files.readString(AppDirectories.dataDirectory().resolve("current-config.json")))
            val routing = root["routing"] as? Map<*, *>
            (routing?.get("rules") as? List<*>).orEmpty()
                .mapNotNull { ((it as? Map<*, *>)?.get("ruleTag") as? String)?.takeIf { tag -> tag.startsWith("perroute-custom-") } }
                .mapTo(hashSetOf()) { it.removePrefix("perroute-custom-") }
        }.getOrDefault(emptySet())
    }

    fun evaluateRoute(application: String, destination: String, port: Int): RouteRuleEvaluator.Verdict =
        RouteRuleEvaluator.evaluate(rules(), applications(), application, destination, port)

    fun activeScenarioId(): String {
        val names = rules().map { it.name() }
        return SCENARIOS.firstOrNull { scenario -> names.any { it.startsWith(scenarioPrefix(scenario)) } }?.id ?: ""
    }

    fun applyScenario(id: String): ActionResult {
        if (isConnected() || phase != ConnectionPhase.DISCONNECTED) return ActionResult(false, "Disconnect before changing scenarios.")
        val scenario = SCENARIOS.firstOrNull { it.id == id } ?: return ActionResult(false, "Scenario was not found.")
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
        return ActionResult(true, "${scenario.title} scenario applied.")
    }

    fun clearScenario(): ActionResult {
        if (isConnected() || phase != ConnectionPhase.DISCONNECTED) return ActionResult(false, "Disconnect before changing scenarios.")
        synchronized(state) { state.routeRules().removeIf { it.name().startsWith(SCENARIO_RULE_PREFIX) } }
        save()
        return ActionResult(true, "Scenario rules removed. Automation settings were left as they are.")
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

    fun importQrImage(path: Path): ActionResult = try { pasteLinks(qrImport.decode(path)) }
    catch (error: Throwable) { ActionResult(false, message(error)) }
    fun importQrClipboard(): ActionResult = try { pasteLinks(qrImport.decodeClipboard()) }
    catch (error: Throwable) { ActionResult(false, message(error)) }
    fun importQrCamera(): ActionResult = try { pasteLinks(qrImport.decodeCamera()) }
    catch (error: Throwable) { ActionResult(false, message(error)) }

    fun rules(): List<RouteRule> = synchronized(state) { state.routeRules().toList() }
    fun addRule(name: String, app: String, type: RouteRule.MatchType, value: String, action: RouteRule.Action): ActionResult {
        if (value.isBlank()) return ActionResult(false, "Enter a domain, IP/CIDR, or port.")
        return try {
            synchronized(state) { state.routeRules().add(RouteRule("", name.ifBlank { "$type rule" }, app, type, value, action, true)) }
            save(); ActionResult(true, "Rule added.")
        } catch (error: Throwable) { ActionResult(false, message(error)) }
    }
    fun removeRule(id: String) { synchronized(state) { state.routeRules().removeIf { it.id() == id } }; save() }
    fun setRuleEnabled(id: String, enabled: Boolean) {
        synchronized(state) {
            val index = state.routeRules().indexOfFirst { it.id() == id }
            if (index >= 0) state.routeRules()[index] = state.routeRules()[index].let {
                RouteRule(it.id(), it.name(), it.applicationPath(), it.matchType(), it.value(), it.action(), enabled)
            }
        }; save()
    }

    fun profiles(): List<ConnectionProfile> = synchronized(state) { state.profiles().toList() }
    fun activeProfileId(): String = synchronized(state) { state.activeProfileId() }
    fun saveProfile(name: String): ActionResult {
        if (name.isBlank()) return ActionResult(false, "Enter a profile name.")
        synchronized(state) {
            state.profiles().removeIf { it.name().equals(name.trim(), true) }
            val profile = ConnectionProfile("", name.trim(), state.appPaths(), state.selectedNodeKey(),
                state.connectionMode(), state.routeRules())
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
            state.setActiveProfileId(profile.id())
        }
        save(); return ActionResult(true, "${profile.name()} profile applied.")
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
        }
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
        if (!runCatching { coreInstaller.inspect().ready() }.getOrDefault(false)) return ActionResult(false, "Xray is not installed. Install it from Settings.")
        if (mode == ConnectionMode.NATIVE_PER_APP && !proxiFyre.inspect().ready()) {
            return ActionResult(false, "Smart per-app needs the bundled Windows driver. Reinstall PerRoute with the Full Installer and approve its single UAC prompt, or choose Local proxy/System proxy.")
        }
        if (mode == ConnectionMode.TUN_COMPATIBILITY && !AdminCheck.isElevated()) {
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
            val tun = mode == ConnectionMode.TUN_COMPATIBILITY
            val config = if (tun) configGenerator.generate(node, apps, state.routingMode())
                else configGenerator.generateApplicationProxy(node,
                    if (mode == ConnectionMode.NATIVE_PER_APP) apps else emptyList(), rules())
            if (mode == ConnectionMode.NATIVE_PER_APP && killSwitch()) {
                proxiFyre.applyAndStart(apps)
            }
            xray.stop()
            xray.start(config, tun)
            if (!tun) {
                Thread.sleep(250)
                if (!xray.isRunning) error("Xray stopped immediately after launch.")
                Thread.ofVirtual().name("perroute-health-check").start {
                    val health = healthCheck.check()
                    if (health.success()) log("Tunnel health: ${health.detail()}")
                    else log("Tunnel health was inconclusive: ${health.detail()}")
                }
                if (mode == ConnectionMode.SYSTEM_PROXY) {
                    systemProxy.apply()
                } else if (mode == ConnectionMode.NATIVE_PER_APP && !killSwitch()) {
                    proxiFyre.applyAndStart(apps)
                }
            }
            activeMode = mode
            connectionReady = true
            phase = ConnectionPhase.CONNECTED
            desiredConnected = true
            markSession(true)
            startTelemetry(mode, apps)
            stateListeners.forEach { it(true) }
            log("Connected to ${cleanName(node.name())}.")
            ActionResult(true, if (mode == ConnectionMode.BROWSER_PROXY)
                "Local proxy ready: SOCKS5 127.0.0.1:${AppProxyLauncher.SOCKS_PORT} • HTTP 127.0.0.1:${AppProxyLauncher.HTTP_PORT}"
            else "Connected.")
        } catch (e: Throwable) {
            if (mode == ConnectionMode.NATIVE_PER_APP && !killSwitch()) runCatching { proxiFyre.stopAndRestore() }
            if (mode == ConnectionMode.SYSTEM_PROXY) runCatching { systemProxy.restore() }
            xray.stop()
            activeMode = null
            connectionReady = false
            phase = ConnectionPhase.DISCONNECTED
            stopTelemetry()
            stateListeners.forEach { it(false) }
            ActionResult(false, message(e))
        }
    }

    @Synchronized
    fun switchNode(key: String): ActionResult {
        val target = nodes().firstOrNull { it.key() == key }
            ?: return ActionResult(false, "Server was not found.")
        if (!isConnected()) {
            selectNode(key)
            return ActionResult(true, "Server selected.")
        }
        if (state.selectedNodeKey() == key) return ActionResult(true, "This server is already active.")
        if (phase != ConnectionPhase.CONNECTED) return ActionResult(false, "Wait for the current connection operation to finish.")

        val previous = selectedNode() ?: return ActionResult(false, "The active server was not found.")
        val mode = activeMode ?: state.connectionMode()
        val apps = applications()
        phase = ConnectionPhase.CONNECTING
        connectionReady = false
        state.setSelectedNodeKey(key)
        save()
        return try {
            val tun = mode == ConnectionMode.TUN_COMPATIBILITY
            val config = if (tun) configGenerator.generate(target, apps, state.routingMode())
                else configGenerator.generateApplicationProxy(target,
                    if (mode == ConnectionMode.NATIVE_PER_APP) apps else emptyList(), rules())
            xray.stop()
            xray.start(config, tun)
            Thread.sleep(250)
            if (!xray.isRunning) error("Xray stopped while switching servers.")
            activeMode = mode
            connectionReady = true
            phase = ConnectionPhase.CONNECTED
            stateListeners.forEach { it(true) }
            log("Switched to ${cleanName(target.name())}.")
            Thread.ofVirtual().name("perroute-switch-health-check").start {
                val health = healthCheck.check()
                if (health.success()) log("Tunnel health: ${health.detail()}")
                else log("Tunnel health was inconclusive: ${health.detail()}")
            }
            ActionResult(true, "Switched to ${cleanName(target.name())}.")
        } catch (switchError: Throwable) {
            state.setSelectedNodeKey(previous.key())
            save()
            val restored = runCatching {
                val tun = mode == ConnectionMode.TUN_COMPATIBILITY
                val previousConfig = if (tun) configGenerator.generate(previous, apps, state.routingMode())
                    else configGenerator.generateApplicationProxy(previous,
                        if (mode == ConnectionMode.NATIVE_PER_APP) apps else emptyList(), rules())
                xray.stop()
                xray.start(previousConfig, tun)
                Thread.sleep(250)
                check(xray.isRunning) { "Previous server could not be restored." }
            }.isSuccess
            if (restored) {
                activeMode = mode
                connectionReady = true
                phase = ConnectionPhase.CONNECTED
                stateListeners.forEach { it(true) }
                ActionResult(false, "Could not switch servers; the previous connection was restored: ${message(switchError)}")
            } else {
                if (mode == ConnectionMode.NATIVE_PER_APP && !killSwitch()) runCatching { proxiFyre.stopAndRestore() }
                if (mode == ConnectionMode.SYSTEM_PROXY) runCatching { systemProxy.restore() }
                xray.stop()
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
        if (phase == ConnectionPhase.DISCONNECTED && !xray.isRunning) {
            return ActionResult(true, "Already disconnected.")
        }
        phase = ConnectionPhase.DISCONNECTING
        val disconnectMode = activeMode ?: state.connectionMode()
        val cleanupError = runCatching {
            if (disconnectMode == ConnectionMode.NATIVE_PER_APP) {
                if (!killSwitch()) proxiFyre.stopAndRestore()
            } else proxiFyre.stopManagedSidecar()
            systemProxy.restore()
        }.exceptionOrNull()
        xray.stop()
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
                    val counters = statsReader.readInboundCounters()
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
                                log("The active server stopped responding. Looking for the fastest fallback.")
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
                            if (desiredConnected && isConnected() && !healthCheck.check().success()) {
                                log("Network changed and the tunnel no longer responds; reconnecting.")
                                xray.stop()
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
                if (!killSwitch()) proxiFyre.stopAndRestore()
            } else proxiFyre.stopManagedSidecar()
            systemProxy.restore()
        }
        activeMode = null
        connectionReady = false
        stopTelemetry()
        xray.stop()
        phase = ConnectionPhase.DISCONNECTED
        history.close()
        saveExecutor.shutdown()
        runCatching { saveExecutor.awaitTermination(20, TimeUnit.SECONDS) }
        saveNow()
    }

    companion object {
        const val MANUAL_SOURCE = "__manual__"
        const val IMPORTED_SOURCE = "__imported__"
        const val SCENARIO_RULE_PREFIX = "Scenario · "
        private fun scenarioPrefix(scenario: Scenario) = "$SCENARIO_RULE_PREFIX${scenario.title} · "

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
                highlights = listOf("Fastest server on connect", "Auto reconnect and failover", "Game downloads stay direct", "Kill switch off"),
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
                highlights = listOf("Keeps your chosen server", "Kill switch on", "Auto reconnect and failover", "Streaming sites always proxied"),
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
