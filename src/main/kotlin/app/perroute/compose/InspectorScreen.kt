package app.perroute.compose

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Timeline
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Which connections the inspector lists. */
private enum class InspectorStatusFilter(val label: String) { ALL("All status"), ACTIVE("Active"), PROBLEMS("Failed or blocked"), CLOSED("Closed") }

/**
 * What each selected application is connected to and how each connection is routed. Three kinds of facts are
 * kept apart everywhere: what PerRoute is configured to do, what the running core was observed doing, and what
 * an external check of the route verified. Nothing is stored; the list lives in memory and is bounded.
 */
@Composable
internal fun ConnectionInspector(shell: Shell) {
    val controller = shell.controller
    val clipboard = LocalClipboardManager.current
    var rows by remember { mutableStateOf<List<InspectorRow>>(emptyList()) }
    var live by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var appFilter by remember { mutableStateOf<String?>(null) }
    var routeFilter by remember { mutableStateOf<String?>(null) }
    var statusFilter by remember { mutableStateOf(InspectorStatusFilter.ALL) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    val available = shell.connected && controller.inspectorAvailable()

    // One refresh every two seconds while the list is live and visible; nothing runs while paused.
    LaunchedEffect(available, live) {
        if (!available) { rows = emptyList(); return@LaunchedEffect }
        while (isActive) {
            rows = withContext(Dispatchers.IO) { controller.inspectorRows() }
            if (!live) break
            delay(2_000)
        }
    }

    if (!shell.connected || !shell.perApp) {
        EmptyState(Icons.Rounded.MonitorHeart, if (shell.perApp) "Connect to inspect connections" else "Available in Smart per-app",
            if (shell.perApp) "The inspector lists each selected app's connections while PerRoute is connected. Nothing is recorded or kept afterwards."
            else "Only Smart per-app gives every selected app its own path through the core, which is what the inspector observes.",
            Modifier.fillMaxSize())
        return
    }

    val filtered = rows.filter { row ->
        (appFilter == null || row.applicationPath.equals(appFilter, true)) &&
            (routeFilter == null || (row.observed.routeLabel ?: row.configured.routeLabel) == routeFilter) &&
            when (statusFilter) {
                InspectorStatusFilter.ALL -> true
                InspectorStatusFilter.ACTIVE -> row.status == "ACTIVE" || row.status == "CONNECTING" || row.status == "SEEN"
                InspectorStatusFilter.PROBLEMS -> row.status == "FAILED" || row.status == "BLOCKED" || row.status == "NO_RESPONSE"
                InspectorStatusFilter.CLOSED -> row.status == "CLOSED"
            } &&
            (query.isBlank() || appName(row.applicationPath).contains(query, true) || row.destination.contains(query, true) ||
                row.observed.sniffedDomain.orEmpty().contains(query, true))
    }
    val selected = rows.firstOrNull { it.id == selectedId }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val showDetails = selected != null && maxWidth >= 640.dp
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextInput(query, { query = it }, "Search apps or destinations…", Modifier.weight(1f), Icons.Rounded.Search)
                    val apps = rows.map { it.applicationPath }.distinctBy { it.lowercase() }
                    MenuButton(appFilter?.let(::appName) ?: "All apps",
                        listOf(MenuEntry("All apps", selected = appFilter == null) { appFilter = null }) +
                            apps.map { path -> MenuEntry(appName(path), selected = appFilter.equals(path, true)) { appFilter = path } },
                        Modifier.width(118.dp), icon = Icons.Rounded.Apps, accessibleLabel = "Filter by app")
                    val routes = rows.mapNotNull { it.observed.routeLabel ?: it.configured.routeLabel }.distinct()
                    MenuButton(routeFilter ?: "All routes",
                        listOf(MenuEntry("All routes", selected = routeFilter == null) { routeFilter = null }) +
                            routes.map { label -> MenuEntry(label, selected = routeFilter == label) { routeFilter = label } },
                        Modifier.width(124.dp), icon = Icons.Rounded.Storage, accessibleLabel = "Filter by route")
                    MenuButton(statusFilter.label, InspectorStatusFilter.entries.map { filter ->
                        MenuEntry(filter.label, selected = statusFilter == filter) { statusFilter = filter }
                    }, Modifier.width(128.dp), icon = Icons.Rounded.Timeline, accessibleLabel = "Filter by status")
                    Segmented(listOf(true, false), live, { if (it) "Live" else "Paused" }, Modifier.width(132.dp)) { live = it }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (live) Cyan else Muted, 7.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(if (live) "Updates every 2 s  •  ${plural(filtered.size, "connection")}" else "Paused  •  ${plural(filtered.size, "connection")}",
                        color = Muted, fontSize = 10.sp)
                    Spacer(Modifier.weight(1f))
                    FactLegend()
                }
                if (!controller.inspectorUsesGateway()) {
                    Spacer(Modifier.height(6.dp))
                    InfoBanner("The traffic gateway is off, so connections come from the core's log only: no byte counts and no close events. Turn it on in Settings > Routing.",
                        Modifier.fillMaxWidth(), Amber)
                }
                Spacer(Modifier.height(7.dp))
                InspectorHeader()
                Spacer(Modifier.height(4.dp))
                if (filtered.isEmpty()) {
                    EmptyState(Icons.Rounded.MonitorHeart, if (rows.isEmpty()) "No connections yet" else "No connections match",
                        if (rows.isEmpty()) "Use one of the selected apps; its connections appear here within a couple of seconds."
                        else "Nothing fits the current search and filters.", Modifier.fillMaxSize())
                } else LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(filtered, key = { it.id }) { row ->
                        InspectorRowView(row, row.id == selectedId) { selectedId = if (selectedId == row.id) null else row.id }
                    }
                }
            }
            if (showDetails && selected != null) GlassCard(Modifier.width(290.dp).fillMaxHeight(), RowShape) {
                InspectorDetails(shell, selected, onClose = { selectedId = null }) {
                    clipboard.setText(AnnotatedString(describe(selected)))
                    shell.notify(ActionResult(true, "Connection details copied."))
                }
            }
        }
    }
}

