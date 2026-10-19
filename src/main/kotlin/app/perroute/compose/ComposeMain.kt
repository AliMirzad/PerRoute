package app.perroute.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Web
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.perroute.model.AppRoutes
import app.perroute.model.ProxyNode
import app.perroute.persistence.AppStateStore
import app.perroute.service.AppDirectories
import app.perroute.service.RoutePlanner
import app.perroute.service.SingleInstanceLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Frame
import java.awt.GraphicsEnvironment
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
import java.awt.geom.RoundRectangle2D
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import javax.imageio.ImageIO

internal const val Slogan = "Your apps. Your routes."
internal const val SloganSubtitle = "Choose a route for each app. Keep the rest direct."
internal const val Credit = "Created By Vexor"
private const val WindowCornerRadius = 16.0

internal enum class Tab(val label: String, val icon: ImageVector) {
    Connection("Connection", Icons.Rounded.Link),
    Apps("Apps", Icons.Rounded.Apps),
    Servers("Servers", Icons.Rounded.Storage),
    Rules("Rules", Icons.Rounded.Description),
    Profiles("Profiles", Icons.Rounded.Folder),
    Workspaces("Workspaces", Icons.Rounded.Web),
    Diagnostics("Diagnostics", Icons.Rounded.MonitorHeart),
    Health("Health", Icons.Rounded.HealthAndSafety),
    Traffic("Traffic", Icons.Rounded.BarChart),
    Settings("Settings", Icons.Rounded.Settings);

    val isTool: Boolean get() = this != Connection && this != Apps && this != Servers && this != Settings
}

internal class DialogSpec(val onDismiss: () -> Unit, val content: @Composable () -> Unit)

internal class AppsDraft {
    val selected = mutableStateListOf<String>()
    var routes by mutableStateOf(mapOf<String, String>())
    var advanced by mutableStateOf(false)
    var auto by mutableStateOf(setOf<String>())
    var pools by mutableStateOf(mapOf<String, String>())
    var manualPending by mutableStateOf(setOf<String>())
    var focused by mutableStateOf<String?>(null)

    fun load(shell: Shell) {
        selected.clear(); selected.addAll(shell.apps)
        routes = shell.appRoutes
        advanced = shell.advanced
        auto = shell.controller.autoApps()
        pools = shell.controller.autoPools()
        manualPending = emptySet()
        if (focused != null && selected.none { it.equals(focused, true) }) focused = null
    }

    fun dirty(shell: Shell): Boolean =
        advanced != shell.advanced || routes != shell.appRoutes || auto != shell.controller.autoApps() ||
            pools != shell.controller.autoPools() || manualPending.isNotEmpty() ||
            selected.map { it.lowercase() } != shell.apps.map { it.lowercase() }

    fun mode(path: String): app.perroute.routing.AppRouteMode {
        val key = AppRoutes.normalize(path)
        return when {
            key in auto -> app.perroute.routing.AppRouteMode.AUTO
            routes.containsKey(key) || key in manualPending -> app.perroute.routing.AppRouteMode.MANUAL
            else -> app.perroute.routing.AppRouteMode.MAIN
        }
    }

    fun setMode(path: String, mode: app.perroute.routing.AppRouteMode) {
        val key = AppRoutes.normalize(path)
        when (mode) {
            app.perroute.routing.AppRouteMode.MAIN -> { auto = auto - key; routes = routes - key; manualPending = manualPending - key }
            app.perroute.routing.AppRouteMode.MANUAL -> { auto = auto - key; if (!routes.containsKey(key)) manualPending = manualPending + key }
            app.perroute.routing.AppRouteMode.AUTO -> { auto = auto + key; manualPending = manualPending - key }
        }
    }

    fun setPool(path: String, pool: app.perroute.routing.AutoCandidatePool) {
        val key = AppRoutes.normalize(path)
        pools = if (pool.kind() == app.perroute.routing.AutoCandidatePool.Kind.ALL) pools - key else pools + (key to pool.encode())
    }

    fun isSelected(path: String) = selected.any { it.equals(path, true) }

