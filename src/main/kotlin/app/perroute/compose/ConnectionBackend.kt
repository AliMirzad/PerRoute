package app.perroute.compose

import app.perroute.service.AdminCheck
import app.perroute.service.AppDirectories
import app.perroute.service.CoreInstaller
import app.perroute.service.InstalledApplicationDiscovery
import app.perroute.service.ProcessDiscovery
import app.perroute.service.ProxiFyreManager
import app.perroute.service.ProxyHealthCheck
import app.perroute.service.TunnelHttp
import app.perroute.service.WindowsSystemProxyManager
import app.perroute.service.XrayProcessManager
import app.perroute.service.XrayStatsReader
import app.perroute.service.XrayConfigGenerator
import app.perroute.gateway.AppGateway
import app.perroute.gateway.GatewayConnection
import app.perroute.localnet.LocalNetwork
import app.perroute.localnet.LocalNetworkScanner
import app.perroute.service.IsolatedCore
import app.perroute.service.WindowsNetworkState
import java.nio.file.Path
import java.util.function.Consumer

/**
 * Everything the controller does to the machine while connecting: the Xray core, the per-app engine and
 * the Windows proxy settings. Tests replace it, so connection logic can run without touching any of them.
 */
interface ConnectionBackend {
    fun coreReady(): Boolean
    fun perAppReady(): Boolean
    fun elevated(): Boolean

    /** Throws when Xray rejects the config. Must not affect a running core. */
    fun validate(config: String, tun: Boolean)
    fun start(config: String, tun: Boolean)
    fun stop()
    fun isRunning(): Boolean
    fun stopOrphan()
    fun addLogListener(listener: Consumer<String>)
    fun addRunningListener(listener: Consumer<Boolean>)

    fun applyPerApp(applications: List<String>)
    fun restorePerApp()
    fun stopPerAppSidecar()
    fun applySystemProxy()
    fun restoreSystemProxy()

    fun checkTunnel(socksPort: Int): ProxyHealthCheck.Result
    fun readInboundCounters(): Map<String, Long>

    /** The public address seen through one loopback SOCKS port, or through the direct route when it is null. */
    fun lookupPublicAddress(socksPort: Int?): RouteReport
    fun systemDnsServers(): List<String>
    fun publicResolver(): String?

    fun installedApplications(): List<InstalledApplicationDiscovery.InstalledApplication>
    fun runningApplications(): List<ProcessDiscovery.RunningApplication>

    /**
     * Moves one route of the running core to another outbound without a restart (see
     * [XrayProcessManager.applyRuntimeChange]). Throws, leaving the core unchanged, when it cannot.
     */
    fun applyRuntimeChange(change: XrayConfigGenerator.RuntimeChange, newTag: String, oldTag: String, config: String) {
        throw UnsupportedOperationException("This backend cannot change a running core")
    }

    /** Opens the traffic gateway's listeners. All of them, or none. */
    fun startGateway(routes: List<AppGateway.Route>) {}
    fun stopGateway() {}
    fun setGatewayLimits(applicationPath: String, downloadBytesPerSecond: Long, uploadBytesPerSecond: Long) {}
    fun gatewayCounters(): Map<String, AppGateway.Counters> = emptyMap()
    fun gatewayRunning(): Boolean = false
    fun addGatewayListener(listener: Consumer<GatewayConnection>) {}

    // ---- Version 3.5. Every default leaves the machine alone, so test backends need not implement them.

    /**
     * Starts the per-app engine. With [bypassLan] off, local-network traffic of the selected apps also reaches
     * the core, which is how local devices are blocked on networks that are not trusted.
     */
    fun applyPerApp(applications: List<String>, bypassLan: Boolean) = applyPerApp(applications)

    /**
     * Changes the layout of the running core without a restart (see [XrayProcessManager.applyRuntimePlan]).
     * Throws, leaving the core unchanged, when it cannot.
     */
    fun applyRuntimePlan(plan: XrayConfigGenerator.RuntimePlan) {
        throw UnsupportedOperationException("This backend cannot change a running core")
    }

    /** The readings of a network snapshot (see [app.perroute.netstate.NetworkStateKeys]); empty when unknown. */
    fun networkState(): Map<String, String> = emptyMap()

    /** Removes a PerRoute proxy whose saved copy is gone, putting [baseline] values back when given. */
    fun resetSystemProxyLeftover(baseline: Map<String, String>?) {}

    /** Removes PerRoute's start-up recovery entry when no restore is pending any more. */
    fun removeRecoveryEntry() {}

    /** Whether the Windows proxy is PerRoute's right now. */
    fun perRouteProxyActive(): Boolean = false

    /** Starts an [IsolatedTunnel] for [config] whose SOCKS port is [socksPort]. */
    fun startIsolatedCore(config: String, socksPort: Int): IsolatedTunnel =
        throw UnsupportedOperationException("This backend cannot start a core of its own")

