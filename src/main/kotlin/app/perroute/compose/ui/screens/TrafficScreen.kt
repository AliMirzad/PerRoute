package app.perroute.compose.ui.screens

import app.perroute.compose.Shell
import app.perroute.compose.controller.BandwidthState
import app.perroute.compose.controller.BandwidthStatus
import app.perroute.compose.controller.TrafficPoint
import app.perroute.compose.ui.components.Amber
import app.perroute.compose.ui.components.AppAvatar
import app.perroute.compose.ui.components.ConfirmDialog
import app.perroute.compose.ui.components.Cyan
import app.perroute.compose.ui.components.DialogCard
import app.perroute.compose.ui.components.EllipsisText
import app.perroute.compose.ui.components.MenuButton
import app.perroute.compose.ui.components.MenuEntry
import app.perroute.compose.ui.components.MenuIcon
import app.perroute.compose.ui.components.Muted
import app.perroute.compose.ui.components.NeutralOverlay
import app.perroute.compose.ui.components.PrimaryButton
import app.perroute.compose.ui.components.Red
import app.perroute.compose.ui.components.RowShape
import app.perroute.compose.ui.components.ScreenPanel
import app.perroute.compose.ui.components.Segmented
import app.perroute.compose.ui.components.StatusDot
import app.perroute.compose.ui.components.SurfaceHigh
import app.perroute.compose.ui.components.Tooltip
import app.perroute.compose.ui.components.Violet2
import app.perroute.compose.ui.components.White
import app.perroute.compose.ui.components.appName
import app.perroute.compose.ui.components.fileName
import app.perroute.compose.ui.components.formatBytes
import app.perroute.compose.ui.components.formatRate
import app.perroute.compose.ui.components.plural

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as DrawPath
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.service.TrafficHistoryStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Which totals the lower part of the screen shows. */
private enum class TrafficView(val label: String) { Today("Today"), Month("This month"), Daily("Daily totals") }
private const val ChartWindowMillis = 90_000L

/** The next round value in the binary units the labels use, so the axis reads "10.0 MB/s" rather than "9.5 MB/s". */
private fun niceCeiling(value: Long): Long {
    var unit = 1024L
    while (unit * 1024 <= value) unit *= 1024
    for (factor in longArrayOf(1, 2, 5, 10, 20, 50, 100, 200, 500)) if (unit * factor >= value) return unit * factor
    return unit * 1024
}

