package app.perroute

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import app.perroute.compose.ActivePalette
import app.perroute.compose.AddServersDialog
import app.perroute.compose.ConnectionBackend
import app.perroute.compose.DarkPalette
import app.perroute.compose.DiagnosticsTab
import app.perroute.compose.ImportMethod
import app.perroute.compose.KeyboardNavigation
import app.perroute.compose.LightPalette
import app.perroute.compose.PerRouteController
import app.perroute.compose.PerRouteTheme
import app.perroute.compose.PerRouteWindow
import app.perroute.compose.QuickAccessWindow
import app.perroute.compose.RouteReport
import app.perroute.compose.SettingsCategory
import app.perroute.compose.Shell
import app.perroute.compose.Tab
import app.perroute.model.AppRoutes
import app.perroute.model.AppState
import app.perroute.model.ConnectionMode
import app.perroute.model.ConnectionProfile
import app.perroute.model.Protocol
import app.perroute.model.ProxyNode
import app.perroute.model.RouteRule
import app.perroute.parser.SubscriptionParser
import app.perroute.persistence.AppStateStore
import app.perroute.service.AppProxyLauncher
import app.perroute.service.InstalledApplicationDiscovery
import app.perroute.service.ProcessDiscovery
import app.perroute.service.ProxyHealthCheck
import app.perroute.service.XrayConfigGenerator
import kotlinx.coroutines.CoroutineDispatcher
import org.jetbrains.skia.EncodedImageFormat
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.function.Consumer
import java.util.prefs.AbstractPreferences
import kotlin.coroutines.CoroutineContext

/**
 * Renders the real screens off-screen with demonstration data and a fake machine, so layouts can be
 * reviewed at the baseline size, a narrow size, 150% scaling and in both themes. Nothing here touches
 * Xray, the per-app driver, Windows proxy settings, the network or the user's saved preferences.
 */
private const val SUBSCRIPTION = "https://subscription.example/api/v1/client/subscribe?token=demo"
private const val CHROME = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"
private const val TELEGRAM = "C:\\Users\\demo\\AppData\\Roaming\\Telegram Desktop\\Telegram.exe"
private const val DISCORD = "C:\\Users\\demo\\AppData\\Local\\Discord\\app-1.0.9\\Discord.exe"
private const val SPOTIFY = "C:\\Users\\demo\\AppData\\Roaming\\Spotify\\Spotify.exe"

private class QueueDispatcher : CoroutineDispatcher() {
    private val queue = ConcurrentLinkedQueue<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
    fun drain() { while (true) (queue.poll() ?: return).run() }
}

private class MemoryPreferences : AbstractPreferences(null, "") {
    private val values = LinkedHashMap<String, String>()
    override fun putSpi(key: String, value: String) { values[key] = value }
    override fun getSpi(key: String): String? = values[key]
    override fun removeSpi(key: String) { values.remove(key) }
    override fun removeNodeSpi() {}
    override fun keysSpi(): Array<String> = values.keys.toTypedArray()
    override fun childrenNamesSpi(): Array<String> = emptyArray()
    override fun childSpi(name: String): AbstractPreferences = MemoryPreferences()
    override fun syncSpi() {}
    override fun flushSpi() {}
}