    fun toggle(path: String) {
        if (isSelected(path)) selected.removeAll { it.equals(path, true) } else selected += path
    }

    fun assign(path: String, nodeKey: String?) {
        val key = AppRoutes.normalize(path)
        routes = if (nodeKey == null) routes - key else routes + (key to nodeKey)
        manualPending = manualPending - key
    }
}

internal class Shell(
    val controller: PerRouteController,
    val hostWindow: Frame?,
    val scope: CoroutineScope,
    val exitApp: () -> Unit
) {
    val nodes = mutableStateListOf<ProxyNode>().also { it.addAll(controller.nodes()) }
    val apps = mutableStateListOf<String>().also { it.addAll(controller.applications()) }
    val logs = mutableStateListOf<String>()
    var selectedNodeKey by mutableStateOf(controller.selectedNodeKey())
    var mode by mutableStateOf(controller.connectionMode())
    var advanced by mutableStateOf(controller.advancedRouting())
    var appRoutes by mutableStateOf(controller.appRoutes())
    var plan by mutableStateOf(controller.routePlan())
    var connected by mutableStateOf(controller.isConnected())
    var phase by mutableStateOf(controller.connectionPhase())
    var traffic by mutableStateOf(controller.trafficSnapshot())
    var routeChecks by mutableStateOf(controller.routeChecks())
    var activeRoutes by mutableStateOf(controller.activeRoutes())
    var routeHealth by mutableStateOf(controller.routeHealth())
    var busy by mutableStateOf(false)
    var tab by mutableStateOf(Tab.Connection)
    var toast by mutableStateOf(controller.takeStartupProblem())
    var toastError by mutableStateOf(toast != null)
    var dialog by mutableStateOf<DialogSpec?>(null)
    var settingsCategory by mutableStateOf(SettingsCategory.General)
    var diagnosticsTab by mutableStateOf(DiagnosticsTab.Routes)
    var addServersRequested by mutableStateOf(false)
    val draft = AppsDraft().also { it.load(this) }

    private val screenState = HashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> screen(key: String, create: () -> T): T = screenState.getOrPut(key, create) as T

    private fun forget(prefix: String) { screenState.keys.removeAll { it.startsWith(prefix) } }

    val mainNode: ProxyNode? get() = nodes.firstOrNull { it.key() == selectedNodeKey } ?: nodes.firstOrNull()
    val locked: Boolean get() = connected || phase != ConnectionPhase.DISCONNECTED
    val perApp: Boolean get() = mode == app.perroute.model.ConnectionMode.NATIVE_PER_APP

    fun refresh() {
        nodes.clear(); nodes.addAll(controller.nodes())
        apps.clear(); apps.addAll(controller.applications())
        selectedNodeKey = controller.selectedNodeKey()
        mode = controller.connectionMode()
        advanced = controller.advancedRouting()
        appRoutes = controller.appRoutes()
        plan = controller.routePlan()
        poll()
    }

    fun poll() {
        connected = controller.isConnected()
        phase = controller.connectionPhase()
        traffic = controller.trafficSnapshot()
        routeChecks = controller.routeChecks()
        activeRoutes = controller.activeRoutes()
        routeHealth = controller.routeHealth()
    }

    fun notify(result: ActionResult) { toast = result.message; toastError = !result.ok }

    fun run(silent: Boolean = false, done: (ActionResult) -> Unit = {}, action: () -> ActionResult) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) {
                    try { action() } catch (error: Throwable) { ActionResult(false, error.message ?: error.javaClass.simpleName) }
                }
                runCatching { refresh() }
                if (!silent || !result.ok) notify(result)
                done(result)
            } finally {
                busy = false
            }
        }
    }

    fun toggleConnection() {
        if (busy) return
        phase = if (connected) ConnectionPhase.DISCONNECTING else ConnectionPhase.CONNECTING
        run { if (controller.isConnected()) controller.disconnect() else controller.connect() }
    }

    fun showDialog(onDismiss: () -> Unit = { dialog = null }, content: @Composable () -> Unit) {
        dialog = DialogSpec(onDismiss, content)
    }

    fun closeDialog() { dialog = null }

    fun saveDraft(then: () -> Unit = {}) {
        val paths = draft.selected.toList()
        val routes = draft.routes
        val advancedFlag = draft.advanced
        val auto = draft.auto
        val pools = draft.pools
        val pending = draft.manualPending.filter { key -> paths.any { AppRoutes.normalize(it) == key } }
        if (advancedFlag && pending.isNotEmpty()) {
            notify(ActionResult(false, "Choose a server for ${joinNames(pending.map { key -> appName(paths.first { AppRoutes.normalize(it) == key }) })} or pick Main or Auto, then save."))
            return
        }
        run(done = { result -> if (result.ok) { draft.load(this); then() } }) {
            controller.saveApplicationSetup(paths, routes, advancedFlag, auto, pools)
        }
    }

    fun discardDraft() { draft.load(this) }

    fun leaveApps(then: () -> Unit) {
        if (tab != Tab.Apps || !draft.dirty(this)) { then(); return }
        showDialog {
            DialogCard("Unsaved app changes", "Your selection or app routes have changed but are not saved yet.", ::closeDialog,
                width = 480.dp, actions = {
                    SecondaryButton("Keep editing") { closeDialog() }
                    SecondaryButton("Discard changes") { closeDialog(); discardDraft(); then() }
                    PrimaryButton("Save changes", enabled = !locked) { closeDialog(); saveDraft(then) }
                }) {
                Text(if (locked) "Disconnect to save app routes, or discard the changes." else "Save them before leaving, or discard them.",
                    color = White.copy(.85f), fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }

    fun navigate(target: Tab) {
        if (target == tab) return
        leaveApps {
            forget("${tab.name}:")
            if (target == Tab.Apps) draft.load(this)
            tab = target
        }
    }

    fun openSettings(category: SettingsCategory) { selectSettings(category); navigate(Tab.Settings) }

    fun selectSettings(category: SettingsCategory) {
        if (category != settingsCategory) forget("${Tab.Settings.name}:${settingsCategory.name}")
        settingsCategory = category
    }

    fun setUiStyle(style: UiStyle) {
        if (Appearance.style == style) return
        controller.setUiStyle(style.key)
        Appearance.style = style
    }

    fun setDarkMode(dark: Boolean) {
        controller.setDarkTheme(dark)
        Appearance.dark = dark
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        when (event.key) {
            Key.Tab, Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> KeyboardNavigation.value = true
            Key.Escape -> {
                if (OpenMenus.value > 0) return false
                dialog?.let { it.onDismiss(); return true }
                if (tab == Tab.Apps && draft.dirty(this)) {
                    discardDraft()
                    notify(ActionResult(true, "Unsaved app changes were discarded."))
                    return true
                }
            }
        }
        return false
    }
}

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

private fun initialWindowSize(): Pair<Dp, Dp> {
    val area = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }.getOrNull()
    val width = (area?.width?.times(.92)?.toInt() ?: 1180).coerceAtMost(1180).coerceAtLeast(MinWindowWidth)
    val height = (area?.height?.times(.92)?.toInt() ?: 790).coerceAtMost(790).coerceAtLeast(MinWindowHeight)
    return width.dp to height.dp
}

internal const val MinWindowWidth = 820
internal const val MinWindowHeight = 620

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
    runCatching { app.perroute.update.StartupHealth(dataDirectory.resolve("startup-health.json"))
        .beginStart(app.perroute.service.SecureUpdateService.CURRENT_VERSION, System.currentTimeMillis()) }
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
    controller.checkForUpdateInBackground()
    Appearance.load(controller)

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
        var trayPlan by remember { mutableStateOf(controller.routePlan()) }
        var trayChecks by remember { mutableStateOf(controller.routeChecks()) }
        var trayTemporaries by remember { mutableStateOf(controller.temporaryRoutes()) }
        var trayApps by remember { mutableStateOf(controller.applications()) }
        val shellHolder = remember { arrayOfNulls<Shell>(1) }
        val trayScope = rememberCoroutineScope()
        val (initialWidth, initialHeight) = remember { initialWindowSize() }
        val windowState = rememberWindowState(width = initialWidth, height = initialHeight, position = WindowPosition(Alignment.Center))
        fun exitPerRoute() {
            controller.close()
            lock.close()
            exitApplication()
        }
        fun hideMainWindow() {
            val shell = shellHolder[0]
            if (shell == null) windowVisible = false else shell.leaveApps { windowVisible = false }
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
                    shellHolder[0]?.refresh()
                }
            }
        }
        fun refreshQuickAccess() {
            trayNodes = controller.nodes()
            traySelectedKey = controller.selectedNodeKey()
            trayConnected = controller.isConnected()
            trayTraffic = controller.trafficSnapshot()
            trayPlan = controller.routePlan()
            trayChecks = controller.routeChecks()
            trayTemporaries = controller.temporaryRoutes()
            trayApps = controller.applications()
        }
        fun trayToast(result: ActionResult) {
            runCatching { nativeTrayIcon?.displayMessage("PerRoute", result.message, if (result.ok) TrayIcon.MessageType.INFO else TrayIcon.MessageType.WARNING) }
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
            onCloseRequest = { hideMainWindow() },
            state = windowState,
            title = "PerRoute",
            icon = painterResource("icons/perroute-icon-256.png"),
            undecorated = true,
            transparent = false,
            resizable = true,
            onPreviewKeyEvent = { event -> shellHolder[0]?.handleKey(event) ?: false }
        ) {
            LaunchedEffect(Unit) {
                mainWindow = window
                window.minimumSize = Dimension(MinWindowWidth, MinWindowHeight)
            }
            LaunchedEffect(Appearance.style, Appearance.dark) {
                window.background = if (Appearance.style == UiStyle.NEW) java.awt.Color(23, 25, 25)
                    else if (Appearance.dark) java.awt.Color(23, 23, 30) else java.awt.Color(247, 247, 250)
            }
            remember { Perf.mark("window.compose") }
            PerRouteWindow(
                controller = controller,
                hostWindow = window,
                exitApp = ::exitPerRoute,
                onShell = { shellHolder[0] = it },
                hideWindow = ::hideMainWindow,
                toggleMaximize = {
                    window.placement = if (window.placement == WindowPlacement.Maximized)
                        WindowPlacement.Floating else WindowPlacement.Maximized
                }
            )
        }
        val quickState = rememberWindowState(width = 390.dp, height = 640.dp)
        Window(
            visible = quickVisible,
            onCloseRequest = { quickVisible = false },
            state = quickState,
            title = "PerRoute Quick Access",
            icon = painterResource("icons/perroute-icon-256.png"),
            undecorated = true,
            transparent = false,
            resizable = false,
            alwaysOnTop = true,
            onPreviewKeyEvent = { event ->
                if (event.type == KeyEventType.KeyDown) when (event.key) {
                    Key.Tab, Key.DirectionUp, Key.DirectionDown -> KeyboardNavigation.value = true
                    Key.Escape -> quickVisible = false
                }
                false
            }
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
            PerRouteTheme {
                QuickAccessWindow(
                    connected = trayConnected,
                    busy = trayToggling,
                    working = trayBusy,
                    main = trayNodes.firstOrNull { it.key() == traySelectedKey } ?: trayNodes.firstOrNull(),
                    nodes = trayNodes,
                    favorites = trayNodes.filter { controller.isFavorite(it.key()) }.mapTo(hashSetOf()) { it.key() },
                    traffic = trayTraffic,
                    plan = trayPlan,
                    mainCheck = trayChecks[traySelectedKey],
                    onToggle = { trayAction(toggling = true) { if (controller.isConnected()) controller.disconnect() else controller.connect() } },
                    onFastest = { trayAction { controller.switchToFastest() } },
                    onServer = { key -> trayAction { controller.switchNode(key) } },
                    onOpen = { quickVisible = false; windowVisible = true },
                    onExit = { quickVisible = false; exitPerRoute() },
                    temporaries = trayTemporaries,
                    apps = trayApps,
                    perApp = controller.connectionMode() == app.perroute.model.ConnectionMode.NATIVE_PER_APP,
                    onTemporary = { path, key, minutes -> trayAction { controller.setTemporaryRoute(path, key, minutes).also { result -> trayToast(result) } } },
                    onEndTemporary = { path -> trayAction { controller.cancelTemporaryRoute(path) } }
                )
            }
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

internal class WindowChrome(val hostWindow: Frame?, val minimize: () -> Unit, val maximize: () -> Unit, val close: () -> Unit)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun PerRouteWindow(
    controller: PerRouteController,
    hostWindow: Frame?,
    exitApp: () -> Unit,
    onShell: (Shell) -> Unit = {},
    hideWindow: () -> Unit = {},
    toggleMaximize: () -> Unit = {},
    initialTab: Tab = Tab.Connection,
    prepare: (Shell) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val shell = remember {
        Shell(controller, hostWindow, scope, exitApp).also { it.tab = initialTab; prepare(it); onShell(it) }
    }
    val chrome = remember { WindowChrome(hostWindow, hideWindow, toggleMaximize, hideWindow) }
    val pendingLogs = remember { ConcurrentLinkedQueue<String>() }

    if (hostWindow != null) RoundedWindowEffect(hostWindow)

    LaunchedEffect(Unit) {
        if (!Perf.enabled) return@LaunchedEffect
        withFrameNanos { }
        Perf.mark("window.firstFrame")
        if (!Perf.bench) return@LaunchedEffect
        delay(3_000)
        Perf.mark("bench.start")
        Perf.value("servers.openMs", Perf.timeToFrame { shell.tab = Tab.Servers })
        Perf.frameGaps("servers.afterOpen", 4_000)
        val keystrokes = listOf("s", "se", "ser", "serv", "server 9", "server 99").map { text ->
            Perf.timeToFrame { Perf.command.value = "q:$text" }
        }
        Perf.value("search.keystrokeMs", "avg=${"%.1f".format(java.util.Locale.ROOT, keystrokes.average())} max=${"%.1f".format(java.util.Locale.ROOT, keystrokes.max())}")
        Perf.timeToFrame { Perf.command.value = "q:" }
        Perf.command.value = "scroll"
        Perf.frameGaps("servers.scroll", 3_000)
        Perf.value("servers.closeMs", Perf.timeToFrame { shell.tab = Tab.Connection })
        Perf.value("settings.openMs", Perf.timeToFrame { shell.tab = Tab.Settings })
        delay(400)
        Perf.value("history.openMs", Perf.timeToFrame { shell.tab = Tab.Traffic })
        delay(400)
        shell.tab = Tab.Connection
        delay(1_000)
        System.gc()
        delay(500)
        Perf.mark("bench.done")
    }

    LaunchedEffect(Unit) {
        if (hostWindow == null) return@LaunchedEffect
        val status = withContext(Dispatchers.IO) { controller.startupHealth() }
        val rollback = withContext(Dispatchers.IO) { controller.rollbackVersion() }
        if (status.struggling() && rollback != null) offerRollback(shell, status, rollback)
        else if (status.updatedFrom().isNotBlank() && System.currentTimeMillis() - status.updatedAt() < 120_000 && rollback != null) {
            shell.notify(ActionResult(true, "Updated from ${status.updatedFrom()}. If something does not work, Settings > Recovery can go back to $rollback."))
        }
        delay(10_000)
        withContext(Dispatchers.IO) { runCatching { controller.markStartupHealthy() } }
    }

    LaunchedEffect(Unit) {
        if (hostWindow == null) return@LaunchedEffect
        var offered: String? = null
        while (isActive) {
            delay(20_000)
            val ready = controller.updateReadyVersion() ?: continue
            if (ready == offered || shell.dialog != null) continue
            offered = ready
            offerUpdate(shell, ready)
        }
    }

    LaunchedEffect(shell.toast, shell.toastError) {
        val visibleMessage = shell.toast ?: return@LaunchedEffect
        delay(if (shell.toastError) 8_000 else 4_000)
        if (shell.toast == visibleMessage) shell.toast = null
    }

    DisposableEffect(Unit) {
        controller.onLog { line -> pendingLogs.offer(line) }
        controller.onConnectionChanged { scope.launch { shell.refresh() } }
        onDispose { }
    }

    LaunchedEffect(Unit) {
        var tick = 0
        while (isActive) {
            delay(250)
            var drained = 0
            while (drained < 100) {
                val line = pendingLogs.poll() ?: break
                shell.logs += line
                drained++
            }
            while (shell.logs.size > 200) shell.logs.removeAt(0)
            shell.connected = controller.isConnected()
            if (!shell.busy) shell.phase = controller.connectionPhase()
            shell.routeChecks = controller.routeChecks()
            shell.activeRoutes = controller.activeRoutes()
            if (++tick % 4 == 0) { shell.traffic = controller.trafficSnapshot(); shell.routeHealth = controller.routeHealth() }
        }
    }

    PerRouteTheme(Appearance.style, Appearance.dark) {
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) KeyboardNavigation.value = false
                    }
                }
            }
        ) {
            val dialogOpen = shell.dialog != null
            Box(Modifier.fillMaxSize().focusProperties { if (dialogOpen) enter = { FocusRequester.Cancel } }.focusGroup()) {
                when (Appearance.style) {
                    UiStyle.NEW -> NewWindow(shell, chrome)
                    UiStyle.SIMPLE -> SimpleWindow(shell, chrome)
                }
            }

            shell.dialog?.let { spec ->
                val dialogFocus = remember(spec) { FocusRequester() }
                LaunchedEffect(spec) { runCatching { dialogFocus.requestFocus() } }
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(if (IsDark) .62f else .38f))
                        .pointerInput(Unit) { detectTapGestures { } }
                        .focusRequester(dialogFocus).focusGroup().focusable(),
                    contentAlignment = Alignment.Center
                ) { spec.content() }
            }

            Toast(shell, Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp))
        }
    }
}