/** Local byte totals and the live speed graph. Only counts are stored; never destinations. */
@Composable
internal fun TrafficScreen(shell: Shell) {
    val controller = shell.controller
    var view by remember { mutableStateOf(TrafficView.Month) }
    var appFilter by remember { mutableStateOf("") }
    var report by remember { mutableStateOf(controller.trafficReport(appFilter)) }
    var points by remember { mutableStateOf(controller.trafficPoints()) }
    LaunchedEffect(appFilter) {
        while (isActive) { report = controller.trafficReport(appFilter); points = controller.trafficPoints(); delay(1_000) }
    }
    val knownApps = (report.monthApplications().map { it.applicationPath() } + shell.apps).distinctBy { it.lowercase() }

    ScreenPanel(
        "Traffic & bandwidth", "Live traffic, usage and limits per app. Stored on this device; destinations are never recorded.",
        actions = {
            MenuIcon(Icons.Rounded.MoreVert, "More traffic actions", listOf(
                MenuEntry("Clear traffic history…", Icons.Rounded.DeleteSweep, danger = true) {
                    shell.showDialog {
                        ConfirmDialog("Clear traffic history?", "All daily, monthly and per-app totals stored on this device are deleted. This cannot be undone.",
                            "Clear history", shell::closeDialog, danger = true) {
                            shell.closeDialog(); controller.clearTrafficHistory(); report = controller.trafficReport(appFilter); points = controller.trafficPoints()
                        }
                    }
                }
            ), size = 38.dp)
        }
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Segmented(TrafficView.entries, view, { it.label }, Modifier.weight(1f)) { view = it }
                MenuButton(
                    if (appFilter.isBlank()) "All apps" else appName(appFilter),
                    listOf(MenuEntry("All apps", selected = appFilter.isBlank()) { appFilter = "" }) +
                        knownApps.map { path -> MenuEntry(appName(path), selected = appFilter.equals(path, true)) { appFilter = path } },
                    Modifier.width(170.dp), icon = Icons.Rounded.Apps, accessibleLabel = "Show totals for an application"
                )
            }
            Spacer(Modifier.height(9.dp))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val tiles: List<@Composable (Modifier) -> Unit> = listOf(
                    { m -> TotalTile(m, Icons.Rounded.ArrowDownward, "Today download", formatBytes(report.todayDownloadBytes()), Cyan) },
                    { m -> TotalTile(m, Icons.Rounded.ArrowUpward, "Today upload", formatBytes(report.todayUploadBytes()), Violet2) },
                    { m -> TotalTile(m, Icons.Rounded.ArrowDownward, "Month download", formatBytes(report.monthDownloadBytes()), Cyan) },
                    { m -> TotalTile(m, Icons.Rounded.ArrowUpward, "Month upload", formatBytes(report.monthUploadBytes()), Violet2) }
                )
                if (maxWidth < 620.dp) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tiles.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { pair.forEach { it(Modifier.weight(1f)) } }
                    }
                } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { tiles.forEach { it(Modifier.weight(1f)) } }
            }
            if (appFilter.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("Totals for ${appName(appFilter)} only.", color = Muted, fontSize = 10.sp)
            }
            Spacer(Modifier.height(9.dp))
            LiveSpeedChart(points, shell.connected)
            Spacer(Modifier.height(9.dp))
            if (shell.apps.isNotEmpty()) {
                BandwidthTable(shell)
                Spacer(Modifier.height(9.dp))
            }
            Column(Modifier.fillMaxWidth().clip(RowShape).background(NeutralOverlay.copy(.035f)).border(1.dp, NeutralOverlay.copy(.06f), RowShape).padding(12.dp)) {
                when (view) {
                    TrafficView.Today -> UsageList("Today by application", report.todayApplications(), appFilter,
                        "Nothing recorded today. Totals appear while selected apps send traffic in Smart per-app mode.")
                    TrafficView.Month -> UsageList("This month by application", report.monthApplications(), appFilter,
                        "Nothing recorded this month yet. Totals appear while selected apps send traffic in Smart per-app mode.")
                    TrafficView.Daily -> DailyList(report.days(), appFilter)
                }
            }
            Spacer(Modifier.height(7.dp))
            Text("Live speed includes all active proxy routes. Only byte counts are kept; there is no per-server or per-destination history.",
                color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}

/** A rate in bits per second, the unit limits are set in. */
internal fun formatBits(bytesPerSecond: Long): String {
    val bits = bytesPerSecond * 8.0
    return when {
        bits >= 1_000_000_000 -> "%.1f Gbps".format(java.util.Locale.ROOT, bits / 1_000_000_000)
        bits >= 1_000_000 -> "%.1f Mbps".format(java.util.Locale.ROOT, bits / 1_000_000)
        bits >= 1_000 -> "%.0f Kbps".format(java.util.Locale.ROOT, bits / 1_000)
        else -> "%.0f bps".format(java.util.Locale.ROOT, bits)
    }
}

/**
 * Every selected app with its live rate, session totals, limits, how much of each limit it uses and whether the
 * limit is being enforced right now. Limits are edited here or in Apps and apply at once while connected.
 */
