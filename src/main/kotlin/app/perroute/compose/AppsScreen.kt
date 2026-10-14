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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.model.AppRoutes
import app.perroute.model.BandwidthPolicy
import app.perroute.model.ProxyNode
import app.perroute.routing.AppRouteMode
import app.perroute.routing.AutoCandidatePool
import app.perroute.routing.AutoServerSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.nio.file.Path

/** Where the application list comes from. */
private enum class AppListSource { INSTALLED, RUNNING }
/** An executable offered for selection, with the name shown for it. */
private data class AppChoice(val name: String, val path: String)
/** Display order for the three routing modes. */
private val RouteModeOrder = listOf(AppRouteMode.AUTO, AppRouteMode.MAIN, AppRouteMode.MANUAL)

/** How one application is routed in the draft: by the main server, a custom one, Auto, or a server that is gone. */
private class DraftRoute(val mode: AppRouteMode, val assignedKey: String?, val node: ProxyNode?, val label: String, val pending: Boolean) {
    val followsMain get() = mode == AppRouteMode.MAIN
    val missing get() = mode == AppRouteMode.MANUAL && assignedKey != null && node == null
}

/** Resolves the unsaved assignment of one application against the current server list. */
private fun draftRoute(shell: Shell, path: String): DraftRoute {
    val key = shell.draft.routes[AppRoutes.normalize(path)]
    val node = key?.let { wanted -> shell.nodes.firstOrNull { it.key() == wanted } }
    val mode = shell.draft.mode(path)
    return DraftRoute(mode, key, node, node?.let(::serverName) ?: key?.let(shell.controller::appRouteLabel).orEmpty(),
        mode == AppRouteMode.MANUAL && key == null)
}

