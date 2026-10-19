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

internal const val BRIDGE_FIREWALL_RULE = "PerRoute LAN Bridge"

interface ConnectionBackend {
    fun coreReady(): Boolean
    fun perAppReady(): Boolean
    fun elevated(): Boolean

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

    fun lookupPublicAddress(socksPort: Int?): RouteReport
    fun systemDnsServers(): List<String>
    fun publicResolver(): String?

    fun installedApplications(): List<InstalledApplicationDiscovery.InstalledApplication>
    fun runningApplications(): List<ProcessDiscovery.RunningApplication>

    fun applyRuntimeChange(change: XrayConfigGenerator.RuntimeChange, newTag: String, oldTag: String, config: String) {
        throw UnsupportedOperationException("This backend cannot change a running core")
    }

    fun startGateway(routes: List<AppGateway.Route>) {}
    fun stopGateway() {}
    fun setGatewayLimits(applicationPath: String, downloadBytesPerSecond: Long, uploadBytesPerSecond: Long) {}
    fun gatewayCounters(): Map<String, AppGateway.Counters> = emptyMap()
    fun gatewayRunning(): Boolean = false
    fun addGatewayListener(listener: Consumer<GatewayConnection>) {}

    fun applyPerApp(applications: List<String>, bypassLan: Boolean) = applyPerApp(applications)

    fun applyRuntimePlan(plan: XrayConfigGenerator.RuntimePlan) {
        throw UnsupportedOperationException("This backend cannot change a running core")
    }

    fun networkState(): Map<String, String> = emptyMap()

    fun resetSystemProxyLeftover(baseline: Map<String, String>?) {}

    fun removeRecoveryEntry() {}

    fun perRouteProxyActive(): Boolean = false

    fun startIsolatedCore(config: String, socksPort: Int): IsolatedTunnel =
        throw UnsupportedOperationException("This backend cannot start a core of its own")

    fun startIsolatedCore(config: String, socksPort: Int, onLine: (String) -> Unit): IsolatedTunnel = startIsolatedCore(config, socksPort)

    fun resourceConditions(): app.perroute.service.ResourceConditions.Snapshot = app.perroute.service.ResourceConditions.Snapshot.NORMAL

    fun resolveShortcut(shortcut: Path): Path? = null

    fun explorerMenuRegistered(executable: Path): Boolean = false

    fun setExplorerMenu(executable: Path?, enabled: Boolean) {}

    fun networkCategory(address: String): String = "Private"

    fun bridgeFirewallReady(core: Path, firstPort: Int, lastPort: Int): Boolean = true

    fun allowBridgeFirewall(core: Path, firstPort: Int, lastPort: Int) {}

    fun startProgram(command: List<String>): Process = throw UnsupportedOperationException("This backend cannot start programs")

    fun profileInUse(profile: Path): Boolean = false

    fun shellOpen(file: Path, arguments: List<String>, elevated: Boolean) {
        throw UnsupportedOperationException("This backend cannot open programs")
    }

    fun localNetworks(resolveNames: Boolean): List<LocalNetwork> = emptyList()

    fun systemResolves(host: String): Boolean = true

    fun directServerTime(): Long? = null
}

