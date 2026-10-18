package app.perroute.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.model.ProxyNode
import app.perroute.service.RoutePlanner

/**
 * What the tray panel shows and can do, the same for both designs: connect or disconnect, pick the main server,
 * reopen the window. Per-app assignments are summarised but never edited here. Quit lives in the overflow menu.
 */
internal class QuickAccessState(
    val connected: Boolean,
    val busy: Boolean,
    val working: Boolean,
    val main: ProxyNode?,
    val nodes: List<ProxyNode>,
    val favorites: Set<String>,
    val traffic: TrafficSnapshot,
    val plan: RoutePlanner.Plan,
    val mainCheck: RouteCheck?,
    val onToggle: () -> Unit,
    val onFastest: () -> Unit,
    val onServer: (String) -> Unit,
    val onOpen: () -> Unit,
    val onExit: () -> Unit
) {
    /** Servers in use lead the list: the main server, then custom and Auto servers, then favorites. */
    val inUse: Map<String, RouteKindLabel> = plan.routes().filter { !it.main() }.associate { it.node().key() to if (it.auto()) RouteKindLabel.AUTO else RouteKindLabel.CUSTOM }
    val ordered: List<ProxyNode> = nodes.sortedWith(compareBy<ProxyNode> {
        when {
            it.key() == main?.key() -> 0
            it.key() in inUse -> 1
            it.key() in favorites -> 2
            else -> 3
        }
    }.thenBy { serverName(it).lowercase() })

    fun kind(node: ProxyNode): RouteKindLabel? = if (node.key() == main?.key()) RouteKindLabel.MAIN else inUse[node.key()]

    val statusText: String get() = when {
        busy && connected -> "Disconnecting…"
        busy -> "Connecting…"
        connected -> "Connected"
        else -> "Not connected"
    }

    val buttonLabel: String get() = when {
        busy && connected -> "Disconnecting…"
        busy -> "Connecting…"
        connected -> "Disconnect"
        else -> "Connect"
    }

    fun menu(): List<MenuEntry> = listOf(
        MenuEntry("Switch to the fastest main server", Icons.Rounded.Speed, "Tests reachable servers first", enabled = nodes.size > 1 && !working, onClick = onFastest),
        MenuEntry("Quit PerRoute", Icons.Rounded.PowerSettingsNew, "Disconnects and closes the app", danger = true, onClick = onExit)
    )

    /** "4 applications · 3 routes", or the mode outside Smart per-app. */
    val summary: String get() = if (plan.applications().isEmpty()) "No apps selected" else
        "${plural(plan.applications().size, "application")}  ·  ${plural(plan.routes().size, "route")}"

    val missing: Int get() = plan.missing().size
}

/** The last check of the main route in words: passed with its latency and age, failed, or not checked. */
internal fun quickCheckText(state: QuickAccessState, now: Long): Pair<String, Boolean?> {
    val check = state.mainCheck
    return when {
        !state.connected || check == null -> "Not checked yet" to null
        check.state == RouteCheckState.CHECKING -> "Checking…" to null
        check.state == RouteCheckState.FAILED -> "Check failed  ·  ${formatAgo(check.atMillis, now)}" to false
        check.stale(now) -> "Stale  ·  ${formatAgo(check.atMillis, now)}" to false
        check.state == RouteCheckState.PASSED -> "Passed" + (check.latencyMillis?.let { "  ·  $it ms" } ?: "") + "  ·  ${formatAgo(check.atMillis, now)}" to true
        else -> "Not checked yet" to null
    }
}

@Composable
internal fun QuickAccessWindow(
    connected: Boolean,
    busy: Boolean,
    working: Boolean,
    main: ProxyNode?,
    nodes: List<ProxyNode>,
    favorites: Set<String>,
    traffic: TrafficSnapshot,
    plan: RoutePlanner.Plan,
    mainCheck: RouteCheck?,
    onToggle: () -> Unit,
    onFastest: () -> Unit,
    onServer: (String) -> Unit,
    onOpen: () -> Unit,
    onExit: () -> Unit
) {
    val state = QuickAccessState(connected, busy, working, main, nodes, favorites, traffic, plan, mainCheck, onToggle, onFastest, onServer, onOpen, onExit)
    when (Tok.style) {
        UiStyle.NEW -> NewQuickAccess(state)
        UiStyle.SIMPLE -> SimpleQuickAccess(state)
    }
}