@Composable
private fun BandwidthTable(shell: Shell) {
    val controller = shell.controller
    val statuses = remember(shell.traffic, shell.connected, shell.apps.toList()) { shell.apps.associateWith { controller.bandwidthStatus(it) } }
    fun edit(path: String) = shell.showDialog {
        DialogCard("Bandwidth limit for ${appName(path)}", "The limit is the app's total across all of its connections, enforced by the traffic gateway.",
            shell::closeDialog, width = 420.dp, actions = { PrimaryButton("Done") { shell.closeDialog() } }) {
            BandwidthEditor(shell, path)
        }
    }
    Column(Modifier.fillMaxWidth().clip(RowShape).background(NeutralOverlay.copy(.035f)).border(1.dp, NeutralOverlay.copy(.06f), RowShape).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Bandwidth by app", color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            val throttled = statuses.values.count { it.state == BandwidthState.THROTTLING }
            val active = shell.traffic.applications.count { it.active }
            Text("${plural(active, "active app")}" + if (throttled > 0) "  •  $throttled throttling" else "", color = if (throttled > 0) Amber else Muted, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text("Rates and limits in megabits per second", color = Muted, fontSize = 9.5.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Text("App", color = Muted, fontSize = 9.5.sp, modifier = Modifier.width(130.dp))
            Text("Current ↓ / ↑", color = Muted, fontSize = 9.5.sp, modifier = Modifier.weight(1f))
            Text("Session ↓ / ↑", color = Muted, fontSize = 9.5.sp, modifier = Modifier.weight(1f))
            Text("Limit ↓ / ↑", color = Muted, fontSize = 9.5.sp, modifier = Modifier.weight(1f))
            Text("Utilization", color = Muted, fontSize = 9.5.sp, modifier = Modifier.weight(1f))
            Text("Status", color = Muted, fontSize = 9.5.sp, modifier = Modifier.width(92.dp))
            Spacer(Modifier.width(30.dp))
        }
        shell.apps.forEach { path ->
            val usage = shell.traffic.applications.firstOrNull { it.path.equals(path, true) }
            val status = statuses[path] ?: controller.bandwidthStatus(path)
            val policy = status.policy
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.width(130.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppAvatar(fileName(path), path, 22.dp)
                    Spacer(Modifier.width(8.dp))
                    EllipsisText(appName(path), White, 11.sp, Modifier.weight(1f), FontWeight.SemiBold)
                }
                Column(Modifier.weight(1f)) {
                    Text("↓ ${formatBits(usage?.downloadBytesPerSecond ?: 0)}", color = Cyan, fontSize = 9.5.sp, maxLines = 1)
                    Text("↑ ${formatBits(usage?.uploadBytesPerSecond ?: 0)}", color = Violet2, fontSize = 9.5.sp, maxLines = 1)
                }
                Column(Modifier.weight(1f)) {
                    Text(formatBytes(usage?.downloadBytes ?: 0), color = White.copy(.86f), fontSize = 9.5.sp, maxLines = 1)
                    Text(formatBytes(usage?.uploadBytes ?: 0), color = White.copy(.86f), fontSize = 9.5.sp, maxLines = 1)
                }
                Column(Modifier.weight(1f)) {
                    Text(if (policy.downloadBitsPerSecond() > 0) formatBits(policy.downloadBytesPerSecond()) else "Unlimited", color = White.copy(.86f), fontSize = 9.5.sp, maxLines = 1)
                    Text(if (policy.uploadBitsPerSecond() > 0) formatBits(policy.uploadBytesPerSecond()) else "Unlimited", color = White.copy(.86f), fontSize = 9.5.sp, maxLines = 1)
                }
                Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    val downShare = policy.downloadBytesPerSecond().takeIf { it > 0 }?.let { (usage?.downloadBytesPerSecond ?: 0).toFloat() / it }
                    val upShare = policy.uploadBytesPerSecond().takeIf { it > 0 }?.let { (usage?.uploadBytesPerSecond ?: 0).toFloat() / it }
                    UtilizationLine(downShare, Cyan)
                    UtilizationLine(upShare, Violet2)
                }
                Box(Modifier.width(92.dp)) { CompactBandwidthStatus(status, shell.connected && usage?.active != true) }
                MenuIcon(Icons.Rounded.MoreVert, "Limits for ${appName(path)}", listOf(
                    MenuEntry("Edit limits…", Icons.Rounded.Speed) { edit(path) },
                    MenuEntry("Set unlimited", Icons.Rounded.AllInclusive, enabled = !policy.unlimited()) {
                        shell.run { controller.setBandwidthPolicy(path, app.perroute.model.BandwidthPolicy.UNLIMITED) }
                    }
                ), size = 28.dp)
            }
        }
        if (statuses.values.any { it.state == BandwidthState.NOT_ENFORCED }) {
            Spacer(Modifier.height(6.dp))
            Text(statuses.values.first { it.state == BandwidthState.NOT_ENFORCED }.detail, color = Red, fontSize = 9.5.sp, lineHeight = 13.sp)
        }
    }
}