/** A key to the three kinds of facts. */
@Composable
private fun FactLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendItem(FactKind.CONFIGURED)
        LegendItem(FactKind.OBSERVED)
        LegendItem(FactKind.VERIFIED)
    }
}

private enum class FactKind(val label: String) { CONFIGURED("Configured"), OBSERVED("Observed"), VERIFIED("Verified") }

private val FactKind.color: Color get() = when (this) {
    FactKind.CONFIGURED -> Violet2
    FactKind.OBSERVED -> Amber
    FactKind.VERIFIED -> Cyan
}

@Composable
private fun LegendItem(kind: FactKind) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(kind.color))
        Spacer(Modifier.width(4.dp))
        Text(kind.label, color = Muted, fontSize = 9.5.sp)
    }
}

/** A small tag that says which kind of fact a value is. */
@Composable
private fun FactTag(kind: FactKind) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(kind.color))
        Spacer(Modifier.width(3.dp))
        Text(kind.label, color = kind.color, fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun InspectorHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("App", color = Muted, fontSize = 9.5.sp, modifier = Modifier.weight(1.1f))
        Text("Destination", color = Muted, fontSize = 9.5.sp, modifier = Modifier.weight(1.3f))
        Text("Route", color = Muted, fontSize = 9.5.sp, modifier = Modifier.weight(1.2f))
        Text("Status", color = Muted, fontSize = 9.5.sp, modifier = Modifier.width(86.dp))
        Text("Live traffic", color = Muted, fontSize = 9.5.sp, modifier = Modifier.width(82.dp))
        Text("Last activity", color = Muted, fontSize = 9.5.sp, modifier = Modifier.width(64.dp))
    }
}

