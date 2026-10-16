package app.perroute.compose.ui.screens

import app.perroute.compose.Shell
import app.perroute.compose.Tab
import app.perroute.compose.controller.ActionResult
import app.perroute.compose.controller.ConnectionPhase
import app.perroute.compose.controller.RouteCheck
import app.perroute.compose.controller.RouteCheckState
import app.perroute.compose.controller.TrafficSnapshot
import app.perroute.compose.ui.components.ActivePalette
import app.perroute.compose.ui.components.Amber
import app.perroute.compose.ui.components.AppAvatar
import app.perroute.compose.ui.components.Badge
import app.perroute.compose.ui.components.ControlShape
import app.perroute.compose.ui.components.Cyan
import app.perroute.compose.ui.components.EllipsisText
import app.perroute.compose.ui.components.GlassCard
import app.perroute.compose.ui.components.GlassShape
import app.perroute.compose.ui.components.IconAction
import app.perroute.compose.ui.components.InfoBanner
import app.perroute.compose.ui.components.Muted
import app.perroute.compose.ui.components.NeutralOverlay
import app.perroute.compose.ui.components.PrimaryButton
import app.perroute.compose.ui.components.Red
import app.perroute.compose.ui.components.RouteBadge
import app.perroute.compose.ui.components.RowShape
import app.perroute.compose.ui.components.SecondaryButton
import app.perroute.compose.ui.components.ServerAvatar
import app.perroute.compose.ui.components.StatusDot
import app.perroute.compose.ui.components.Tooltip
import app.perroute.compose.ui.components.Violet2
import app.perroute.compose.ui.components.White
import app.perroute.compose.ui.components.appName
import app.perroute.compose.ui.components.fileName
import app.perroute.compose.ui.components.formatAgo
import app.perroute.compose.ui.components.formatBytes
import app.perroute.compose.ui.components.formatDuration
import app.perroute.compose.ui.components.formatRate
import app.perroute.compose.ui.components.joinNames
import app.perroute.compose.ui.components.plural
import app.perroute.compose.ui.components.pressable
import app.perroute.compose.ui.components.protocolLabel
import app.perroute.compose.ui.components.serverName

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.model.ConnectionMode
import app.perroute.model.ProxyNode
import app.perroute.service.AppProxyLauncher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** A clock that ticks once a second, so "12 seconds ago" keeps moving and stale checks are noticed. */
@Composable
internal fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) {
    while (isActive) { delay(1_000); value = System.currentTimeMillis() }
}

/**
 * A route in use and the applications on it, as shown in route lists. Main and Manual routes are shared by
 * the apps on one server; every app in Auto mode has a route of its own. [id] is what checks are stored under.
 */
internal class RouteRow(val node: ProxyNode, val main: Boolean, val applications: List<String>, val id: String = node.key(), val auto: Boolean = false)

/** Routes of the running connection, or the ones a connection would use while disconnected. */
internal fun routeRows(shell: Shell): List<RouteRow> =
    if (shell.connected) shell.activeRoutes.map { RouteRow(it.node, it.main, it.applications, it.id, it.auto) }
    else shell.plan.routes().map { RouteRow(it.node(), it.main(), if (shell.perApp) it.applications() else emptyList(), it.id(), it.auto()) }

/** "Main", "Custom" (outlined) or "Auto" (teal): the three never differ by colour alone. */
@Composable
internal fun RouteKindBadge(row: RouteRow) {
    if (row.auto) Badge("Auto", Cyan, icon = Icons.Rounded.AutoAwesome) else RouteBadge(row.main)
}

/** The connection mode as it is named in the interface. */
internal fun modeLabel(mode: ConnectionMode): String = when (mode) {
    ConnectionMode.NATIVE_PER_APP -> "Smart per-app"
    ConnectionMode.BROWSER_PROXY -> "Local proxy"
    ConnectionMode.SYSTEM_PROXY -> "System proxy"
    ConnectionMode.TUN_COMPATIBILITY -> "TUN"
}

/**
 * What was last observed on one route, in words and with an icon. A route that was never checked, is
 * being checked, failed, or was checked too long ago each reads differently.
 */
