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
    override fun addLogListener(listener: Consumer<String>) = xray.addLogListener(listener)
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
}