@Composable
private fun InspectorRowView(row: InspectorRow, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().focusRing(RowShape).clip(RowShape)
            .selectable(selected, role = Role.Button, onClick = onSelect).pointerHoverIcon(PointerIcon.Hand)
            .background(if (selected) Violet.copy(.14f) else NeutralOverlay.copy(.035f))
            .border(1.dp, if (selected) Violet2.copy(.55f) else NeutralOverlay.copy(.06f), RowShape)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.weight(1.1f), verticalAlignment = Alignment.CenterVertically) {
            AppAvatar(fileName(row.applicationPath), row.applicationPath, 26.dp)
            Spacer(Modifier.width(7.dp))
            Column {
                EllipsisText(appName(row.applicationPath), White, 11.sp, fontWeight = FontWeight.SemiBold)
                Text(row.protocol.ifBlank { "?" }, color = Muted, fontSize = 9.sp)
            }
        }
        Column(Modifier.weight(1.3f).padding(end = 6.dp)) {
            EllipsisText(row.destination, White.copy(.9f), 10.5.sp, fontFamily = FontFamily.Monospace)
            val domain = row.observed.sniffedDomain
            if (domain != null && !domain.equals(row.destinationHost, true)) EllipsisText(domain, Muted, 9.sp)
            else if (row.udpDestinations > 1) Text("${row.udpDestinations} destinations", color = Muted, fontSize = 9.sp)
        }
        Column(Modifier.weight(1.2f).padding(end = 6.dp)) {
            RouteLine(FactKind.CONFIGURED, row.configured.routeLabel, row.configured.kind)
            RouteLine(FactKind.OBSERVED, row.observed.routeLabel ?: "Not observed", row.observed.kind)
        }
        Box(Modifier.width(86.dp)) { StatusLabel(row) }
        Column(Modifier.width(82.dp)) {
            if (row.viaGateway) {
                Text("↓ ${formatRate(row.downloadRate)}", color = Cyan, fontSize = 9.5.sp, maxLines = 1)
                Text("↑ ${formatRate(row.uploadRate)}", color = Violet2, fontSize = 9.5.sp, maxLines = 1)
            } else Text("—", color = Muted, fontSize = 10.sp)
        }
        Text(formatAgo(row.lastActivityAt), color = Muted, fontSize = 9.5.sp, maxLines = 1, modifier = Modifier.width(64.dp))
    }
}