private class DemoBackend(private val perAppDriver: Boolean = true) : ConnectionBackend {
    @Volatile private var running = false
    @Volatile private var startedAt = 0L
    override fun coreReady() = true
    override fun perAppReady() = perAppDriver
    override fun elevated() = false
    override fun validate(config: String, tun: Boolean) {}
    override fun start(config: String, tun: Boolean) { running = true; startedAt = System.currentTimeMillis() }
    override fun stop() { running = false }
    override fun isRunning() = running
    override fun stopOrphan() {}
    override fun addLogListener(listener: Consumer<String>) {}
    override fun addRunningListener(listener: Consumer<Boolean>) {}
    override fun applyPerApp(applications: List<String>) {}
    override fun restorePerApp() {}
    override fun stopPerAppSidecar() {}
    override fun applySystemProxy() {}
    override fun restoreSystemProxy() {}
    override fun checkTunnel(socksPort: Int): ProxyHealthCheck.Result =
        if (socksPort == XrayConfigGenerator.routeCheckPort(1)) ProxyHealthCheck.Result(false, "The proxy could not reach the test endpoint (SOCKS 4).")
        else ProxyHealthCheck.Result(true, "Tunnel answered from cp.cloudflare.com (HTTP 204)")
    override fun readInboundCounters(): Map<String, Long> {
        val seconds = (System.currentTimeMillis() - startedAt) / 250.0
        val counters = LinkedHashMap<String, Long>()
        listOf(4_600_000L to 420_000L, 900_000L to 150_000L, 0L to 0L, 300_000L to 27_000L).forEachIndexed { index, (down, up) ->
            counters["inbound>>>${XrayConfigGenerator.perAppTag(index)}>>>traffic>>>downlink"] = (down * seconds / 4).toLong()
            counters["inbound>>>${XrayConfigGenerator.perAppTag(index)}>>>traffic>>>uplink"] = (up * seconds / 4).toLong()
        }
        return counters
    }
    override fun lookupPublicAddress(socksPort: Int?): RouteReport = when (socksPort) {
        null -> RouteReport("198.51.100.24", "IR")
        AppProxyLauncher.SOCKS_PORT -> RouteReport("203.0.113.10", "DE")
        XrayConfigGenerator.routeCheckPort(0) -> RouteReport("203.0.113.20", "NL")
        else -> RouteReport(error = "The proxy could not reach www.cloudflare.com (SOCKS 4).")
    }
    override fun systemDnsServers() = listOf("192.0.2.53", "192.0.2.54")
    override fun publicResolver(): String? = "192.0.2.80"
    override fun installedApplications() = listOf(
        InstalledApplicationDiscovery.InstalledApplication("Google Chrome", CHROME),
        InstalledApplicationDiscovery.InstalledApplication("Telegram", TELEGRAM),
        InstalledApplicationDiscovery.InstalledApplication("Discord", DISCORD),
        InstalledApplicationDiscovery.InstalledApplication("Spotify", SPOTIFY),
        InstalledApplicationDiscovery.InstalledApplication("Visual Studio Code", "C:\\Program Files\\Microsoft VS Code\\Code.exe"),
        InstalledApplicationDiscovery.InstalledApplication("Mozilla Firefox", "C:\\Program Files\\Mozilla Firefox\\firefox.exe"),
        InstalledApplicationDiscovery.InstalledApplication("Steam", "C:\\Program Files (x86)\\Steam\\steam.exe"),
        InstalledApplicationDiscovery.InstalledApplication("A media player with a remarkably long product name, Professional Edition",
            "C:\\Program Files\\Example Software Company\\Remarkably Long Product Folder\\Professional Edition\\bin\\x64\\player.exe")
    )
    override fun runningApplications() = listOf(
        ProcessDiscovery.RunningApplication("chrome.exe", CHROME), ProcessDiscovery.RunningApplication("Telegram.exe", TELEGRAM))
}

private class Demo(
    val advanced: Boolean = false,
    val connected: Boolean = false,
    val missing: Boolean = false,
    val empty: Boolean = false,
    val mode: ConnectionMode = ConnectionMode.NATIVE_PER_APP,
    val dark: Boolean = true
)

private val listeners = List(3) { ServerSocket(0, 16, InetAddress.getLoopbackAddress()) }.onEach { server ->
    Thread.ofPlatform().daemon().start { while (true) runCatching { server.accept().close() } }
}
private val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