@Composable
private fun UtilizationLine(share: Float?, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        UsageBar(Modifier.weight(1f), share ?: 0f, if (share != null && share >= .9f) Amber else color)
        Text(share?.let { "${(it * 100).toInt().coerceAtMost(999)}%" } ?: "—", color = Muted, fontSize = 8.5.sp,
            modifier = Modifier.width(34.dp).padding(start = 5.dp), maxLines = 1)
    }
}

@Composable
private fun CompactBandwidthStatus(status: BandwidthStatus, idle: Boolean) {
    val (label, color) = when (status.state) {
        BandwidthState.UNLIMITED -> (if (idle) "Idle" else "Unlimited") to Muted
        BandwidthState.ENFORCED -> "Enforced" to Cyan
        BandwidthState.THROTTLING -> "Throttling" to Amber
        BandwidthState.PENDING -> "Pending" to Violet2
        BandwidthState.NOT_ENFORCED -> "Not enforced" to Red
    }
    Tooltip(status.detail) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color, 7.dp)
            Spacer(Modifier.width(5.dp))
            Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** A download or upload total. */
@Composable
private fun TotalTile(modifier: Modifier, icon: ImageVector, label: String, value: String, accent: Color) {
    Row(
        modifier.clip(RowShape).background(NeutralOverlay.copy(.04f)).border(1.dp, accent.copy(.16f), RowShape).padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(9.dp))
        Column {
            Text(label.uppercase(), color = Muted, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, letterSpacing = .5.sp, maxLines = 1)
            Text(value, color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** A horizontal bar showing a share of the largest value. */
@Composable
private fun UsageBar(modifier: Modifier, fraction: Float, color: Color) {
    Box(modifier.height(6.dp).clip(CircleShape).background(NeutralOverlay.copy(.08f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(color))
    }
}

/** Download and upload per application. */
@Composable
private fun UsageList(title: String, entries: List<TrafficHistoryStore.Entry>, filter: String, empty: String) {
    val shown = entries.filter { filter.isBlank() || it.applicationPath().equals(filter, true) }
    Text(title, color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    if (shown.isEmpty()) { Text(empty, color = Muted, fontSize = 10.5.sp, lineHeight = 14.sp); return }
    val maxDown = entries.maxOf { it.downloadBytes() }.coerceAtLeast(1L)
    val maxUp = entries.maxOf { it.uploadBytes() }.coerceAtLeast(1L)
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text("App", color = Muted, fontSize = 9.5.sp, modifier = Modifier.width(150.dp))
        Text("Download", color = Muted, fontSize = 9.5.sp, modifier = Modifier.weight(1f))
        Text("Upload", color = Muted, fontSize = 9.5.sp, modifier = Modifier.weight(1f))
    }
    shown.forEach { item ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.width(150.dp), verticalAlignment = Alignment.CenterVertically) {
                AppAvatar(fileName(item.applicationPath()), item.applicationPath(), 22.dp)
                Spacer(Modifier.width(8.dp))
                EllipsisText(appName(item.applicationPath()), White, 11.sp, Modifier.weight(1f))
            }
            Row(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "Download ${formatBytes(item.downloadBytes())}" },
                verticalAlignment = Alignment.CenterVertically) {
                UsageBar(Modifier.weight(1f), item.downloadBytes().toFloat() / maxDown, Cyan)
                Text(formatBytes(item.downloadBytes()), color = White.copy(.86f), fontSize = 10.sp, modifier = Modifier.width(74.dp).padding(start = 8.dp), maxLines = 1)
            }
            Row(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "Upload ${formatBytes(item.uploadBytes())}" },
                verticalAlignment = Alignment.CenterVertically) {
                UsageBar(Modifier.weight(1f), item.uploadBytes().toFloat() / maxUp, Violet2)
                Text(formatBytes(item.uploadBytes()), color = White.copy(.86f), fontSize = 10.sp, modifier = Modifier.width(74.dp).padding(start = 8.dp), maxLines = 1)
            }
        }
    }
}