/** Selecting applications and, with Advanced routing on, how each one picks its server. Edits go to the draft until Save changes. */
@Composable
internal fun AppsScreen(shell: Shell) {
    val scope = rememberCoroutineScope()
    val draft = shell.draft
    var query by remember { mutableStateOf("") }
    var runningLoading by remember { mutableStateOf(true) }
    var installedLoading by remember { mutableStateOf(true) }
    var source by remember { mutableStateOf(AppListSource.INSTALLED) }
    var running by remember { mutableStateOf<List<AppChoice>>(emptyList()) }
    var installed by remember { mutableStateOf<List<AppChoice>>(emptyList()) }
    val locked = shell.locked
    val dirty = draft.dirty(shell)

    fun refreshApplications(forceRefresh: Boolean = false) {
        scope.launch {
            runningLoading = true
            installedLoading = true
            val runningTask = async(Dispatchers.IO) {
                runCatching { shell.controller.runningApplications(forceRefresh).map { AppChoice(it.name(), it.path()) } }.getOrDefault(emptyList())
            }
            val installedTask = async(Dispatchers.IO) {
                runCatching { shell.controller.installedApplications(forceRefresh).map { AppChoice(it.name(), it.path()) } }.getOrDefault(emptyList())
            }
            try { running = runningTask.await() } finally { runningLoading = false }
            try { installed = installedTask.await() } finally { installedLoading = false }
        }
    }
    LaunchedEffect(Unit) { refreshApplications() }
    val listState = rememberLazyListState()
    LaunchedEffect(source, query) { listState.scrollToItem(0) }
    // Every application that was selected at some point keeps its row, so a browsed EXE that is
    // unticked by mistake can simply be ticked again.
    val manual = remember { mutableStateListOf<String>() }
    (shell.apps + draft.selected).forEach { path -> if (manual.none { it.equals(path, true) }) manual += path }

    val selectedRoutes = draft.selected.map { it to draftRoute(shell, it) }
    val unresolved = if (draft.advanced) selectedRoutes.filter { it.second.missing } else emptyList()
    val serverCount = if (!draft.advanced) 1 else {
        val mainKey = shell.mainNode?.key()
        (selectedRoutes.mapNotNull { (_, route) ->
            when {
                route.mode == AppRouteMode.AUTO -> "auto:${route.label}"
                route.followsMain || route.missing || route.pending -> mainKey
                else -> route.assignedKey
            }
        } + listOfNotNull(mainKey)).distinct().size
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 740.dp
        val pickerWidth = if (maxWidth >= 860.dp) 166.dp else 140.dp
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                GlassCard(Modifier.weight(1f).fillMaxHeight()) {
                    Column(Modifier.fillMaxSize().padding(15.dp)) {
                        Text("Applications", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(if (draft.advanced) "Select apps, then choose how each one picks its server." else "Select the apps that should use the proxy.",
                            color = Muted, fontSize = 11.sp)
                        Spacer(Modifier.height(10.dp))
                        AdvancedRow(
                            title = "Advanced routing",
                            detail = when {
                                !shell.perApp -> "Per-app servers apply in Smart per-app only. Current mode: ${modeLabel(shell.mode)}."
                                draft.advanced -> "Each app follows Main, a server you pick, or Auto."
                                else -> "Off: every selected app uses the main server."
                            },
                            checked = draft.advanced, enabled = !locked
                        ) { draft.advanced = it }
                        if (locked) {
                            Spacer(Modifier.height(8.dp))
                            InfoBanner("Disconnect to change the selection, app routes or Advanced routing. Bandwidth limits can be changed now.",
                                Modifier.fillMaxWidth(), Amber, Icons.Rounded.Lock)
                        } else if (!shell.perApp) {
                            Spacer(Modifier.height(8.dp))
                            InfoBanner("The app selection is used by Smart per-app. You are in ${modeLabel(shell.mode)} mode.", Modifier.fillMaxWidth()) {
                                SecondaryButton("Change mode", trailing = Icons.Rounded.ChevronRight) { shell.openSettings(SettingsCategory.Connection) }
                            }
                        } else if (unresolved.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            InfoBanner("${plural(unresolved.size, "app")} ${if (unresolved.size == 1) "needs" else "need"} a replacement server before you can connect: " +
                                joinNames(unresolved.map { appName(it.first) }) + ".", Modifier.fillMaxWidth(), Amber, Icons.Rounded.WarningAmber)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilterChip("Installed apps", source == AppListSource.INSTALLED) { source = AppListSource.INSTALLED }
                            FilterChip("Running apps", source == AppListSource.RUNNING) { source = AppListSource.RUNNING }
                            TextInput(query, { query = it }, "Search applications", Modifier.weight(1f), Icons.Rounded.Search)
                            IconAction(Icons.Rounded.Refresh, "Refresh the application list", size = 38.dp) { refreshApplications(true) }
                        }
                        Spacer(Modifier.height(9.dp))
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            val loading = if (source == AppListSource.RUNNING) runningLoading else installedLoading
                            val available = if (source == AppListSource.RUNNING) running else installed
                            // Apps added with Browse, or no longer discovered, still need a row to be managed.
                            val extra = manual.filter { path -> available.none { it.path.equals(path, true) } }
                                .map { AppChoice(fileName(it), it) }
                            // Saved selections lead the list. The order is fixed when the list loads, so ticking
                            // a box never makes its row jump away from the pointer.
                            val pinned = remember(available, shell.apps.toList()) { shell.apps.mapTo(hashSetOf()) { it.lowercase() } }
                            val filtered = (extra + available).filter { query.isBlank() || it.name.contains(query, true) || it.path.contains(query, true) }
                                .sortedByDescending { it.path.lowercase() in pinned }
                            when {
                                // Rows are only shown once discovery is done, so the list never reorders under the pointer.
                                loading -> LoadingState("Looking for applications…", Modifier.fillMaxSize())
                                filtered.isEmpty() && query.isNotBlank() -> EmptyState(Icons.Rounded.SearchOff, "No applications match \"$query\"",
                                    "Try another name, switch between installed and running apps, or browse for the EXE.", Modifier.fillMaxSize()) {
                                    SecondaryButton("Clear search") { query = "" }
                                }
                                filtered.isEmpty() -> EmptyState(Icons.Rounded.Apps, "No applications found",
                                    if (source == AppListSource.RUNNING) "Start the application first, or pick it from installed apps." else "Browse for the EXE to add an application manually.",
                                    Modifier.fillMaxSize()) { SecondaryButton("Refresh", icon = Icons.Rounded.Refresh) { refreshApplications(true) } }
                                else -> LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    items(filtered, key = { it.path.lowercase() }) { app ->
                                        AppRow(shell, app, draft.isSelected(app.path), locked, pickerWidth, wide)
                                    }
                                }
                            }
                        }
                    }
                }
                if (wide) GlassCard(Modifier.width(236.dp).fillMaxHeight()) {
                    val focused = draft.focused?.takeIf { path -> draft.selected.any { it.equals(path, true) } }
                    if (focused != null) FocusedPanel(shell, focused, locked)
                    else SelectedPanel(shell, selectedRoutes, serverCount, locked)
                }
            }
            Spacer(Modifier.height(11.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (locked) Icons.Rounded.Lock else Icons.Rounded.AltRoute, null, tint = Muted, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        when {
                            locked -> "Routes can be edited while disconnected."
                            dirty -> "Unsaved changes  •  ${plural(draft.selected.size, "app")}" + if (draft.advanced) "  •  ${plural(serverCount, "route")}" else ""
                            else -> "${plural(draft.selected.size, "app")} selected" + if (draft.advanced) "  •  ${plural(serverCount, "route")}" else ""
                        },
                        color = if (dirty && !locked) Amber else Muted, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                SecondaryButton("Browse for an EXE", icon = Icons.Rounded.FolderOpen, enabled = !locked) {
                    val owner = shell.hostWindow ?: return@SecondaryButton
                    val dialog = FileDialog(owner, "Choose application", FileDialog.LOAD).apply {
                        filenameFilter = java.io.FilenameFilter { _, name -> name.endsWith(".exe", true) }
                        isVisible = true
                    }
                    val file = dialog.file
                    if (file != null) {
                        val path = Path.of(dialog.directory, file).toAbsolutePath().normalize().toString()
                        if (!draft.isSelected(path)) draft.selected += path
                        draft.focused = path
                    }
                }
                SecondaryButton("Cancel", enabled = dirty) { shell.discardDraft() }
                PrimaryButton("Save changes", enabled = dirty && !locked, busy = shell.busy) { shell.saveDraft() }
            }
        }
    }
}