private fun demoState(demo: Demo): AppState {
    val state = AppState()
    state.setConnectionMode(demo.mode)
    if (demo.empty) return state
    val main = ProxyNode("de-1", "\uD83C\uDDE9\uD83C\uDDEA Germany DE-01", Protocol.VLESS, "127.0.0.1", listeners[0].localPort, "id-de-1", mapOf("security" to "tls"), "vless://id-de-1@de1.example.net:443#Germany%20DE-01")
    val second = ProxyNode("de-2", "Germany DE-02", Protocol.VLESS, "127.0.0.1", listeners[1].localPort, "id-de-2", mapOf("security" to "tls"), "")
    val third = ProxyNode("nl-1", "Netherlands NL-01", Protocol.VLESS, "127.0.0.1", listeners[2].localPort, "id-nl-1", mapOf("security" to "tls"), "")
    val london = ProxyNode("uk-1", "London UK-01", Protocol.VMESS, "127.0.0.1", closedPort, "id-uk-1", emptyMap(), "")
    val long = ProxyNode("us-3", "US | New York premium gateway with an exceptionally long descriptive name 03", Protocol.TROJAN, "127.0.0.1", listeners[0].localPort, "id-us-3", emptyMap(), "")
    val profileJson = """{"remarks":"Balanced profile","outbounds":[{"tag":"proxy-a","protocol":"vless","settings":{"vnext":[{"address":"127.0.0.1","port":${listeners[1].localPort},"users":[{"id":"33333333-3333-3333-3333-333333333333","encryption":"none"}]}]}},{"tag":"direct","protocol":"freedom","settings":{}}]}"""
    val profile = SubscriptionParser().parse(profileJson).nodes()[0].let { ProxyNode("xp-1", it.name(), it.protocol(), it.address(), it.port(), it.credential(), it.options(), it.sourceLink()) }
    state.nodes().addAll(listOf(main, second, third, london, long, profile))
    listOf(main, second, third, long, profile).forEach { state.nodeSources()[it.key()] = SUBSCRIPTION }
    state.nodeSources()[london.key()] = PerRouteController.MANUAL_SOURCE
    state.subscriptionUrls().add(SUBSCRIPTION)
    state.favoriteNodeKeys().addAll(listOf("de-1", "nl-1"))
    state.nodeTags()["de-2"] = "Work"
    state.setSelectedNodeKey("de-1")
    state.appPaths().addAll(listOf(CHROME, TELEGRAM, DISCORD, SPOTIFY))
    state.setAdvancedRouting(demo.advanced)
    state.appRoutes()[AppRoutes.normalize(CHROME)] = "nl-1"
    state.appRoutes()[AppRoutes.normalize(TELEGRAM)] = if (demo.missing) "jp-4" else "de-2"
    state.appRouteLabels().putAll(mapOf("nl-1" to "Netherlands NL-01", "de-2" to "Germany DE-02", "jp-4" to "Japan JP-04"))
    val rules = listOf(
        RouteRule("r1", "Local banking", "", RouteRule.MatchType.DOMAIN, "bank.example.ir", RouteRule.Action.DIRECT, true),
        RouteRule("r2", "Work tools", "", RouteRule.MatchType.DOMAIN, "github.com, slack.com", RouteRule.Action.PROXY, true),
        RouteRule("r3", "Block trackers", CHROME, RouteRule.MatchType.DOMAIN, "keyword:tracker", RouteRule.Action.BLOCK, true),
        RouteRule("r4", "Office network", "", RouteRule.MatchType.IP, "203.0.113.0/24", RouteRule.Action.DIRECT, false)
    )
    state.routeRules().addAll(rules)
    state.profiles().add(ConnectionProfile("p1", "Daily browsing", state.appPaths(), "de-1", ConnectionMode.NATIVE_PER_APP, rules, false, emptyMap()))
    state.profiles().add(ConnectionProfile("p2", "Office", state.appPaths(), "de-1", ConnectionMode.NATIVE_PER_APP, rules, true, HashMap(state.appRoutes())))
    state.profiles().add(ConnectionProfile("p3", "Travel", emptyList(), "nl-1", ConnectionMode.SYSTEM_PROXY, emptyList(), false, emptyMap()))
    state.setActiveProfileId(if (demo.advanced) "p2" else "p1")
    return state
}

private fun seedHistory(directory: Path) {
    val today = LocalDate.now()
    fun day(date: LocalDate, scale: Long) = """"$date":{"${CHROME.replace("\\", "\\\\")}":{"down":${3_500_000_00L * scale / 10},"up":${26_900_000L * scale / 10}},""" +
        """"${TELEGRAM.replace("\\", "\\\\")}":{"down":${1_400_000_00L * scale / 10},"up":${12_200_000L * scale / 10}},""" +
        """"${DISCORD.replace("\\", "\\\\")}":{"down":${81_300_000L * scale / 10},"up":${7_300_000L * scale / 10}}}"""
    val days = (0L until minOf(6L, today.dayOfMonth.toLong())).joinToString(",") { day(today.minusDays(it), 10 - it) }
    Files.createDirectories(directory)
    Files.writeString(directory.resolve("traffic-history.json"), "{\"days\":{$days}}")
}