@Composable
private fun RouteLine(kind: FactKind, label: String, route: RouteKind) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(kind.color))
        Spacer(Modifier.width(5.dp))
        EllipsisText(label, when (route) {
            RouteKind.DIRECT -> Cyan
            RouteKind.BLOCK -> Red
            RouteKind.UNKNOWN -> Muted
            RouteKind.PROXY -> White.copy(.88f)
        }, 10.sp, fontWeight = if (kind == FactKind.OBSERVED) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun StatusLabel(row: InspectorRow) {
    val (label, color) = when (row.status) {
        "ACTIVE" -> "Active" to Cyan
        "CONNECTING" -> "Opening" to Muted
        "SEEN" -> "Seen" to Muted
        "CLOSED" -> "Closed" to Muted
        "BLOCKED" -> "Blocked" to Red
        "NO_RESPONSE" -> "No response" to Amber
        else -> "Failed" to Red
    }
    Tooltip(row.failure) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color, 7.dp)
            Spacer(Modifier.width(5.dp))
            Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** The details panel: configured, observed and verified facts in three separate blocks. */
@Composable
private fun InspectorDetails(shell: Shell, row: InspectorRow, onClose: () -> Unit, onCopy: () -> Unit) {
    val pids = remember(row.applicationPath) { shell.controller.processIds(row.applicationPath) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppAvatar(fileName(row.applicationPath), row.applicationPath, 32.dp)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                EllipsisText(appName(row.applicationPath), White, 13.sp, fontWeight = FontWeight.Bold)
                EllipsisText(fileName(row.applicationPath) + if (pids.isNotEmpty()) "  •  PID ${pids.joinToString(", ")}" else "", Muted, 9.5.sp)
            }
            IconAction(Icons.Rounded.Close, "Close the details", size = 26.dp, onClick = onClose)
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FactBlock(FactKind.CONFIGURED, "1. Configured", "What PerRoute is set up to do. A prediction from your rules, not a measurement.") {
                DetailRow("Route", row.configured.routeLabel)
                DetailRow("Matching rule", row.configured.ruleLabel)
                DetailRow("Rule source", row.configured.ruleSource)
                if (!row.configured.certain) Text("Some rules depend on details only the core knows (for example a domain list); the observed result is authoritative.",
                    color = Muted, fontSize = 9.sp, lineHeight = 12.sp)
            }
            FactBlock(FactKind.OBSERVED, "2. Observed", "What the running core and the traffic gateway saw for this connection.") {
                DetailRow("Process path", row.applicationPath)
                DetailRow("Destination", row.destination)
                DetailRow("Sniffed domain", row.observed.sniffedDomain ?: "Not observed")
                DetailRow("Protocol", row.protocol.ifBlank { "Unknown" })
                DetailRow("Effective route", row.observed.routeLabel ?: "Not observed yet")
                DetailRow("Core outbound", row.observed.outboundTag ?: "Not observed yet")
                DetailRow("Matched rule", row.observed.ruleLabel ?: "Not observed")
                if (row.viaGateway) {
                    DetailRow("Live traffic", "↓ ${formatRate(row.downloadRate)}   ↑ ${formatRate(row.uploadRate)}")
                    DetailRow("Total", "${formatBytes(row.downloadBytes)} down  •  ${formatBytes(row.uploadBytes)} up")
                }
                DetailRow("First seen", formatAgo(row.openedAt))
                if (row.closedAt > 0) DetailRow("Closed", formatAgo(row.closedAt))
                if (row.failure.isNotBlank()) DetailRow("Failure", row.failure)
            }
            FactBlock(FactKind.VERIFIED, "3. Verified", "The last external check of the route this connection uses. It describes the route, not this one connection.") {
                val verified = row.verified
                if (verified == null) {
                    Text("Not verified yet. Run checks in Route checks to look up the exit address of this route.", color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
                } else {
                    DetailRow("Route", verified.routeLabel)
                    DetailRow("External IP", verified.publicIp?.let { ip -> ip + (verified.country?.let { "  $it" } ?: "") } ?: "—")
                    DetailRow("Route test", if (verified.passed) "Passed" else "Failed: ${verified.detail}")
                    DetailRow("Tested", formatAgo(verified.atMillis))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Copy details", Modifier.fillMaxWidth(), icon = Icons.Rounded.ContentCopy, onClick = onCopy)
    }
}

@Composable
private fun FactBlock(kind: FactKind, title: String, subtitle: String, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(kind.color.copy(.06f)).border(1.dp, kind.color.copy(.30f), shape).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(when (kind) {
                FactKind.CONFIGURED -> Icons.Rounded.Info
                FactKind.OBSERVED -> Icons.Rounded.Timeline
                FactKind.VERIFIED -> Icons.Rounded.CheckCircle
            }, null, tint = kind.color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
            Text(title, color = kind.color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(subtitle, color = Muted, fontSize = 9.sp, lineHeight = 12.sp)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = Muted, fontSize = 9.5.sp, modifier = Modifier.width(92.dp))
        Text(value, color = White.copy(.9f), fontSize = 9.5.sp, lineHeight = 13.sp, modifier = Modifier.weight(1f))
    }
}

/** A plain-text copy of one connection, with each fact labelled. No payload exists to include. */
private fun describe(row: InspectorRow): String = buildString {
    appendLine("${appName(row.applicationPath)} (${row.applicationPath})")
    appendLine("Destination: ${row.destination} ${row.protocol}" + (row.observed.sniffedDomain?.let { " ($it)" } ?: ""))
    appendLine("Configured: ${row.configured.routeLabel}; rule: ${row.configured.ruleLabel} (${row.configured.ruleSource})")
    appendLine("Observed: ${row.observed.routeLabel ?: "not observed"}; outbound: ${row.observed.outboundTag ?: "-"}; rule: ${row.observed.ruleLabel ?: "not observed"}")
    appendLine("Status: ${row.status}" + if (row.failure.isNotBlank()) " (${row.failure})" else "")
    if (row.viaGateway) appendLine("Traffic: ${formatBytes(row.downloadBytes)} down, ${formatBytes(row.uploadBytes)} up")
    val verified = row.verified
    append("Verified: " + if (verified == null) "not verified" else "${verified.routeLabel} exit ${verified.publicIp ?: "-"} (${if (verified.passed) "passed" else "failed"}, ${formatAgo(verified.atMillis)})")
}

