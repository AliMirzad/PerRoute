package app.perroute.compose.ui.screens

import app.perroute.compose.Shell
import app.perroute.compose.Slogan
import app.perroute.compose.SloganSubtitle
import app.perroute.compose.Tab
import app.perroute.compose.controller.ActionResult
import app.perroute.compose.controller.EnvironmentState
import app.perroute.compose.ui.components.ActivePalette
import app.perroute.compose.ui.components.Amber
import app.perroute.compose.ui.components.Badge
import app.perroute.compose.ui.components.ConfirmDialog
import app.perroute.compose.ui.components.ControlShape
import app.perroute.compose.ui.components.Cyan
import app.perroute.compose.ui.components.DarkPalette
import app.perroute.compose.ui.components.EmptyState
import app.perroute.compose.ui.components.FieldLabel
import app.perroute.compose.ui.components.InfoBanner
import app.perroute.compose.ui.components.LightPalette
import app.perroute.compose.ui.components.LoadingState
import app.perroute.compose.ui.components.Muted
import app.perroute.compose.ui.components.NeutralOverlay
import app.perroute.compose.ui.components.PrimaryButton
import app.perroute.compose.ui.components.RadioMark
import app.perroute.compose.ui.components.Red
import app.perroute.compose.ui.components.RowShape
import app.perroute.compose.ui.components.ScreenPanel
import app.perroute.compose.ui.components.SecondaryButton
import app.perroute.compose.ui.components.Segmented
import app.perroute.compose.ui.components.TextInput
import app.perroute.compose.ui.components.ToggleSwitch
import app.perroute.compose.ui.components.Violet
import app.perroute.compose.ui.components.Violet2
import app.perroute.compose.ui.components.White
import app.perroute.compose.ui.components.focusRing
import app.perroute.compose.ui.components.plural

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.model.ConnectionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.EventQueue

/** The groups of the Settings screen. */
internal enum class SettingsCategory(val label: String, val icon: ImageVector) {
    General("General", Icons.Rounded.Tune),
    Connection("Connection", Icons.Rounded.Wifi),
    Routing("Routing", Icons.Rounded.AltRoute),
    Automation("Automation", Icons.Rounded.Sync),
    Updates("Updates", Icons.Rounded.SystemUpdateAlt),
    Components("Components", Icons.Rounded.Inventory2),
    Backup("Backup & Restore", Icons.Rounded.Backup),
    ActivityLog("Activity log", Icons.Rounded.Article)
}