@Composable
internal fun RouteCheckStatus(check: RouteCheck?, connected: Boolean, now: Long, modifier: Modifier = Modifier, showLatency: Boolean = true) {
    val state = check?.state ?: RouteCheckState.NOT_CHECKED
    val stale = check?.stale(now) == true
    val (icon, color, title) = when {
        !connected -> Triple(Icons.Rounded.RadioButtonUnchecked, Muted, "Not connected")
        state == RouteCheckState.CHECKING -> Triple(null, Muted, "Checking…")
        state == RouteCheckState.NOT_CHECKED -> Triple(Icons.Rounded.RadioButtonUnchecked, Muted, "Not checked yet")
        stale -> Triple(Icons.Rounded.Schedule, Amber, if (state == RouteCheckState.PASSED) "Stale · last passed" else "Stale · last failed")
        state == RouteCheckState.PASSED -> Triple(Icons.Rounded.CheckCircle, Cyan,
            if (showLatency && check?.latencyMillis != null) "Passed · ${check.latencyMillis} ms" else "Last check passed")
        else -> Triple(Icons.Rounded.WarningAmber, Amber, "Check failed")
    }
    val detail = when {
        !connected -> ""
        state == RouteCheckState.PASSED || state == RouteCheckState.FAILED -> formatAgo(check!!.atMillis, now)
        else -> ""
    }
    Tooltip(if (connected) check?.detail.orEmpty() else "Connect to check this server.", modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon == null) CircularProgressIndicator(color = Muted, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            else Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
            Column {
                Text(title, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (detail.isNotBlank()) Text(detail, color = Muted, fontSize = 9.5.sp, maxLines = 1)
            }
        }
    }
}