/** The Advanced routing switch with its explanation. Apps and Settings both show it; they change the same setting. */
@Composable
internal fun AdvancedRow(title: String, detail: String, checked: Boolean, enabled: Boolean, badge: Boolean = false, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RowShape).background(if (checked) Violet.copy(.10f) else NeutralOverlay.copy(.04f))
            .border(1.dp, if (checked) Violet.copy(.30f) else NeutralOverlay.copy(.07f), RowShape).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Violet.copy(.14f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.AltRoute, null, tint = Violet2, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                if (badge) { Spacer(Modifier.width(8.dp)); Badge("Advanced", Violet2) }
            }
            Text(detail, color = Muted, fontSize = 10.5.sp, lineHeight = 14.sp)
        }
        Spacer(Modifier.width(10.dp))
        ToggleSwitch(checked, title, enabled, onToggle)
    }
}

/** One application: its selection box and, when Advanced routing is on, its mode and server. */
@Composable
private fun AppRow(shell: Shell, app: AppChoice, selected: Boolean, locked: Boolean, pickerWidth: Dp, wide: Boolean) {
    val draft = shell.draft
    val focused = draft.focused?.equals(app.path, true) == true
    Row(
        Modifier.fillMaxWidth().alpha(if (locked) .82f else 1f).clip(RowShape)
            .background(if (selected) Violet.copy(if (focused) .17f else .11f) else NeutralOverlay.copy(.035f))
            .border(1.dp, if (focused) Violet2.copy(.65f) else if (selected) Violet.copy(.34f) else NeutralOverlay.copy(.06f), RowShape),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Only this part toggles the selection. The mode and server controls sit beside it, not inside it,
        // so using them can never select or unselect the application.
        Row(
            Modifier.weight(1f).focusRing(RowShape).clip(RowShape)
                .toggleable(selected, enabled = !locked, role = Role.Checkbox) {
                    draft.toggle(app.path)
                    draft.focused = if (draft.isSelected(app.path)) app.path else draft.focused?.takeUnless { it.equals(app.path, true) }
                }
                .then(if (locked) Modifier else Modifier.pointerHoverIcon(PointerIcon.Hand))
                .semantics { contentDescription = "Route ${app.name.removeSuffix(".exe")} through the proxy" }
                .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppAvatar(app.name, app.path)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                EllipsisText(app.name.removeSuffix(".exe"), White, 12.5.sp, fontWeight = FontWeight.SemiBold)
                EllipsisText(app.path, Muted, 9.sp)
            }
            Spacer(Modifier.width(10.dp))
            CheckMark(selected)
        }
        if (draft.advanced) {
            if (selected) {
                Segmented(RouteModeOrder, draft.mode(app.path), { it.label() }, Modifier.width(if (wide) 190.dp else 172.dp),
                    enabled = !locked) { mode -> draft.setMode(app.path, mode); draft.focused = app.path }
                Spacer(Modifier.width(6.dp))
            }
            Box(Modifier.width(pickerWidth).padding(end = 8.dp)) {
                if (selected) RouteCell(shell, app.path, !locked) { draft.focused = app.path }
                else Text("Direct", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 10.dp))
            }
        } else if (selected && shell.controller.bandwidthPolicy(app.path) != BandwidthPolicy.UNLIMITED) {
            Badge("Limited", Amber, icon = Icons.Rounded.Speed)
            Spacer(Modifier.width(10.dp))
        }
    }
}