private class Scene(
    val name: String,
    val demo: Demo = Demo(),
    val width: Int = 940,
    val height: Int = 710,
    val scale: Float = 1f,
    val tab: Tab = Tab.Connection,
    val runChecks: Boolean = false,
    val settleMillis: Long = 1_600,
    val clicks: List<Pair<Int, Int>> = emptyList(),
    val tabs: Int = 0,
    val prepare: (Shell) -> Unit = {},
    val quick: Boolean = false
)

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
private fun render(scene: Scene, output: Path) {
    val data = Files.createTempDirectory("perroute-ui-")
    System.setProperty("perroute.data", data.toString())
    seedHistory(data)
    val preferences = MemoryPreferences().apply {
        putBoolean("darkTheme", scene.demo.dark); putBoolean("autoSpeedTest", false); putBoolean("autoFailover", false); putBoolean("autoReconnect", false)
    }
    val controller = PerRouteController(demoState(scene.demo), AppStateStore(data.resolve("state.bin")), DemoBackend(), preferences)
    ActivePalette = if (scene.demo.dark) DarkPalette else LightPalette
    if (scene.demo.connected) {
        val result = controller.connect()
        check(result.ok) { "${scene.name}: ${result.message}" }
        Thread.sleep(2_600)
    }
    if (scene.runChecks) controller.checkRoutes()
    val dispatcher = QueueDispatcher()
    val content: @Composable () -> Unit = {
        if (scene.quick) PerRouteTheme(scene.demo.dark) {
            val nodes = controller.nodes()
            QuickAccessWindow(
                connected = controller.isConnected(), busy = false, working = false,
                main = nodes.firstOrNull { it.key() == controller.selectedNodeKey() }, nodes = nodes,
                favorites = nodes.filter { controller.isFavorite(it.key()) }.mapTo(hashSetOf()) { it.key() },
                traffic = controller.trafficSnapshot(), plan = controller.routePlan(), mainCheck = controller.routeChecks()[controller.selectedNodeKey()],
                onToggle = {}, onFastest = {}, onServer = {}, onOpen = {}, onExit = {}
            )
        } else PerRouteWindow(controller, null, exitApp = {}, initialTab = scene.tab, prepare = scene.prepare)
    }
    val pixelWidth = (scene.width * scene.scale).toInt()
    val pixelHeight = (scene.height * scene.scale).toInt()
    val compose = ImageComposeScene(pixelWidth, pixelHeight, Density(scene.scale), dispatcher, content)
    try {
        fun settle(millis: Long) {
            val until = System.currentTimeMillis() + millis
            while (System.currentTimeMillis() < until) { dispatcher.drain(); compose.render(System.nanoTime()); Thread.sleep(40) }
        }
        settle(scene.settleMillis)
        scene.clicks.forEach { (x, y) ->
            val position = Offset(x * scene.scale, y * scene.scale)
            compose.sendPointerEvent(PointerEventType.Move, position)
            compose.sendPointerEvent(PointerEventType.Press, position)
            compose.sendPointerEvent(PointerEventType.Release, position)
            settle(500)
        }
        if (scene.tabs > 0) {
            // Keyboard traversal: the focus outline must be visible and stay inside an open dialog.
            KeyboardNavigation.value = true
            repeat(scene.tabs) {
                compose.sendKeyEvent(KeyEvent(Key.Tab, KeyEventType.KeyDown))
                compose.sendKeyEvent(KeyEvent(Key.Tab, KeyEventType.KeyUp))
                settle(120)
            }
        }
        dispatcher.drain()
        val image = compose.render(System.nanoTime())
        Files.write(output.resolve("${scene.name}.png"), image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        println("[snapshot] ${scene.name}  ${pixelWidth}x$pixelHeight")
    } finally {
        compose.close()
        controller.close()
        System.clearProperty("perroute.data")
        runCatching { Files.walk(data).use { files -> files.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}

fun main(args: Array<String>) {
    val output = Path.of(args.getOrElse(0) { "build/ui-snapshots" }).toAbsolutePath()
    val only = args.drop(1).toSet()
    Files.createDirectories(output)
    val standard = Demo()
    val standardOn = Demo(connected = true)
    val advanced = Demo(advanced = true)
    val advancedOn = Demo(advanced = true, connected = true)
    val pastePreview: (Shell) -> Unit = { shell ->
        val outcome = shell.controller.previewPasted(
            "vless://11111111-1111-1111-1111-111111111111@fr1.example.net:443?security=tls#France FR-01\n" +
                "trojan://secret@sg1.example.net:443?security=tls#Singapore SG-02\nwireguard://not-supported\nvless://broken")
        shell.showDialog { AddServersDialog(shell, ImportMethod.PASTE, outcome) { shell.closeDialog() } }
    }
    val scenes = listOf(
        Scene("01-connection-standard-ready", standard),
        Scene("02-connection-standard-connected", standardOn),
        Scene("03-connection-advanced-connected", advancedOn),
        Scene("04-connection-missing-server", Demo(advanced = true, missing = true)),
        Scene("05-connection-empty", Demo(empty = true)),
        Scene("06-connection-local-proxy", Demo(connected = true, mode = ConnectionMode.BROWSER_PROXY)),
        Scene("10-apps-standard", standard, tab = Tab.Apps, settleMillis = 3_500),
        Scene("11-apps-advanced", advanced, tab = Tab.Apps, settleMillis = 3_500),
        Scene("12-apps-advanced-missing", Demo(advanced = true, missing = true), tab = Tab.Apps, settleMillis = 3_500),
        Scene("13-apps-locked", advancedOn, tab = Tab.Apps, settleMillis = 3_500),
        Scene("14-apps-unsaved-leave", advanced, tab = Tab.Apps, settleMillis = 3_000, prepare = { it.draft.advanced = false; it.navigate(Tab.Servers) }),
        Scene("15-apps-picker-open", advanced, tab = Tab.Apps, settleMillis = 3_500, clicks = listOf(565 to 400)),
        Scene("16-apps-picker-chosen", advanced, tab = Tab.Apps, settleMillis = 3_500, clicks = listOf(565 to 400, 600 to 560)),
        Scene("17-apps-row-toggled", advanced, tab = Tab.Apps, settleMillis = 3_500, clicks = listOf(200 to 400)),
        Scene("25-servers-test-menu", advanced, tab = Tab.Servers, settleMillis = 2_500, clicks = listOf(838 to 237)),
        Scene("26-servers-row-menu", advanced, tab = Tab.Servers, settleMillis = 2_500, clicks = listOf(876 to 422)),
        Scene("27-servers-tag-menu", advanced, tab = Tab.Servers, settleMillis = 2_500, clicks = listOf(839 to 483)),
        Scene("28-servers-tag-dialog", advanced, tab = Tab.Servers, settleMillis = 2_500, clicks = listOf(839 to 483, 760 to 566)),
        Scene("33-rules-editor", advanced, tab = Tab.Rules, clicks = listOf(850 to 183)),
        Scene("34-rules-edit-existing", advanced, tab = Tab.Rules, clicks = listOf(569 to 407, 600 to 430)),
        Scene("42-profiles-preview", advanced, tab = Tab.Profiles, clicks = listOf(190 to 403)),
        Scene("53-diagnostics-tester", advancedOn, tab = Tab.Diagnostics, runChecks = true, clicks = listOf(750 to 308)),
        Scene("20-servers-advanced", advanced, tab = Tab.Servers, settleMillis = 2_500),
        Scene("90-keyboard-focus", advanced, tab = Tab.Rules, tabs = 5),
        Scene("93-keyboard-rules-list", advanced, tab = Tab.Rules, tabs = 17),
        Scene("91-keyboard-dialog-2", advanced, tab = Tab.Profiles, clicks = listOf(190 to 403), tabs = 2),
        Scene("92-keyboard-dialog-9", advanced, tab = Tab.Profiles, clicks = listOf(190 to 403), tabs = 9),
        Scene("21-servers-standard-connected", standardOn, tab = Tab.Servers, settleMillis = 2_500),
        Scene("22-servers-empty", Demo(empty = true), tab = Tab.Servers),
        Scene("23-add-servers-qr", advanced, tab = Tab.Servers, prepare = { shell -> shell.showDialog { AddServersDialog(shell, ImportMethod.QR) { shell.closeDialog() } } }),
        Scene("24-add-servers-review", advanced, tab = Tab.Servers, prepare = pastePreview),
        Scene("30-rules", advanced, tab = Tab.Rules),
        Scene("31-rules-locked", advancedOn, tab = Tab.Rules),
        Scene("32-rules-empty", Demo(empty = true), tab = Tab.Rules),
        Scene("40-profiles", advanced, tab = Tab.Profiles),
        Scene("41-profiles-empty", Demo(empty = true), tab = Tab.Profiles),
        Scene("50-diagnostics-connected", advancedOn, tab = Tab.Diagnostics, runChecks = true),
        Scene("51-diagnostics-disconnected", advanced, tab = Tab.Diagnostics),
        Scene("52-diagnostics-loaded-rules", advancedOn, tab = Tab.Diagnostics, prepare = { it.diagnosticsTab = DiagnosticsTab.Loaded }),
        Scene("60-traffic", advancedOn, tab = Tab.Traffic, settleMillis = 3_000),
        Scene("61-traffic-empty", Demo(empty = true), tab = Tab.Traffic),
        Scene("70-settings-general", standard, tab = Tab.Settings),
        Scene("71-settings-connection", standard, tab = Tab.Settings, prepare = { it.settingsCategory = SettingsCategory.Connection }),
        Scene("72-settings-connection-locked", advancedOn, tab = Tab.Settings, prepare = { it.settingsCategory = SettingsCategory.Connection }),
        Scene("73-settings-automation", standard, tab = Tab.Settings, prepare = { it.settingsCategory = SettingsCategory.Automation }),
        Scene("74-settings-updates", standard, tab = Tab.Settings, prepare = { it.settingsCategory = SettingsCategory.Updates }),
        Scene("75-settings-components", standard, tab = Tab.Settings, prepare = { it.settingsCategory = SettingsCategory.Components }),
        Scene("76-settings-log", standardOn, tab = Tab.Settings, prepare = { it.settingsCategory = SettingsCategory.ActivityLog }),
        Scene("80-quick-standard", standardOn, 390, 600, quick = true),
        Scene("81-quick-advanced", advancedOn, 390, 600, quick = true),
        Scene("82-quick-disconnected-light", Demo(dark = false), 390, 600, quick = true),
        Scene("narrow-connection", advancedOn, 820, 620),
        Scene("narrow-apps", advanced, 820, 620, tab = Tab.Apps, settleMillis = 3_500),
        Scene("narrow-servers", advanced, 820, 620, tab = Tab.Servers, settleMillis = 2_500),
        Scene("narrow-rules", advanced, 820, 620, tab = Tab.Rules),
        Scene("narrow-diagnostics", advancedOn, 820, 620, tab = Tab.Diagnostics, runChecks = true),
        Scene("narrow-settings", standard, 820, 620, tab = Tab.Settings, prepare = { it.settingsCategory = SettingsCategory.Connection }),
        Scene("narrow-traffic", advancedOn, 820, 620, tab = Tab.Traffic, settleMillis = 3_000),
        Scene("scaled150-connection", advancedOn, scale = 1.5f),
        Scene("scaled150-apps", advanced, scale = 1.5f, tab = Tab.Apps, settleMillis = 3_500),
        Scene("scaled150-servers", advanced, scale = 1.5f, tab = Tab.Servers, settleMillis = 2_500),
        Scene("light-connection", Demo(advanced = true, connected = true, dark = false)),
        Scene("light-apps", Demo(advanced = true, dark = false), tab = Tab.Apps, settleMillis = 3_500),
        Scene("light-servers", Demo(advanced = true, dark = false), tab = Tab.Servers, settleMillis = 2_500),
        Scene("light-rules", Demo(advanced = true, dark = false), tab = Tab.Rules),
        Scene("light-diagnostics", Demo(advanced = true, connected = true, dark = false), tab = Tab.Diagnostics, runChecks = true),
        Scene("light-settings", Demo(dark = false), tab = Tab.Settings, prepare = { it.settingsCategory = SettingsCategory.Connection })
    )
    scenes.filter { only.isEmpty() || it.name in only }.forEach { render(it, output) }
    println("Snapshots written to $output")
    kotlin.system.exitProcess(0)
}