@Composable
private fun NewQuickAccess(state: QuickAccessState) {
    val now by rememberNow()
    Column(Modifier.fillMaxSize().background(Ink)) {
        CharcoalColors {
            Column(Modifier.fillMaxWidth().background(Ink).padding(horizontal = 22.dp, vertical = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NewMark(24.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("PerRoute", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Text(app.perroute.service.SecureUpdateService.CURRENT_VERSION, color = White.copy(.75f), fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    MenuIcon(Icons.Rounded.MoreHoriz, "More actions", state.menu(), tint = White, bordered = false)
                }
                Spacer(Modifier.height(14.dp))
                val (label, color) = when {
                    state.busy -> state.statusText.uppercase() to Amber
                    state.connected -> "CONNECTED" to Cyan
                    else -> "NOT CONNECTED" to Muted
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(color, 9.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.5.sp)
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.Top) {
                    ServerAvatar(state.main, 56.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        EllipsisText(state.main?.let(::serverName) ?: "No server selected", White, 21.sp, fontWeight = FontWeight.Bold)
                        Text(if (state.main == null) "Open PerRoute to add a server" else "Main", color = White.copy(.75f), fontSize = 14.sp)
                        state.main?.let { EllipsisText(it.endpoint(), White.copy(.75f), 12.sp) }
                        Spacer(Modifier.height(6.dp))
                        val (check, passed) = quickCheckText(state, now)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(when (passed) { true -> Icons.Rounded.CheckCircle; false -> Icons.Rounded.WarningAmber; null -> Icons.Rounded.Schedule }, null,
                                tint = when (passed) { true -> Cyan; false -> Amber; null -> Muted }, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(7.dp))
                            Text(check, color = White.copy(.9f), fontSize = 12.5.sp, maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ArrowDownward, null, tint = Color(0xFF6FB6FF), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.connected) formatMbps(state.traffic.downloadBytesPerSecond) else "—", color = White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(16.dp))
                    Divider(Modifier.height(24.dp), vertical = true)
                    Spacer(Modifier.width(16.dp))
                    Icon(Icons.Rounded.ArrowUpward, null, tint = Cyan, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.connected) formatMbps(state.traffic.uploadBytesPerSecond) else "—", color = White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(14.dp))
                PrimaryButton(state.buttonLabel, Modifier.fillMaxWidth(), icon = Icons.Rounded.PowerSettingsNew, enabled = state.main != null,
                    busy = state.busy, height = 48.dp, onClick = state.onToggle)
                Spacer(Modifier.height(12.dp))
                Divider()
                Spacer(Modifier.height(10.dp))
                Text(state.summary, color = White.copy(.85f), fontSize = 12.5.sp)
                if (state.missing > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text("${plural(state.missing, "app")} ${if (state.missing == 1) "needs" else "need"} a replacement server. Open PerRoute to fix it.",
                        color = Amber, fontSize = 11.5.sp, lineHeight = 15.sp)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp)) {
            Text("Main server choices", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("Other app routes stay unchanged.", color = Muted, fontSize = 12.5.sp)
            Spacer(Modifier.height(8.dp))
            if (state.nodes.isEmpty()) Text("No servers yet. Open PerRoute to add one.", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            else LazyColumn(Modifier.weight(1f)) {
                items(state.ordered, key = { it.key() }) { node -> QuickServerRow(state, node) }
            }
            Spacer(Modifier.height(10.dp))
            SecondaryButton("Open PerRoute", Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.OpenInNew, height = 44.dp, onClick = state.onOpen)
            Spacer(Modifier.height(8.dp))
            Text(Credit, color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.End))
        }
    }
}

/** One main-server choice: a radio, the server, its protocol and role, and whether it is a favorite. */
@Composable
internal fun QuickServerRow(state: QuickAccessState, node: ProxyNode) {
    val active = node.key() == state.main?.key()
    val shape = RoundedCornerShape(6.dp)
    Column {
        Row(
            Modifier.fillMaxWidth().height(58.dp).alpha(if (state.working && !active) .6f else 1f).focusRing(shape)
                .selectable(active, enabled = !state.working, role = Role.RadioButton) { state.onServer(node.key()) }
                .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioMark(active, 22.dp)
            Spacer(Modifier.width(14.dp))
            ServerAvatar(node, 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EllipsisText(serverName(node), White, 14.sp, Modifier.weight(1f, fill = false), FontWeight.SemiBold)
                    if (!Tok.isNew) state.kind(node)?.let { Spacer(Modifier.width(8.dp)); RoutePill(it) }
                }
                Text(protocolLabel(node) + if (Tok.isNew) state.kind(node)?.let { "  ·  ${it.label}" }.orEmpty() else "", color = Muted, fontSize = 12.sp)
            }
            val favorite = node.key() in state.favorites
            Icon(if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (favorite) "Favorite" else null,
                tint = if (favorite) Color(0xFFF2B01E) else Muted, modifier = Modifier.size(22.dp))
        }
        Divider()
    }
}