private fun offerUpdate(shell: Shell, version: String) {
    shell.showDialog {
        DialogCard("PerRoute $version is ready", "Downloaded and verified in the background.", shell::closeDialog, width = 480.dp,
            actions = {
                SecondaryButton("Later") { shell.closeDialog() }
                PrimaryButton("Restart and update") {
                    shell.closeDialog()
                    val result = shell.controller.launchDownloadedUpdate()
                    shell.notify(result)
                    if (result.ok) shell.exitApp()
                }
            }) {
            DialogText("PerRoute closes, Windows asks for Administrator approval once, and PerRoute $version opens by itself when it is done. Your servers, apps and settings are kept, and this version stays available for a rollback.")
            if (shell.connected) DialogText("Routed apps are disconnected for the moment the update takes.", Muted)
        }
    }
}

private fun offerRollback(shell: Shell, status: app.perroute.update.StartupHealth.Status, version: String) {
    shell.showDialog {
        DialogCard("PerRoute did not start properly", "PerRoute ${status.version()} did not finish starting ${status.failedStarts()} times in a row.", shell::closeDialog, width = 480.dp,
            actions = {
                SecondaryButton("Keep this version") { shell.closeDialog() }
                PrimaryButton("Go back to $version") {
                    shell.closeDialog()
                    shell.run(done = { result -> if (result.ok) shell.exitApp() }) { shell.controller.rollBack() }
                }
            }) {
            DialogText("The previous version, $version, is still on this computer. Going back keeps your servers, apps and settings. Windows asks for Administrator approval, PerRoute closes, and $version opens when it is done.")
            DialogText("If this message came after you closed PerRoute quickly, keep this version.", Muted)
        }
    }
}