/** The server one selected app uses, by its mode: the main server, a server picker, or Auto's current decision. */
@Composable
private fun RouteCell(shell: Shell, path: String, enabled: Boolean, onFocus: () -> Unit) {
    val route = draftRoute(shell, path)
    when (route.mode) {
        AppRouteMode.MANUAL -> RoutePicker(shell, path, enabled)
        AppRouteMode.MAIN -> Row(Modifier.fillMaxWidth().height(36.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            EllipsisText(shell.mainNode?.let(::serverName) ?: "No main server", White.copy(.9f), 11.sp, Modifier.weight(1f), FontWeight.SemiBold)
            Spacer(Modifier.width(5.dp))
            RouteBadge(true)
        }
        AppRouteMode.AUTO -> {
            val pool = AutoCandidatePool.decode(shell.draft.pools[AppRoutes.normalize(path)])
            val decision = remember(path, pool, shell.nodes.size, shell.activeRoutes) { shell.controller.previewAutoDecision(path, pool) }
            val node = decision.nodeKey()?.let { key -> shell.nodes.firstOrNull { it.key() == key } }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).pressable(RoundedCornerShape(11.dp), label = "Details of ${appName(path)}", onClick = onFocus)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                EllipsisText(node?.let(::serverName) ?: "No server", White.copy(.92f), 11.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge("Auto", Cyan)
                    Spacer(Modifier.width(5.dp))
                    EllipsisText(decision.summary(), if (decision.reason() == AutoServerSelector.Reason.PROVISIONAL_MAIN) Amber else Cyan, 9.sp)
                }
            }
        }
    }
}