/** Download and upload per day of the current month. */
@Composable
private fun DailyList(days: List<TrafficHistoryStore.Day>, filter: String) {
    Text("Daily totals this month" + if (filter.isBlank()) "" else " for ${appName(filter)}", color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    if (days.isEmpty()) { Text("No day of this month has recorded traffic yet.", color = Muted, fontSize = 10.5.sp); return }
    val maxDown = days.maxOf { it.downloadBytes() }.coerceAtLeast(1L)
    val maxUp = days.maxOf { it.uploadBytes() }.coerceAtLeast(1L)
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text("Day", color = Muted, fontSize = 9.5.sp, modifier = Modifier.width(110.dp))
        Text("Download", color = Muted, fontSize = 9.5.sp, modifier = Modifier.weight(1f))
        Text("Upload", color = Muted, fontSize = 9.5.sp, modifier = Modifier.weight(1f))
    }
    days.forEach { day ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(day.date(), color = White, fontSize = 11.sp, modifier = Modifier.width(110.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                UsageBar(Modifier.weight(1f), day.downloadBytes().toFloat() / maxDown, Cyan)
                Text(formatBytes(day.downloadBytes()), color = White.copy(.86f), fontSize = 10.sp, modifier = Modifier.width(74.dp).padding(start = 8.dp), maxLines = 1)
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                UsageBar(Modifier.weight(1f), day.uploadBytes().toFloat() / maxUp, Violet2)
                Text(formatBytes(day.uploadBytes()), color = White.copy(.86f), fontSize = 10.sp, modifier = Modifier.width(74.dp).padding(start = 8.dp), maxLines = 1)
            }
        }
    }
}

/** The last 90 seconds of download and upload speed, with exact values under the pointer. */
@Composable
private fun LiveSpeedChart(points: List<TrafficPoint>, connected: Boolean) {
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
    val latest = visible.lastOrNull()

    Column(Modifier.fillMaxWidth().height(186.dp).clip(RowShape).background(NeutralOverlay.copy(.035f)).border(1.dp, NeutralOverlay.copy(.06f), RowShape).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Live speed · last 90 seconds", color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (hovered == null) {
                StatusDot(Cyan)
                Spacer(Modifier.width(5.dp))
                Text("Download" + (latest?.let { "  ${formatRate(it.downloadBytesPerSecond)}" } ?: ""), color = Muted, fontSize = 9.5.sp)
                Spacer(Modifier.width(10.dp))
                StatusDot(Violet2)
                Spacer(Modifier.width(5.dp))
                Text("Upload" + (latest?.let { "  ${formatRate(it.uploadBytesPerSecond)}" } ?: ""), color = Muted, fontSize = 9.5.sp)
            } else {
                val secondsAgo = ((now - hovered.atMillis) / 1_000).coerceAtLeast(0)
                Text(if (secondsAgo == 0L) "now" else "$secondsAgo s ago", color = Muted, fontSize = 9.5.sp)
                Spacer(Modifier.width(10.dp))
                Text("↓ ${formatRate(hovered.downloadBytesPerSecond)}", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Text("↑ ${formatRate(hovered.uploadBytesPerSecond)}", color = Violet2, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.width(58.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Text(if (visible.isEmpty()) "" else formatRate(scaleMax), color = Muted.copy(.85f), fontSize = 8.5.sp, maxLines = 1)
                Text(if (visible.isEmpty()) "" else formatRate(scaleMax / 2), color = Muted.copy(.85f), fontSize = 8.5.sp, maxLines = 1)
                Text("0", color = Muted.copy(.85f), fontSize = 8.5.sp)
            }
            Box(Modifier.weight(1f).fillMaxSize()) {
                Canvas(
                    Modifier.fillMaxSize()
                        .semantics { contentDescription = "Live download and upload speed over the last 90 seconds" }
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
                    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.BarChart, null, tint = Muted, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(
                            when {
                                !connected -> "Connect to start recording speed"
                                points.isEmpty() -> "Waiting for the first samples…"
                                else -> "No traffic samples in the last 90 seconds"
                            }, color = Muted, fontSize = 10.5.sp
                        )
                    }
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
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().padding(start = 58.dp)) {
            Text("90s ago", color = Muted.copy(.75f), fontSize = 8.5.sp)
            Spacer(Modifier.weight(1f))
            Text("45s ago", color = Muted.copy(.75f), fontSize = 8.5.sp)
            Spacer(Modifier.weight(1f))
            Text("now", color = Muted.copy(.75f), fontSize = 8.5.sp)
        }
    }
}