@Composable
private fun Toast(shell: Shell, modifier: Modifier) {
    AnimatedVisibility(visible = shell.toast != null, modifier = modifier, enter = fadeIn(tween(90)), exit = fadeOut(tween(70))) {
        shell.toast?.let { message ->
            val error = shell.toastError
            val shape = RoundedCornerShape(if (Tok.isNew) 8.dp else 14.dp)
            val newStyle = Tok.isNew
            val back = when {
                newStyle -> NewColors.Charcoal
                IsDark -> if (error) Color(0xFF3A1C24) else Color(0xFF1B3328)
                else -> if (error) Color(0xFFFFEAEA) else Color(0xFFE6F6EC)
            }
            val accent = if (newStyle) (if (error) NewColors.Panel.danger else NewColors.Lime) else if (error) Red else Cyan
            val text = if (newStyle) NewColors.Panel.foreground else White
            Row(
                Modifier.padding(horizontal = 24.dp).widthIn(max = 640.dp)
                    .clip(shape).background(back)
                    .border(1.dp, accent.copy(.45f), shape)
                    .clickable(onClickLabel = "Dismiss message") { shell.toast = null }
                    .semantics { liveRegion = if (error) LiveRegionMode.Assertive else LiveRegionMode.Polite }
                    .padding(horizontal = 15.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(if (error) Icons.Rounded.Close else Icons.Rounded.Check, if (error) "Problem" else "Done",
                    tint = accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(message, color = text, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f, fill = false))
            }
        }
    }
}

@Composable
internal fun RoundedWindowEffect(hostWindow: Frame) {
    DisposableEffect(hostWindow) {
        fun applyShape() {
            runCatching {
                val maximized = hostWindow.extendedState and Frame.MAXIMIZED_BOTH != 0
                hostWindow.shape = if (maximized || hostWindow.width <= 0 || hostWindow.height <= 0) {
                    null
                } else {
                    RoundRectangle2D.Double(0.0, 0.0, hostWindow.width.toDouble(), hostWindow.height.toDouble(), WindowCornerRadius, WindowCornerRadius)
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
internal fun WindowDragArea(hostWindow: Frame?, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    val dragAnchors = remember { arrayOfNulls<Point>(2) }
    Box(
        modifier.pointerInput(hostWindow) {
            if (hostWindow == null) return@pointerInput
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
                    hostWindow.setLocation(windowStart.x + pointerNow.x - pointerStart.x, windowStart.y + pointerNow.y - pointerStart.y)
                }
            }
        },
        content = content
    )
}

private enum class WindowControl { MINIMIZE, RESTORE, CLOSE }

@Composable
internal fun WindowButtons(chrome: WindowChrome, tint: Color, height: Dp = 44.dp, width: Dp = 50.dp) {
    Row(Modifier.fillMaxHeight()) {
        WindowButton(WindowControl.MINIMIZE, "Minimize to the notification area", tint, height, width, chrome.minimize)
        WindowButton(WindowControl.RESTORE, "Maximize or restore", tint, height, width, chrome.maximize)
        WindowButton(WindowControl.CLOSE, "Close to the notification area", tint, height, width, chrome.close)
    }
}

@Composable
private fun WindowButton(control: WindowControl, label: String, tint: Color, height: Dp, width: Dp, onClick: () -> Unit) {
    Box(
        Modifier.width(width).height(height).focusRing(RoundedCornerShape(6.dp))
            .semantics { contentDescription = label }
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(15.dp)) {
            val stroke = 1.4.dp.toPx()
            when (control) {
                WindowControl.MINIMIZE -> drawLine(tint, Offset(size.width * .15f, size.height * .55f), Offset(size.width * .85f, size.height * .55f), stroke)
                WindowControl.CLOSE -> {
                    drawLine(tint, Offset(size.width * .15f, size.height * .15f), Offset(size.width * .85f, size.height * .85f), stroke)
                    drawLine(tint, Offset(size.width * .85f, size.height * .15f), Offset(size.width * .15f, size.height * .85f), stroke)
                }
                WindowControl.RESTORE -> drawRect(tint, Offset(size.width * .17f, size.height * .17f),
                    androidx.compose.ui.geometry.Size(size.width * .66f, size.height * .66f), style = Stroke(stroke))
            }
        }
    }
}

internal fun routeApplications(route: RoutePlanner.Route): String = joinNames(route.applications().map(::appName))