/** Chooses the server of an app in Manual mode. */
@Composable
private fun RoutePicker(shell: Shell, path: String, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    val route = draftRoute(shell, path)
    val latencies = remember(open) { shell.controller.cachedLatencies() }
    val scores = remember(open) { if (open) shell.controller.qualityScores() else emptyMap() }
    Box {
        Row(
            Modifier.fillMaxWidth().height(36.dp).alpha(if (enabled) 1f else .6f)
                .pressable(RoundedCornerShape(11.dp), enabled, label = "Server for ${appName(path)}: ${if (route.missing) "missing" else route.node?.let(::serverName) ?: "not chosen"}") {
                    filter = ""; open = true
                }
                .background(if (route.missing || route.pending) Amber.copy(.10f) else NeutralOverlay.copy(.06f))
                .border(1.dp, if (route.missing || route.pending) Amber.copy(.40f) else NeutralOverlay.copy(.11f), RoundedCornerShape(11.dp))
                .padding(start = 8.dp, end = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                route.missing -> {
                    Icon(Icons.Rounded.WarningAmber, null, tint = Amber, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    EllipsisText("Missing: ${route.label.ifBlank { "removed server" }}", Amber, 10.5.sp, Modifier.weight(1f), FontWeight.SemiBold)
                }
                route.pending -> EllipsisText("Choose a server", Amber, 10.5.sp, Modifier.weight(1f), FontWeight.SemiBold)
                else -> {
                    EllipsisText(route.node?.let(::serverName) ?: "No server", White.copy(.92f), 11.sp, Modifier.weight(1f), FontWeight.SemiBold)
                    Spacer(Modifier.width(5.dp))
                    RouteBadge(false)
                }
            }
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Rounded.UnfoldMore, null, tint = Muted, modifier = Modifier.size(15.dp))
        }
        // Favorites lead the list and carry a star, so they are easy to find among many servers.
        val favorites = remember(open) { shell.nodes.filter { shell.controller.isFavorite(it.key()) }.mapTo(hashSetOf()) { it.key() } }
        val candidates = shell.nodes
            .filter { node ->
                filter.isBlank() || serverName(node).contains(filter, true) || node.endpoint().contains(filter, true) ||
                    shell.controller.nodeTag(node.key()).contains(filter, true)
            }
            .sortedByDescending { it.key() in favorites }
        val entries = candidates.map { node ->
            val favorite = node.key() in favorites
            val tag = shell.controller.nodeTag(node.key())
            val latency = latencies[node.key()]?.takeIf { it >= 0 }?.let { "$it ms" }
            MenuEntry(serverName(node), icon = if (favorite) Icons.Rounded.Star else null,
                detail = listOfNotNull(if (favorite) "Favorite" else null, protocolLabel(node),
                    PerRouteController.sourceLabel(shell.controller.nodeSource(node.key())), tag.takeIf { it.isNotBlank() }?.let { "#$it" }, latency,
                    scores[node.key()]?.let { qualitySummary(it) })
                    .joinToString("  •  "),
                selected = route.assignedKey == node.key()) { shell.draft.assign(path, node.key()) }
        }
        PopupMenu(open, { open = false }, entries, minWidth = 280.dp, header = if (shell.nodes.size > 8) ({
            Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                TextInput(filter, { filter = it }, "Filter servers", icon = Icons.Rounded.Search)
            }
        }) else null)
    }
}