/** Status, the connect action, live speed and one row per server in use. Fills the window when there is room and scrolls when there is not. */
@Composable
internal fun ConnectionScreen(shell: Shell) {
    val now by rememberNow()
    val main = shell.mainNode
    val rows = routeRows(shell)
    val missing = shell.plan.missing()
    val needsApps = shell.perApp && shell.apps.isEmpty()
    val connecting = shell.phase == ConnectionPhase.CONNECTING
    val disconnecting = shell.phase == ConnectionPhase.DISCONNECTING
    val localProxy = shell.mode == ConnectionMode.BROWSER_PROXY && main != null
    val statusTitle = when {
        connecting -> "Connecting…"
        disconnecting -> "Disconnecting…"
        shell.connected -> "Connected"
        main == null || needsApps || missing.isNotEmpty() -> "Complete your setup"
        else -> "Not connected"
    }
    val statusColor = when {
        shell.connected && !disconnecting -> Cyan
        connecting || disconnecting -> Amber
        else -> Muted.copy(.7f)
    }
    val summary = when {
        main == null -> "No server yet"
        !shell.perApp -> "1 server"
        else -> "${plural(shell.apps.size, "app")}  •  ${plural(rows.map { it.node.key() }.distinct().size.coerceAtLeast(1), "server")}"
    }
    val buttonEnabled = !shell.busy || connecting || disconnecting
    val monitoring = shell.controller.routeMonitoring()
    val routeRecovering = shell.connected && monitoring && shell.routeHealth.any { it.health?.state() == app.perroute.routing.RouteHealthTracker.State.RECOVERING }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 760.dp
        // Tall enough to pin the shortcut cards to the bottom and let the status card take everything above them.
        val roomy = maxHeight >= 470.dp
        val cardBackground by animateColorAsState(
            if (shell.connected) if (ActivePalette.isDark) Color(0xFF10352F).copy(.88f) else Color(0xFFE0F4EF)
            else if (ActivePalette.isDark) Color.White.copy(.055f) else Color.White.copy(.78f),
            animationSpec = tween(450), label = "connection-card-background"
        )
        val cardBorder by animateColorAsState(
            if (shell.connected) Cyan.copy(.62f)
            else if (ActivePalette.isDark) Color.White.copy(.09f) else Color.Black.copy(.08f),
            animationSpec = tween(450), label = "connection-card-border"
        )
        val card = Modifier.fillMaxWidth().clip(GlassShape).background(cardBackground).border(1.dp, cardBorder, GlassShape)

        // The status and the main action side by side: used when there is no height to spare.
        val compactHero: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(statusColor, 12.dp)
                    Spacer(Modifier.width(11.dp))
                    Column {
                        Text(statusTitle, color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(modeLabel(shell.mode), color = Muted, fontSize = 11.sp)
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ConnectButton(shell.connected, connecting, disconnecting, buttonEnabled,
                        Modifier.width(if (narrow) 170.dp else 214.dp), onClick = shell::toggleConnection)
                    Spacer(Modifier.height(6.dp))
                    Text(summary, color = Muted, fontSize = 10.5.sp)
                }
                if (!narrow) Spacer(Modifier.weight(1f))
            }
        }
        // The same information stacked and centred, with a larger button, when the window has room for it.
        val largeHero: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(statusColor, 13.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(statusTitle, color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Text(modeLabel(shell.mode), color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))
                ConnectButton(shell.connected, connecting, disconnecting, buttonEnabled,
                    Modifier.width(if (narrow) 230.dp else 280.dp), large = true, onClick = shell::toggleConnection)
                Spacer(Modifier.height(7.dp))
                Text(summary, color = Muted, fontSize = 11.sp)
            }
        }
        val stats: @Composable (Boolean) -> Unit = { large ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                StatTile(Modifier.weight(1f), Icons.Rounded.Download, "Download",
                    if (shell.connected) formatRate(shell.traffic.downloadBytesPerSecond) else "—", Cyan, large)
                StatTile(Modifier.weight(1f), Icons.Rounded.Upload, "Upload",
                    if (shell.connected) formatRate(shell.traffic.uploadBytesPerSecond) else "—", Violet2, large)
                StatTile(Modifier.weight(1f), Icons.Rounded.Schedule, "Session",
                    if (shell.connected) formatDuration(shell.traffic.connectedSeconds) else "—", White, large)
                if (!narrow) StatTile(Modifier.weight(1f), Icons.Rounded.GraphicEq, "Main server latency",
                    if (shell.connected) shell.traffic.latencyMillis?.let { "$it ms" } ?: "Measuring…" else "—", White, large)
            }
        }
        val endpoints: @Composable () -> Unit = {
            if (localProxy) {
                val clipboard = LocalClipboardManager.current
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    listOf("SOCKS5" to AppProxyLauncher.SOCKS_PORT, "HTTP" to AppProxyLauncher.HTTP_PORT).forEach { (label, port) ->
                        ProxyEndpoint(Modifier.weight(1f), label, "127.0.0.1:$port", shell.connected) {
                            clipboard.setText(AnnotatedString("127.0.0.1:$port"))
                            shell.notify(ActionResult(true, "$label endpoint copied."))
                        }
                    }
                }
            }
        }
        // One row per server in use. The modifier lets the roomy layout cap the list and scroll it.
        val routes: @Composable (Modifier) -> Unit = { list ->
            if (main == null) {
                InfoBanner("Add a server to get started. PerRoute supports subscriptions, share links and QR codes.",
                    Modifier.fillMaxWidth(), Amber, Icons.Rounded.WarningAmber) {
                    SecondaryButton("Add servers", trailing = Icons.Rounded.ChevronRight) { shell.addServersRequested = true; shell.navigate(Tab.Servers) }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 11.dp)) {
                    Text("Server", color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1.1f))
                    Text("Applications", color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1.3f))
                    Text(if (shell.connected && monitoring) "Status / Last check" else "Last check", color = Muted, fontSize = 10.sp, modifier = Modifier.width(170.dp))
                }
                Spacer(Modifier.height(5.dp))
                Column(list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    rows.forEach { row ->
                        val health = if (shell.connected) shell.routeHealth.firstOrNull { it.route.id == row.id }?.health else null
                        RouteSummaryRow(shell, row, shell.routeChecks[row.id], health, monitoring, now)
                    }
                }
            }
        }
        val banner: @Composable () -> Unit = {
            when {
                missing.isNotEmpty() -> InfoBanner(
                    "${appName(missing[0].applicationPath())} is assigned to ${missing[0].label().ifBlank { "a custom server" }}, which is no longer in your server list" +
                        (if (missing.size > 1) ", and ${plural(missing.size - 1, "more app")} has the same problem" else "") +
                        ". Connecting is blocked until you choose a replacement.",
                    Modifier.fillMaxWidth(), Amber, Icons.Rounded.WarningAmber
                ) { SecondaryButton("Fix in Apps", trailing = Icons.Rounded.ChevronRight) { shell.navigate(Tab.Apps) } }
                needsApps -> InfoBanner("Choose at least one application for Smart per-app.", Modifier.fillMaxWidth(), Amber, Icons.Rounded.WarningAmber) {
                    SecondaryButton("Manage apps", trailing = Icons.Rounded.ChevronRight) { shell.navigate(Tab.Apps) }
                }
                !shell.perApp -> InfoBanner(when (shell.mode) {
                    ConnectionMode.BROWSER_PROXY -> "Enter either endpoint in an application's own proxy settings. Everything else stays direct."
                    ConnectionMode.SYSTEM_PROXY -> "Applications that follow Windows proxy settings use the main server."
                    else -> "Network traffic is routed by the compatibility tunnel."
                }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Shield) { Badge(modeLabel(shell.mode), Muted) }
                shell.connected && monitoring && rows.size > 1 -> {
                    val affected = shell.routeHealth.count { it.health?.affected() == true }
                    val healthy = shell.routeHealth.count { it.health?.state() == app.perroute.routing.RouteHealthTracker.State.HEALTHY }
                    InfoBanner(
                        "${plural(healthy, "route")} healthy" + (if (affected > 0) "  •  $affected recovering  •  Each route recovers on its own; the others keep running."
                            else "  •  Each route is checked and recovered on its own."),
                        Modifier.fillMaxWidth(), if (affected > 0) Amber else Muted, Icons.Rounded.AltRoute
                    ) { Badge("Independent recovery", Cyan) }
                }
                shell.plan.advancedEffective() && rows.size > 1 -> InfoBanner(
                    "${plural(rows.filter { !it.main }.sumOf { it.applications.size }, "app")} use their own route. Manual servers are never switched automatically; " +
                        "changing the main server replaces only the main route.",
                    Modifier.fillMaxWidth(), icon = Icons.Rounded.AltRoute
                ) { Badge("Advanced routing", Violet2) }
                shell.plan.advancedEffective() -> InfoBanner("Advanced routing is on, but every selected app follows the main server.",
                    Modifier.fillMaxWidth(), icon = Icons.Rounded.AltRoute) { Badge("Advanced routing", Violet2) }
                else -> InfoBanner("All selected apps use the main server. Other applications stay on your regular connection.",
                    Modifier.fillMaxWidth(), icon = Icons.Rounded.Shield) { Badge("Standard routing", Muted) }
            }
        }
        val shortcuts: @Composable () -> Unit = {
            val applicationsCard: @Composable (Modifier) -> Unit = { modifier ->
                ShortcutCard(
                    modifier, Icons.Rounded.Apps, Cyan, "Applications",
                    when {
                        !shell.perApp -> "Not used in ${modeLabel(shell.mode)} mode"
                        shell.apps.isEmpty() -> "No apps selected"
                        else -> "${plural(shell.apps.size, "app")} selected"
                    },
                    when {
                        !shell.perApp -> "Selection applies in Smart per-app"
                        shell.connected -> activeAppSummary(shell.traffic)
                        else -> joinNames(shell.apps.map(::appName))
                    },
                    "Manage apps"
                ) { shell.navigate(Tab.Apps) }
            }
            val serverCard: @Composable (Modifier) -> Unit = { modifier ->
                val mainApps = rows.firstOrNull { it.main }?.applications.orEmpty()
                ShortcutCard(
                    modifier, Icons.Rounded.Storage, Violet2, "Main server",
                    main?.let(::serverName) ?: "No server selected",
                    when {
                        main == null -> "Add a subscription or share link"
                        !shell.perApp -> "${protocolLabel(main)}  •  ${main.endpoint()}"
                        shell.apps.isEmpty() -> "No apps selected yet"
                        mainApps.size == shell.apps.size -> "Used by all ${plural(shell.apps.size, "selected app")}"
                        mainApps.isEmpty() -> "No selected app uses it right now"
                        else -> "Used by ${joinNames(mainApps.map(::appName))}"
                    },
                    if (main == null) "Add servers" else "Choose main server"
                ) { if (main == null) shell.addServersRequested = true; shell.navigate(Tab.Servers) }
            }
            if (narrow) Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                applicationsCard(Modifier.fillMaxWidth())
                serverCard(Modifier.fillMaxWidth())
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                applicationsCard(Modifier.weight(1f))
                serverCard(Modifier.weight(1f))
            }
        }

        if (roomy) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            BoxWithConstraints(card.weight(1f)) {
                // Heights of the parts that are always there, so the hero and the route list can share the rest.
                val fixed = 193.dp + if (localProxy) 54.dp else 0.dp
                val recovering = routeRecovering
                val rowsNeeded = (if (main == null) 41.dp else 50.dp * rows.size + 6.dp * (rows.size - 1).coerceAtLeast(0)) + if (recovering) 196.dp else 0.dp
                val large = maxHeight >= fixed + 150.dp + rowsNeeded
                val routesMax = (maxHeight - fixed - (if (large) 134.dp else 81.dp)).coerceAtLeast(50.dp)
                Column(Modifier.fillMaxSize().padding(18.dp)) {
                    if (large) {
                        Spacer(Modifier.weight(1f))
                        largeHero()
                        Spacer(Modifier.weight(1f))
                    } else {
                        compactHero()
                        Spacer(Modifier.height(13.dp))
                    }
                    stats(large)
                    endpoints()
                    Spacer(Modifier.height(12.dp))
                    routes(Modifier.heightIn(max = routesMax).verticalScroll(rememberScrollState()))
                    Spacer(Modifier.height(10.dp))
                    if (!large) Spacer(Modifier.weight(1f))
                    banner()
                }
            }
            // While a route recovers its details need the room more than the shortcuts do.
            if (!routeRecovering) shortcuts()
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Column(card.padding(16.dp)) {
                compactHero()
                Spacer(Modifier.height(13.dp))
                stats(false)
                endpoints()
                Spacer(Modifier.height(12.dp))
                routes(Modifier)
                Spacer(Modifier.height(10.dp))
                banner()
            }
            shortcuts()
        }
    }
}