    /** Starts a program, such as a workspace browser, and returns it. */
    fun startProgram(command: List<String>): Process = throw UnsupportedOperationException("This backend cannot start programs")

    /** Whether a browser profile folder is open in a running browser right now. */
    fun profileInUse(profile: Path): Boolean = false

    /** Opens [file] through the Windows shell; with [elevated] Windows asks for Administrator approval first. */
    fun shellOpen(file: Path, arguments: List<String>, elevated: Boolean) {
        throw UnsupportedOperationException("This backend cannot open programs")
    }

    /** The local networks this computer is connected to and the devices seen on them. */
    fun localNetworks(resolveNames: Boolean): List<LocalNetwork> = emptyList()

    /** Whether Windows resolves [host] through its usual DNS servers. */
    fun systemResolves(host: String): Boolean = true

    /** The time a public web server reports, read over the direct route, or null when it could not be read. */
    fun directServerTime(): Long? = null
}

/** A core of its own with one loopback SOCKS port, used by tests, comparisons and browser workspaces. */
interface IsolatedTunnel : AutoCloseable {
    fun socksPort(): Int
    fun alive(): Boolean
    fun lastLines(): List<String>
    override fun close()
}

/** The real machine: Xray, the per-app driver, Windows proxy settings and the network. */
class SystemConnectionBackend : ConnectionBackend {
    private val coreInstaller by lazy { CoreInstaller() }
    private val xray by lazy { XrayProcessManager() }
    private val proxiFyre by lazy { ProxiFyreManager() }
    private val systemProxy by lazy { WindowsSystemProxyManager(AppDirectories.dataDirectory()) }
    private val healthCheck by lazy { ProxyHealthCheck() }
    private val statsReader by lazy { XrayStatsReader() }
    private val installedDiscovery by lazy { InstalledApplicationDiscovery() }
    private val processDiscovery by lazy { ProcessDiscovery() }

    override fun coreReady(): Boolean = runCatching { coreInstaller.inspect().ready() }.getOrDefault(false)
    override fun perAppReady(): Boolean = runCatching { proxiFyre.inspect().ready() }.getOrDefault(false)
    override fun elevated(): Boolean = runCatching { AdminCheck.isElevated() }.getOrDefault(false)

    override fun validate(config: String, tun: Boolean) = xray.validate(config, tun)
    override fun start(config: String, tun: Boolean) = xray.start(config, tun)
    override fun stop() = xray.stop()
    override fun isRunning(): Boolean = xray.isRunning
    override fun stopOrphan() = xray.stopOrphan()
    override fun addLogListener(listener: Consumer<String>) { xray.addLogListener(listener); logListeners += listener }
    override fun addRunningListener(listener: Consumer<Boolean>) = xray.addStateListener(listener)

    override fun applyPerApp(applications: List<String>) = proxiFyre.applyAndStart(applications)
    override fun restorePerApp() = proxiFyre.stopAndRestore()
    override fun stopPerAppSidecar() = proxiFyre.stopManagedSidecar()
    override fun applySystemProxy() = systemProxy.apply()
    override fun restoreSystemProxy() = systemProxy.restore()

    override fun checkTunnel(socksPort: Int): ProxyHealthCheck.Result = healthCheck.check("127.0.0.1", socksPort)
    override fun readInboundCounters(): Map<String, Long> = statsReader.readInboundCounters()

