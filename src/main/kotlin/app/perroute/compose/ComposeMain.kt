package app.perroute.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Policy
import kotlinx.coroutines.Job
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Workspaces
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path as DrawPath
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.perroute.model.ConnectionMode
import app.perroute.model.ProxyNode
import app.perroute.model.RouteRule
import app.perroute.persistence.AppStateStore
import app.perroute.service.AppDirectories
import app.perroute.service.AppProxyLauncher
import app.perroute.service.SingleInstanceLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.MouseInfo
import java.awt.Point
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.WindowStateListener
import java.awt.GraphicsEnvironment
import java.awt.geom.RoundRectangle2D
import java.nio.file.Path
import java.nio.file.Files
import java.io.PrintWriter
import java.io.StringWriter
import javax.imageio.ImageIO
import java.util.concurrent.ConcurrentLinkedQueue

private data class Palette(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val foreground: Color,
    val muted: Color,
    val primary: Color,
    val primaryBright: Color,
    val success: Color,
    val danger: Color,
    val warning: Color,
    val isDark: Boolean
)

private val DarkPalette = Palette(
    Color(0xFF080A12), Color(0xFF111522), Color(0xFF181D2D), Color(0xFFF5F7FF),
    Color(0xFF98A2B8), Color(0xFF806CFF), Color(0xFFAE7CFF), Color(0xFF5AE6D0),
    Color(0xFFFF6985), Color(0xFFFFC766), true
)
private val LightPalette = Palette(
    Color(0xFFF3F5FA), Color(0xFFFFFFFF), Color(0xFFE9EDF5), Color(0xFF171A26),
    Color(0xFF5E687B), Color(0xFF5848D6), Color(0xFF6A50DF), Color(0xFF087B6B),
    Color(0xFFB72E4A), Color(0xFF865900), false
)
private var ActivePalette = DarkPalette
private val Ink get() = ActivePalette.background
private val Surface get() = ActivePalette.surface
private val SurfaceHigh get() = ActivePalette.surfaceHigh
private val White get() = ActivePalette.foreground
private val Muted get() = ActivePalette.muted
private val Violet get() = ActivePalette.primary
private val Violet2 get() = ActivePalette.primaryBright
private val Cyan get() = ActivePalette.success
private val Red get() = ActivePalette.danger
private val Amber get() = ActivePalette.warning
private val NeutralOverlay get() = if (ActivePalette.isDark) Color.White else Color.Black
private val GlassShape = RoundedCornerShape(26.dp)
private const val WindowCornerRadius = 30.0
private val UiFont = FontFamily(
    Font("fonts/Inter-Variable.ttf", FontWeight.Normal),
    Font("fonts/Inter-Variable.ttf", FontWeight.SemiBold),
    Font("fonts/Inter-Variable.ttf", FontWeight.Bold)
)

private enum class Overlay { None, Apps, Servers, Rules, Profiles, History, QrImport, Inspector, Settings }
private enum class ServerSort { DEFAULT, FASTEST, SLOWEST }
private enum class AppListSource { RUNNING, INSTALLED }
private enum class WindowControl { MINIMIZE, RESTORE, CLOSE }
private data class AppChoice(val name: String, val path: String)
private const val AutoSpeedTestLimit = 10
private data class ServerMeta(
    val source: String,
    val sourceLabel: String,
    val tag: String,
    val country: String,
    val favorite: Boolean
)

private fun runProxyGuard(dataDirectory: Path, parentPid: Long?) {
    val proxy = app.perroute.service.WindowsSystemProxyManager(dataDirectory)
    val parent = parentPid?.let { ProcessHandle.of(it).orElse(null) }
    while (parent != null && parent.isAlive) {
        if (!proxy.hasPendingRestore()) return
        Thread.sleep(1_000)
    }
    Thread.sleep(1_500)
    if (!proxy.hasPendingRestore()) return
    val lock = runCatching { SingleInstanceLock.acquire(dataDirectory) }.getOrNull() ?: return
    try {
        runCatching { app.perroute.service.XrayProcessManager().stopOrphan() }
        runCatching { proxy.restore() }
    } finally {
        lock.close()
    }
}

fun main(args: Array<String>) {
    Perf.mark("main.enter")
    val dataDirectory = AppDirectories.dataDirectory()
    val guardIndex = args.indexOf("--guard")
    if (guardIndex >= 0) {
        runProxyGuard(dataDirectory, args.getOrNull(guardIndex + 1)?.toLongOrNull())
        return
    }
    val lock = try { SingleInstanceLock.acquire(dataDirectory) } catch (e: Exception) { null }
    if (lock == null) return
    if ("--recover" in args) {
        runCatching { app.perroute.service.WindowsSystemProxyManager(dataDirectory).restore() }
        lock.close()
        return
    }
    val store = AppStateStore(dataDirectory.resolve("state.bin"))
    val stateLoader = java.util.concurrent.CompletableFuture.supplyAsync {
        runCatching { store.load() }
    }
    runCatching {
        Toolkit.getDefaultToolkit()
        org.jetbrains.skiko.Library.load()
    }
    Perf.mark("ui.warm")
    val loaded = stateLoader.join()
    val state = loaded.getOrElse { app.perroute.model.AppState() }
    Perf.mark("state.loaded")
    val controller = PerRouteController(state, store)
    loaded.exceptionOrNull()?.let { controller.reportStartupProblem("Saved settings could not be opened: ${it.message ?: it.javaClass.simpleName}") }
    Perf.mark("controller.ready")

    try { application {
        remember { Perf.mark("app.compose") }
        var windowVisible by remember { mutableStateOf(true) }
        var quickVisible by remember { mutableStateOf(false) }
        var quickHiddenAt by remember { mutableStateOf(0L) }
        var quickShownAt by remember { mutableStateOf(0L) }
        var trayBusy by remember { mutableStateOf(false) }
        var trayToggling by remember { mutableStateOf(false) }
        var mainWindow by remember { mutableStateOf<Frame?>(null) }
        var nativeTrayIcon by remember { mutableStateOf<TrayIcon?>(null) }
        var trayConnected by remember { mutableStateOf(controller.isConnected()) }
        var traySelectedKey by remember { mutableStateOf(controller.selectedNodeKey()) }
        var trayNodes by remember { mutableStateOf(controller.nodes()) }
        var trayTraffic by remember { mutableStateOf(controller.trafficSnapshot()) }
        val trayScope = rememberCoroutineScope()
        val windowState = rememberWindowState(width = 940.dp, height = 710.dp)
        fun exitPerRoute() {
            controller.close()
            lock.close()
            exitApplication()
        }
        fun trayAction(toggling: Boolean = false, action: () -> ActionResult) {
            if (trayBusy) return
            trayScope.launch {
                trayBusy = true
                trayToggling = toggling
                try {
                    withContext(Dispatchers.IO) { runCatching(action) }
                } finally {
                    trayBusy = false
                    trayToggling = false
                    trayConnected = controller.isConnected()
                    traySelectedKey = controller.selectedNodeKey()
                }
            }
        }
        fun refreshQuickAccess() {
            trayNodes = controller.nodes()
            traySelectedKey = controller.selectedNodeKey()
            trayConnected = controller.isConnected()
            trayTraffic = controller.trafficSnapshot()
        }
        DisposableEffect(Unit) {
            controller.onConnectionChanged { value ->
                trayScope.launch {
                    trayConnected = value
                    traySelectedKey = controller.selectedNodeKey()
                }
            }
            onDispose { }
        }
        LaunchedEffect(Unit) {
            while (isActive) {
                if (quickVisible) refreshQuickAccess()
                delay(1_000)
            }
        }
        DisposableEffect(Unit) {
            var installedIcon: TrayIcon? = null
            if (SystemTray.isSupported()) {
                val image = Thread.currentThread().contextClassLoader
                    .getResource("icons/perroute-icon-256.png")?.let(ImageIO::read)
                if (image != null) {
                    val icon = TrayIcon(image, "PerRoute").apply { isImageAutoSize = true }
                    icon.addMouseListener(object : MouseAdapter() {
                        override fun mouseReleased(event: MouseEvent) {
                            if (event.button != MouseEvent.BUTTON1 && event.button != MouseEvent.BUTTON3) return
                            trayScope.launch {
                                if (windowVisible) {
                                    mainWindow?.let { frame ->
                                        if (frame.extendedState and Frame.ICONIFIED != 0) {
                                            frame.extendedState = frame.extendedState and Frame.ICONIFIED.inv()
                                        }
                                        frame.toFront()
                                        frame.requestFocus()
                                    }
                                    return@launch
                                }
                                val justHidden = System.currentTimeMillis() - quickHiddenAt < 500
                                if (quickVisible) {
                                    quickVisible = false
                                } else if (!justHidden) {
                                    refreshQuickAccess()
                                    quickVisible = true
                                }
                            }
                        }
                    })
                    runCatching { SystemTray.getSystemTray().add(icon) }
                        .onSuccess { installedIcon = icon; nativeTrayIcon = icon }
                }
            }
            onDispose {
                installedIcon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
                nativeTrayIcon = null
            }
        }
        LaunchedEffect(trayConnected, nativeTrayIcon) {
            nativeTrayIcon?.toolTip = if (trayConnected) "PerRoute — Connected" else "PerRoute — Disconnected"
        }
        Window(
            visible = windowVisible,
            onCloseRequest = { windowVisible = false },
            state = windowState,
            title = "PerRoute",
            icon = painterResource("icons/perroute-icon-256.png"),
            undecorated = true,
            transparent = false,
            resizable = true
        ) {
            LaunchedEffect(Unit) {
                mainWindow = window
                window.minimumSize = Dimension(820, 620)
                window.background = java.awt.Color(8, 10, 18)
            }
            remember { Perf.mark("window.compose") }
            PerRouteWindow(
                controller = controller,
                hostWindow = window,
                exitApp = ::exitPerRoute,
                titleBar = {
                    TitleBar(
                        hostWindow = window,
                        minimized = { windowVisible = false },
                        maximized = {
                            window.placement = if (window.placement == WindowPlacement.Maximized)
                                WindowPlacement.Floating else WindowPlacement.Maximized
                        },
                        close = { windowVisible = false }
                    )
                }
            )
        }
        val quickState = rememberWindowState(width = 390.dp, height = 590.dp)
        Window(
            visible = quickVisible,
            onCloseRequest = { quickVisible = false },
            state = quickState,
            title = "PerRoute Quick Access",
            icon = painterResource("icons/perroute-icon-256.png"),
            undecorated = true,
            transparent = false,
            resizable = false,
            alwaysOnTop = true
        ) {
            LaunchedEffect(quickVisible) {
                if (!quickVisible) return@LaunchedEffect
                quickShownAt = System.currentTimeMillis()
                val area = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                window.setLocation((area.x + area.width - window.width - 12).coerceAtLeast(area.x),
                    (area.y + area.height - window.height - 12).coerceAtLeast(area.y))
                window.toFront()
                window.requestFocus()
            }
            DisposableEffect(window) {
                runCatching { if (!window.isDisplayable) window.type = java.awt.Window.Type.UTILITY }
                val focusListener = object : WindowAdapter() {
                    override fun windowLostFocus(event: WindowEvent?) {
                        if (!quickVisible) return
                        if (System.currentTimeMillis() - quickShownAt < 700) {
                            EventQueue.invokeLater {
                                if (quickVisible) { window.toFront(); window.requestFocus() }
                            }
                            return
                        }
                        quickHiddenAt = System.currentTimeMillis()
                        quickVisible = false
                    }
                }
                window.addWindowFocusListener(focusListener)
                onDispose { window.removeWindowFocusListener(focusListener) }
            }
            RoundedWindowEffect(window)
            QuickAccessWindow(
                connected = trayConnected,
                busy = trayToggling,
                selected = trayNodes.firstOrNull { it.key() == traySelectedKey },
                nodes = trayNodes.sortedWith(compareByDescending<ProxyNode> { controller.isFavorite(it.key()) }.thenBy { it.name() }),
                traffic = trayTraffic,
                onToggle = { trayAction(toggling = true) { if (controller.isConnected()) controller.disconnect() else controller.connect() } },
                onFastest = { trayAction { controller.switchToFastest() } },
                onServer = { key -> trayAction { controller.switchNode(key) } },
                onOpen = { quickVisible = false; windowVisible = true },
                onExit = { quickVisible = false; exitPerRoute() }
            )
        }
    } } catch (error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        runCatching {
            Files.createDirectories(dataDirectory)
            Files.writeString(dataDirectory.resolve("compose-error.log"), trace)
        }
        controller.close()
        lock.close()
    }
}

