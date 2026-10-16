package app.perroute.compose.ui.components

import app.perroute.compose.AuroraBackground
import app.perroute.compose.controller.RouteCheck
import app.perroute.compose.controller.RouteCheckState
import app.perroute.compose.controller.TrafficSnapshot
import app.perroute.compose.ui.screens.ConnectButton
import app.perroute.compose.ui.screens.rememberNow

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.model.ProxyNode
import app.perroute.service.RoutePlanner

/**
 * The tray panel: connect or disconnect, pick the main server, reopen the window. Per-app assignments
 * are summarised but never edited here. Quit lives in the overflow menu, away from the main action.
 */
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
    val now by rememberNow()
    // Servers in use lead the list: the main server, then any custom servers, then favorites.
    val inUse = plan.routes().filter { !it.main() }.mapTo(hashSetOf()) { it.node().key() }
    val ordered = nodes.sortedWith(compareBy<ProxyNode> {
        when {
            it.key() == main?.key() -> 0
            it.key() in inUse -> 1
            it.key() in favorites -> 2
            else -> 3
        }
    }.thenBy { serverName(it).lowercase() })
    Box(Modifier.fillMaxSize().background(Ink)) {
        AuroraBackground()
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource("icons/perroute-icon-256.png"), null, Modifier.size(26.dp))
                Spacer(Modifier.width(9.dp))
                Text("PerRoute", color = White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                StatusDot(if (busy) Amber else if (connected) Cyan else Muted.copy(.7f))
                Spacer(Modifier.width(6.dp))
                Text(when {
                    busy && connected -> "Disconnecting…"
                    busy -> "Connecting…"
                    connected -> "Connected"
                    else -> "Not connected"
                }, color = if (connected && !busy) Cyan else Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                MenuIcon(Icons.Rounded.MoreVert, "More actions", listOf(
                    MenuEntry("Switch to the fastest main server", Icons.Rounded.Speed, "Tests reachable servers first", enabled = nodes.size > 1 && !working, onClick = onFastest),
                    MenuEntry("Quit PerRoute", Icons.Rounded.PowerSettingsNew, "Disconnects and closes the app", danger = true, onClick = onExit)
                ))
            }
            Spacer(Modifier.height(12.dp))
            val cardBackground by animateColorAsState(
                if (connected) if (ActivePalette.isDark) Color(0xFF10352F).copy(.88f) else Color(0xFFE0F4EF)
                else if (ActivePalette.isDark) Color.White.copy(.055f) else Color.White.copy(.78f),
                animationSpec = tween(450), label = "quick-card-background"
            )
            val cardBorder by animateColorAsState(
                if (connected) Cyan.copy(.62f) else if (ActivePalette.isDark) Color.White.copy(.09f) else Color.Black.copy(.08f),
                animationSpec = tween(450), label = "quick-card-border"
            )
            Column(Modifier.fillMaxWidth().clip(GlassShape).background(cardBackground).border(1.dp, cardBorder, GlassShape).padding(13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ServerAvatar(main, 34.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            EllipsisText(main?.let(::serverName) ?: "No server selected", White, 13.5.sp, Modifier.weight(1f, fill = false), FontWeight.Bold)
                            if (main != null) { Spacer(Modifier.width(7.dp)); RouteBadge(true) }
                        }
                        EllipsisText(main?.endpoint() ?: "Open PerRoute to add a server", Muted, 10.sp)
                    }
                }
                Spacer(Modifier.height(11.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    QuickStat(Modifier.weight(1f), "Down", if (connected) formatRate(traffic.downloadBytesPerSecond) else "—", Cyan)
                    QuickStat(Modifier.weight(1f), "Up", if (connected) formatRate(traffic.uploadBytesPerSecond) else "—", Violet2)
                    QuickStat(
                        Modifier.weight(1f), "Last check",
                        when {
                            !connected || mainCheck == null -> "—"
                            mainCheck.state == RouteCheckState.CHECKING -> "Checking"
                            mainCheck.state == RouteCheckState.FAILED -> "Failed"
                            mainCheck.stale(now) -> "Stale"
                            mainCheck.latencyMillis != null -> "${mainCheck.latencyMillis} ms"
                            mainCheck.state == RouteCheckState.PASSED -> "Passed"
                            else -> "—"
                        },
                        if (connected && mainCheck?.state == RouteCheckState.FAILED) Amber else White
                    )
                }
            }
            if (plan.missing().isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                InfoBanner("${plural(plan.missing().size, "app")} ${if (plan.missing().size == 1) "needs" else "need"} a replacement server. Open PerRoute to fix it.",
                    Modifier.fillMaxWidth(), Amber, Icons.Rounded.WarningAmber)
            } else if (plan.advancedEffective() && plan.routes().size > 1) {
                Spacer(Modifier.height(8.dp))
                InfoBanner("Advanced routing: ${plural(plan.applications().size, "app")}  •  ${plural(plan.routes().size, "server")}. Manage app routes in PerRoute.",
                    Modifier.fillMaxWidth(), icon = Icons.Rounded.AltRoute)
            }
            Spacer(Modifier.height(10.dp))
            ConnectButton(connected && !busy, busy && !connected, busy && connected, enabled = main != null, modifier = Modifier.fillMaxWidth(), onClick = onToggle)
            Spacer(Modifier.height(13.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Storage, null, tint = Muted, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                SectionLabel("Main server choices")
            }
            Spacer(Modifier.height(7.dp))
            if (nodes.isEmpty()) Text("No servers yet. Open PerRoute to add one.", color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                items(ordered, key = { it.key() }) { node ->
                    val active = node.key() == main?.key()
                    val shape = RoundedCornerShape(12.dp)
                    Row(
                        Modifier.fillMaxWidth().height(40.dp).alpha(if (working && !active) .6f else 1f).focusRing(shape).clip(shape)
                            .selectable(active, enabled = !working, role = Role.RadioButton) { onServer(node.key()) }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .background(if (active) Violet.copy(.14f) else NeutralOverlay.copy(.04f))
                            .border(1.dp, if (active) Violet2.copy(.42f) else NeutralOverlay.copy(.06f), shape)
                            .padding(horizontal = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioMark(active)
                        Spacer(Modifier.width(9.dp))
                        Text(serverName(node), color = White.copy(if (active) 1f else .9f), fontSize = 12.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (node.key() in favorites) { Spacer(Modifier.width(6.dp)); Icon(Icons.Rounded.Star, "Favorite", tint = Amber, modifier = Modifier.size(13.dp)) }
                        if (active) { Spacer(Modifier.width(6.dp)); RouteBadge(true) }
                        else if (node.key() in inUse) { Spacer(Modifier.width(6.dp)); RouteBadge(false) }
                        Spacer(Modifier.width(8.dp))
                        Text(protocolLabel(node), color = Muted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            SecondaryButton("Open PerRoute", Modifier.fillMaxWidth(), icon = Icons.Rounded.Apps, onClick = onOpen)
        }
    }
}

/** A compact live value in the Quick Access panel. */
@Composable
private fun QuickStat(modifier: Modifier, label: String, value: String, accent: Color) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(NeutralOverlay.copy(.05f)).border(1.dp, NeutralOverlay.copy(.08f), RoundedCornerShape(12.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp)
    ) {
        Text(value, color = accent, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = Muted, fontSize = 9.sp)
    }
}