/** Names the selected applications that are sending traffic right now. */
private fun activeAppSummary(traffic: TrafficSnapshot): String {
    val active = traffic.applications.filter { it.active }
    if (active.isEmpty()) return "No selected app is using the tunnel right now"
    return "Active: " + active.joinToString(", ") { "${appName(it.path)} (${formatBytes(it.downloadBytes + it.uploadBytes)})" }
}

/**
 * One route with its applications and its own health. A route that is recovering expands to say that only it
 * is affected, what protects its apps meanwhile, and what has happened so far.
 */
@Composable
private fun RouteSummaryRow(shell: Shell, row: RouteRow, check: RouteCheck?, health: app.perroute.routing.RouteHealthTracker.Snapshot?,
                            monitoring: Boolean, now: Long) {
    val activePaths = shell.traffic.applications.filter { it.active }.map { it.path.lowercase() }.toSet()
    val affected = shell.connected && monitoring && health?.affected() == true
    var expanded by remember(row.id) { mutableStateOf(false) }
    val showDetails = affected && (expanded || health?.state() == app.perroute.routing.RouteHealthTracker.State.RECOVERING)
    val borderColor = if (affected) Amber.copy(.65f) else NeutralOverlay.copy(.07f)
    Column(
        Modifier.fillMaxWidth().clip(RowShape).background(if (affected) Amber.copy(.06f) else NeutralOverlay.copy(if (ActivePalette.isDark) .14f else .04f))
            .border(1.dp, borderColor, RowShape)
    ) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.weight(1.1f), verticalAlignment = Alignment.CenterVertically) {
            ServerAvatar(row.node, 30.dp)
            Spacer(Modifier.width(9.dp))
            EllipsisText(serverName(row.node), White, 12.5.sp, Modifier.weight(1f, fill = false), FontWeight.SemiBold)
            Spacer(Modifier.width(7.dp))
            RouteKindBadge(row)
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.weight(1.3f).padding(end = 8.dp)) {
            when {
                !shell.perApp -> Text(if (shell.mode == ConnectionMode.SYSTEM_PROXY) "Apps that follow Windows proxy settings" else "Apps you point at the local proxy",
                    color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                row.applications.isEmpty() -> Text("No selected app", color = Muted, fontSize = 11.sp)
                else -> Tooltip(row.applications.joinToString("\n") { appName(it) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        row.applications.take(3).forEach { path ->
                            AppAvatar(fileName(path), path, 22.dp)
                            Spacer(Modifier.width(5.dp))
                            Text(appName(path), color = White.copy(.86f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false))
                            if (shell.connected && path.lowercase() in activePaths) { Spacer(Modifier.width(4.dp)); StatusDot(Cyan, 6.dp) }
                            Spacer(Modifier.width(9.dp))
                        }
                        if (row.applications.size > 3) Text("+${row.applications.size - 3}", color = Muted, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        if (affected && health != null) RouteHealthStatus(health, now, Modifier.width(150.dp))
        else RouteCheckStatus(check, shell.connected, now, Modifier.width(150.dp))
        Box(Modifier.width(20.dp)) {
            if (affected) IconAction(if (showDetails) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                if (showDetails) "Hide recovery details" else "Show recovery details", size = 22.dp) { expanded = !showDetails }
        }
    }
    if (showDetails && health != null) RecoveryDetails(shell, row, health, now)
    }
}

/** Recovering (with the attempt and the countdown) or failed, for a route that is not healthy. */
@Composable
private fun RouteHealthStatus(health: app.perroute.routing.RouteHealthTracker.Snapshot, now: Long, modifier: Modifier) {
    val recovering = health.state() == app.perroute.routing.RouteHealthTracker.State.RECOVERING
    val wait = ((health.nextCheckAt() - now) / 1_000L).coerceAtLeast(0L)
    Tooltip(health.lastError(), modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (recovering) CircularProgressIndicator(color = Amber, strokeWidth = 2.dp, modifier = Modifier.size(15.dp))
            else Icon(Icons.Rounded.ErrorOutline, null, tint = Red, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
            Column {
                Text(if (recovering) "Recovering" else "Route failed", color = if (recovering) Amber else Red, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(if (recovering) "Attempt ${health.attempt()} of ${health.maxAttempts()}  •  Retry in ${wait}s"
                    else "Checked again in ${wait}s", color = Muted, fontSize = 9.5.sp, maxLines = 1)
            }
        }
    }
}

/** Why only this route is affected, what protects its apps, the timeline, and what the user can do. */
@Composable
private fun RecoveryDetails(shell: Shell, row: RouteRow, health: app.perroute.routing.RouteHealthTracker.Snapshot, now: Long) {
    val killSwitch = shell.controller.killSwitch()
    Column(Modifier.fillMaxWidth().padding(start = 11.dp, end = 11.dp, bottom = 11.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (killSwitch) Badge("Kill switch active", Red, icon = Icons.Rounded.Shield)
            Badge("No direct fallback", Muted, icon = Icons.Rounded.Block)
            Spacer(Modifier.weight(1f))
            PrimaryButton("Retry now", Modifier.height(34.dp)) { shell.run { shell.controller.retryRoute(row.id) } }
            SecondaryButton("Change server", Modifier.height(34.dp)) {
                if (row.main) shell.navigate(Tab.Servers) else shell.navigate(Tab.Apps)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(NeutralOverlay.copy(if (ActivePalette.isDark) .18f else .04f))
                .padding(12.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Info, null, tint = Amber, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Only this route is affected", color = Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(5.dp))
                val apps = joinNames(row.applications.map(::appName)).ifBlank { "Apps on this route" }
                Text("$apps ${if (row.applications.size == 1) "is" else "are"} waiting for ${serverName(row.node)}. " +
                    "Other apps and routes keep working. " +
                    (if (killSwitch) "The kill switch keeps these apps offline until the route is back."
                    else "Their connections fail instead of leaving through your regular connection.") +
                    (if (row.auto) " Auto moves the app to another working server when this one does not recover." else ""),
                    color = White.copy(.82f), fontSize = 10.5.sp, lineHeight = 15.sp)
                if (health.lastError().isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Text("Last error: ${health.lastError()}", color = Muted, fontSize = 9.5.sp, lineHeight = 13.sp)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Recovery timeline", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                health.events().takeLast(5).forEach { event ->
                    val color = when (event.type()) {
                        app.perroute.routing.RouteHealthTracker.EventType.CHECK_FAILED, app.perroute.routing.RouteHealthTracker.EventType.EXHAUSTED -> Red
                        app.perroute.routing.RouteHealthTracker.EventType.RECOVERED, app.perroute.routing.RouteHealthTracker.EventType.CHECK_PASSED -> Cyan
                        else -> Violet2
                    }
                    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 4.dp)) { StatusDot(color, 7.dp) }
                        Spacer(Modifier.width(7.dp))
                        Text(java.time.Instant.ofEpochMilli(event.atMillis()).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
                            .withNano(0).toString(), color = Muted, fontSize = 9.5.sp, modifier = Modifier.width(52.dp))
                        Column {
                            Text(event.title(), color = White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            if (event.detail().isNotBlank()) Text(event.detail(), color = Muted, fontSize = 9.sp, lineHeight = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/** A labelled live value such as download speed. */
@Composable
private fun StatTile(modifier: Modifier, icon: ImageVector, label: String, value: String, accent: Color, large: Boolean = false) {
    Row(
        modifier.clip(RowShape).background(NeutralOverlay.copy(if (ActivePalette.isDark) .14f else .04f))
            .border(1.dp, NeutralOverlay.copy(.07f), RowShape).padding(horizontal = 11.dp, vertical = if (large) 12.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(accent.copy(.12f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(9.dp))
        Column {
            Text(label, color = Muted, fontSize = 10.sp)
            Text(value, color = White, fontSize = if (large) 15.sp else 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A local proxy address that is copied when pressed. */
@Composable
private fun ProxyEndpoint(modifier: Modifier, label: String, value: String, active: Boolean, onCopy: () -> Unit) {
    Row(
        modifier.height(44.dp).pressable(ControlShape, label = "Copy $label endpoint $value", onClick = onCopy)
            .background((if (active) Cyan else NeutralOverlay).copy(if (active) .10f else .045f))
            .border(1.dp, (if (active) Cyan else NeutralOverlay).copy(if (active) .30f else .08f), ControlShape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = if (active) Cyan else Muted, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
            Text(value, color = White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Icon(Icons.Rounded.ContentCopy, null, tint = if (active) Cyan else Muted, modifier = Modifier.size(15.dp))
    }
}

/** A summary card with a button that leads to the screen where it can be changed. */
@Composable
private fun ShortcutCard(modifier: Modifier, icon: ImageVector, accent: Color, eyebrow: String, title: String, detail: String, action: String, onClick: () -> Unit) {
    GlassCard(modifier) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(.13f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(eyebrow, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                EllipsisText(title, White, 13.sp, fontWeight = FontWeight.Bold)
                EllipsisText(detail, Muted, 10.5.sp)
            }
            Spacer(Modifier.width(9.dp))
            SecondaryButton(action, trailing = Icons.Rounded.ChevronRight, onClick = onClick)
        }
    }
}

/** The connect / disconnect button, including its in-progress states. */
@Composable
internal fun ConnectButton(connected: Boolean, connecting: Boolean, disconnecting: Boolean, enabled: Boolean, modifier: Modifier = Modifier, large: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val busy = connecting || disconnecting
    val colors = if (connected && !disconnecting) listOf(Color(0xFF0B9B83), Color(0xFF087765)) else listOf(Color(0xFF6B57D9), Color(0xFF8A5CE6))
    val label = when {
        connecting -> "Connecting…"
        disconnecting -> "Disconnecting…"
        connected -> "Disconnect"
        else -> "Connect"
    }
    Row(
        modifier.height(if (large) 56.dp else 48.dp)
            .shadow(if (connected) 14.dp else 9.dp, shape, ambientColor = colors.first().copy(.34f), spotColor = colors.last().copy(.4f))
            .pressable(shape, enabled && !busy, label = label, onClick = onClick)
            .background(Brush.horizontalGradient(colors)).border(1.dp, Color.White.copy(.18f), shape)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
    ) {
        if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(17.dp))
        else Icon(Icons.Rounded.PowerSettingsNew, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(9.dp))
        Text(label, color = Color.White, fontSize = if (large) 15.sp else 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