    override fun lookupPublicAddress(socksPort: Int?): RouteReport {
        val route = if (socksPort == null) TunnelHttp.Route.direct() else TunnelHttp.Route.socks(socksPort)
        val trace = runCatching { TunnelHttp.getText(route, "www.cloudflare.com", "/cdn-cgi/trace", 6_000) }
        trace.getOrNull()?.let { body ->
            val fields = body.lines().mapNotNull { line ->
                line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() }
            }.toMap()
            fields["ip"]?.takeIf { it.isNotBlank() }?.let { return RouteReport(ip = it, country = fields["loc"]) }
        }
        val plain = runCatching { TunnelHttp.getText(route, "api.ipify.org", "/", 6_000).trim() }
        plain.getOrNull()?.takeIf { it.isNotBlank() && it.length <= 45 }?.let { return RouteReport(ip = it) }
        val error = plain.exceptionOrNull() ?: trace.exceptionOrNull()
        return RouteReport(error = error?.message?.takeIf { it.isNotBlank() } ?: error?.javaClass?.simpleName ?: "No answer")
    }

    override fun systemDnsServers(): List<String> = runCatching {
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

    override fun publicResolver(): String? =
        runCatching { java.net.InetAddress.getByName("whoami.akamai.net").hostAddress }.getOrNull()

    override fun installedApplications(): List<InstalledApplicationDiscovery.InstalledApplication> = installedDiscovery.list()
    override fun runningApplications(): List<ProcessDiscovery.RunningApplication> = processDiscovery.list()

    private val gatewayListeners = java.util.concurrent.CopyOnWriteArrayList<Consumer<GatewayConnection>>()
    private val gateway by lazy {
        AppGateway({ connection -> gatewayListeners.forEach { it.accept(connection) } },
            { line -> xrayLog(line) })
    }
    private fun xrayLog(line: String) = logListeners.forEach { it.accept(line) }
    private val logListeners = java.util.concurrent.CopyOnWriteArrayList<Consumer<String>>()

    override fun applyRuntimeChange(change: XrayConfigGenerator.RuntimeChange, newTag: String, oldTag: String, config: String) =
        xray.applyRuntimeChange(change.outbounds(), change.routing(), newTag, oldTag, config)

    override fun applyPerApp(applications: List<String>, bypassLan: Boolean) = proxiFyre.applyAndStart(applications, bypassLan)

    override fun applyRuntimePlan(plan: XrayConfigGenerator.RuntimePlan) = xray.applyRuntimePlan(plan)

    private val networkReader by lazy { WindowsNetworkState(systemProxy, proxiFyre, xray, AppDirectories.dataDirectory()) }
    override fun networkState(): Map<String, String> = networkReader.read(systemDnsServers())
    override fun resetSystemProxyLeftover(baseline: Map<String, String>?) = systemProxy.resetLeftover(baseline)
    override fun removeRecoveryEntry() = systemProxy.removeStaleRecoveryEntry()
    override fun perRouteProxyActive(): Boolean = runCatching { systemProxy.perRouteProxyActive() }.getOrDefault(false)

    override fun startIsolatedCore(config: String, socksPort: Int): IsolatedTunnel {
        val core = IsolatedCore.start(config, socksPort)
        return object : IsolatedTunnel {
            override fun socksPort(): Int = core.socksPort()
            override fun alive(): Boolean = core.alive()
            override fun lastLines(): List<String> = core.lastLines()
            override fun close() = core.close()
        }
    }

    override fun startProgram(command: List<String>): Process {
        val executable = Path.of(command.first())
        return ProcessBuilder(command).directory(executable.parent?.toFile()).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    }

    /** Chromium keeps a file named lockfile open, exclusively, for as long as a profile is in use. */
    override fun profileInUse(profile: Path): Boolean {
        val lock = profile.resolve("lockfile")
        if (!java.nio.file.Files.exists(lock)) return false
        return runCatching { java.nio.file.Files.delete(lock); false }.getOrDefault(true)
    }

    /**
     * ShellExecute rather than CreateProcess, so a program that asks for Administrator rights gets the usual
     * Windows prompt instead of failing (ProcessBuilder cannot start such a program from a normal process).
     */
    override fun shellOpen(file: Path, arguments: List<String>, elevated: Boolean) {
        val quoted = arguments.joinToString(",") { "'" + it.replace("'", "''") + "'" }
        val command = buildString {
            append("Start-Process -FilePath '").append(file.toString().replace("'", "''")).append("'")
            if (arguments.isNotEmpty()) append(" -ArgumentList @(").append(quoted).append(")")
            if (elevated) append(" -Verb RunAs")
        }
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command)
            .redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); error("Windows did not start ${file.fileName} in time.") }
        if (process.exitValue() != 0) {
            val cancelled = output.contains("canceled by the user", ignoreCase = true) || output.contains("cancelled by the user", ignoreCase = true)
            error(if (cancelled) "The Administrator prompt was cancelled." else "Windows could not start ${file.fileName}: ${output.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()}")
        }
    }

    private val scanner by lazy { LocalNetworkScanner() }
    override fun localNetworks(resolveNames: Boolean): List<LocalNetwork> =
        scanner.scan(WindowsNetworkState.defaultGateways(), systemDnsServers(), resolveNames)

    override fun systemResolves(host: String): Boolean = runCatching { java.net.InetAddress.getByName(host); true }.getOrDefault(false)

    override fun directServerTime(): Long? = runCatching {
        val client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).proxy(java.net.ProxySelector.of(null)).build()
        val response = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://www.cloudflare.com/cdn-cgi/trace"))
            .timeout(java.time.Duration.ofSeconds(6)).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString())
        response.headers().firstValue("date").map { java.time.ZonedDateTime.parse(it, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.orElse(null)
    }.getOrNull()

    override fun startGateway(routes: List<AppGateway.Route>) = gateway.start(routes)
    override fun stopGateway() = gateway.stop()
    override fun setGatewayLimits(applicationPath: String, downloadBytesPerSecond: Long, uploadBytesPerSecond: Long) =
        gateway.setLimits(applicationPath, downloadBytesPerSecond, uploadBytesPerSecond)
    override fun gatewayCounters(): Map<String, AppGateway.Counters> = gateway.counters()
    override fun gatewayRunning(): Boolean = gateway.running()
    override fun addGatewayListener(listener: Consumer<GatewayConnection>) { gatewayListeners += listener }
}
