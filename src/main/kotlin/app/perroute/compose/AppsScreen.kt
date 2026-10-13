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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
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
import app.perroute.model.ProxyNode
import app.perroute.service.RoutePlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.nio.file.Path

/** Where the application list comes from. */
private enum class AppListSource { INSTALLED, RUNNING }
/** An executable offered for selection, with the name shown for it. */
private data class AppChoice(val name: String, val path: String)

/** How one application is routed in the draft: by the main server, a custom one, or a server that is gone. */
private class DraftRoute(val assignedKey: String?, val node: ProxyNode?, val label: String) {
    val followsMain get() = assignedKey == null
    val missing get() = assignedKey != null && node == null
}

/** Resolves the unsaved assignment of one application against the current server list. */
private fun draftRoute(shell: Shell, path: String): DraftRoute {
    val key = shell.draft.routes[AppRoutes.normalize(path)]
    val node = key?.let { wanted -> shell.nodes.firstOrNull { it.key() == wanted } }
    return DraftRoute(key, node, node?.let(::serverName) ?: key?.let(shell.controller::appRouteLabel).orEmpty())
}

/** Selecting applications and, with Advanced routing on, their servers. Every edit goes to the draft and is applied only by Save changes. */
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
        (selectedRoutes.mapNotNull { (_, route) -> if (route.followsMain || route.missing) mainKey else route.assignedKey } + listOfNotNull(mainKey)).distinct().size
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 740.dp
        val pickerWidth = if (maxWidth >= 860.dp) 196.dp else 164.dp
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                GlassCard(Modifier.weight(1f).fillMaxHeight()) {
                    Column(Modifier.fillMaxSize().padding(15.dp)) {
                        Text("Applications", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(if (draft.advanced) "Select apps, then choose their server." else "Select the apps that should use the proxy.",
                            color = Muted, fontSize = 11.sp)
                        Spacer(Modifier.height(10.dp))
                        AdvancedRow(
                            title = "Advanced routing",
                            detail = when {
                                !shell.perApp -> "Custom servers apply in Smart per-app only. Current mode: ${modeLabel(shell.mode)}."
                                draft.advanced -> "Choose a different server for specific apps."
                                else -> "Off: every selected app uses the main server."
                            },
                            checked = draft.advanced, enabled = !locked
                        ) { draft.advanced = it }
                        if (locked) {
                            Spacer(Modifier.height(8.dp))
                            InfoBanner("Disconnect to change the selection, app routes or Advanced routing.", Modifier.fillMaxWidth(), Amber, Icons.Rounded.Lock)
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
                                        AppRow(shell, app, draft.isSelected(app.path), locked, pickerWidth)
                                    }
                                }
                            }
                        }
                    }
                }
                if (wide) GlassCard(Modifier.width(226.dp).fillMaxHeight()) {
                    SelectedPanel(shell, selectedRoutes, serverCount, locked)
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
                            dirty -> "Unsaved changes  •  ${plural(draft.selected.size, "app")}" + if (draft.advanced) "  •  ${plural(serverCount, "server")}" else ""
                            else -> "${plural(draft.selected.size, "app")} selected" + if (draft.advanced) "  •  ${plural(serverCount, "server")}" else ""
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

/** One application: its selection box and, when Advanced routing is on, its server picker. */
@Composable
private fun AppRow(shell: Shell, app: AppChoice, selected: Boolean, locked: Boolean, pickerWidth: Dp) {
    val draft = shell.draft
    Row(
        Modifier.fillMaxWidth().alpha(if (locked) .72f else 1f).clip(RowShape)
            .background(if (selected) Violet.copy(.11f) else NeutralOverlay.copy(.035f))
            .border(1.dp, if (selected) Violet.copy(.34f) else NeutralOverlay.copy(.06f), RowShape),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Only this part toggles the selection. The server picker sits beside it, not inside it,
        // so opening the picker can never select or unselect the application.
        Row(
            Modifier.weight(1f).focusRing(RowShape).clip(RowShape)
                .toggleable(selected, enabled = !locked, role = Role.Checkbox) { draft.toggle(app.path) }
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
            Box(Modifier.width(pickerWidth).padding(end = 8.dp)) {
                if (selected) RoutePicker(shell, app.path, !locked)
                else Text("Direct", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
}

/** Chooses the server of one selected application. "Main server" is always the first option and the default. */
@Composable
private fun RoutePicker(shell: Shell, path: String, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    val route = draftRoute(shell, path)
    val main = shell.mainNode
    val shown = if (route.followsMain) main else route.node
    val latencies = remember(open) { shell.controller.cachedLatencies() }
    Box {
        Row(
            Modifier.fillMaxWidth().height(36.dp).alpha(if (enabled) 1f else .6f)
                .pressable(RoundedCornerShape(11.dp), enabled, label = "Server for ${appName(path)}: ${if (route.missing) "missing" else shown?.let(::serverName) ?: "none"}") {
                    filter = ""; open = true
                }
                .background(if (route.missing) Amber.copy(.10f) else NeutralOverlay.copy(.06f))
                .border(1.dp, if (route.missing) Amber.copy(.40f) else NeutralOverlay.copy(.11f), RoundedCornerShape(11.dp))
                .padding(start = 8.dp, end = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (route.missing) {
                Icon(Icons.Rounded.WarningAmber, null, tint = Amber, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                EllipsisText("Missing: ${route.label.ifBlank { "removed server" }}", Amber, 10.5.sp, Modifier.weight(1f), FontWeight.SemiBold)
            } else {
                EllipsisText(shown?.let(::serverName) ?: "No server", White.copy(.92f), 11.sp, Modifier.weight(1f), FontWeight.SemiBold)
                Spacer(Modifier.width(5.dp))
                RouteBadge(route.followsMain, long = false)
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
        val entries = buildList {
            add(MenuEntry("Main server", detail = main?.let { "Follows ${serverName(it)}" } ?: "No main server yet", selected = route.followsMain) {
                shell.draft.assign(path, null)
            })
            candidates.forEach { node ->
                val favorite = node.key() in favorites
                val tag = shell.controller.nodeTag(node.key())
                val latency = latencies[node.key()]?.takeIf { it >= 0 }?.let { "$it ms" }
                add(MenuEntry(serverName(node), icon = if (favorite) Icons.Rounded.Star else null,
                    detail = listOfNotNull(if (favorite) "Favorite" else null, protocolLabel(node),
                        PerRouteController.sourceLabel(shell.controller.nodeSource(node.key())), tag.takeIf { it.isNotBlank() }?.let { "#$it" }, latency)
                        .joinToString("  •  "),
                    selected = route.assignedKey == node.key()) { shell.draft.assign(path, node.key()) })
            }
        }
        PopupMenu(open, { open = false }, entries, minWidth = 260.dp, header = if (shell.nodes.size > 8) ({
            Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                TextInput(filter, { filter = it }, "Filter servers", icon = Icons.Rounded.Search)
            }
        }) else null)
    }
}

/** The side panel that lists what is selected, the server each app would use, and the main server. */
@Composable
private fun SelectedPanel(shell: Shell, selectedRoutes: List<Pair<String, DraftRoute>>, serverCount: Int, locked: Boolean) {
    val draft = shell.draft
    val main = shell.mainNode
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Text("Selected", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(plural(selectedRoutes.size, "app") + if (draft.advanced) "  •  ${plural(serverCount, "server")}" else "", color = Cyan, fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))
        if (selectedRoutes.isEmpty()) {
            Text("Nothing selected yet. Tick an application to route it through the proxy.", color = Muted, fontSize = 10.5.sp, lineHeight = 14.sp,
                modifier = Modifier.weight(1f))
        } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(selectedRoutes, key = { it.first.lowercase() }) { (path, route) ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(NeutralOverlay.copy(.05f))
                        .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppAvatar(fileName(path), path, 28.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        EllipsisText(appName(path), White.copy(.9f), 11.5.sp, fontWeight = FontWeight.SemiBold)
                        if (draft.advanced) EllipsisText(
                            when {
                                route.missing -> "Needs a replacement"
                                route.followsMain -> main?.let(::serverName) ?: "No main server"
                                else -> route.label
                            },
                            if (route.missing) Amber else Muted, 9.5.sp
                        )
                    }
                    IconAction(Icons.Rounded.Close, "Remove ${appName(path)} from the selection", enabled = !locked, size = 26.dp) {
                        draft.selected.removeAll { it.equals(path, true) }
                    }
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
                val followers = selectedRoutes.filter { !draft.advanced || it.second.followsMain || it.second.assignedKey == main?.key() }.map { appName(it.first) }
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
            if (draft.advanced) "Turn off Advanced to use the main server for all apps. Custom choices stay saved."
            else if (draft.routes.isNotEmpty()) "${plural(draft.routes.size, "saved custom choice")} will apply again when Advanced is turned on."
            else "Turn on Advanced to give specific apps their own server.",
            color = Muted, fontSize = 9.5.sp, lineHeight = 13.sp
        )
    }
}