/** The side panel for one application: its route mode, Auto's decision and its bandwidth limits. */
@Composable
private fun FocusedPanel(shell: Shell, path: String, locked: Boolean) {
    val draft = shell.draft
    val route = draftRoute(shell, path)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.Rounded.ChevronLeft, "Back to all selected apps", size = 26.dp) { draft.focused = null }
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f)) {
                Text("Selected", color = White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                EllipsisText(appName(path), Cyan, 11.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(NeutralOverlay.copy(.05f)).padding(start = 8.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppAvatar(fileName(path), path, 30.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                EllipsisText(appName(path), White, 11.5.sp, fontWeight = FontWeight.SemiBold)
                EllipsisText(path, Muted, 8.5.sp)
            }
            IconAction(Icons.Rounded.Close, "Remove ${appName(path)} from the selection", enabled = !locked, size = 26.dp) {
                draft.selected.removeAll { it.equals(path, true) }
                draft.focused = null
            }
        }
        Spacer(Modifier.height(11.dp))
        Text("Route mode", color = White, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        if (!draft.advanced) {
            Text("Turn on Advanced routing to choose Main, Manual or Auto for each app. Now it uses the main server.",
                color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
        } else {
            Segmented(RouteModeOrder, route.mode, { it.label() }, Modifier.fillMaxWidth(), enabled = !locked) { draft.setMode(path, it) }
            Spacer(Modifier.height(9.dp))
            DecisionCard(shell, path, route)
        }
        Spacer(Modifier.height(13.dp))
        BandwidthEditor(shell, path)
        if (locked && draft.advanced) {
            Spacer(Modifier.height(10.dp))
            InfoBanner("Route modes change while disconnected. Limits apply right away.", Modifier.fillMaxWidth(), Amber, Icons.Rounded.Lock)
        }
    }
}

/** What the app uses now and why: the main server, the chosen server, or Auto's decision and explanation. */
@Composable
private fun DecisionCard(shell: Shell, path: String, route: DraftRoute) {
    val shape = RoundedCornerShape(15.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(NeutralOverlay.copy(.04f)).border(1.dp, NeutralOverlay.copy(.08f), shape).padding(10.dp)) {
        Text("Current decision", color = White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        when (route.mode) {
            AppRouteMode.MAIN -> {
                val main = shell.mainNode
                ServerLine(main)
                Spacer(Modifier.height(6.dp))
                QualityLine(shell, main)
                Text("Follows the main server and changes with it.", color = Muted, fontSize = 9.5.sp, lineHeight = 13.sp)
            }
            AppRouteMode.MANUAL -> {
                ServerLine(route.node)
                Spacer(Modifier.height(6.dp))
                if (route.node != null) QualityLine(shell, route.node)
                Text(if (route.pending) "Choose a server in the list. Manual choices are never changed automatically."
                    else "You chose this server. PerRoute never changes it automatically.", color = Muted, fontSize = 9.5.sp, lineHeight = 13.sp)
            }
            AppRouteMode.AUTO -> {
                val pool = AutoCandidatePool.decode(shell.draft.pools[AppRoutes.normalize(path)])
                val decision = remember(path, pool, shell.nodes.size, shell.activeRoutes) { shell.controller.previewAutoDecision(path, pool) }
                val node = decision.nodeKey()?.let { key -> shell.nodes.firstOrNull { it.key() == key } }
                ServerLine(node)
                Spacer(Modifier.height(6.dp))
                QualityLine(shell, node)
                Text(decision.explanation(), color = Muted, fontSize = 9.5.sp, lineHeight = 13.sp)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge("Auto", Cyan, icon = Icons.Rounded.AutoAwesome)
                    Spacer(Modifier.width(6.dp))
                    EllipsisText(decision.summary(), Cyan, 9.5.sp)
                }
                Spacer(Modifier.height(8.dp))
                val tags = shell.nodes.map { shell.controller.nodeTag(it.key()) }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
                MenuButton("Choose from: ${pool.describe()}",
                    listOf(MenuEntry("All servers", selected = pool.kind() == AutoCandidatePool.Kind.ALL) { shell.draft.setPool(path, AutoCandidatePool.ALL) },
                        MenuEntry("Favorites", selected = pool.kind() == AutoCandidatePool.Kind.FAVORITES) {
                            shell.draft.setPool(path, AutoCandidatePool(AutoCandidatePool.Kind.FAVORITES, ""))
                        }) + tags.map { tag ->
                        MenuEntry("#$tag", selected = pool.kind() == AutoCandidatePool.Kind.TAG && pool.tag().equals(tag, true)) {
                            shell.draft.setPool(path, AutoCandidatePool(AutoCandidatePool.Kind.TAG, tag))
                        }
                    }, Modifier.fillMaxWidth(), accessibleLabel = "Servers Auto may choose for ${appName(path)}: ${pool.describe()}", enabled = !shell.locked, height = 34.dp)
            }
        }
    }
}

@Composable
private fun ServerLine(node: ProxyNode?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ServerAvatar(node, 24.dp)
        Spacer(Modifier.width(7.dp))
        EllipsisText(node?.let(::serverName) ?: "No server", White, 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun QualityLine(shell: Shell, node: ProxyNode?) {
    if (node == null) return
    val score = remember(node.key(), shell.activeRoutes) { shell.controller.qualityScore(node.key()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        QualityPill(score, compact = true)
        Spacer(Modifier.width(7.dp))
        EllipsisText(score.headline(), Muted, 9.5.sp)
    }
    Spacer(Modifier.height(5.dp))
}

/**
 * Download and upload limits of one app, in megabits per second. They are enforced by the traffic gateway
 * for all of the app's connections together; the status says when that is not the case and why.
 */
@Composable
internal fun BandwidthEditor(shell: Shell, path: String) {
    val controller = shell.controller
    var policy by remember(path) { mutableStateOf(controller.bandwidthPolicy(path)) }
    var down by remember(path) { mutableStateOf(policy.downloadBitsPerSecond().takeIf { it > 0 }?.let { formatMbpsInput(it) } ?: "") }
    var up by remember(path) { mutableStateOf(policy.uploadBitsPerSecond().takeIf { it > 0 }?.let { formatMbpsInput(it) } ?: "") }
    var status by remember(path) { mutableStateOf(controller.bandwidthStatus(path)) }
    LaunchedEffect(path, shell.traffic, shell.connected) { status = controller.bandwidthStatus(path) }
    val unlimited = policy.unlimited() && down.isBlank() && up.isBlank()
    fun parsed(text: String): Long? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 1_000_000).toLong() }
    val valid = (down.isBlank() || parsed(down) != null) && (up.isBlank() || parsed(up) != null)
    val next = BandwidthPolicy(parsed(down) ?: 0L, parsed(up) ?: 0L)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Bandwidth limit", color = White, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("Unlimited", color = Muted, fontSize = 10.sp)
        Spacer(Modifier.width(6.dp))
        ToggleSwitch(unlimited, "Unlimited bandwidth for ${appName(path)}") { on ->
            if (on) {
                down = ""; up = ""
                shell.run(silent = true) { controller.setBandwidthPolicy(path, BandwidthPolicy.UNLIMITED).also { policy = BandwidthPolicy.UNLIMITED; status = controller.bandwidthStatus(path) } }
            } else if (down.isBlank() && up.isBlank()) down = "10"
        }
    }
    Spacer(Modifier.height(7.dp))
    LimitField("Download", down, { down = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(7) }, "Unlimited")
    Spacer(Modifier.height(6.dp))
    LimitField("Upload", up, { up = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(7) }, "Unlimited")
    Spacer(Modifier.height(7.dp))
    val changed = next != policy
    SecondaryButton(if (changed) "Apply limits" else "Limits applied", Modifier.fillMaxWidth(), enabled = changed && valid) {
        shell.run { controller.setBandwidthPolicy(path, next).also { policy = controller.bandwidthPolicy(path); status = controller.bandwidthStatus(path) } }
    }
    Spacer(Modifier.height(7.dp))
    BandwidthStatusPill(status)
    if (!valid) Text("Enter a number in Mbps, for example 2.5.", color = Red, fontSize = 9.5.sp)
}

private fun formatMbpsInput(bits: Long): String {
    val mbps = bits / 1_000_000.0
    return if (mbps == Math.floor(mbps)) mbps.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, mbps).trimEnd('0').trimEnd('.')
}

@Composable
private fun LimitField(label: String, value: String, onChange: (String) -> Unit, hint: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 10.5.sp, modifier = Modifier.width(62.dp))
        TextInput(value, onChange, hint, Modifier.weight(1f))
        Spacer(Modifier.width(6.dp))
        Text("Mbps", color = Muted, fontSize = 10.sp)
    }
}