/** A category rail on the left and the chosen group on the right. Each option lives in exactly one group. */
@Composable
internal fun SettingsScreen(shell: Shell) {
    ScreenPanel("Settings", "Preferences, connection behavior, and maintenance.") {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val railWidth = if (maxWidth >= 780.dp) 176.dp else 148.dp
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.width(railWidth).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SettingsCategory.entries.forEach { category ->
                        val selected = shell.settingsCategory == category
                        Row(
                            Modifier.fillMaxWidth().height(40.dp).focusRing(ControlShape).clip(ControlShape)
                                .selectable(selected, role = Role.Tab) { shell.settingsCategory = category }
                                .pointerHoverIcon(PointerIcon.Hand)
                                .background(if (selected) Violet else NeutralOverlay.copy(.0f)).padding(horizontal = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(category.icon, null, tint = if (selected) androidx.compose.ui.graphics.Color.White else Muted, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(category.label, color = if (selected) androidx.compose.ui.graphics.Color.White else White.copy(.8f), fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (shell.settingsCategory) {
                        SettingsCategory.General -> GeneralSettings(shell)
                        SettingsCategory.Connection -> ConnectionSettings(shell)
                        SettingsCategory.Routing -> RoutingSettings(shell)
                        SettingsCategory.Backup -> BackupSettings(shell)
                        SettingsCategory.Automation -> AutomationSettings(shell)
                        SettingsCategory.Updates -> UpdateSettings(shell)
                        SettingsCategory.Components -> ComponentSettings(shell)
                        SettingsCategory.ActivityLog -> ActivityLog(shell)
                    }
                }
            }
        }
    }
}

/** The scrolling body of one settings group. */
@Composable
internal fun SettingsPage(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(title, color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(9.dp))
        content()
    }
}

/** Appearance, window behaviour, reset and about. */
@Composable
private fun GeneralSettings(shell: Shell) = SettingsPage("General") {
    FieldLabel("Appearance")
    Segmented(listOf(false, true), shell.darkMode, { if (it) "Dark" else "Light" }, Modifier.fillMaxWidth(),
        icon = { if (it) Icons.Rounded.DarkMode else Icons.Rounded.LightMode }) { dark ->
        shell.darkMode = dark
        ActivePalette = if (dark) DarkPalette else LightPalette
        shell.controller.setDarkTheme(dark)
    }
    Spacer(Modifier.height(14.dp))
    FieldLabel("Window")
    InfoBanner("Closing or minimizing the window keeps PerRoute in the notification area. Use Quick Access there to connect, switch the main server, or quit.",
        Modifier.fillMaxWidth(), icon = Icons.Rounded.DesktopWindows)
    Spacer(Modifier.height(14.dp))
    FieldLabel("Reset")
    Row(
        Modifier.fillMaxWidth().clip(RowShape).background(Red.copy(.06f)).border(1.dp, Red.copy(.22f), RowShape).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Reset PerRoute", color = White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (shell.locked) "Disconnect first. A reset is only possible while disconnected."
                else "Clears every server, subscription, selected app, app route, rule, profile, the traffic history and all settings.",
                color = if (shell.locked) Amber else Muted, fontSize = 10.5.sp, lineHeight = 14.sp
            )
        }
        Spacer(Modifier.width(10.dp))
        SecondaryButton("Reset everything…", icon = Icons.Rounded.RestartAlt, enabled = !shell.locked && !shell.busy) {
            shell.showDialog {
                ConfirmDialog(
                    "Reset PerRoute?",
                    "This removes all servers and subscriptions, the selected apps and their routes, rules, profiles and presets, " +
                        "the traffic history and every setting, and returns PerRoute to how it was when first installed. " +
                        "It cannot be undone. Installed components are not affected.",
                    "Reset everything", shell::closeDialog, danger = true
                ) {
                    shell.closeDialog()
                    shell.run(done = { result ->
                        if (result.ok) {
                            shell.logs.clear()
                            shell.darkMode = shell.controller.isDarkTheme()
                            ActivePalette = if (shell.darkMode) DarkPalette else LightPalette
                            shell.draft.load(shell)
                        }
                    }) { shell.controller.resetAll() }
                }
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    FieldLabel("About")
    Column(Modifier.fillMaxWidth().clip(RowShape).background(NeutralOverlay.copy(.035f)).border(1.dp, NeutralOverlay.copy(.06f), RowShape).padding(12.dp)) {
        Text("PerRoute ${app.perroute.service.SecureUpdateService.CURRENT_VERSION}", color = White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        Text("$Slogan $SloganSubtitle", color = Muted, fontSize = 10.5.sp, lineHeight = 14.sp)
        Spacer(Modifier.height(6.dp))
        Text("Created by Ali Mirzad", color = Violet2.copy(.85f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .35.sp)
    }
}

/** Connection mode and the Advanced routing switch. Locked while connected. */
@Composable
private fun ConnectionSettings(shell: Shell) {
    var environment by remember { mutableStateOf<EnvironmentState?>(null) }
    LaunchedEffect(Unit) {
        environment = withContext(Dispatchers.IO) { runCatching { shell.controller.environment() }.getOrDefault(EnvironmentState(false, false, false)) }
    }
    val locked = shell.locked
    SettingsPage("Connection") {
        FieldLabel("Connection mode")
        if (locked) {
            InfoBanner("Disconnect before changing connection settings.", Modifier.fillMaxWidth(), Amber, Icons.Rounded.Lock)
            Spacer(Modifier.height(8.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            ConnectionMode.values().filter { it != ConnectionMode.TUN_COMPATIBILITY }.forEach { mode ->
                val driverMissing = mode == ConnectionMode.NATIVE_PER_APP && environment?.perAppReady == false
                ModeRow(mode, mode == shell.mode, !locked && !driverMissing && !shell.busy, driverMissing) {
                    shell.controller.setMode(mode)
                    shell.refresh()
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        FieldLabel("Servers per app")
        AdvancedRow(
            title = "Use different servers per app",
            detail = (if (shell.advanced) "On: selected apps can use a custom server. Apps without one follow the main server."
                else "Off: all selected apps use the main server.") + " Saved custom assignments are kept when this is off." +
                (if (!shell.perApp) " Applies in Smart per-app only; ${modeLabel(shell.mode)} ignores assignments." else ""),
            checked = shell.advanced, enabled = !locked && !shell.busy, badge = true
        ) { enabled ->
            val result = shell.controller.setAdvancedRouting(enabled)
            shell.refresh()
            shell.draft.load(shell)
            shell.notify(result)
        }
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    locked -> "Connection is active. Disconnect to change the mode or server assignments."
                    shell.appRoutes.isEmpty() -> "No custom assignments saved yet."
                    else -> "${plural(shell.appRoutes.size, "custom assignment")} saved" + if (shell.advanced) "." else " and currently ignored."
                },
                color = if (locked) Amber else Muted, fontSize = 10.5.sp, lineHeight = 14.sp, modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            SecondaryButton("Manage app routes", trailing = Icons.Rounded.ChevronRight) { shell.navigate(Tab.Apps) }
        }
    }
}

/** One connection mode as a radio row. */
@Composable
private fun ModeRow(mode: ConnectionMode, selected: Boolean, enabled: Boolean, driverMissing: Boolean, onClick: () -> Unit) {
    val (title, detail) = when (mode) {
        ConnectionMode.NATIVE_PER_APP -> "Smart per-app" to "Only selected apps use the proxy"
        ConnectionMode.BROWSER_PROXY -> "Local proxy" to "Set a SOCKS5 or HTTP proxy inside individual apps"
        ConnectionMode.SYSTEM_PROXY -> "System proxy" to "Apps that follow Windows proxy settings"
        ConnectionMode.TUN_COMPATIBILITY -> "TUN compatibility" to "Broad compatibility; requires Administrator"
    }
    Row(
        Modifier.fillMaxWidth().alpha(if (enabled || selected) 1f else .5f).focusRing(RowShape).clip(RowShape)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .background(if (selected) Violet.copy(.13f) else NeutralOverlay.copy(.035f))
            .border(1.dp, if (selected) Violet.copy(.36f) else NeutralOverlay.copy(.06f), RowShape).padding(11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(if (selected) Violet.copy(.2f) else NeutralOverlay.copy(.05f)), contentAlignment = Alignment.Center) {
            Icon(when (mode) {
                ConnectionMode.SYSTEM_PROXY -> Icons.Rounded.DesktopWindows
                ConnectionMode.NATIVE_PER_APP -> Icons.Rounded.AltRoute
                else -> Icons.Rounded.Tune
            }, null, tint = if (selected) Violet2 else Muted, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            Text(if (driverMissing) "Needs the per-app driver from the Full Installer. See Components." else detail,
                color = if (driverMissing) Amber else Muted, fontSize = 10.5.sp)
        }
        RadioMark(selected)
    }
}

/** Reconnect, failover, kill switch, fastest-on-connect and automatic speed tests. */
@Composable
private fun AutomationSettings(shell: Shell) {
    val controller = shell.controller
    var autoFailover by remember { mutableStateOf(controller.autoFailover()) }
    var fastestOnConnect by remember { mutableStateOf(controller.fastestOnConnect()) }
    var autoSpeedTest by remember { mutableStateOf(controller.autoSpeedTest()) }
    var autoReconnect by remember { mutableStateOf(controller.autoReconnect()) }
    var killSwitch by remember { mutableStateOf(controller.killSwitch()) }
    SettingsPage("Automation") {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            FeatureToggleRow(Icons.Rounded.Refresh, "Auto reconnect", "Reconnect after sleep, network changes, or an unexpected core stop.", autoReconnect) {
                autoReconnect = it; controller.setAutoReconnect(it)
            }
            FeatureToggleRow(Icons.Rounded.SwapHoriz, "Main server failover", "After three failed checks, switch the main server to the fastest reachable one.", autoFailover) {
                autoFailover = it; controller.setAutoFailover(it)
            }
            FeatureToggleRow(Icons.Rounded.Shield, "Per-app kill switch", "Block selected apps while their tunnel is unavailable.", killSwitch) {
                killSwitch = it; controller.setKillSwitch(it)
            }
            FeatureToggleRow(Icons.Rounded.Speed, "Fastest main server on connect", "Measure reachable servers and use the lowest latency as the main server before connecting.", fastestOnConnect) {
                fastestOnConnect = it; controller.setFastestOnConnect(it)
            }
            FeatureToggleRow(Icons.Rounded.Download, "Automatic speed test", "After pinging, measure the download speed of the 10 best servers (up to 10 MB each).", autoSpeedTest) {
                autoSpeedTest = it; controller.setAutoSpeedTest(it)
            }
        }
        Spacer(Modifier.height(9.dp))
        InfoBanner("Custom servers do not switch automatically. Failover and fastest-on-connect only change the main server, and a main-server switch restarts every route briefly.",
            Modifier.fillMaxWidth())
    }
}

/** An automation option with its explanation and switch. */
@Composable
internal fun FeatureToggleRow(icon: ImageVector, title: String, detail: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RowShape).background(NeutralOverlay.copy(.035f)).border(1.dp, NeutralOverlay.copy(.06f), RowShape).padding(11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(if (checked) Cyan.copy(.14f) else NeutralOverlay.copy(.05f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (checked) Cyan else Muted, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Muted, fontSize = 10.5.sp, lineHeight = 14.sp)
        }
        Spacer(Modifier.width(10.dp))
        ToggleSwitch(checked, title, onToggle = onToggle)
    }
}

/** The signed update feed, checking, downloading and installing an update. */
@Composable
private fun UpdateSettings(shell: Shell) {
    val controller = shell.controller
    var updateFeed by remember { mutableStateOf(controller.updateFeedUrl()) }
    var availableVersion by remember { mutableStateOf(controller.availableUpdateVersion()) }
    var updateDownloading by remember { mutableStateOf(controller.isUpdateDownloading()) }
    var updateProgress by remember { mutableStateOf(controller.updateDownloadProgress()) }
    var updateMessage by remember { mutableStateOf(controller.updateDownloadMessage()) }
    var downloadedUpdate by remember { mutableStateOf(controller.downloadedUpdatePath()) }
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
    SettingsPage("Updates") {
        FieldLabel("Signed update feed")
        TextInput(updateFeed, { updateFeed = it; controller.setUpdateFeedUrl(it) }, "Signed HTTPS manifest URL", icon = Icons.Rounded.Link)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(if (shell.busy) "Checking…" else "Check for updates", icon = Icons.Rounded.Refresh, enabled = !shell.busy) {
                shell.run { controller.checkForUpdate() }
            }
            if (availableVersion != null || updateDownloading || downloadedUpdate != null) {
                PrimaryButton(
                    when {
                        downloadedUpdate != null -> "Install update"
                        updateDownloading -> "Downloading $updateProgress%"
                        else -> "Download $availableVersion"
                    }, icon = Icons.Rounded.Download, enabled = !updateDownloading
                ) {
                    if (downloadedUpdate != null) {
                        val result = controller.launchDownloadedUpdate()
                        shell.notify(result)
                        if (result.ok) shell.exitApp()
                    } else shell.notify(controller.downloadAvailableUpdateInBackground())
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                updateDownloading -> "Downloading in the background. You can leave this page or minimize PerRoute."
                updateMessage.isNotBlank() -> updateMessage
                availableVersion != null -> "PerRoute $availableVersion is available."
                else -> "You are running PerRoute ${app.perroute.service.SecureUpdateService.CURRENT_VERSION}."
            },
            color = if (updateDownloading) Cyan else Muted, fontSize = 10.5.sp, lineHeight = 14.sp
        )
        Spacer(Modifier.height(9.dp))
        InfoBanner("Only manifests signed with PerRoute's offline Ed25519 release key are accepted, and every download is checked by size and SHA-256 before it can be installed.",
            Modifier.fillMaxWidth(), icon = Icons.Rounded.Shield)
    }
}

/** Whether the Xray core and the per-app driver are installed. */
@Composable
private fun ComponentSettings(shell: Shell) {
    val controller = shell.controller
    val scope = rememberCoroutineScope()
    var environment by remember { mutableStateOf<EnvironmentState?>(null) }
    var progress by remember { mutableStateOf("") }
    suspend fun reload() {
        environment = withContext(Dispatchers.IO) { runCatching { controller.environment() }.getOrDefault(EnvironmentState(false, false, false)) }
    }
    LaunchedEffect(Unit) { reload() }
    SettingsPage("Components") {
        val state = environment
        if (state == null) LoadingState("Checking installed components…", Modifier.fillMaxWidth())
        else Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            EnvironmentRow(Icons.Rounded.Dns, "Xray core", "Connection and protocol engine", state.coreReady, "Install", !shell.busy && !shell.locked) {
                progress = "Preparing…"
                shell.run(done = { progress = ""; scope.launch { reload() } }) {
                    controller.installCore { update -> EventQueue.invokeLater { progress = update } }
                }
            }
            EnvironmentRow(Icons.Rounded.Shield, "Per-app driver", "Routes arbitrary applications in Smart per-app mode", state.perAppReady, null, false) {}
        }
        if (progress.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(progress, color = Amber, fontSize = 11.sp)
        }
        Spacer(Modifier.height(9.dp))
        InfoBanner("The Full Installer sets up the per-app driver with a single Administrator approval. After that PerRoute connects without asking again. The portable build can reuse an existing driver installation.",
            Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Check again", icon = Icons.Rounded.Refresh) { scope.launch { environment = null; reload() } }
    }
}

/** One component and its state, with an install action where PerRoute can install it. */
@Composable
private fun EnvironmentRow(icon: ImageVector, title: String, detail: String, ready: Boolean, action: String?, actionEnabled: Boolean, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RowShape).background(NeutralOverlay.copy(.035f)).border(1.dp, NeutralOverlay.copy(.06f), RowShape).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (ready) Cyan else Amber, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Muted, fontSize = 10.5.sp)
        }
        Spacer(Modifier.width(8.dp))
        when {
            ready -> Badge("Ready", Cyan, icon = Icons.Rounded.Shield)
            action != null -> SecondaryButton(action, icon = Icons.Rounded.Download, enabled = actionEnabled, onClick = onAction)
            else -> Badge("Not installed", Amber)
        }
    }
}

/** The session log as selectable text. */
@Composable
private fun ActivityLog(shell: Shell) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Activity log", color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("The last 200 events of this session. Select text and press Ctrl+C, or copy everything.", color = Muted, fontSize = 10.5.sp)
            }
            SecondaryButton("Copy all", icon = Icons.Rounded.ContentCopy, enabled = shell.logs.isNotEmpty()) {
                clipboard.setText(AnnotatedString(shell.logs.takeLast(200).joinToString("\n")))
                shell.notify(ActionResult(true, "Activity log copied."))
            }
        }
        Spacer(Modifier.height(9.dp))
        if (shell.logs.isEmpty()) {
            EmptyState(Icons.Rounded.Article, "Nothing logged yet", "Connection events, test results and parser warnings appear here.", Modifier.fillMaxSize())
        } else BasicTextField(
            value = shell.logs.takeLast(200).joinToString("\n"),
            onValueChange = {},
            readOnly = true,
            textStyle = TextStyle(color = White.copy(.78f), fontSize = 10.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp),
            cursorBrush = SolidColor(Cyan),
            modifier = Modifier.fillMaxSize().clip(RowShape)
                .background(NeutralOverlay.copy(if (ActivePalette.isDark) .20f else .045f))
                .verticalScroll(rememberScrollState()).padding(12.dp)
                .semantics { contentDescription = "Activity log" }
        )
    }
}