interface IsolatedTunnel : AutoCloseable {
    fun socksPort(): Int
    fun alive(): Boolean
    fun lastLines(): List<String>
    override fun close()
}

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

    override fun startIsolatedCore(config: String, socksPort: Int, onLine: (String) -> Unit): IsolatedTunnel {
        val core = IsolatedCore.start(config, socksPort) { line -> onLine(line) }
        return object : IsolatedTunnel {
            override fun socksPort(): Int = core.socksPort()
            override fun alive(): Boolean = core.alive()
            override fun lastLines(): List<String> = core.lastLines()
            override fun close() = core.close()
        }
    }

    override fun resourceConditions() = app.perroute.service.ResourceConditions.read()

    override fun resolveShortcut(shortcut: Path): Path? {
        val script = "(New-Object -ComObject WScript.Shell).CreateShortcut('" + shortcut.toString().replace("'", "''") + "').TargetPath"
        val output = powerShell(script) ?: return null
        return output.lines().lastOrNull { it.isNotBlank() }?.trim()?.takeIf { it.isNotBlank() }?.let { runCatching { Path.of(it) }.getOrNull() }
    }

    override fun explorerMenuRegistered(executable: Path): Boolean = app.perroute.service.ExplorerMenu.registeredFor(executable)

    override fun setExplorerMenu(executable: Path?, enabled: Boolean) {
        if (enabled && executable != null) app.perroute.service.ExplorerMenu.register(executable) else app.perroute.service.ExplorerMenu.unregister()
    }

    override fun networkCategory(address: String): String {
        val script = "\$i=(Get-NetIPAddress -IPAddress '" + address.replace("'", "") + "' -ErrorAction Stop | Select-Object -First 1).InterfaceIndex;" +
            "(Get-NetConnectionProfile -InterfaceIndex \$i -ErrorAction Stop).NetworkCategory.ToString()"
        return powerShell(script)?.lines()?.lastOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    override fun bridgeFirewallReady(core: Path, firstPort: Int, lastPort: Int): Boolean {
        val process = ProcessBuilder("netsh.exe", "advfirewall", "firewall", "show", "rule", "name=$BRIDGE_FIREWALL_RULE", "verbose")
            .redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); return false }
        if (process.exitValue() != 0) return false
        val ports = "$firstPort-$lastPort"
        val text = output.lowercase()
        return Regex(Regex.escape(ports)).findAll(output).count() >= 2 && Regex(Regex.escape(core.toString().lowercase())).findAll(text).count() >= 2 &&
            Regex("\\btcp\\b").containsMatchIn(text) && Regex("\\budp\\b").containsMatchIn(text)
    }

    override fun allowBridgeFirewall(core: Path, firstPort: Int, lastPort: Int) {
        val program = core.toString().replace("'", "''")
        val rule = { protocol: String ->
            "netsh advfirewall firewall add rule name=\"$BRIDGE_FIREWALL_RULE\" dir=in action=allow program=\"\$core\" protocol=$protocol localport=$firstPort-$lastPort profile=private,domain | Out-Null; if (\$LASTEXITCODE -ne 0) { exit 2 }"
        }
        val inner = "\$core='$program'; netsh advfirewall firewall delete rule name=\"$BRIDGE_FIREWALL_RULE\" | Out-Null; ${rule("TCP")}; ${rule("UDP")}; exit 0"
        val encoded = java.util.Base64.getEncoder().encodeToString(inner.toByteArray(Charsets.UTF_16LE))
        val outer = "\$p = Start-Process -FilePath powershell.exe -ArgumentList '-NoProfile','-NonInteractive','-EncodedCommand','$encoded' " +
            "-Verb RunAs -Wait -PassThru -WindowStyle Hidden; exit \$p.ExitCode"
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", outer).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        if (!process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); error("Windows did not answer in time.") }
        if (process.exitValue() != 0) {
            val cancelled = output.contains("canceled by the user", ignoreCase = true) || output.contains("cancelled by the user", ignoreCase = true)
            error(if (cancelled) "The Administrator prompt was cancelled." else "Windows Firewall did not accept the rule.")
        }
    }

    private fun powerShell(script: String): String? = runCatching {
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); return@runCatching null }
        if (process.exitValue() != 0) null else output
    }.getOrNull()

    override fun startProgram(command: List<String>): Process {
        val executable = Path.of(command.first())
        return ProcessBuilder(command).directory(executable.parent?.toFile()).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    }

    override fun profileInUse(profile: Path): Boolean {
        val lock = profile.resolve("lockfile")
        if (!java.nio.file.Files.exists(lock)) return false
        return runCatching { java.nio.file.Files.delete(lock); false }.getOrDefault(true)
    }

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