@Composable
private fun QuickAccessWindow(
    connected: Boolean,
    busy: Boolean,
    selected: ProxyNode?,
    nodes: List<ProxyNode>,
    traffic: TrafficSnapshot,
    onToggle: () -> Unit,
    onFastest: () -> Unit,
    onServer: (String) -> Unit,
    onOpen: () -> Unit,
    onExit: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(Ink)) {
        AuroraBackground()
        Column(Modifier.fillMaxSize().padding(17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource("icons/perroute-icon-256.png"), null, Modifier.size(28.dp))
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text("PerRoute", color = White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(if (connected) "Private route active" else "Ready", color = if (connected) Cyan else Muted, fontSize = 10.5.sp)
                }
            }
            Spacer(Modifier.height(13.dp))
            val cardBackground by animateColorAsState(
                if (connected) if (ActivePalette.isDark) Color(0xFF10352F).copy(.88f) else Color(0xFFE0F4EF)
                else if (ActivePalette.isDark) Color.White.copy(.055f) else Color.White.copy(.72f),
                animationSpec = tween(450), label = "quick-card-background"
            )
            val cardBorder by animateColorAsState(
                if (connected) Cyan.copy(.62f)
                else if (ActivePalette.isDark) Color.White.copy(.09f) else Color.Black.copy(.07f),
                animationSpec = tween(450), label = "quick-card-border"
            )
            val actionScale by animateFloatAsState(if (connected) 1.025f else 1f, tween(260), label = "quick-action-scale")
            Box(Modifier.fillMaxWidth().height(128.dp).clip(GlassShape).background(cardBackground).border(1.dp, cardBorder, GlassShape)) {
                if (connected) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(
                            Brush.radialGradient(listOf(Cyan.copy(if (ActivePalette.isDark) .16f else .11f), Color.Transparent)),
                            radius = size.minDimension * .9f,
                            center = Offset(size.width * .5f, size.height * .5f)
                        )
                    }
                }
                Column(Modifier.fillMaxSize().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(selected?.let { PerRouteController.cleanName(it.name()) } ?: "No server selected", color = White,
                            fontSize = 14.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        if (connected) { Spacer(Modifier.width(8.dp)); SmallChip("LIVE", Cyan) }
                    }
                    Text(selected?.endpoint() ?: "Open PerRoute to add a server", color = Muted, fontSize = 10.5.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        LiveStat(Modifier.weight(1f), "DOWN", formatRate(traffic.downloadBytesPerSecond), Cyan)
                        LiveStat(Modifier.weight(1f), "UP", formatRate(traffic.uploadBytesPerSecond), Violet2)
                        LiveStat(Modifier.weight(1f), "PING", traffic.latencyMillis?.let { "$it ms" } ?: "--", Amber)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            JetBrainsConnectButton(connected, busy, actionScale, onToggle, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            SecondaryButton(Icons.Rounded.Speed, "Switch to fastest server", onClick = onFastest)
            Spacer(Modifier.height(14.dp))
            Text("QUICK SERVERS", color = Muted, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp)
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(nodes, key = { it.key() }) { node ->
                    val active = node.key() == selected?.key()
                    val shape = RoundedCornerShape(13.dp)
                    Row(
                        Modifier.fillMaxWidth().height(42.dp).clip(shape)
                            .background(if (active) Cyan.copy(.10f) else NeutralOverlay.copy(.04f))
                            .border(1.dp, if (active) Cyan.copy(.28f) else NeutralOverlay.copy(.06f), shape)
                            .clickable { onServer(node.key()) }.pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusDot(active)
                        Spacer(Modifier.width(10.dp))
                        Text(PerRouteController.cleanName(node.name()), color = White.copy(if (active) 1f else .9f),
                            fontSize = 12.5.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Text(node.protocol().name, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(Icons.Rounded.Apps, "Open PerRoute", modifier = Modifier.weight(1f), onClick = onOpen)
                SecondaryButton(Icons.Rounded.PowerSettingsNew, "Quit", modifier = Modifier.weight(1f), onClick = onExit)
            }
        }
    }
}

@Composable
private fun PerRouteWindow(
    controller: PerRouteController,
    hostWindow: Frame,
    exitApp: () -> Unit,
    titleBar: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val nodes = remember { mutableStateListOf<ProxyNode>().also { it.addAll(controller.nodes()) } }
    val apps = remember { mutableStateListOf<String>().also { it.addAll(controller.applications()) } }
    val logs = remember { mutableStateListOf<String>() }
    val pendingLogs = remember { ConcurrentLinkedQueue<String>() }
    var selectedNodeKey by remember { mutableStateOf(controller.selectedNodeKey()) }
    var mode by remember { mutableStateOf(controller.connectionMode()) }
    var connected by remember { mutableStateOf(controller.isConnected()) }
    var traffic by remember { mutableStateOf(controller.trafficSnapshot()) }
    var busy by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf(Overlay.None) }
    var toast by remember { mutableStateOf(controller.takeStartupProblem()) }
    var toastError by remember { mutableStateOf(toast != null) }
    var darkMode by remember { mutableStateOf(controller.isDarkTheme()) }
    ActivePalette = if (darkMode) DarkPalette else LightPalette

    RoundedWindowEffect(hostWindow)

    LaunchedEffect(Unit) {
        if (!Perf.enabled) return@LaunchedEffect
        withFrameNanos { }
        Perf.mark("window.firstFrame")
        if (!Perf.bench) return@LaunchedEffect
        delay(3_000)
        Perf.mark("bench.start")
        Perf.value("servers.openMs", Perf.timeToFrame { overlay = Overlay.Servers })
        Perf.frameGaps("servers.afterOpen", 4_000)
        val keystrokes = listOf("s", "se", "ser", "serv", "server 9", "server 99").map { text ->
            Perf.timeToFrame { Perf.command.value = "q:$text" }
        }
        Perf.value("search.keystrokeMs", "avg=${"%.1f".format(java.util.Locale.ROOT, keystrokes.average())} max=${"%.1f".format(java.util.Locale.ROOT, keystrokes.max())}")
        Perf.timeToFrame { Perf.command.value = "q:" }
        Perf.command.value = "scroll"
        Perf.frameGaps("servers.scroll", 3_000)
        Perf.value("servers.closeMs", Perf.timeToFrame { overlay = Overlay.None })
        Perf.value("settings.openMs", Perf.timeToFrame { overlay = Overlay.Settings })
        delay(400)
        Perf.value("history.openMs", Perf.timeToFrame { overlay = Overlay.History })
        delay(400)
        overlay = Overlay.None
        delay(1_000)
        System.gc()
        delay(500)
        Perf.mark("bench.done")
    }

    LaunchedEffect(toast, toastError) {
        val visibleMessage = toast ?: return@LaunchedEffect
        delay(if (toastError) 6_000 else 4_000)
        if (toast == visibleMessage) toast = null
    }

    fun refresh() {
        nodes.clear(); nodes.addAll(controller.nodes())
        apps.clear(); apps.addAll(controller.applications())
        selectedNodeKey = controller.selectedNodeKey()
    }

    DisposableEffect(Unit) {
        controller.onLog { line -> pendingLogs.offer(line) }
        controller.onConnectionChanged { value -> scope.launch { connected = value } }
        onDispose { }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(250)
            var drained = 0
            while (drained < 100) {
                val line = pendingLogs.poll() ?: break
                logs += line
                drained++
            }
            while (logs.size > 200) logs.removeAt(0)
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            traffic = controller.trafficSnapshot()
            delay(1_000)
        }
    }

    fun launchAction(action: suspend () -> ActionResult) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) {
                    try { action() } catch (error: Throwable) {
                        ActionResult(false, error.message ?: error.javaClass.simpleName)
                    }
                }
                connected = runCatching { controller.isConnected() }.getOrDefault(false)
                runCatching { refresh() }
                toast = result.message
                toastError = !result.ok
            } finally {
                busy = false
            }
        }
    }

    MaterialTheme(
        colorScheme = if (darkMode)
            darkColorScheme(primary = Violet, secondary = Cyan, background = Ink, surface = Surface)
        else lightColorScheme(primary = Violet, secondary = Cyan, background = Ink, surface = Surface),
        typography = MaterialTheme.typography.copy(
            bodyLarge = TextStyle(fontFamily = UiFont),
            bodyMedium = TextStyle(fontFamily = UiFont),
            titleLarge = TextStyle(fontFamily = UiFont)
        )
    ) {
        CompositionLtr {
            Box(
                Modifier.fillMaxSize()
            ) {
                AuroraBackground()
                Column(Modifier.fillMaxSize()) {
                    titleBar()
                    Dashboard(
                        modifier = Modifier.weight(1f),
                        nodes = nodes,
                        apps = apps,
                        selectedNodeKey = selectedNodeKey,
                        mode = mode,
                        connected = connected,
                        traffic = traffic,
                        busy = busy,
                        requiresApplications = mode == ConnectionMode.NATIVE_PER_APP,
                        onConnect = {
                            launchAction {
                                if (controller.isConnected()) controller.disconnect() else controller.connect()
                            }
                        },
                        openApps = { if (!connected && mode == ConnectionMode.NATIVE_PER_APP) overlay = Overlay.Apps },
                        openServers = { overlay = Overlay.Servers },
                        openRules = { overlay = Overlay.Rules },
                        openProfiles = { overlay = Overlay.Profiles },
                        openHistory = { overlay = Overlay.History },
                        openQr = { overlay = Overlay.QrImport },
                        openInspector = { overlay = Overlay.Inspector },
                        openSettings = { overlay = Overlay.Settings }
                    )
                }

                AnimatedVisibility(
                    visible = overlay != Overlay.None,
                    enter = fadeIn(tween(90)),
                    exit = fadeOut(tween(70))
                ) {
                    when (overlay) {
                        Overlay.Apps -> AppsOverlay(
                            controller = controller,
                            selected = apps,
                            hostWindow = hostWindow,
                            close = { refresh(); overlay = Overlay.None }
                        )
                        Overlay.Servers -> ServersOverlay(
                            controller = controller,
                            nodes = nodes,
                            selectedKey = selectedNodeKey,
                            connected = connected,
                            busy = busy,
                            setBusy = { busy = it },
                            notify = { result ->
                                refresh(); toast = result.message; toastError = !result.ok
                            },
                            close = { refresh(); overlay = Overlay.None }
                        )
                        Overlay.Settings -> SettingsOverlay(
                            controller = controller,
                            currentMode = mode,
                            darkMode = darkMode,
                            logs = logs,
                            busy = busy,
                            connected = connected,
                            exitApp = exitApp,
                            setBusy = { busy = it },
                            onMode = { value -> mode = value; controller.setMode(value) },
                            onTheme = { value -> darkMode = value; controller.setDarkTheme(value) },
                            notify = { result -> toast = result.message; toastError = !result.ok },
                            close = { overlay = Overlay.None }
                        )
                        Overlay.Rules -> RulesOverlay(controller, apps, connected, { result ->
                            toast = result.message; toastError = !result.ok
                        }) { overlay = Overlay.None }
                        Overlay.Profiles -> ProfilesOverlay(controller, connected, { result ->
                            refresh(); mode = controller.connectionMode(); toast = result.message; toastError = !result.ok
                        }) { overlay = Overlay.None }
                        Overlay.History -> HistoryOverlay(controller) { overlay = Overlay.None }
                        Overlay.Inspector -> InspectorOverlay(controller, apps, connected) { overlay = Overlay.None }
                        Overlay.QrImport -> QrImportOverlay(controller, hostWindow, { result ->
                            refresh(); toast = result.message; toastError = !result.ok
                        }) { overlay = Overlay.None }
                        else -> Unit
                    }
                }

                AnimatedVisibility(
                    visible = toast != null,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
                    enter = fadeIn(tween(90)),
                    exit = fadeOut(tween(70))
                ) {
                    toast?.let { message ->
                        Row(
                            Modifier.widthIn(max = 620.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(
                                    if (ActivePalette.isDark) {
                                        if (toastError) Color(0xFF351925) else Color(0xFF132B29)
                                    } else {
                                        if (toastError) Color(0xFFFFE7EC) else Color(0xFFE0F4EF)
                                    }
                                )
                                .border(1.dp, if (toastError) Red.copy(.35f) else Cyan.copy(.3f), RoundedCornerShape(18.dp))
                                .clickable { toast = null }
                                .padding(horizontal = 16.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(if (toastError) Icons.Rounded.Close else Icons.Rounded.Check, null,
                                tint = if (toastError) Red else Cyan, modifier = Modifier.size(19.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(message, color = White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundedWindowEffect(hostWindow: Frame) {
    DisposableEffect(hostWindow) {
        fun applyShape() {
            runCatching {
                val maximized = hostWindow.extendedState and Frame.MAXIMIZED_BOTH != 0
                hostWindow.shape = if (maximized || hostWindow.width <= 0 || hostWindow.height <= 0) {
                    null
                } else {
                    RoundRectangle2D.Double(
                        0.0,
                        0.0,
                        hostWindow.width.toDouble(),
                        hostWindow.height.toDouble(),
                        WindowCornerRadius,
                        WindowCornerRadius
                    )
                }
            }
        }

        val componentListener = object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent?) = applyShape()
            override fun componentShown(event: ComponentEvent?) = applyShape()
        }
        val stateListener = WindowStateListener { EventQueue.invokeLater(::applyShape) }
        hostWindow.addComponentListener(componentListener)
        hostWindow.addWindowStateListener(stateListener)
        EventQueue.invokeLater(::applyShape)
        onDispose {
            hostWindow.removeComponentListener(componentListener)
            hostWindow.removeWindowStateListener(stateListener)
        }
    }
}

@Composable
private fun AuroraBackground() {
    Canvas(Modifier.fillMaxSize().background(Ink)) {
        drawRect(
            Brush.linearGradient(
                if (ActivePalette.isDark) listOf(Color(0xFF080A12), Color(0xFF0D1020), Color(0xFF090B13))
                else listOf(Color(0xFFF8F9FD), Color(0xFFF0F3FA), Color(0xFFF7F8FC))
            )
        )
        drawCircle(
            brush = Brush.radialGradient(listOf(Violet.copy(if (ActivePalette.isDark) .26f else .13f), Color.Transparent)),
            radius = size.minDimension * .62f,
            center = Offset(size.width * .88f, size.height * .16f)
        )
        drawCircle(
            brush = Brush.radialGradient(listOf(Cyan.copy(if (ActivePalette.isDark) .20f else .10f), Color.Transparent)),
            radius = size.minDimension * .52f,
            center = Offset(size.width * .08f, size.height * .92f)
        )
        drawCircle(
            brush = Brush.radialGradient(listOf(Violet2.copy(if (ActivePalette.isDark) .10f else .07f), Color.Transparent)),
            radius = size.minDimension * .38f,
            center = Offset(size.width * .52f, size.height * .64f)
        )
    }
}

@Composable
private fun TitleBar(hostWindow: Frame, minimized: () -> Unit, maximized: () -> Unit, close: () -> Unit) {
    val dragAnchors = remember { arrayOfNulls<Point>(2) }
    Row(
        Modifier.fillMaxWidth().height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(138.dp))
        Box(
            Modifier.weight(1f).fillMaxHeight().pointerInput(hostWindow) {
                detectDragGestures(
                    onDragStart = {
                        dragAnchors[0] = MouseInfo.getPointerInfo()?.location
                        dragAnchors[1] = hostWindow.location
                    },
                    onDragEnd = { dragAnchors.fill(null) },
                    onDragCancel = { dragAnchors.fill(null) }
                ) { change, _ ->
                    val pointerStart = dragAnchors[0]
                    val windowStart = dragAnchors[1]
                    val pointerNow = MouseInfo.getPointerInfo()?.location
                    if (pointerStart != null && windowStart != null && pointerNow != null) {
                        change.consume()
                        hostWindow.setLocation(
                            windowStart.x + pointerNow.x - pointerStart.x,
                            windowStart.y + pointerNow.y - pointerStart.y
                        )
                    }
                }
            }
        ) {
            Row(
                Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Image(painterResource("icons/perroute-icon-256.png"), null, Modifier.size(25.dp))
                Spacer(Modifier.width(9.dp))
                Text("PERROUTE", color = White.copy(.92f), fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 2.2.sp)
                Spacer(Modifier.width(8.dp))
                Text("1.0.0", color = Violet2, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(CircleShape).background(Violet.copy(.13f)).padding(horizontal = 7.dp, vertical = 3.dp))
            }
        }
        CompositionLtr {
            Row(Modifier.width(138.dp).fillMaxHeight(), verticalAlignment = Alignment.Top) {
                WindowsWindowButton(WindowControl.MINIMIZE, minimized)
                WindowsWindowButton(WindowControl.RESTORE, maximized)
                WindowsWindowButton(WindowControl.CLOSE, close)
            }
        }
    }
}

@Composable
private fun WindowsWindowButton(control: WindowControl, onClick: () -> Unit) {
    Box(
        Modifier.width(46.dp).height(34.dp)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(14.dp)) {
            val stroke = 1.2.dp.toPx()
            val color = White.copy(.88f)
            when (control) {
                WindowControl.MINIMIZE -> drawLine(color, Offset(size.width * .20f, size.height * .68f), Offset(size.width * .80f, size.height * .68f), stroke)
                WindowControl.CLOSE -> {
                    drawLine(color, Offset(size.width * .20f, size.height * .20f), Offset(size.width * .80f, size.height * .80f), stroke)
                    drawLine(color, Offset(size.width * .80f, size.height * .20f), Offset(size.width * .20f, size.height * .80f), stroke)
                }
                WindowControl.RESTORE -> {
                    drawRect(color, Offset(size.width * .30f, size.height * .18f), androidx.compose.ui.geometry.Size(size.width * .50f, size.height * .50f), style = Stroke(stroke))
                    drawRect(color, Offset(size.width * .18f, size.height * .32f), androidx.compose.ui.geometry.Size(size.width * .50f, size.height * .50f), style = Stroke(stroke))
                }
            }
        }
    }
}

@Composable
private fun Dashboard(
    modifier: Modifier,
    nodes: List<ProxyNode>,
    apps: List<String>,
    selectedNodeKey: String,
    mode: ConnectionMode,
    connected: Boolean,
    traffic: TrafficSnapshot,
    busy: Boolean,
    requiresApplications: Boolean,
    onConnect: () -> Unit,
    openApps: () -> Unit,
    openServers: () -> Unit,
    openRules: () -> Unit,
    openProfiles: () -> Unit,
    openHistory: () -> Unit,
    openQr: () -> Unit,
    openInspector: () -> Unit,
    openSettings: () -> Unit
) {
    val selected = nodes.firstOrNull { it.key() == selectedNodeKey } ?: nodes.firstOrNull()
    val applicationSummary = apps.joinToString(", ") { fileName(it).removeSuffix(".exe") }
    Column(modifier.padding(start = 26.dp, end = 26.dp, bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Private routing, only where you want it.", color = White,
                    fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("Everything else stays on your regular connection.", color = Muted, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                JetBrainsIconButton(Icons.Rounded.Rule, "Rules", openRules)
                JetBrainsIconButton(Icons.Rounded.Workspaces, "Profiles", openProfiles)
                JetBrainsIconButton(Icons.Rounded.BarChart, "Traffic history", openHistory)
                JetBrainsIconButton(Icons.Rounded.Policy, "Privacy & routing inspector", openInspector)
                JetBrainsIconButton(Icons.Rounded.QrCodeScanner, "Import QR", openQr)
                JetBrainsIconButton(Icons.Rounded.Settings, "Settings", openSettings)
            }
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ConnectCard(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                selected = selected,
                appCount = apps.size,
                mode = mode,
                connected = connected,
                traffic = traffic,
                busy = busy,
                requiresApplications = requiresApplications,
                onConnect = onConnect
            )
            Row(Modifier.fillMaxWidth().height(148.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SelectionCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.Apps,
                    accent = Cyan,
                    eyebrow = "APPLICATIONS",
                    title = when {
                        mode != ConnectionMode.NATIVE_PER_APP -> "No app selection needed"
                        apps.isEmpty() -> "No applications selected"
                        else -> "${apps.size} applications selected"
                    },
                    detail = when (mode) {
                        ConnectionMode.BROWSER_PROXY -> "Enter the local proxy inside the destination app"
                        ConnectionMode.SYSTEM_PROXY -> "Uses the current Windows user proxy settings"
                        else -> if (apps.isEmpty()) "Running, installed, or any executable"
                        else if (connected) activeAppSummary(traffic, applicationSummary)
                        else applicationSummary
                    },
                    chips = when {
                        mode == ConnectionMode.BROWSER_PROXY -> listOf("MANUAL PROXY")
                        mode == ConnectionMode.SYSTEM_PROXY -> listOf("WINDOWS PROXY")
                        apps.isEmpty() -> emptyList()
                        else -> listOf("${apps.size} selected")
                    },
                    enabled = !connected && mode == ConnectionMode.NATIVE_PER_APP,
                    onClick = openApps
                )
                SelectionCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.Public,
                    accent = Violet2,
                    eyebrow = "SERVER",
                    title = selected?.let { PerRouteController.cleanName(it.name()) } ?: "No server selected",
                    detail = selected?.let { "${it.protocol().name}  •  ${it.endpoint()}" } ?: "Add a subscription or share link",
                    chips = if (nodes.isEmpty()) emptyList() else listOf("${nodes.size} servers", selected?.protocol()?.name ?: ""),
                    enabled = true,
                    onClick = openServers
                )
            }
        }
    }
}

@Composable
private fun ConnectCard(
    modifier: Modifier,
    selected: ProxyNode?,
    appCount: Int,
    mode: ConnectionMode,
    connected: Boolean,
    traffic: TrafficSnapshot,
    busy: Boolean,
    requiresApplications: Boolean,
    onConnect: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val cardBackground by animateColorAsState(
        if (connected) if (ActivePalette.isDark) Color(0xFF10352F).copy(.88f) else Color(0xFFE0F4EF)
        else if (ActivePalette.isDark) Color.White.copy(.055f) else Color.White.copy(.72f),
        animationSpec = tween(450), label = "connection-card-background"
    )
    val cardBorder by animateColorAsState(
        if (connected) Cyan.copy(.62f)
        else if (ActivePalette.isDark) Color.White.copy(.09f) else Color.Black.copy(.07f),
        animationSpec = tween(450), label = "connection-card-border"
    )
    val actionScale by animateFloatAsState(if (connected) 1.025f else 1f, tween(260), label = "connection-action-scale")
    Box(modifier.clip(GlassShape).background(cardBackground).border(1.dp, cardBorder, GlassShape)) {
        if (connected) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    Brush.radialGradient(listOf(Cyan.copy(if (ActivePalette.isDark) .16f else .11f), Color.Transparent)),
                    radius = size.minDimension * .58f,
                    center = Offset(size.width * .52f, size.height * .53f)
                )
            }
        }
        Column(
            Modifier.fillMaxSize().padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(connected)
                Spacer(Modifier.width(9.dp))
                Text(if (connected) "PRIVATE ROUTE ACTIVE" else "READY TO CONNECT", color = if (connected) Cyan else Muted,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(when (mode) {
                    ConnectionMode.NATIVE_PER_APP -> "SMART PER-APP"
                    ConnectionMode.BROWSER_PROXY -> "LOCAL PROXY"
                    ConnectionMode.SYSTEM_PROXY -> "SYSTEM PROXY"
                    ConnectionMode.TUN_COMPATIBILITY -> "TUN"
                }, color = White.copy(.6f), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp)
            }
            Spacer(Modifier.weight(1f))
            JetBrainsConnectButton(connected, busy, actionScale, onConnect)
            Spacer(Modifier.height(16.dp))
            if (connected || selected == null || (requiresApplications && appCount == 0)) {
                Text(if (connected) "Connected" else "Complete your setup",
                    color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
            }
            Text(
                when {
                    selected == null -> "Choose a server to continue"
                    requiresApplications && appCount == 0 -> "Choose at least one application"
                    mode == ConnectionMode.BROWSER_PROXY && connected -> "Use either endpoint in the destination application's proxy settings"
                    mode == ConnectionMode.BROWSER_PROXY -> "Creates local SOCKS5 and HTTP proxy endpoints"
                    mode == ConnectionMode.SYSTEM_PROXY && connected -> "Windows system proxy is active"
                    mode == ConnectionMode.SYSTEM_PROXY -> "All apps that use Windows proxy settings"
                    connected -> "$appCount apps routed through ${PerRouteController.cleanName(selected.name())}"
                    else -> "$appCount apps  •  ${PerRouteController.cleanName(selected.name())}"
                },
                color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            if (mode == ConnectionMode.BROWSER_PROXY && selected != null) {
                Spacer(Modifier.height(11.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    ProxyEndpoint(
                        modifier = Modifier.weight(1f),
                        label = "SOCKS5",
                        value = "127.0.0.1:${AppProxyLauncher.SOCKS_PORT}",
                        active = connected,
                        onCopy = { clipboard.setText(AnnotatedString("127.0.0.1:${AppProxyLauncher.SOCKS_PORT}")) }
                    )
                    ProxyEndpoint(
                        modifier = Modifier.weight(1f),
                        label = "HTTP",
                        value = "127.0.0.1:${AppProxyLauncher.HTTP_PORT}",
                        active = connected,
                        onCopy = { clipboard.setText(AnnotatedString("127.0.0.1:${AppProxyLauncher.HTTP_PORT}")) }
                    )
                }
            }
            if (connected && selected != null) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    SmallChip("LIVE", Cyan)
                    SmallChip(selected.protocol().name, Violet2)
                    SmallChip(when (mode) {
                        ConnectionMode.NATIVE_PER_APP -> "$appCount APPS"
                        ConnectionMode.BROWSER_PROXY -> "LOCAL"
                        ConnectionMode.SYSTEM_PROXY -> "SYSTEM"
                        ConnectionMode.TUN_COMPATIBILITY -> "TUN"
                    }, Cyan)
                }
            }
            if (connected) {
                Spacer(Modifier.height(11.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    LiveStat(Modifier.weight(1f), "DOWNLOAD", formatRate(traffic.downloadBytesPerSecond), Cyan)
                    LiveStat(Modifier.weight(1f), "UPLOAD", formatRate(traffic.uploadBytesPerSecond), Violet2)
                    LiveStat(Modifier.weight(1f), "DURATION", formatDuration(traffic.connectedSeconds), White)
                    LiveStat(Modifier.weight(1f), "LATENCY", traffic.latencyMillis?.let { "$it ms" } ?: "--", Amber)
                }
                if (mode == ConnectionMode.NATIVE_PER_APP) {
                    val activeApps = traffic.applications.filter { it.active }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(Cyan.copy(.07f)).padding(horizontal = 11.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusDot(activeApps.isNotEmpty())
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (activeApps.isEmpty()) "Waiting for selected apps to use the tunnel"
                            else activeApps.joinToString("  •  ") {
                                "${fileName(it.path).removeSuffix(".exe")} ${formatBytes(it.downloadBytes + it.uploadBytes)}"
                            },
                            color = if (activeApps.isEmpty()) Muted else White.copy(.80f),
                            fontSize = 9.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(NeutralOverlay.copy(if (ActivePalette.isDark) .18f else .055f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Shield, null, tint = if (connected) Cyan else Muted, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(9.dp))
                Text(when (mode) {
                    ConnectionMode.NATIVE_PER_APP -> if (connected) "Other applications remain on your regular connection" else "Only selected applications will be routed"
                    ConnectionMode.BROWSER_PROXY -> "Configure the destination app with SOCKS5 or HTTP; other apps stay direct"
                    ConnectionMode.SYSTEM_PROXY -> "Applications that follow Windows proxy settings will use this route"
                    ConnectionMode.TUN_COMPATIBILITY -> "Network traffic is routed by the compatibility tunnel"
                },
                    color = White.copy(.72f), fontSize = 11.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LiveStat(modifier: Modifier, label: String, value: String, accent: Color) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(NeutralOverlay.copy(.045f))
            .border(1.dp, accent.copy(.13f), RoundedCornerShape(12.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp)
    ) {
        Text(label, color = Muted, fontSize = 7.5.sp, fontWeight = FontWeight.Bold, letterSpacing = .5.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = accent, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun formatRate(bytes: Long): String = "${formatBytes(bytes)}/s"

private fun formatBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L).toDouble()
    return when {
        value >= 1024.0 * 1024.0 * 1024.0 -> String.format("%.1f GB", value / (1024.0 * 1024.0 * 1024.0))
        value >= 1024.0 * 1024.0 -> String.format("%.1f MB", value / (1024.0 * 1024.0))
        value >= 1024.0 -> String.format("%.1f KB", value / 1024.0)
        else -> "${value.toLong()} B"
    }
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remaining = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remaining)
    else "%02d:%02d".format(minutes, remaining)
}

private fun activeAppSummary(traffic: TrafficSnapshot, fallback: String): String {
    val active = traffic.applications.filter { it.active }
    if (active.isEmpty()) return "No selected application is using the tunnel right now"
    return active.joinToString(", ") {
        "${fileName(it.path).removeSuffix(".exe")} (${formatBytes(it.downloadBytes + it.uploadBytes)})"
    }.ifBlank { fallback }
}

@Composable
private fun ProxyEndpoint(
    modifier: Modifier,
    label: String,
    value: String,
    active: Boolean,
    onCopy: () -> Unit
) {
    Row(
        modifier.height(46.dp).clip(RoundedCornerShape(12.dp))
            .background((if (active) Cyan else NeutralOverlay).copy(if (active) .10f else .045f))
            .border(1.dp, (if (active) Cyan else NeutralOverlay).copy(if (active) .30f else .08f), RoundedCornerShape(12.dp))
            .clickable(onClick = onCopy).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = if (active) Cyan else Muted, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
            CompositionLtr { Text(value, color = White, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
        }
        Icon(Icons.Rounded.ContentCopy, "Copy $label endpoint", tint = if (active) Cyan else Muted, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun SelectionCard(
    modifier: Modifier,
    icon: ImageVector,
    accent: Color,
    eyebrow: String,
    title: String,
    detail: String,
    chips: List<String>,
    enabled: Boolean,
    onClick: () -> Unit
) {
    GlassCard(modifier.alpha(if (enabled) 1f else .62f), onClick = if (enabled) onClick else null) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(11.dp)).background(accent.copy(.13f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = accent, modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(eyebrow, color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Rounded.KeyboardArrowLeft, null, tint = Muted, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(title, color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(detail, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            if (chips.isNotEmpty()) {
                CompositionLtr {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        chips.forEach { SmallChip(it, accent) }
                    }
                }
            } else {
                Text("+ Add", color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun AppsOverlay(
    controller: PerRouteController,
    selected: List<String>,
    hostWindow: Frame,
    close: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var runningLoading by remember { mutableStateOf(true) }
    var installedLoading by remember { mutableStateOf(true) }
    var source by remember { mutableStateOf(AppListSource.INSTALLED) }
    var running by remember { mutableStateOf<List<AppChoice>>(emptyList()) }
    var installed by remember { mutableStateOf<List<AppChoice>>(emptyList()) }
    val selectedPaths = remember { mutableStateListOf<String>().also { it.addAll(selected) } }

    fun refreshApplications(forceRefresh: Boolean = false) {
        scope.launch {
            runningLoading = true
            installedLoading = true
            val runningTask = async(Dispatchers.IO) {
                runCatching {
                    controller.runningApplications(forceRefresh).map { AppChoice(it.name(), it.path()) }
                }.getOrDefault(emptyList())
            }
            val installedTask = async(Dispatchers.IO) {
                runCatching {
                    controller.installedApplications(forceRefresh).map { AppChoice(it.name(), it.path()) }
                }.getOrDefault(emptyList())
            }
            try {
                running = runningTask.await()
            } finally {
                runningLoading = false
            }
            try {
                installed = installedTask.await()
            } finally {
                installedLoading = false
            }
        }
    }
    LaunchedEffect(Unit) { refreshApplications() }

    OverlayShell(
        title = "Choose applications",
        subtitle = "Smart per-app routes only the applications selected here",
        close = close
    ) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    SourceFilterChip("Installed apps", source == AppListSource.INSTALLED) { source = AppListSource.INSTALLED }
                    SourceFilterChip("Running apps", source == AppListSource.RUNNING) { source = AppListSource.RUNNING }
                    Spacer(Modifier.weight(1f))
                    SmallIconButtonGlass(Icons.Rounded.Refresh, "Refresh applications") { refreshApplications(true) }
                }
                Spacer(Modifier.height(10.dp))
                ModernInput(query, { query = it }, "Search applications", Icons.Rounded.Search)
                Spacer(Modifier.height(12.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val loading = if (source == AppListSource.RUNNING) runningLoading else installedLoading
                    if (loading) CircularProgressIndicator(color = Violet2, modifier = Modifier.align(Alignment.Center).size(32.dp), strokeWidth = 2.dp)
                    else {
                        val available = if (source == AppListSource.RUNNING) running else installed
                        val filtered = available.filter { query.isBlank() || it.name.contains(query, true) || it.path.contains(query, true) }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(filtered, key = { it.path.lowercase() }) { app ->
                                AppRow(
                                    app.name,
                                    app.path,
                                    selectedPaths.any { it.equals(app.path, true) },
                                    null,
                                    true
                                ) {
                                    if (selectedPaths.any { it.equals(app.path, true) }) selectedPaths.removeIf { it.equals(app.path, true) }
                                    else selectedPaths += app.path
                                }
                            }
                        }
                    }
                }
            }
            Column(Modifier.width(248.dp).fillMaxHeight()) {
                GlassCard(Modifier.fillMaxWidth().weight(1f)) {
                    Column(Modifier.fillMaxSize().padding(17.dp)) {
                        Text("Selected", color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("${selectedPaths.size} applications", color = Cyan, fontSize = 11.sp)
                        Spacer(Modifier.height(12.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            items(selectedPaths.toList(), key = { it.lowercase() }) { path ->
                                SelectedAppChip(path) { selectedPaths.removeIf { it.equals(path, true) } }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                SecondaryButton(Icons.Rounded.FolderOpen, "Browse for an EXE") {
                    val dialog = FileDialog(hostWindow, "Choose application", FileDialog.LOAD).apply {
                        filenameFilter = java.io.FilenameFilter { _, name -> name.endsWith(".exe", true) }
                        isVisible = true
                    }
                    val file = dialog.file
                    if (file != null) {
                        val path = Path.of(dialog.directory, file).toAbsolutePath().normalize().toString()
                        if (selectedPaths.none { it.equals(path, true) }) selectedPaths += path
                    }
                }
                Spacer(Modifier.height(10.dp))
                PrimaryButton("Save selection") {
                    controller.replaceApplications(selectedPaths)
                    close()
                }
            }
        }
    }
}

@Composable
private fun ServersOverlay(
    controller: PerRouteController,
    nodes: List<ProxyNode>,
    selectedKey: String,
    connected: Boolean,
    busy: Boolean,
    setBusy: (Boolean) -> Unit,
    notify: (ActionResult) -> Unit,
    close: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var url by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var pasteMode by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var sourceFilter by remember { mutableStateOf("__all__") }
    var serverSort by remember { mutableStateOf(ServerSort.DEFAULT) }
    var latencies by remember { mutableStateOf(controller.cachedLatencies()) }
    var pinging by remember { mutableStateOf(false) }
    var pingRevision by remember { mutableStateOf(0) }
    var runRealTest by remember { mutableStateOf(false) }
    var hideUnavailable by remember { mutableStateOf(false) }
    var metaRevision by remember { mutableStateOf(0) }
    var speeds by remember { mutableStateOf(controller.cachedSpeeds()) }
    var speedTestingKey by remember { mutableStateOf<String?>(null) }
    var speedJob by remember { mutableStateOf<Job?>(null) }
    val visibleServers = remember { mutableStateOf<List<ProxyNode>>(emptyList()) }
    fun runSpeedTest(targets: List<ProxyNode>) {
        if (speedJob?.isActive == true || targets.isEmpty()) return
        speedJob = scope.launch {
            try {
                for (node in targets) {
                    if (!isActive) break
                    speedTestingKey = node.key()
                    val speed = withContext(Dispatchers.IO) { controller.speedTestServer(node) }
                    speeds = speeds + (node.key() to speed)
                }
            } finally {
                speedTestingKey = null
            }
        }
    }
    val benchListState = rememberLazyListState()
    var tagText by remember(selectedKey) { mutableStateOf(controller.nodeTag(selectedKey)) }

    val snapshot = nodes.toList()
    val subscriptions = remember(snapshot, metaRevision) { controller.subscriptions() }
    val meta = remember(snapshot, metaRevision) {
        snapshot.associate { node ->
            val source = controller.nodeSource(node.key())
            node.key() to ServerMeta(
                source = source,
                sourceLabel = PerRouteController.sourceLabel(source),
                tag = controller.nodeTag(node.key()),
                country = countryLabel(node),
                favorite = controller.isFavorite(node.key())
            )
        }
    }

    val pingSignature = remember(snapshot) { snapshot.joinToString("|") { "${it.key()}:${it.endpoint()}" } }
    LaunchedEffect(pingSignature, pingRevision) {
        if (snapshot.isEmpty()) {
            latencies = emptyMap()
            return@LaunchedEffect
        }
        pinging = true
        try {
            val measured = withContext(Dispatchers.IO) {
                if (runRealTest) controller.realTestServers(snapshot) else controller.pingServers(snapshot)
            }
            latencies = measured
            if (controller.autoSpeedTest()) {
                val known = speeds
                runSpeedTest(snapshot.filter { (measured[it.key()] ?: -1L) >= 0L && it.key() !in known }
                    .sortedBy { measured[it.key()] }.take(AutoSpeedTestLimit))
            }
        } finally {
            pinging = false
        }
    }

    fun execute(block: () -> ActionResult) {
        if (busy) return
        scope.launch {
            setBusy(true)
            try {
                val result = withContext(Dispatchers.IO) {
                    try { block() } catch (error: Throwable) {
                        ActionResult(false, error.message ?: error.javaClass.simpleName)
                    }
                }
                notify(result)
                metaRevision++
            } finally {
                setBusy(false)
            }
        }
    }

    OverlayShell(
        title = "Servers",
        subtitle = if (connected) "Select another server to switch without restarting your applications" else "Add subscriptions, update them, and choose a route",
        close = close
    ) {
        Column(Modifier.fillMaxSize()) {
            if (connected) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(Cyan.copy(.10f))
                        .border(1.dp, Cyan.copy(.25f), RoundedCornerShape(15.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusDot(true)
                    Spacer(Modifier.width(9.dp))
                    Text("Live switching is active — choose any server below.", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            } else if (pasteMode) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(NeutralOverlay.copy(.04f)).padding(14.dp)) {
                    Text("Share links or subscription content", color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    ModernTextArea(pasted, { pasted = it }, "vless://…\nvmess://…")
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("Add links", compact = true) {
                            val links = pasted
                            pasted = ""
                            pasteMode = false
                            execute { controller.pasteLinks(links) }
                        }
                        SecondaryButton(Icons.Rounded.ArrowBack, "Cancel", compact = true) { pasted = ""; pasteMode = false }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModernInput(url, { url = it }, "Subscription URL", Icons.Rounded.Link, Modifier.weight(1f))
                    PrimaryButton(if (busy) "Loading…" else "Add subscription", compact = true) {
                        val subscriptionUrl = url.trim()
                        url = ""
                        execute { controller.fetchSubscription(subscriptionUrl) }
                    }
                    SecondaryButton(Icons.Rounded.ContentPaste, "Add manual", compact = true) { pasteMode = true }
                }
            }
            Spacer(Modifier.height(16.dp))
            val manualPresent = meta.values.any { it.source == PerRouteController.MANUAL_SOURCE }
            val importedPresent = meta.values.any { it.source == PerRouteController.IMPORTED_SOURCE }
            val groupFilters = remember(meta) {
                meta.values.map { it.tag }.filter { it.isNotBlank() }.distinct().map { "tag:$it" } +
                    meta.values.map { it.country }.filter { it.isNotBlank() }.distinct().map { "country:$it" }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val sourceScroll = rememberScrollState()
                Row(
                    Modifier.weight(1f).wheelAndDragScroll(sourceScroll).horizontalScroll(sourceScroll),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    SourceFilterChip("All", sourceFilter == "__all__") { sourceFilter = "__all__" }
                    SourceFilterChip("Favorites", sourceFilter == "__favorites__") { sourceFilter = "__favorites__" }
                    subscriptions.distinct().forEach { source ->
                        SubscriptionFilterChip(
                            label = PerRouteController.sourceLabel(source),
                            selected = sourceFilter == source,
                            onClick = { sourceFilter = source },
                            onCopy = {
                                clipboard.setText(AnnotatedString(source))
                                notify(ActionResult(true, "Subscription URL copied."))
                            }
                        )
                    }
                    if (manualPresent) SourceFilterChip("Manual", sourceFilter == PerRouteController.MANUAL_SOURCE) {
                        sourceFilter = PerRouteController.MANUAL_SOURCE
                    }
                    if (importedPresent) SourceFilterChip("Imported", sourceFilter == PerRouteController.IMPORTED_SOURCE) {
                        sourceFilter = PerRouteController.IMPORTED_SOURCE
                    }
                }
                if (!connected && sourceFilter in subscriptions) {
                    Spacer(Modifier.width(8.dp))
                    IconButtonGlass(Icons.Rounded.DeleteOutline, "Remove subscription") {
                        val target = sourceFilter
                        sourceFilter = "__all__"
                        execute { controller.removeSubscription(target) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (groupFilters.isNotEmpty()) {
                    Text("Tags", color = Muted, fontSize = 10.sp)
                    Spacer(Modifier.width(8.dp))
                    val tagScroll = rememberScrollState()
                    Row(
                        Modifier.weight(1f).wheelAndDragScroll(tagScroll).horizontalScroll(tagScroll),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        groupFilters.forEach { filter ->
                            val label = if (filter.startsWith("tag:")) "#${filter.removePrefix("tag:")}"
                                else filter.removePrefix("country:")
                            SourceFilterChip(label, sourceFilter == filter) {
                                sourceFilter = if (sourceFilter == filter) "__all__" else filter
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (!connected) {
                    Spacer(Modifier.width(8.dp))
                    SecondaryButton(Icons.Rounded.Refresh, if (busy) "Updating…" else "Update all", compact = true) {
                        execute { controller.updateAllSubscriptions() }
                    }
                }
                Spacer(Modifier.width(8.dp))
                SecondaryButton(Icons.Rounded.Speed, if (pinging) "Testing tunnels…" else "Real test", compact = true) {
                    if (!pinging) {
                        runRealTest = true
                        pingRevision++
                    }
                }
                Spacer(Modifier.width(8.dp))
                val speedRunning = speedTestingKey != null
                SecondaryButton(Icons.Rounded.Download, if (speedRunning) "Stop speed test" else "Speed test", compact = true) {
                    if (speedRunning) speedJob?.cancel()
                    else runSpeedTest(visibleServers.value.filter { (latencies[it.key()] ?: 0L) >= 0L })
                }
            }
            Spacer(Modifier.height(10.dp))
            val matching = remember(snapshot, meta, sourceFilter, hideUnavailable, query, latencies) {
                snapshot.filter { node ->
                    val info = meta[node.key()] ?: return@filter false
                    (when {
                        sourceFilter == "__all__" -> true
                        sourceFilter == "__favorites__" -> info.favorite
                        sourceFilter.startsWith("tag:") -> info.tag.equals(sourceFilter.removePrefix("tag:"), true)
                        sourceFilter.startsWith("country:") -> info.country == sourceFilter.removePrefix("country:")
                        else -> info.source == sourceFilter
                    }) && (!hideUnavailable || (latencies[node.key()] ?: -1L) >= 0L) &&
                        (query.isBlank() || node.name().contains(query, true) || node.endpoint().contains(query, true) ||
                            info.sourceLabel.contains(query, true))
                }
            }
            val filtered = remember(matching, serverSort, latencies) {
                when (serverSort) {
                    ServerSort.DEFAULT -> matching
                    ServerSort.FASTEST -> matching.sortedWith(
                        compareBy<ProxyNode> { (latencies[it.key()] ?: -1L) < 0L }
                            .thenBy { latencies[it.key()]?.takeIf { value -> value >= 0L } ?: Long.MAX_VALUE }
                    )
                    ServerSort.SLOWEST -> matching.sortedWith(
                        compareBy<ProxyNode> { (latencies[it.key()] ?: -1L) < 0L }
                            .thenByDescending { latencies[it.key()]?.takeIf { value -> value >= 0L } ?: Long.MIN_VALUE }
                    )
                }
            }
            SideEffect { visibleServers.value = filtered }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${matching.size} of ${snapshot.size} servers", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.width(12.dp))
                Text("Sort", color = Muted, fontSize = 10.sp)
                Spacer(Modifier.width(6.dp))
                SourceFilterChip("Default", serverSort == ServerSort.DEFAULT) { serverSort = ServerSort.DEFAULT }
                Spacer(Modifier.width(5.dp))
                SourceFilterChip("Fastest", serverSort == ServerSort.FASTEST) { serverSort = ServerSort.FASTEST }
                Spacer(Modifier.width(5.dp))
                SourceFilterChip("Slowest", serverSort == ServerSort.SLOWEST) { serverSort = ServerSort.SLOWEST }
                Spacer(Modifier.width(5.dp))
                SourceFilterChip("Hide failed", hideUnavailable) { hideUnavailable = !hideUnavailable }
                Spacer(Modifier.weight(1f))
                ModernInput(tagText, {
                    tagText = it
                    if (selectedKey.isNotBlank()) {
                        controller.setNodeTag(selectedKey, it)
                        metaRevision++
                    }
                }, "Active tag", Icons.Rounded.StarBorder, Modifier.width(145.dp))
                Spacer(Modifier.width(7.dp))
                ModernInput(query, { query = it }, "Search name, address, or source", Icons.Rounded.Search, Modifier.width(270.dp))
            }
            Spacer(Modifier.height(10.dp))
            if (Perf.enabled) {
                val listState = benchListState
                LaunchedEffect(Perf.command.value) {
                    val command = Perf.command.value
                    if (command.startsWith("q:")) query = command.removePrefix("q:")
                    if (command == "scroll") listState.animateScrollBy(60_000f, tween(3_000))
                }
            }
            LazyColumn(Modifier.fillMaxSize(), state = benchListState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered, key = { it.key() }) { node ->
                    val info = meta[node.key()]
                    ServerRow(
                        node = node,
                        source = info?.sourceLabel ?: "",
                        tag = info?.tag?.ifBlank { info.country } ?: "",
                        latencyMs = latencies[node.key()],
                        pinging = pinging && !latencies.containsKey(node.key()),
                        speed = speeds[node.key()],
                        speedTesting = speedTestingKey == node.key(),
                        onSpeed = { runSpeedTest(listOf(node)) },
                        selected = node.key() == selectedKey,
                        favorite = info?.favorite == true,
                        onFavorite = { controller.toggleFavorite(node.key()); metaRevision++ },
                        onSelect = { execute { controller.switchNode(node.key()) } },
                        onCopy = {
                            val export = node.exportText()
                            if (export.isBlank()) {
                                notify(ActionResult(false, "This server has no original config to copy."))
                            } else {
                                clipboard.setText(AnnotatedString(export))
                                notify(ActionResult(true, if (node.option("rawProfileJson").isBlank()) "Share link copied." else "JSON config copied."))
                            }
                        },
                        onDelete = if (connected) null else ({
                            controller.removeNode(node.key())
                            notify(ActionResult(true, "Server removed."))
                        })
                    )
                }
            }
        }
    }
}

@Composable
private fun RulesOverlay(
    controller: PerRouteController,
    applications: List<String>,
    connected: Boolean,
    notify: (ActionResult) -> Unit,
    close: () -> Unit
) {
    val rules = remember { mutableStateListOf<RouteRule>().also { it.addAll(controller.rules()) } }
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var app by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(RouteRule.MatchType.DOMAIN) }
    var action by remember { mutableStateOf(RouteRule.Action.DIRECT) }
    fun refreshRules() { rules.clear(); rules.addAll(controller.rules()) }
    OverlayShell("Routing rules", "Per-app domain, IP/CIDR, and port decisions", close) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            GlassCard(Modifier.width(330.dp).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                    Text("NEW RULE", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    ModernInput(name, { name = it }, "Rule name", Icons.Rounded.Rule)
                    Spacer(Modifier.height(8.dp))
                    Text("APPLICATION", color = Muted, fontSize = 9.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SourceFilterChip("All routed apps", app.isBlank()) { app = "" }
                        applications.forEach { path -> SourceFilterChip(fileName(path).removeSuffix(".exe"), app == path) { app = path } }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("MATCH", color = Muted, fontSize = 9.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RouteRule.MatchType.values().forEach { choice ->
                            SourceFilterChip(choice.name, type == choice) { type = choice }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    ModernInput(value, { value = it }, when (type) {
                        RouteRule.MatchType.DOMAIN -> "example.com, full:api.example.com"
                        RouteRule.MatchType.IP -> "1.2.3.0/24, geoip:ir"
                        RouteRule.MatchType.PORT -> "80,443,1000-2000"
                    }, Icons.Rounded.Search)
                    Spacer(Modifier.height(10.dp))
                    Text("ACTION", color = Muted, fontSize = 9.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RouteRule.Action.values().forEach { choice ->
                            SourceFilterChip(choice.name, action == choice) { action = choice }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton(if (connected) "Disconnect to edit" else "Add rule") {
                        if (!connected) {
                            val result = controller.addRule(name, app, type, value, action)
                            notify(result)
                            if (result.ok) { name = ""; value = ""; refreshRules() }
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (connected) Text("Rules are read-only while connected.", color = Amber, fontSize = 10.sp)
                Text("${rules.size} rules - first match wins", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rules, key = { it.id() }) { rule ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                .background(if (rule.enabled()) Violet.copy(.10f) else NeutralOverlay.copy(.035f))
                                .border(1.dp, if (rule.enabled()) Violet.copy(.25f) else NeutralOverlay.copy(.06f), RoundedCornerShape(16.dp))
                                .clickable(enabled = !connected) { controller.setRuleEnabled(rule.id(), !rule.enabled()); refreshRules() }
                                .padding(13.dp), verticalAlignment = Alignment.CenterVertically
                        ) {
                            CheckBox(rule.enabled())
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text(rule.name(), color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text("${if (rule.applicationPath().isBlank()) "All apps" else fileName(rule.applicationPath()).removeSuffix(".exe")}  •  ${rule.matchType()} ${rule.value()}  →  ${rule.action()}",
                                    color = Muted, fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (!connected) SmallIconButtonGlass(Icons.Rounded.DeleteOutline, "Delete rule") {
                                controller.removeRule(rule.id()); refreshRules()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfilesOverlay(
    controller: PerRouteController,
    connected: Boolean,
    notify: (ActionResult) -> Unit,
    close: () -> Unit
) {
    val profiles = remember { mutableStateListOf<app.perroute.model.ConnectionProfile>().also { it.addAll(controller.profiles()) } }
    var name by remember { mutableStateOf("") }
    fun refreshProfiles() { profiles.clear(); profiles.addAll(controller.profiles()) }
    var activeScenario by remember { mutableStateOf(controller.activeScenarioId()) }
    OverlayShell("Scenarios & profiles", "One-click presets, or save your own apps, server, mode, and rules together", close) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("ONE-CLICK SCENARIOS")
                Spacer(Modifier.weight(1f))
                if (activeScenario.isNotBlank() && !connected) {
                    Text("Remove scenario rules", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                            val result = controller.clearScenario(); notify(result); activeScenario = controller.activeScenarioId()
                        }.pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            Spacer(Modifier.height(9.dp))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PerRouteController.SCENARIOS.forEach { scenario ->
                    ScenarioCard(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        scenario = scenario,
                        active = activeScenario == scenario.id,
                        enabled = !connected
                    ) {
                        val result = controller.applyScenario(scenario.id)
                        notify(result)
                        activeScenario = controller.activeScenarioId()
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(
                if (connected) "Disconnect to switch scenarios."
                else "A scenario sets the automation switches and adds its rules above yours. Your apps, server, connection mode, and own rules are not touched.",
                color = if (connected) Amber else Muted, fontSize = 9.5.sp, lineHeight = 13.sp
            )
            Spacer(Modifier.height(16.dp))
            SectionLabel("YOUR PROFILES")
            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                ModernInput(name, { name = it }, "Profile name - e.g. Work", Icons.Rounded.Workspaces, Modifier.weight(1f))
                PrimaryButton(if (connected) "Disconnect to save" else "Save current", compact = true) {
                    if (!connected) { val result = controller.saveProfile(name); notify(result); if (result.ok) { name = ""; refreshProfiles() } }
                }
            }
            Spacer(Modifier.height(15.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(profiles, key = { it.id() }) { profile ->
                    val active = controller.activeProfileId() == profile.id()
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                            .background(if (active) Cyan.copy(.09f) else NeutralOverlay.copy(.035f))
                            .border(1.dp, if (active) Cyan.copy(.28f) else NeutralOverlay.copy(.06f), RoundedCornerShape(18.dp))
                            .clickable(enabled = !connected) {
                                val result = controller.applyProfile(profile.id()); notify(result); refreshProfiles(); activeScenario = controller.activeScenarioId()
                            }.padding(15.dp), verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Violet.copy(.14f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Workspaces, null, tint = if (active) Cyan else Violet2, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(profile.name(), color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("${profile.mode().name.replace('_', ' ')}  •  ${profile.applicationPaths().size} apps  •  ${profile.rules().size} rules",
                                color = Muted, fontSize = 10.sp)
                        }
                        if (active) SmallChip("ACTIVE", Cyan)
                        Spacer(Modifier.width(8.dp))
                        if (!connected) SmallIconButtonGlass(Icons.Rounded.DeleteOutline, "Delete profile") {
                            controller.removeProfile(profile.id()); refreshProfiles()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScenarioCard(modifier: Modifier, scenario: Scenario, active: Boolean, enabled: Boolean, onApply: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val accent = if (active) Cyan else Violet2
    Column(
        modifier.alpha(if (enabled || active) 1f else .55f).clip(shape)
            .background(if (active) Cyan.copy(.09f) else NeutralOverlay.copy(.035f))
            .border(1.dp, if (active) Cyan.copy(.30f) else NeutralOverlay.copy(.07f), shape)
            .clickable(enabled = enabled, onClick = onApply).pointerHoverIcon(PointerIcon.Hand)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(accent.copy(.14f)), contentAlignment = Alignment.Center) {
                Icon(when (scenario.id) {
                    "work" -> Icons.Rounded.Workspaces
                    "gaming" -> Icons.Rounded.Speed
                    else -> Icons.Rounded.Public
                }, null, tint = accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(scenario.title, color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (active) SmallChip("ACTIVE", Cyan)
        }
        Spacer(Modifier.height(8.dp))
        Text(scenario.summary, color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
        Spacer(Modifier.height(8.dp))
        scenario.highlights.forEach { line ->
            Row(Modifier.padding(vertical = 1.5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Check, null, tint = accent, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(6.dp))
                Text(line, color = White.copy(.82f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun InspectorOverlay(
    controller: PerRouteController,
    applications: List<String>,
    connected: Boolean,
    close: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var inspection by remember { mutableStateOf<RouteInspection?>(null) }
    var loading by remember { mutableStateOf(false) }
    val rules = remember { controller.rules() }
    var loadedRuleIds by remember { mutableStateOf<Set<String>?>(null) }
    var destination by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("443") }
    var app by remember { mutableStateOf("") }
    var verdict by remember { mutableStateOf<app.perroute.service.RouteRuleEvaluator.Verdict?>(null) }

    fun runCheck() {
        if (loading) return
        scope.launch {
            loading = true
            try {
                val (routes, loaded) = withContext(Dispatchers.IO) { controller.inspectRoutes() to controller.loadedRuleIds() }
                inspection = routes
                loadedRuleIds = loaded
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(connected) { runCheck() }

    OverlayShell("Privacy & routing inspector", "See what each route exposes and which rule a destination will hit", close) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("ROUTES")
                    Spacer(Modifier.weight(1f))
                    SecondaryButton(Icons.Rounded.Refresh, if (loading) "Checking…" else "Run check", compact = true) { runCheck() }
                }
                Spacer(Modifier.height(9.dp))
                val report = inspection
                InspectorRouteCard(
                    title = "Direct route",
                    subtitle = "Everything that is not routed through PerRoute",
                    accent = Violet2,
                    report = report?.direct,
                    loading = loading && report == null,
                    lines = listOf(
                        "System DNS servers" to (report?.systemDnsServers?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "Unknown"),
                        "Resolver seen publicly" to (report?.publicResolver ?: "Unknown")
                    )
                )
                Spacer(Modifier.height(9.dp))
                InspectorRouteCard(
                    title = "Tunnel route",
                    subtitle = if (connected) "Traffic that PerRoute sends through the active server" else "Connect to inspect the tunnel",
                    accent = Cyan,
                    report = report?.tunnel,
                    loading = loading && connected && report?.tunnel == null,
                    lines = if (!connected) emptyList() else listOf(
                        "DNS" to when (report?.mode ?: controller.connectionMode()) {
                            ConnectionMode.NATIVE_PER_APP -> "Selected apps usually ask Windows for DNS, so lookups use the system resolver above. Only their connections are tunnelled."
                            ConnectionMode.TUN_COMPATIBILITY -> "DNS is carried inside the tunnel."
                            else -> "Apps using the HTTP proxy hand host names to the tunnel exit. SOCKS clients may still resolve locally first."
                        }
                    )
                )
                Spacer(Modifier.height(10.dp))
                val (statusText, statusColor) = when {
                    report == null -> "Checking what remote servers can see…" to Muted
                    report.tunnel == null -> "Not connected. All traffic currently leaves through the direct route." to Amber
                    report.tunnel.ip == null -> "The tunnel did not answer the lookup. The connection may be down." to Red
                    report.tunnelExposesDirectIp -> "The tunnel shows the same public address as the direct route. Routed traffic is not hidden." to Red
                    report.direct.ip == null -> "The tunnel has its own exit address. The direct route could not be checked." to Amber
                    else -> "The tunnel exits from a different address than your direct route." to Cyan
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(statusColor.copy(.09f))
                        .border(1.dp, statusColor.copy(.24f), RoundedCornerShape(14.dp)).padding(horizontal = 13.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Shield, null, tint = statusColor, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(9.dp))
                    Text(statusText, color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 15.sp)
                }
                Spacer(Modifier.height(9.dp))
                val killSwitch = report?.killSwitch ?: controller.killSwitch()
                val perApp = (report?.mode ?: controller.connectionMode()) == ConnectionMode.NATIVE_PER_APP
                Text(
                    when {
                        killSwitch && perApp -> "Per-app kill switch is on: selected apps stay blocked while the tunnel is unavailable."
                        killSwitch -> "Per-app kill switch is on, but it only acts in Smart per-app mode."
                        else -> "Per-app kill switch is off: if the tunnel drops, selected apps fall back to the direct route."
                    },
                    color = Muted, fontSize = 10.sp, lineHeight = 14.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "This view reports what public servers observe at this moment. It does not capture packets, so it cannot prove the absence of leaks. Use a dedicated leak test before relying on it for sensitive work.",
                    color = Muted.copy(.8f), fontSize = 9.5.sp, lineHeight = 13.sp
                )
            }
            GlassCard(Modifier.width(400.dp).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    SectionLabel("RULE CHECK")
                    Spacer(Modifier.height(9.dp))
                    if (applications.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SourceFilterChip("Any app", app.isBlank()) { app = ""; verdict = null }
                            applications.forEach { path ->
                                SourceFilterChip(fileName(path).removeSuffix(".exe"), app == path) { app = path; verdict = null }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        ModernInput(destination, { destination = it; verdict = null }, "Domain or IP, e.g. github.com", Icons.Rounded.Public, Modifier.weight(1f))
                        ModernInput(portText, { value -> portText = value.filter(Char::isDigit).take(5); verdict = null }, "Port", Icons.Rounded.Dns, Modifier.width(104.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton("Check route") {
                        if (destination.isNotBlank()) {
                            verdict = controller.evaluateRoute(app, destination, portText.toIntOrNull() ?: 0)
                        }
                    }
                    verdict?.let { result ->
                        val color = when (result.action()) {
                            RouteRule.Action.PROXY -> Cyan
                            RouteRule.Action.DIRECT -> Amber
                            RouteRule.Action.BLOCK -> Red
                        }
                        Spacer(Modifier.height(9.dp))
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(color.copy(.09f))
                                .border(1.dp, color.copy(.24f), RoundedCornerShape(13.dp)).padding(11.dp)
                        ) {
                            Text("${if (result.certain()) "" else "Probably "}${result.action()}", color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(3.dp))
                            Text(result.reason(), color = White.copy(.82f), fontSize = 10.sp, lineHeight = 14.sp)
                            if (!result.certain()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Could not evaluate locally, may match first: " + result.uncertain().joinToString(", ") { it.name() },
                                    color = Amber, fontSize = 9.5.sp, lineHeight = 13.sp
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    SectionLabel(if (connected) "RULES IN THE RUNNING CORE" else "SAVED RULES")
                    Spacer(Modifier.height(8.dp))
                    if (rules.isEmpty()) Text("No routing rules yet. Add them from Rules or apply a scenario.", color = Muted, fontSize = 10.sp)
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(rules, key = { it.id() }) { rule ->
                            val loaded = loadedRuleIds
                            val (label, color) = when {
                                !rule.enabled() -> "Off" to Muted
                                loaded == null -> "Saved" to Muted
                                rule.id() in loaded -> "Active" to Cyan
                                else -> "Not loaded" to Red
                            }
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(NeutralOverlay.copy(.04f))
                                    .padding(horizontal = 11.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(rule.name(), color = White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${rule.matchType()} ${rule.value()}  →  ${rule.action()}", color = Muted, fontSize = 9.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Spacer(Modifier.width(8.dp))
                                SmallChip(label, color)
                            }
                        }
                    }
                    if (connected) {
                        Spacer(Modifier.height(6.dp))
                        Text("\"Not loaded\" means the rule targets an app that is not in the current selection, or was added after connecting.",
                            color = Muted.copy(.8f), fontSize = 9.sp, lineHeight = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectorRouteCard(
    title: String,
    subtitle: String,
    accent: Color,
    report: RouteReport?,
    loading: Boolean,
    lines: List<Pair<String, String>>
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(subtitle, color = Muted, fontSize = 9.5.sp)
                }
                if (loading) CircularProgressIndicator(color = accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                else report?.country?.takeIf { it.isNotBlank() }?.let { SmallChip(it, accent) }
            }
            Spacer(Modifier.height(9.dp))
            Text("PUBLIC IP", color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = .5.sp)
            Spacer(Modifier.height(2.dp))
            CompositionLtr {
                Text(
                    when {
                        report?.ip != null -> report.ip
                        report?.error != null -> "Unavailable: ${report.error}"
                        loading -> "Checking…"
                        else -> "—"
                    },
                    color = if (report?.ip != null) accent else Muted,
                    fontSize = if (report?.ip != null) 16.sp else 11.sp,
                    fontWeight = FontWeight.SemiBold, fontFamily = if (report?.ip != null) FontFamily.Monospace else UiFont,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
            lines.forEach { (label, value) ->
                Spacer(Modifier.height(8.dp))
                Text(label.uppercase(), color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = .5.sp)
                Spacer(Modifier.height(2.dp))
                Text(value, color = White.copy(.82f), fontSize = 10.5.sp, lineHeight = 14.sp)
            }
        }
    }
}

private const val ChartWindowMillis = 90_000L

private fun niceCeiling(value: Long): Long {
    var step = 1_000L
    while (true) {
        for (factor in longArrayOf(1, 2, 5)) if (step * factor >= value) return step * factor
        step *= 10
    }
}

@Composable
private fun LiveSpeedChart(points: List<TrafficPoint>) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) { now = System.currentTimeMillis(); delay(1_000) }
    }
    var hoverX by remember { mutableStateOf<Float?>(null) }
    var chartWidth by remember { mutableStateOf(0) }
    val visible = points.filter { now - it.atMillis in 0..ChartWindowMillis }
    val scaleMax = niceCeiling(visible.maxOfOrNull { maxOf(it.downloadBytesPerSecond, it.uploadBytesPerSecond) } ?: 0L)
    fun fraction(point: TrafficPoint) = 1f - (now - point.atMillis).toFloat() / ChartWindowMillis
    val hovered = hoverX?.takeIf { chartWidth > 0 }?.let { x ->
        visible.minByOrNull { kotlin.math.abs(fraction(it) * chartWidth - x) }
            ?.takeIf { kotlin.math.abs(fraction(it) * chartWidth - x) <= chartWidth / 30f }
    }

    GlassCard(Modifier.fillMaxWidth().height(196.dp)) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("LIVE SPEED - LAST 90 SECONDS", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (hovered == null) {
                    StatusDot(true)
                    Spacer(Modifier.width(5.dp))
                    Text("Download", color = Muted, fontSize = 9.sp)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Violet2))
                    Spacer(Modifier.width(5.dp))
                    Text("Upload", color = Muted, fontSize = 9.sp)
                } else {
                    val secondsAgo = ((now - hovered.atMillis) / 1_000).coerceAtLeast(0)
                    Text(if (secondsAgo == 0L) "now" else "$secondsAgo s ago", color = Muted, fontSize = 9.sp)
                    Spacer(Modifier.width(10.dp))
                    Text("↓ ${formatRate(hovered.downloadBytesPerSecond)}", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Text("↑ ${formatRate(hovered.uploadBytesPerSecond)}", color = Violet2, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().weight(1f)) {
                Canvas(
                    Modifier.fillMaxSize()
                        .onSizeChanged { chartWidth = it.width }
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    hoverX = if (event.type == PointerEventType.Exit) null
                                        else event.changes.firstOrNull()?.position?.x
                                }
                            }
                        }
                ) {
                    val inset = 3.dp.toPx()
                    val plotHeight = size.height - inset * 2
                    fun x(point: TrafficPoint) = size.width * fraction(point)
                    fun y(value: Long) = inset + plotHeight * (1f - value.toFloat() / scaleMax)
                    for (line in 0..2) {
                        val gridY = inset + plotHeight * line / 2f
                        drawLine(NeutralOverlay.copy(.08f), Offset(0f, gridY), Offset(size.width, gridY), 1.dp.toPx())
                    }
                    if (visible.isEmpty()) return@Canvas
                    val runs = ArrayList<MutableList<TrafficPoint>>()
                    visible.forEach { point ->
                        val last = runs.lastOrNull()?.lastOrNull()
                        if (last == null || point.atMillis - last.atMillis > 3_500) runs += mutableListOf(point)
                        else runs.last() += point
                    }
                    fun drawSeries(color: Color, fill: Boolean, value: (TrafficPoint) -> Long) {
                        runs.forEach { run ->
                            if (run.size == 1) {
                                drawCircle(color, 2.dp.toPx(), Offset(x(run[0]), y(value(run[0]))))
                                return@forEach
                            }
                            val line = DrawPath().apply {
                                moveTo(x(run[0]), y(value(run[0])))
                                for (index in 1 until run.size) lineTo(x(run[index]), y(value(run[index])))
                            }
                            if (fill) {
                                val area = DrawPath().apply {
                                    addPath(line)
                                    lineTo(x(run.last()), y(0))
                                    lineTo(x(run.first()), y(0))
                                    close()
                                }
                                drawPath(area, color.copy(.10f))
                            }
                            drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                    drawSeries(Cyan, fill = true) { it.downloadBytesPerSecond }
                    drawSeries(Violet2, fill = false) { it.uploadBytesPerSecond }
                    hovered?.let { point ->
                        drawLine(NeutralOverlay.copy(.28f), Offset(x(point), 0f), Offset(x(point), size.height), 1.dp.toPx())
                        drawCircle(Cyan, 4.dp.toPx(), Offset(x(point), y(point.downloadBytesPerSecond)))
                        drawCircle(Violet2, 4.dp.toPx(), Offset(x(point), y(point.uploadBytesPerSecond)))
                    }
                }
                if (visible.isEmpty()) {
                    Text(
                        if (points.isEmpty()) "Connect to start recording speed" else "No traffic samples in the last 90 seconds",
                        color = Muted.copy(.8f), fontSize = 10.5.sp, modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    Text(formatRate(scaleMax), color = Muted.copy(.85f), fontSize = 8.5.sp,
                        modifier = Modifier.align(Alignment.TopStart).padding(start = 2.dp, top = 5.dp))
                }
                if (hovered != null && chartWidth > 0) {
                    val tooltipWidth = 132.dp
                    Column(
                        Modifier.offset {
                            val pointX = chartWidth * fraction(hovered)
                            val width = tooltipWidth.roundToPx()
                            val gap = 10.dp.roundToPx()
                            val left = if (pointX + gap + width <= chartWidth) pointX + gap else pointX - gap - width
                            IntOffset(left.toInt().coerceAtLeast(0), 0)
                        }.width(tooltipWidth).clip(RoundedCornerShape(10.dp))
                            .background(SurfaceHigh.copy(.96f))
                            .border(1.dp, NeutralOverlay.copy(.10f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 9.dp, vertical = 6.dp)
                    ) {
                        Text("Download  ${formatRate(hovered.downloadBytesPerSecond)}", color = Cyan, fontSize = 9.5.sp, maxLines = 1)
                        Text("Upload  ${formatRate(hovered.uploadBytesPerSecond)}", color = Violet2, fontSize = 9.5.sp, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("90 s ago", color = Muted.copy(.75f), fontSize = 8.5.sp)
                Spacer(Modifier.weight(1f))
                Text("45 s", color = Muted.copy(.75f), fontSize = 8.5.sp)
                Spacer(Modifier.weight(1f))
                Text("now", color = Muted.copy(.75f), fontSize = 8.5.sp)
            }
        }
    }
}

@Composable
private fun HistoryOverlay(controller: PerRouteController, close: () -> Unit) {
    var snapshot by remember { mutableStateOf(controller.trafficHistory()) }
    var points by remember { mutableStateOf(controller.trafficPoints()) }
    LaunchedEffect(Unit) {
        while (isActive) { snapshot = controller.trafficHistory(); points = controller.trafficPoints(); delay(1_000) }
    }
    OverlayShell("Traffic history", "Local byte totals only - destinations are never recorded", close) {
        Column(Modifier.fillMaxSize()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LiveStat(Modifier.weight(1f), "TODAY DOWNLOAD", formatBytes(snapshot.todayDownloadBytes()), Cyan)
                LiveStat(Modifier.weight(1f), "TODAY UPLOAD", formatBytes(snapshot.todayUploadBytes()), Violet2)
                LiveStat(Modifier.weight(1f), "MONTH DOWNLOAD", formatBytes(snapshot.monthDownloadBytes()), Cyan)
                LiveStat(Modifier.weight(1f), "MONTH UPLOAD", formatBytes(snapshot.monthUploadBytes()), Violet2)
                IconButtonGlass(Icons.Rounded.DeleteSweep, "Clear history") { controller.clearTrafficHistory(); snapshot = controller.trafficHistory() }
            }
            Spacer(Modifier.height(12.dp))
            LiveSpeedChart(points)
            Spacer(Modifier.height(12.dp))
            Text("THIS MONTH BY APPLICATION", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(snapshot.applications(), key = { it.applicationPath() }) { item ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(NeutralOverlay.copy(.035f)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        AppAvatar(fileName(item.applicationPath()))
                        Spacer(Modifier.width(10.dp))
                        Text(fileName(item.applicationPath()).removeSuffix(".exe"), color = White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text("↓ ${formatBytes(item.downloadBytes())}   ↑ ${formatBytes(item.uploadBytes())}", color = Muted, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun QrImportOverlay(
    controller: PerRouteController,
    hostWindow: Frame,
    notify: (ActionResult) -> Unit,
    close: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    fun execute(block: () -> ActionResult) {
        if (busy) return
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) { runCatching(block).getOrElse { ActionResult(false, it.message ?: it.javaClass.simpleName) } }
            busy = false; notify(result); if (result.ok) close()
        }
    }
    OverlayShell("Import QR", "Read a VLESS, VMess, Trojan, Shadowsocks, or subscription QR", close) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Rounded.QrCodeScanner, null, tint = Violet2, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(18.dp))
            Text(if (busy) "Reading QR code…" else "Choose a source", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(Icons.Rounded.Image, "Image file", compact = true) {
                    val dialog = FileDialog(hostWindow, "Choose QR image", FileDialog.LOAD).apply { isVisible = true }
                    dialog.file?.let { file -> execute { controller.importQrImage(Path.of(dialog.directory, file)) } }
                }
                SecondaryButton(Icons.Rounded.ContentPaste, "Clipboard", compact = true) { execute { controller.importQrClipboard() } }
                PrimaryButton("Camera", compact = true) { execute { controller.importQrCamera() } }
            }
            Spacer(Modifier.height(12.dp))
            Text("Camera capture waits up to 12 seconds and closes the camera immediately after detection.", color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun SettingsOverlay(
    controller: PerRouteController,
    currentMode: ConnectionMode,
    darkMode: Boolean,
    logs: List<String>,
    busy: Boolean,
    connected: Boolean,
    exitApp: () -> Unit,
    setBusy: (Boolean) -> Unit,
    onMode: (ConnectionMode) -> Unit,
    onTheme: (Boolean) -> Unit,
    notify: (ActionResult) -> Unit,
    close: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var environment by remember { mutableStateOf(EnvironmentState(false, false, false)) }
    var progress by remember { mutableStateOf("") }
    var autoFailover by remember { mutableStateOf(controller.autoFailover()) }
    var fastestOnConnect by remember { mutableStateOf(controller.fastestOnConnect()) }
    var autoSpeedTest by remember { mutableStateOf(controller.autoSpeedTest()) }
    var autoReconnect by remember { mutableStateOf(controller.autoReconnect()) }
    var killSwitch by remember { mutableStateOf(controller.killSwitch()) }
    var updateFeed by remember { mutableStateOf(controller.updateFeedUrl()) }
    var availableVersion by remember { mutableStateOf(controller.availableUpdateVersion()) }
    var updateDownloading by remember { mutableStateOf(controller.isUpdateDownloading()) }
    var updateProgress by remember { mutableStateOf(controller.updateDownloadProgress()) }
    var updateMessage by remember { mutableStateOf(controller.updateDownloadMessage()) }
    var downloadedUpdate by remember { mutableStateOf(controller.downloadedUpdatePath()) }
    LaunchedEffect(Unit) {
        environment = withContext(Dispatchers.IO) {
            runCatching { controller.environment() }.getOrDefault(EnvironmentState(false, false, false))
        }
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            availableVersion = controller.availableUpdateVersion()
            updateDownloading = controller.isUpdateDownloading()
            updateProgress = controller.updateDownloadProgress()
            updateMessage = controller.updateDownloadMessage()
            downloadedUpdate = controller.downloadedUpdatePath()
            delay(750)
        }
    }

    fun execute(block: ((String) -> Unit) -> ActionResult) {
        if (busy) return
        scope.launch {
            setBusy(true)
            progress = "Preparing…"
            try {
                val result = withContext(Dispatchers.IO) {
                    try {
                        block { update -> EventQueue.invokeLater { progress = update } }
                    } catch (error: Throwable) {
                        ActionResult(false, error.message ?: error.javaClass.simpleName)
                    }
                }
                environment = withContext(Dispatchers.IO) {
                    runCatching { controller.environment() }.getOrDefault(environment)
                }
                notify(result)
            } finally {
                progress = ""
                setBusy(false)
            }
        }
    }

    OverlayShell(title = "Settings", subtitle = "Appearance, connection mode, and system components", close = close) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                SectionLabel("APPEARANCE")
                Spacer(Modifier.height(9.dp))
                ThemeModeRow(darkMode = darkMode, onTheme = onTheme)
                Spacer(Modifier.height(16.dp))
                SectionLabel("CONNECTION MODE")
                Spacer(Modifier.height(9.dp))
                if (connected) {
                    Text("Disconnect before changing the connection mode.", color = Amber, fontSize = 10.sp)
                    Spacer(Modifier.height(8.dp))
                }
                ConnectionMode.values().filter { it != ConnectionMode.TUN_COMPATIBILITY }.forEach { mode ->
                    val enabled = !connected && (mode != ConnectionMode.NATIVE_PER_APP || environment.perAppReady)
                    ModeRow(mode, mode == currentMode, enabled) { if (!busy) onMode(mode) }
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(10.dp))
                SectionLabel("AUTOMATION")
                Spacer(Modifier.height(9.dp))
                FeatureToggleRow(
                    icon = Icons.Rounded.SwapHoriz,
                    title = "Auto failover",
                    detail = "After three failed checks, switch to the fastest reachable server",
                    checked = autoFailover
                ) {
                    autoFailover = !autoFailover
                    controller.setAutoFailover(autoFailover)
                }
                Spacer(Modifier.height(8.dp))
                FeatureToggleRow(
                    icon = Icons.Rounded.Refresh,
                    title = "Auto reconnect",
                    detail = "Restore the tunnel after sleep, network changes, or an unexpected core stop",
                    checked = autoReconnect
                ) {
                    autoReconnect = !autoReconnect
                    controller.setAutoReconnect(autoReconnect)
                }
                Spacer(Modifier.height(8.dp))
                FeatureToggleRow(
                    icon = Icons.Rounded.Shield,
                    title = "Per-app kill switch",
                    detail = "Keep selected Smart apps blocked whenever their tunnel is unavailable",
                    checked = killSwitch
                ) {
                    killSwitch = !killSwitch
                    controller.setKillSwitch(killSwitch)
                }
                Spacer(Modifier.height(8.dp))
                FeatureToggleRow(
                    icon = Icons.Rounded.Speed,
                    title = "Fastest server on connect",
                    detail = "Measure reachable servers and choose the lowest latency before connecting",
                    checked = fastestOnConnect
                ) {
                    fastestOnConnect = !fastestOnConnect
                    controller.setFastestOnConnect(fastestOnConnect)
                }
                Spacer(Modifier.height(8.dp))
                FeatureToggleRow(
                    icon = Icons.Rounded.Download,
                    title = "Automatic speed test",
                    detail = "After pinging, measure download speed of the 10 best servers (up to 10 MB each)",
                    checked = autoSpeedTest
                ) {
                    autoSpeedTest = !autoSpeedTest
                    controller.setAutoSpeedTest(autoSpeedTest)
                }
                Spacer(Modifier.height(16.dp))
                SectionLabel("SECURE UPDATES")
                Spacer(Modifier.height(9.dp))
                ModernInput(
                    value = updateFeed,
                    onValueChange = {
                        updateFeed = it
                        controller.setUpdateFeedUrl(it)
                    },
                    hint = "Signed HTTPS manifest URL",
                    icon = Icons.Rounded.Link
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(Icons.Rounded.Refresh, if (busy) "Checking…" else "Check for updates", compact = true) {
                        execute { _ -> controller.checkForUpdate() }
                    }
                    if (availableVersion != null || updateDownloading || downloadedUpdate != null) {
                        PrimaryButton(
                            when {
                                downloadedUpdate != null -> "Install update"
                                updateDownloading -> "Downloading $updateProgress%"
                                else -> "Download $availableVersion"
                            },
                            compact = true
                        ) {
                            if (downloadedUpdate != null) {
                                val result = controller.launchDownloadedUpdate()
                                notify(result)
                                if (result.ok) exitApp()
                            } else {
                                notify(controller.downloadAvailableUpdateInBackground())
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        updateDownloading -> "Downloading in the background. You can close Settings or minimize PerRoute."
                        updateMessage.isNotBlank() -> updateMessage
                        else -> "Manifests must be signed with PerRoute's offline Ed25519 release key; downloads are SHA-256 verified."
                    },
                    color = if (updateDownloading) Cyan else Muted,
                    fontSize = 9.5.sp,
                    lineHeight = 13.sp
                )
                Spacer(Modifier.height(16.dp))
                SectionLabel("SYSTEM COMPONENTS")
                Spacer(Modifier.height(9.dp))
                EnvironmentRow(Icons.Rounded.Dns, "Xray core", environment.coreReady, "Connection and protocol engine") {
                    execute { update -> controller.installCore(update) }
                }
                Spacer(Modifier.height(8.dp))
                EnvironmentRow(
                    Icons.Rounded.Shield,
                    "Live per-app driver",
                    environment.perAppReady,
                    "Installed automatically by the Full Installer for arbitrary applications",
                    install = null
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "The Full Installer provisions the per-app driver with one UAC approval. After that, PerRoute connects normally without asking for Administrator access. The portable build can reuse an existing driver installation.",
                    color = Muted,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )
                if (progress.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(progress, color = Amber, fontSize = 11.sp)
                }

                Spacer(Modifier.height(18.dp))
                Text(
                    "Created by Ali Mirzad",
                    color = Violet2.copy(.78f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = .35.sp
                )
            }
            GlassCard(Modifier.width(350.dp).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Terminal, null, tint = Cyan, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Activity log", color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        SmallIconButtonGlass(Icons.Rounded.ContentCopy, "Copy all logs") {
                            clipboard.setText(AnnotatedString(logs.takeLast(200).joinToString("\n")))
                            notify(ActionResult(true, "Activity log copied."))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Select any text and press Ctrl+C, or copy everything.", color = Muted, fontSize = 9.sp)
                    Spacer(Modifier.height(9.dp))
                    CompositionLtr {
                        BasicTextField(
                            value = logs.takeLast(200).joinToString("\n"),
                            onValueChange = {},
                            readOnly = true,
                            textStyle = TextStyle(color = White.copy(.72f), fontSize = 9.5.sp, fontFamily = FontFamily.Monospace),
                            cursorBrush = SolidColor(Cyan),
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(15.dp))
                                .background(NeutralOverlay.copy(if (ActivePalette.isDark) .24f else .045f))
                                .verticalScroll(rememberScrollState()).padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayShell(title: String, subtitle: String, close: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(.67f)).padding(28.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize().widthIn(max = 980.dp).heightIn(max = 630.dp)
                .shadow(38.dp, GlassShape)
                .clip(GlassShape)
                .background(Surface.copy(.98f))
                .border(1.dp, if (ActivePalette.isDark) Color.White.copy(.10f) else Color.Black.copy(.08f), GlassShape)
        ) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(subtitle, color = Muted, fontSize = 11.sp)
                    }
                    IconButtonGlass(Icons.Rounded.Close, "Close", close)
                }
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }
}

@Composable
private fun AppRow(
    name: String,
    path: String,
    selected: Boolean,
    supportLabel: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else .48f).clip(RoundedCornerShape(17.dp))
            .background(if (selected) Violet.copy(.13f) else NeutralOverlay.copy(.035f))
            .border(1.dp, if (selected) Violet.copy(.34f) else NeutralOverlay.copy(.055f), RoundedCornerShape(17.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppAvatar(name)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name.removeSuffix(".exe"), color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            CompositionLtr { Text(path, color = Muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        supportLabel?.let { label ->
            Text(
                label,
                color = if (enabled) Cyan else Amber,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .background((if (enabled) Cyan else Amber).copy(.09f))
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            )
            Spacer(Modifier.width(9.dp))
        }
        CheckBox(selected)
    }
}

@Composable
private fun SelectedAppChip(path: String, remove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(NeutralOverlay.copy(.05f))
            .padding(start = 10.dp, end = 7.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(fileName(path).removeSuffix(".exe"), color = White.copy(.82f), fontSize = 11.sp,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Rounded.Close, null, tint = Muted, modifier = Modifier.size(16.dp).clickable(onClick = remove))
    }
}

@Composable
private fun ServerRow(
    node: ProxyNode,
    source: String,
    tag: String,
    latencyMs: Long?,
    pinging: Boolean,
    speed: Long?,
    speedTesting: Boolean,
    onSpeed: () -> Unit,
    selected: Boolean,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onSelect: () -> Unit,
    onCopy: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp))
            .background(if (selected) Violet.copy(.13f) else NeutralOverlay.copy(.035f))
            .border(1.dp, if (selected) Violet2.copy(.38f) else NeutralOverlay.copy(.055f), RoundedCornerShape(17.dp))
            .clickable(onClick = onSelect).padding(horizontal = 15.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(Violet.copy(.15f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Public, null, tint = Violet2, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(PerRouteController.cleanName(node.name()), color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            CompositionLtr { Text("${node.protocol().name}  •  ${node.endpoint()}  •  $source", color = Muted, fontSize = 10.sp) }
        }
        Spacer(Modifier.width(6.dp))
        if (tag.isNotBlank()) SmallChip(tag, Violet2)
        if (tag.isNotBlank()) Spacer(Modifier.width(6.dp))
        LatencyChip(latencyMs, pinging)
        Spacer(Modifier.width(6.dp))
        SpeedChip(speed, speedTesting, onSpeed)
        Spacer(Modifier.width(8.dp))
        if (selected) SmallChip("Active", Cyan)
        Spacer(Modifier.width(8.dp))
        SmallIconButtonGlass(
            if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            "Favorite",
            tint = if (favorite) Amber else Muted,
            onClick = onFavorite
        )
        Spacer(Modifier.width(4.dp))
        SmallIconButtonGlass(Icons.Rounded.ContentCopy, "Copy config", onCopy)
        onDelete?.let {
            Spacer(Modifier.width(4.dp))
            SmallIconButtonGlass(Icons.Rounded.DeleteOutline, "Remove server", it)
        }
    }
}

@Composable
private fun LatencyChip(latencyMs: Long?, pinging: Boolean) {
    val (label, color) = when {
        pinging -> "…" to Muted
        latencyMs == null -> "—" to Muted
        latencyMs < 0 -> "Offline" to Red
        latencyMs <= 150 -> "${latencyMs} ms" to Cyan
        latencyMs <= 350 -> "${latencyMs} ms" to Amber
        else -> "${latencyMs} ms" to Red
    }
    Text(
        label,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(10.dp))
            .background(color.copy(.10f))
            .padding(horizontal = 9.dp, vertical = 6.dp)
    )
}

@Composable
private fun SpeedChip(bytesPerSecond: Long?, testing: Boolean, onClick: () -> Unit) {
    val megabits = bytesPerSecond?.takeIf { it >= 0 }?.let { it * 8 / 1_000_000.0 }
    val (label, color) = when {
        testing -> "Testing…" to Amber
        bytesPerSecond == null -> "Speed" to Muted
        megabits == null -> "Failed" to Red
        megabits >= 20 -> formatMegabits(megabits) to Cyan
        megabits >= 5 -> formatMegabits(megabits) to Amber
        else -> formatMegabits(megabits) to Red
    }
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(.10f))
            .clickable(enabled = !testing, onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Download, "Test download speed", tint = color, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

private fun formatMegabits(value: Double): String =
    if (value >= 100) "${value.toInt()} Mbps" else String.format(java.util.Locale.ROOT, "%.1f Mbps", value)

private fun Modifier.wheelAndDragScroll(state: ScrollState): Modifier = this
    .pointerInput(state) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Scroll) continue
                val change = event.changes.firstOrNull() ?: continue
                if (change.isConsumed) continue
                val delta = if (change.scrollDelta.x != 0f) change.scrollDelta.x else change.scrollDelta.y
                state.dispatchRawDelta(delta * 48.dp.toPx())
                change.consume()
            }
        }
    }
    .pointerInput(state) {
        detectHorizontalDragGestures { change, dragAmount ->
            change.consume()
            state.dispatchRawDelta(-dragAmount)
        }
    }

@Composable
private fun SourceFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Color.White else Muted,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(11.dp))
            .background(if (selected) Violet else if (ActivePalette.isDark) Color.White.copy(.05f) else Color.Black.copy(.045f))
            .border(1.dp, if (selected) Violet2.copy(.55f) else Color.Transparent, RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp)
    )
}

@Composable
private fun SubscriptionFilterChip(label: String, selected: Boolean, onClick: () -> Unit, onCopy: () -> Unit) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(11.dp))
            .background(if (selected) Violet else if (ActivePalette.isDark) Color.White.copy(.05f) else Color.Black.copy(.045f))
            .border(1.dp, if (selected) Violet2.copy(.55f) else Color.Transparent, RoundedCornerShape(11.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = if (selected) Color.White else Muted,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.clickable(onClick = onClick).padding(start = 11.dp, end = 7.dp, top = 7.dp, bottom = 7.dp)
        )
        Icon(
            Icons.Rounded.ContentCopy,
            "Copy subscription URL",
            tint = if (selected) Color.White.copy(.82f) else Muted,
            modifier = Modifier.padding(end = 8.dp).size(14.dp).clickable(onClick = onCopy)
        )
    }
}

@Composable
private fun ModeRow(mode: ConnectionMode, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val (title, detail) = when (mode) {
        ConnectionMode.NATIVE_PER_APP -> "Smart per-app" to "Routes only the applications selected on the main screen"
        ConnectionMode.BROWSER_PROXY -> "Local proxy" to "Manual SOCKS5 and HTTP ports for apps with proxy settings"
        ConnectionMode.SYSTEM_PROXY -> "System proxy" to "No admin; applies to apps that follow Windows proxy settings"
        ConnectionMode.TUN_COMPATIBILITY -> "TUN compatibility" to "Broad compatibility; requires Administrator"
    }
    Row(
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else .48f).clip(RoundedCornerShape(16.dp))
            .background(if (selected) Violet.copy(.13f) else NeutralOverlay.copy(.035f))
            .border(1.dp, if (selected) Violet.copy(.32f) else NeutralOverlay.copy(.05f), RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) Violet.copy(.2f) else NeutralOverlay.copy(.05f)),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Tune, null, tint = if (selected) Violet2 else Muted, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Muted, fontSize = 10.sp)
        }
        CheckBox(selected)
    }
}

@Composable
private fun FeatureToggleRow(
    icon: ImageVector,
    title: String,
    detail: String,
    checked: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (checked) Cyan.copy(.09f) else NeutralOverlay.copy(.035f))
            .border(1.dp, if (checked) Cyan.copy(.25f) else NeutralOverlay.copy(.05f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(if (checked) Cyan.copy(.15f) else NeutralOverlay.copy(.05f)),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (checked) Cyan else Muted, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Muted, fontSize = 9.5.sp, lineHeight = 13.sp)
        }
        CheckBox(checked)
    }
}

@Composable
private fun ThemeModeRow(darkMode: Boolean, onTheme: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (ActivePalette.isDark) Color.White.copy(.035f) else Color.Black.copy(.035f))
            .border(1.dp, if (ActivePalette.isDark) Color.White.copy(.06f) else Color.Black.copy(.06f), RoundedCornerShape(16.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ThemeChoice(
            modifier = Modifier.weight(1f),
            icon = Icons.Rounded.LightMode,
            label = "Light",
            selected = !darkMode,
            onClick = { onTheme(false) }
        )
        ThemeChoice(
            modifier = Modifier.weight(1f),
            icon = Icons.Rounded.DarkMode,
            label = "Dark",
            selected = darkMode,
            onClick = { onTheme(true) }
        )
    }
}

@Composable
private fun ThemeChoice(modifier: Modifier, icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier.height(40.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) Violet.copy(.16f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = if (selected) Violet2 else Muted, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, color = if (selected) White else Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EnvironmentRow(icon: ImageVector, title: String, ready: Boolean, detail: String, install: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(NeutralOverlay.copy(.035f)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (ready) Cyan else Amber, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Muted, fontSize = 9.5.sp)
        }
        if (ready) SmallChip("Ready", Cyan)
        else if (install != null) Text("Install", color = Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = install).padding(8.dp))
        else SmallChip("No-admin", Muted)
    }
}

@Composable
private fun ModernInput(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    icon: ImageVector,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    Row(
        modifier.height(46.dp).clip(RoundedCornerShape(15.dp)).background(NeutralOverlay.copy(.045f))
            .border(1.dp, NeutralOverlay.copy(.07f), RoundedCornerShape(15.dp)).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(9.dp))
        BasicTextField(
            value, onValueChange, singleLine = true,
            textStyle = TextStyle(color = White, fontSize = 12.sp, fontFamily = UiFont),
            cursorBrush = SolidColor(Violet2),
            modifier = Modifier.weight(1f),
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, color = Muted.copy(.7f), fontSize = 11.sp); inner() } }
        )
    }
}

@Composable
private fun ModernTextArea(value: String, onValueChange: (String) -> Unit, hint: String) {
    BasicTextField(
        value, onValueChange,
        textStyle = TextStyle(color = White, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(Violet2),
        modifier = Modifier.fillMaxWidth().height(95.dp).clip(RoundedCornerShape(14.dp))
            .background(NeutralOverlay.copy(if (ActivePalette.isDark) .2f else .045f))
            .border(1.dp, NeutralOverlay.copy(.07f), RoundedCornerShape(14.dp)).padding(12.dp),
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, color = Muted.copy(.6f), fontSize = 10.sp); inner() } }
    )
}

@Composable
private fun GlassCard(modifier: Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val interaction = if (onClick == null) modifier else modifier.clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
    Box(
        interaction.clip(GlassShape).background(if (ActivePalette.isDark) Color.White.copy(.055f) else Color.White.copy(.72f))
            .border(1.dp, if (ActivePalette.isDark) Color.White.copy(.09f) else Color.Black.copy(.07f), GlassShape)
    ) { content() }
}

@Composable
private fun JetBrainsConnectButton(
    connected: Boolean,
    busy: Boolean,
    scale: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(218.dp)
) {
    val shape = RoundedCornerShape(11.dp)
    val colors = if (connected) {
        listOf(Color(0xFF0B9B83), Color(0xFF087765))
    } else {
        listOf(Color(0xFF6B57D9), Color(0xFF8A5CE6))
    }
    Row(
        modifier.height(52.dp).scale(scale)
            .shadow(if (connected) 16.dp else 10.dp, shape, ambientColor = colors.first().copy(.34f), spotColor = colors.last().copy(.4f))
            .clip(shape).background(Brush.horizontalGradient(colors))
            .border(1.dp, Color.White.copy(.18f), shape)
            .clickable(enabled = !busy, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (busy) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(19.dp))
        } else {
            Icon(Icons.Rounded.PowerSettingsNew, null, tint = Color.White, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            when {
                busy && connected -> "Disconnecting…"
                busy -> "Connecting…"
                connected -> "Disconnect"
                else -> "Connect"
            },
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = .1.sp
        )
    }
}

@Composable
private fun JetBrainsIconButton(icon: ImageVector, hint: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    Box(
        Modifier.size(38.dp).clip(shape)
            .background(if (ActivePalette.isDark) Color(0xFF2B2D30) else Color(0xFFF0F1F3))
            .border(1.dp, if (ActivePalette.isDark) Color.White.copy(.11f) else Color.Black.copy(.10f), shape)
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, hint, tint = if (ActivePalette.isDark) Color(0xFFD7D8DA) else Color(0xFF303237), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun PrimaryButton(label: String, compact: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.then(if (compact) Modifier.height(46.dp) else Modifier.fillMaxWidth().height(48.dp))
            .clip(RoundedCornerShape(15.dp))
            .background(Brush.horizontalGradient(listOf(Violet, Color(0xFF9B65E9))))
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = if (compact) 19.dp else 12.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}

@Composable
private fun SecondaryButton(
    icon: ImageVector,
    label: String,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier.then(if (compact) Modifier.height(46.dp) else Modifier.fillMaxWidth().height(46.dp))
            .clip(RoundedCornerShape(15.dp)).background(NeutralOverlay.copy(.055f))
            .border(1.dp, NeutralOverlay.copy(.08f), RoundedCornerShape(15.dp))
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = Muted, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = White.copy(.82f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun IconButtonGlass(icon: ImageVector, hint: String, onClick: () -> Unit) {
    Row(
        Modifier.height(40.dp).clip(RoundedCornerShape(13.dp)).background(NeutralOverlay.copy(.05f))
            .border(1.dp, NeutralOverlay.copy(.075f), RoundedCornerShape(13.dp))
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, hint, tint = White.copy(.78f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(hint, color = White.copy(.72f), fontSize = 11.sp)
    }
}

@Composable
private fun SmallIconButtonGlass(icon: ImageVector, hint: String, onClick: () -> Unit) =
    SmallIconButtonGlass(icon, hint, Muted, onClick)

@Composable
private fun SmallIconButtonGlass(icon: ImageVector, hint: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(NeutralOverlay.copy(.045f))
            .border(1.dp, NeutralOverlay.copy(.065f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, hint, tint = tint, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun AppAvatar(name: String) {
    val colors = listOf(Cyan, Violet2, Amber, Color(0xFFFF7C8E), Color(0xFF79A9FF))
    val color = colors[kotlin.math.abs(name.hashCode()) % colors.size]
    Box(Modifier.size(39.dp).clip(RoundedCornerShape(13.dp)).background(color.copy(.14f)), contentAlignment = Alignment.Center) {
        Text(name.firstOrNull()?.uppercase() ?: "A", color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CheckBox(selected: Boolean) {
    Box(
        Modifier.size(21.dp).clip(RoundedCornerShape(7.dp))
            .background(if (selected) Violet else NeutralOverlay.copy(.05f))
            .border(1.dp, if (selected) Violet2 else NeutralOverlay.copy(.13f), RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center
    ) { if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
}

@Composable
private fun SmallChip(label: String, color: Color) {
    Text(
        label, color = color, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(color.copy(.1f)).padding(horizontal = 9.dp, vertical = 5.dp)
    )
}

@Composable
private fun StatusDot(active: Boolean) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(if (active) Cyan else Color(0xFF596174)))
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
}

@Composable
private fun CompositionRtl(content: @Composable () -> Unit) =
    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, content = content)

@Composable
private fun CompositionLtr(content: @Composable () -> Unit) =
    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)

private fun fileName(path: String): String = runCatching { Path.of(path).fileName.toString() }.getOrDefault(path)

private fun countryLabel(node: ProxyNode): String {
    val name = PerRouteController.cleanName(node.name()).trim()
    val codePoints = name.codePoints().limit(2).toArray()
    if (codePoints.size == 2 && codePoints.all { it in 0x1F1E6..0x1F1FF }) {
        return String(codePoints, 0, codePoints.size)
    }
    val suffix = node.address().substringAfterLast('.', "").uppercase()
    return suffix.takeIf { it.length == 2 && it.all(Char::isLetter) } ?: ""
}