/** Enforced, throttling, pending or not enforced, with the reason in words. */
@Composable
internal fun BandwidthStatusPill(status: BandwidthStatus) {
    val (label, color) = when (status.state) {
        BandwidthState.UNLIMITED -> "Unlimited" to Muted
        BandwidthState.ENFORCED -> "Enforced" to Cyan
        BandwidthState.THROTTLING -> "Throttling" to Amber
        BandwidthState.PENDING -> "Pending" to Violet2
        BandwidthState.NOT_ENFORCED -> "Not enforced" to Red
    }
    val shape = RoundedCornerShape(13.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(color.copy(.09f)).border(1.dp, color.copy(.35f), shape).padding(horizontal = 10.dp, vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color)
            Spacer(Modifier.width(7.dp))
            Text(label, color = color, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
        }
        if (status.state != BandwidthState.UNLIMITED) Text(status.detail, color = Muted, fontSize = 9.sp, lineHeight = 12.sp)
    }
}

/** The side panel that lists what is selected, how each app is routed, and the main server. */
@Composable
private fun SelectedPanel(shell: Shell, selectedRoutes: List<Pair<String, DraftRoute>>, serverCount: Int, locked: Boolean) {
    val draft = shell.draft
    val main = shell.mainNode
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Text("Selected", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(plural(selectedRoutes.size, "app") + if (draft.advanced) "  •  ${plural(serverCount, "route")}" else "", color = Cyan, fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))
        if (selectedRoutes.isEmpty()) {
            Text("Nothing selected yet. Tick an application to route it through the proxy.", color = Muted, fontSize = 10.5.sp, lineHeight = 14.sp,
                modifier = Modifier.weight(1f))
        } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(selectedRoutes, key = { it.first.lowercase() }) { (path, route) ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pressable(RoundedCornerShape(12.dp), label = "Details of ${appName(path)}") { draft.focused = path }
                        .background(NeutralOverlay.copy(.05f)).padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppAvatar(fileName(path), path, 28.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        EllipsisText(appName(path), White.copy(.9f), 11.5.sp, fontWeight = FontWeight.SemiBold)
                        if (draft.advanced) EllipsisText(
                            when {
                                route.missing -> "Needs a replacement"
                                route.pending -> "Choose a server"
                                route.mode == AppRouteMode.AUTO -> "Auto"
                                route.followsMain -> main?.let(::serverName) ?: "No main server"
                                else -> route.label
                            },
                            if (route.missing || route.pending) Amber else if (route.mode == AppRouteMode.AUTO) Cyan else Muted, 9.5.sp
                        )
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = Muted, modifier = Modifier.size(15.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(NeutralOverlay.copy(.09f)))
        Spacer(Modifier.height(10.dp))
        Text("Main server", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ServerAvatar(main, 28.dp)
            Spacer(Modifier.width(8.dp))
            Column {
                EllipsisText(main?.let(::serverName) ?: "No server yet", White, 12.sp, fontWeight = FontWeight.SemiBold)
                val followers = selectedRoutes.filter { !draft.advanced || it.second.followsMain }.map { appName(it.first) }
                EllipsisText(
                    when {
                        main == null -> "Add one in Servers"
                        selectedRoutes.isEmpty() -> "No apps selected"
                        followers.size == selectedRoutes.size -> "Used by all selected apps"
                        followers.isEmpty() -> "No selected app uses it"
                        else -> "Used by ${joinNames(followers, 2)}"
                    }, Muted, 9.5.sp
                )
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(
            if (draft.advanced) "Select an app to see its decision and set bandwidth limits. Turning Advanced off keeps every choice."
            else if (draft.routes.isNotEmpty() || draft.auto.isNotEmpty()) "Saved per-app choices apply again when Advanced is turned on. Select an app for bandwidth limits."
            else "Select an app to set bandwidth limits, or turn on Advanced to give apps their own server.",
            color = Muted, fontSize = 9.5.sp, lineHeight = 13.sp
        )
    }
}
