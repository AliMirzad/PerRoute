package app.perroute.compose

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.model.ProxyNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.nio.file.Path

/** How the server list is ordered. */
private enum class ServerSort(val label: String) { DEFAULT("Default order"), FASTEST("Lowest latency"), SLOWEST("Highest latency") }
private const val AutoSpeedTestLimit = 10
/** What the list needs to know about a server besides the server itself. */
private data class ServerMeta(val source: String, val sourceLabel: String, val tag: String, val country: String, val favorite: Boolean)

/** The server list with filters, tests, favorites and tags, and the explicit Use as main server action. */
@Composable
internal fun ServersScreen(shell: Shell) {
    val controller = shell.controller
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var query by remember { mutableStateOf("") }
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
    var highlightedKey by remember { mutableStateOf(shell.selectedNodeKey) }
    val visibleServers = remember { mutableStateOf<List<ProxyNode>>(emptyList()) }
    val listState = rememberLazyListState()
    val locked = shell.locked

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

    fun openAddServers() = shell.showDialog { AddServersDialog(shell) { shell.closeDialog(); metaRevision++ } }
    LaunchedEffect(shell.addServersRequested) {
        if (shell.addServersRequested) { shell.addServersRequested = false; openAddServers() }
    }

    val snapshot = shell.nodes.toList()
    val subscriptions = remember(snapshot, metaRevision) { controller.subscriptions() }
    val meta = remember(snapshot, metaRevision) {
        snapshot.associate { node ->
            val source = controller.nodeSource(node.key())
            node.key() to ServerMeta(source, PerRouteController.sourceLabel(source), controller.nodeTag(node.key()),
                countryCode(node), controller.isFavorite(node.key()))
        }
    }
    val rows = routeRows(shell)
    val usage = rows.associate { it.node.key() to it }
    val showUsage = shell.perApp && (shell.plan.advancedEffective() || shell.connected && rows.size > 1)

    val pingSignature = remember(snapshot) { snapshot.joinToString("|") { "${it.key()}:${it.endpoint()}" } }
    LaunchedEffect(pingSignature, pingRevision) {
        if (snapshot.isEmpty()) { latencies = emptyMap(); return@LaunchedEffect }
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

    fun execute(block: () -> ActionResult) = shell.run(done = { metaRevision++ }, action = block)
    fun useAsMain(node: ProxyNode) = execute { controller.switchNode(node.key()) }
    fun copyConfig(node: ProxyNode) {
        val export = node.exportText()
        if (export.isBlank()) shell.notify(ActionResult(false, "This server has no original config to copy."))
        else {
            clipboard.setText(AnnotatedString(export))
            shell.notify(ActionResult(true, if (node.option("rawProfileJson").isBlank()) "Share link copied." else "JSON config copied."))
        }
    }
    // Every tag in use, so a server can be put into an existing tag with one click instead of retyping it.
    val allTags = meta.values.map { it.tag }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
    fun assignTag(node: ProxyNode, tag: String) {
        controller.setNodeTag(node.key(), tag)
        metaRevision++
    }
    fun editTag(node: ProxyNode) {
        val text = mutableStateOf(controller.nodeTag(node.key()))
        shell.showDialog {
            DialogCard("Tag for ${serverName(node)}", "Tags group servers so you can filter the list. A server has one tag.", shell::closeDialog,
                width = 460.dp, actions = {
                    SecondaryButton("Cancel") { shell.closeDialog() }
                    if (controller.nodeTag(node.key()).isNotBlank()) SecondaryButton("Remove tag", icon = Icons.Rounded.DeleteOutline) {
                        assignTag(node, ""); shell.closeDialog()
                    }
                    PrimaryButton("Save tag", enabled = text.value.isNotBlank()) { assignTag(node, text.value); shell.closeDialog() }
                }) {
                if (allTags.isNotEmpty()) {
                    FieldLabel("Choose an existing tag")
                    val tagScroll = rememberScrollState()
                    Row(Modifier.fillMaxWidth().wheelAndDragScroll(tagScroll).horizontalScroll(tagScroll), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        allTags.forEach { tag ->
                            FilterChip("#$tag", text.value.equals(tag, true)) { assignTag(node, tag); shell.closeDialog() }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                FieldLabel(if (allTags.isEmpty()) "Tag name" else "Or create a new tag")
                TextInput(text.value, { text.value = it.take(24) }, "For example: Gaming", icon = Icons.Rounded.Label)
            }
        }
    }
    /** The tag menu of one server: every existing tag, a way to create one, and a way to clear it. */
    fun tagMenu(node: ProxyNode, current: String): List<MenuEntry> =
        allTags.map { tag -> MenuEntry("#$tag", Icons.Rounded.Label, selected = current.equals(tag, true)) { assignTag(node, tag) } } +
            MenuEntry("New tag…", Icons.Rounded.AddCircle, "Create a tag and put this server in it") { editTag(node) } +
            MenuEntry("No tag", Icons.Rounded.DeleteOutline, enabled = current.isNotBlank()) { assignTag(node, "") }
    fun removeServer(node: ProxyNode) {
        val assigned = shell.appRoutes.values.count { it == node.key() }
        shell.showDialog {
            ConfirmDialog("Remove ${serverName(node)}?",
                if (assigned == 0) "The server is removed from your list. You can add it again later."
                else "${plural(assigned, "app")} ${if (assigned == 1) "is" else "are"} assigned to this server. They keep the assignment and will need a replacement server before you can connect with Advanced routing.",
                "Remove server", shell::closeDialog, danger = true) {
                shell.closeDialog()
                execute { controller.removeNode(node.key()) }
            }
        }
    }

    ScreenPanel(
        "Servers", "Manage servers and choose the main route.",
        actions = {
            SecondaryButton("Update subscriptions", icon = Icons.Rounded.Refresh, enabled = !locked && subscriptions.isNotEmpty() && !shell.busy) {
                execute { controller.updateAllSubscriptions() }
            }
            PrimaryButton("Add servers", icon = Icons.Rounded.AddCircle) { openAddServers() }
        },
        footer = {
            val highlighted = snapshot.firstOrNull { it.key() == highlightedKey }
            val main = shell.mainNode
            Row(Modifier.weight(1f).clip(RowShape).background(NeutralOverlay.copy(.04f)).border(1.dp, NeutralOverlay.copy(.07f), RowShape)
                .padding(horizontal = 11.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ServerAvatar(main, 24.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EllipsisText(main?.let(::serverName) ?: "No main server", White, 11.5.sp, Modifier.weight(1f, fill = false), FontWeight.SemiBold)
                        if (main != null) { Spacer(Modifier.width(7.dp)); RouteBadge(main = true, long = true) }
                    }
                    EllipsisText(
                        if (shell.connected) "Switching the main server restarts the shared core. Every routed app reconnects briefly."
                        else "Changing the main server keeps custom assignments.", Muted, 9.5.sp
                    )
                }
            }
            PrimaryButton(
                if (highlighted != null && highlighted.key() != main?.key()) "Use ${serverName(highlighted).take(18)} as main" else "Use as main server",
                enabled = highlighted != null && highlighted.key() != main?.key() && !shell.busy,
                busy = shell.busy && shell.phase == ConnectionPhase.CONNECTING
            ) { highlighted?.let(::useAsMain) }
        }
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 800.dp
            Column(Modifier.fillMaxSize()) {
                val manualPresent = meta.values.any { it.source == PerRouteController.MANUAL_SOURCE }
                val importedPresent = meta.values.any { it.source == PerRouteController.IMPORTED_SOURCE }
                val groupFilters = remember(meta) {
                    meta.values.map { it.tag }.filter { it.isNotBlank() }.distinct().map { "tag:$it" } +
                        meta.values.map { it.country }.filter { it.isNotBlank() }.distinct().map { "country:$it" }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val sourceScroll = rememberScrollState()
                    Row(Modifier.weight(1f).wheelAndDragScroll(sourceScroll).horizontalScroll(sourceScroll), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip("All", sourceFilter == "__all__") { sourceFilter = "__all__" }
                        FilterChip("Favorites", sourceFilter == "__favorites__") { sourceFilter = "__favorites__" }
                        subscriptions.distinct().forEach { source ->
                            FilterChip(PerRouteController.sourceLabel(source), sourceFilter == source) { sourceFilter = source }
                        }
                        if (manualPresent) FilterChip("Manual", sourceFilter == PerRouteController.MANUAL_SOURCE) { sourceFilter = PerRouteController.MANUAL_SOURCE }
                        if (importedPresent) FilterChip("Imported", sourceFilter == PerRouteController.IMPORTED_SOURCE) { sourceFilter = PerRouteController.IMPORTED_SOURCE }
                    }
                    Spacer(Modifier.width(8.dp))
                    TextInput(query, { query = it }, "Search servers…", Modifier.width(if (wide) 190.dp else 150.dp), Icons.Rounded.Search)
                    Spacer(Modifier.width(7.dp))
                    MenuButton(
                        if (wide) serverSort.label else "Sort",
                        ServerSort.entries.map { sort -> MenuEntry(sort.label, selected = serverSort == sort) { serverSort = sort } },
                        Modifier.width(if (wide) 148.dp else 84.dp), accessibleLabel = "Sort servers: ${serverSort.label}"
                    )
                    Spacer(Modifier.width(7.dp))
                    val speedRunning = speedTestingKey != null
                    MenuButton(
                        if (pinging) "Testing…" else if (speedRunning) "Speed test…" else "Test servers",
                        listOf(
                            MenuEntry("Ping all servers", Icons.Rounded.Schedule, "TCP latency to every endpoint", enabled = !pinging) { runRealTest = false; pingRevision++ },
                            MenuEntry("Real tunnel test", Icons.Rounded.Speed, "Starts an isolated core per server", enabled = !pinging) { runRealTest = true; pingRevision++ },
                            if (speedRunning) MenuEntry("Stop speed test", Icons.Rounded.Stop, "Keeps the results measured so far") { speedJob?.cancel() }
                            else MenuEntry("Speed test listed servers", Icons.Rounded.Download, "One at a time, bounded download") {
                                runSpeedTest(visibleServers.value.filter { (latencies[it.key()] ?: 0L) >= 0L })
                            },
                            MenuEntry(if (hideUnavailable) "Show failed servers" else "Hide failed servers", Icons.Rounded.VisibilityOff,
                                "Servers whose last test did not answer", selected = hideUnavailable) { hideUnavailable = !hideUnavailable }
                        ),
                        Modifier.width(132.dp), icon = Icons.Rounded.Science, accessibleLabel = "Test servers"
                    )
                }
                if (groupFilters.isNotEmpty()) {
                    Spacer(Modifier.height(7.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Tags", color = Muted, fontSize = 10.sp)
                        Spacer(Modifier.width(8.dp))
                        val tagScroll = rememberScrollState()
                        Row(Modifier.weight(1f).wheelAndDragScroll(tagScroll).horizontalScroll(tagScroll), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            groupFilters.forEach { filter ->
                                val label = if (filter.startsWith("tag:")) "#${filter.removePrefix("tag:")}" else filter.removePrefix("country:")
                                FilterChip(label, sourceFilter == filter) { sourceFilter = if (sourceFilter == filter) "__all__" else filter }
                            }
                        }
                    }
                }
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
                                info.sourceLabel.contains(query, true) || info.tag.contains(query, true))
                    }
                }
                val filtered = remember(matching, serverSort, latencies) {
                    when (serverSort) {
                        ServerSort.DEFAULT -> matching
                        ServerSort.FASTEST -> matching.sortedWith(
                            compareBy<ProxyNode> { (latencies[it.key()] ?: -1L) < 0L }
                                .thenBy { latencies[it.key()]?.takeIf { value -> value >= 0L } ?: Long.MAX_VALUE })
                        ServerSort.SLOWEST -> matching.sortedWith(
                            compareBy<ProxyNode> { (latencies[it.key()] ?: -1L) < 0L }
                                .thenByDescending { latencies[it.key()]?.takeIf { value -> value >= 0L } ?: Long.MIN_VALUE })
                    }
                }
                SideEffect { visibleServers.value = filtered }
                // A new filter, search or sort is a new list: start it from the top instead of keeping
                // the scroll position of whichever row happened to be visible before.
                LaunchedEffect(sourceFilter, serverSort, hideUnavailable, query) { listState.scrollToItem(0) }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${matching.size} of ${snapshot.size} servers", color = Muted, fontSize = 11.sp)
                    if (hideUnavailable) { Spacer(Modifier.width(8.dp)); Badge("Failed hidden", Amber) }
                    Spacer(Modifier.weight(1f))
                    if (sourceFilter in subscriptions) {
                        IconAction(Icons.Rounded.ContentCopy, "Copy this subscription URL", size = 28.dp) {
                            clipboard.setText(AnnotatedString(sourceFilter))
                            shell.notify(ActionResult(true, "Subscription URL copied."))
                        }
                        Spacer(Modifier.width(6.dp))
                        IconAction(Icons.Rounded.DeleteOutline, if (locked) "Disconnect to remove this subscription" else "Remove this subscription and its servers",
                            enabled = !locked, size = 28.dp) {
                            val target = sourceFilter
                            shell.showDialog {
                                ConfirmDialog("Remove ${PerRouteController.sourceLabel(target)}?",
                                    "The subscription and all of its servers are removed. Apps assigned to one of them will need a replacement server.",
                                    "Remove subscription", shell::closeDialog, danger = true) {
                                    shell.closeDialog(); sourceFilter = "__all__"
                                    execute { controller.removeSubscription(target) }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (Perf.enabled) {
                    LaunchedEffect(Perf.command.value) {
                        val command = Perf.command.value
                        if (command.startsWith("q:")) query = command.removePrefix("q:")
                        if (command == "scroll") listState.animateScrollBy(60_000f, tween(3_000))
                    }
                }
                when {
                    snapshot.isEmpty() -> EmptyState(Icons.Rounded.Storage, "No servers yet",
                        "Add a subscription URL, paste share links, or scan a QR code. You review everything before it is saved.", Modifier.fillMaxSize()) {
                        PrimaryButton("Add servers", icon = Icons.Rounded.AddCircle) { openAddServers() }
                    }
                    filtered.isEmpty() -> EmptyState(Icons.Rounded.SearchOff, "No servers match",
                        "Nothing fits the current search and filters.", Modifier.fillMaxSize()) {
                        SecondaryButton("Clear search and filters") { query = ""; sourceFilter = "__all__"; hideUnavailable = false }
                    }
                    else -> LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(filtered, key = { it.key() }) { node ->
                            val info = meta[node.key()]
                            val isMain = node.key() == shell.mainNode?.key()
                            val route = usage[node.key()]
                            ServerRow(
                                node = node,
                                source = info?.sourceLabel ?: "",
                                tag = info?.tag ?: "",
                                main = isMain,
                                custom = showUsage && route != null && !route.main,
                                usedBy = when {
                                    !wide || !shell.perApp -> null
                                    showUsage -> route?.applications ?: emptyList()
                                    isMain -> shell.apps.toList()
                                    else -> null
                                },
                                allApps = !showUsage && isMain,
                                latencyMs = latencies[node.key()],
                                pinging = pinging && !latencies.containsKey(node.key()),
                                speed = speeds[node.key()],
                                speedTesting = speedTestingKey == node.key(),
                                onSpeed = { runSpeedTest(listOf(node)) },
                                highlighted = node.key() == highlightedKey,
                                favorite = info?.favorite == true,
                                onFavorite = { controller.toggleFavorite(node.key()); metaRevision++ },
                                onHighlight = { highlightedKey = node.key() },
                                tagMenu = tagMenu(node, info?.tag.orEmpty()),
                                menu = listOf(
                                    MenuEntry("Use as main server", Icons.Rounded.CheckCircle, enabled = !isMain && !shell.busy) { useAsMain(node) },
                                    MenuEntry("Copy config", Icons.Rounded.ContentCopy) { copyConfig(node) },

                                    MenuEntry("Remove server", Icons.Rounded.DeleteOutline, if (locked) "Disconnect first" else "", enabled = !locked, danger = true) { removeServer(node) }
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One server: badges, the apps using it, latency, speed, favorite and its menu. */
@Composable
private fun ServerRow(
    node: ProxyNode,
    source: String,
    tag: String,
    main: Boolean,
    custom: Boolean,
    usedBy: List<String>?,
    allApps: Boolean,
    latencyMs: Long?,
    pinging: Boolean,
    speed: Long?,
    speedTesting: Boolean,
    onSpeed: () -> Unit,
    highlighted: Boolean,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onHighlight: () -> Unit,
    tagMenu: List<MenuEntry>,
    menu: List<MenuEntry>
) {
    Row(
        Modifier.fillMaxWidth().focusRing(RowShape).clip(RowShape)
            .selectable(highlighted, role = Role.RadioButton, onClick = onHighlight).pointerHoverIcon(PointerIcon.Hand)
            .background(if (highlighted) Violet.copy(.13f) else NeutralOverlay.copy(.035f))
            .border(1.dp, if (highlighted) Violet2.copy(.45f) else NeutralOverlay.copy(.06f), RowShape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ServerAvatar(node, 36.dp)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EllipsisText(serverName(node), White, 12.5.sp, Modifier.weight(1f, fill = false), FontWeight.SemiBold)
                if (main) { Spacer(Modifier.width(7.dp)); RouteBadge(true) }
                else if (custom) { Spacer(Modifier.width(7.dp)); RouteBadge(false) }
                if (favorite) { Spacer(Modifier.width(7.dp)); Badge("Favorite", Amber, icon = Icons.Rounded.Star) }
                if (tag.isNotBlank()) { Spacer(Modifier.width(7.dp)); Badge(tag, Cyan, icon = Icons.Rounded.Label) }
            }
            Spacer(Modifier.height(2.dp))
            EllipsisText(listOfNotNull(protocolLabel(node), source.ifBlank { null }).joinToString("  •  "), Muted, 10.sp)
        }
        if (usedBy != null) {
            Spacer(Modifier.width(8.dp))
            Column(Modifier.width(168.dp)) {
                when {
                    usedBy.isEmpty() -> Text("Not assigned", color = Muted, fontSize = 10.5.sp)
                    else -> {
                        Text("Used by", color = Muted, fontSize = 9.5.sp)
                        Tooltip(usedBy.joinToString("\n") { appName(it) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (allApps) Text("All ${plural(usedBy.size, "selected app")}", color = White.copy(.86f), fontSize = 10.5.sp, maxLines = 1)
                                else {
                                    usedBy.take(2).forEach { path ->
                                        AppAvatar(fileName(path), path, 18.dp)
                                        Spacer(Modifier.width(4.dp))
                                        Text(appName(path), color = White.copy(.86f), fontSize = 10.5.sp, maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                        Spacer(Modifier.width(7.dp))
                                    }
                                    if (usedBy.size > 2) Text("+${usedBy.size - 2}", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        LatencyChip(latencyMs, pinging)
        Spacer(Modifier.width(6.dp))
        SpeedChip(speed, speedTesting, serverName(node), onSpeed)
        Spacer(Modifier.width(7.dp))
        IconAction(if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            if (favorite) "Remove ${serverName(node)} from favorites" else "Add ${serverName(node)} to favorites",
            tint = if (favorite) Amber else Muted, onClick = onFavorite)
        Spacer(Modifier.width(5.dp))
        MenuIcon(Icons.Rounded.Label, if (tag.isBlank()) "Choose a tag for ${serverName(node)}" else "Tag of ${serverName(node)}: $tag. Change it.",
            tagMenu, tint = if (tag.isBlank()) Muted else Cyan)
        Spacer(Modifier.width(5.dp))
        MenuIcon(Icons.Rounded.MoreVert, "More actions for ${serverName(node)}", menu)
    }
}

/** The last measured latency, or that the server did not answer. */
@Composable
private fun LatencyChip(latencyMs: Long?, pinging: Boolean) {
    val (label, color) = when {
        pinging -> "Testing…" to Muted
        latencyMs == null -> "Untested" to Muted
        latencyMs < 0 -> "No answer" to Red
        latencyMs <= 150 -> "$latencyMs ms" to Cyan
        latencyMs <= 350 -> "$latencyMs ms" to Amber
        else -> "$latencyMs ms" to Red
    }
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(.10f)).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Schedule, null, tint = color, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** The last measured download speed; pressing it tests this server only. */
@Composable
private fun SpeedChip(bytesPerSecond: Long?, testing: Boolean, name: String, onClick: () -> Unit) {
    val megabits = bytesPerSecond?.takeIf { it >= 0 }?.let { it * 8 / 1_000_000.0 }
    val (label, color) = when {
        testing -> "Testing…" to Amber
        bytesPerSecond == null -> "Speed" to Muted
        megabits == null -> "Failed" to Red
        megabits >= 20 -> formatMegabits(megabits) to Cyan
        megabits >= 5 -> formatMegabits(megabits) to Amber
        else -> formatMegabits(megabits) to Red
    }
    Tooltip("Test the download speed of this server") {
        Row(
            Modifier.pressable(RoundedCornerShape(10.dp), !testing, label = "Download speed of $name: $label. Activate to test.", onClick = onClick)
                .background(color.copy(.10f)).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Download, null, tint = color, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

private fun formatMegabits(value: Double): String =
    if (value >= 100) "${value.toInt()} Mbps" else String.format(java.util.Locale.ROOT, "%.1f Mbps", value)

/** The ways servers can be brought in. */
internal enum class ImportMethod(val label: String, val icon: ImageVector) {
    SUBSCRIPTION("Subscription URL", Icons.Rounded.Link),
    PASTE("Paste links", Icons.Rounded.ContentPaste),
    QR("QR code", Icons.Rounded.QrCodeScanner)
}

/**
 * Three ways to bring servers in. Each one only produces a preview; the list and any parser problems
 * are shown first, and nothing is stored until Add servers is pressed.
 */
@Composable
internal fun AddServersDialog(
    shell: Shell,
    initialMethod: ImportMethod = ImportMethod.SUBSCRIPTION,
    initialOutcome: ImportOutcome? = null,
    close: () -> Unit
) {
    val controller = shell.controller
    val scope = rememberCoroutineScope()
    var method by remember { mutableStateOf(initialMethod) }
    var url by remember { mutableStateOf("") }
    var pasted by remember { mutableStateOf("") }
    var working by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf(initialOutcome) }
    val locked = shell.locked

    fun review(label: String, block: () -> ImportOutcome) {
        if (working.isNotBlank()) return
        scope.launch {
            working = label
            outcome = null
            try {
                outcome = withContext(Dispatchers.IO) { runCatching(block).getOrElse { ImportOutcome(error = it.message ?: it.javaClass.simpleName) } }
            } finally { working = "" }
        }
    }

    val preview = outcome?.preview
    DialogCard("Add servers", "Choose how to import your connection details.", close, width = 640.dp, actions = {
        SecondaryButton("Cancel", onClick = close)
        PrimaryButton(if (preview == null) "Add servers" else "Add ${plural(preview.candidates.size, "server")}",
            enabled = preview != null && working.isBlank() && !(preview.replaceSource && locked)) {
            val accepted = preview ?: return@PrimaryButton
            close()
            shell.run { controller.commitImport(accepted) }
        }
    }) {
        Segmented(ImportMethod.entries, method, { it.label }, Modifier.fillMaxWidth(), icon = { it.icon }) { method = it; outcome = null }
        Spacer(Modifier.height(12.dp))
        when (method) {
            ImportMethod.SUBSCRIPTION -> {
                FieldLabel("Subscription URL")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextInput(url, { url = it; outcome = null }, "https://…", Modifier.weight(1f), Icons.Rounded.Link, enabled = !locked)
                    SecondaryButton("Fetch servers", icon = Icons.Rounded.Download, enabled = url.isNotBlank() && working.isBlank() && !locked) {
                        val target = url
                        review("Fetching the subscription…") { controller.previewSubscription(target) }
                    }
                }
                Spacer(Modifier.height(7.dp))
                if (locked) InfoBanner("Disconnect before adding or refreshing a subscription. Pasted links and QR codes can be added while connected.",
                    Modifier.fillMaxWidth(), Amber, Icons.Rounded.WarningAmber)
                else Text("Updating a saved subscription keeps favorites, tags, the main server and per-app assignments for servers that still exist.",
                    color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
            }
            ImportMethod.PASTE -> {
                FieldLabel("Share links, subscription content or Xray JSON")
                TextArea(pasted, { pasted = it; outcome = null }, "vless://…\nvmess://…\ntrojan://…\nss://…", Modifier.fillMaxWidth().height(104.dp))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Review links", icon = Icons.Rounded.Search, enabled = pasted.isNotBlank() && working.isBlank()) {
                        val content = pasted
                        review("Reading the links…") { controller.previewPasted(content) }
                    }
                    SecondaryButton("Paste from clipboard", icon = Icons.Rounded.ContentPaste) {
                        runCatching {
                            java.awt.Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
                        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { pasted = it; outcome = null }
                    }
                }
            }
            ImportMethod.QR -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val scanning = working.startsWith("Camera")
                Column(
                    Modifier.weight(1f).height(150.dp).clip(RowShape).background(NeutralOverlay.copy(.04f))
                        .border(1.dp, NeutralOverlay.copy(.09f), RowShape).padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                ) {
                    if (scanning) CircularProgressIndicator(color = Violet2, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
                    else Icon(Icons.Rounded.CameraAlt, null, tint = Muted, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(7.dp))
                    Text(if (scanning) "Camera is on. Hold the QR code steady." else "The camera is off until you start it.",
                        color = Muted, fontSize = 10.5.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(9.dp))
                    SecondaryButton(if (scanning) "Scanning…" else "Start camera", icon = Icons.Rounded.CameraAlt, enabled = working.isBlank()) {
                        review("Camera: looking for a QR code for up to 12 seconds…") { controller.previewQrCamera() }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImportOption(Icons.Rounded.Image, "Choose image", "Import a QR code from a file", working.isBlank()) {
                        val owner = shell.hostWindow ?: return@ImportOption
                        val dialog = FileDialog(owner, "Choose QR image", FileDialog.LOAD).apply { isVisible = true }
                        dialog.file?.let { file ->
                            val path = Path.of(dialog.directory, file)
                            review("Reading the image…") { controller.previewQrImage(path) }
                        }
                    }
                    ImportOption(Icons.Rounded.ContentPaste, "Paste from clipboard", "Use a copied QR image or link text", working.isBlank()) {
                        review("Reading the clipboard…") { controller.previewQrClipboard() }
                    }
                    Text("The camera closes as soon as a code is read or the time runs out.", color = Muted, fontSize = 9.5.sp, lineHeight = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(13.dp))
        SectionLabel("Review")
        Spacer(Modifier.height(7.dp))
        val error = outcome?.error
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                working.isNotBlank() -> CircularProgressIndicator(color = Violet2, strokeWidth = 2.dp, modifier = Modifier.size(13.dp))
                error != null -> Icon(Icons.Rounded.WarningAmber, null, tint = Red, modifier = Modifier.size(15.dp))
                preview != null -> Icon(Icons.Rounded.CheckCircle, null, tint = Cyan, modifier = Modifier.size(15.dp))
                else -> StatusDot(Muted.copy(.7f), 9.dp)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    working.isNotBlank() -> working
                    error != null -> "Nothing was added. $error"
                    preview != null -> listOfNotNull(
                        "${plural(preview.candidates.size, "server")} found",
                        "${preview.newCount} new",
                        (preview.candidates.size - preview.newCount).takeIf { it > 0 }?.let { "$it already saved" },
                        preview.removedCount.takeIf { it > 0 }?.let { "$it no longer in this subscription" }
                    ).joinToString("  •  ")
                    method == ImportMethod.QR -> "No servers scanned yet"
                    else -> "Nothing to review yet"
                },
                color = if (error != null) Red else if (preview != null) White else Muted, fontSize = 11.sp, lineHeight = 15.sp
            )
        }
        if (preview != null) {
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                preview.candidates.take(40).forEach { candidate ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(NeutralOverlay.copy(.04f)).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        ServerAvatar(candidate.node, 24.dp)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            EllipsisText(serverName(candidate.node), White, 11.5.sp, fontWeight = FontWeight.SemiBold)
                            EllipsisText("${protocolLabel(candidate.node)}  •  ${candidate.node.endpoint()}", Muted, 9.5.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        if (candidate.alreadySaved) Badge("Already saved", Muted) else Badge("New", Cyan)
                    }
                }
                if (preview.candidates.size > 40) Text("and ${preview.candidates.size - 40} more", color = Muted, fontSize = 10.sp)
            }
            if (preview.removedCount > 0) {
                Spacer(Modifier.height(8.dp))
                InfoBanner("${plural(preview.removedCount, "server")} from the previous version of this subscription will be removed. Apps assigned to them will need a replacement.",
                    Modifier.fillMaxWidth(), Amber, Icons.Rounded.WarningAmber)
            }
        }
        val warnings = preview?.warnings.orEmpty()
        if (warnings.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().clip(RowShape).background(Amber.copy(.08f)).border(1.dp, Amber.copy(.24f), RowShape).padding(10.dp)) {
                Text("${plural(warnings.size, "entry", "entries")} could not be read and will be skipped", color = Amber, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                warnings.take(8).forEach { Text("•  $it", color = White.copy(.8f), fontSize = 10.sp, lineHeight = 14.sp) }
                if (warnings.size > 8) Text("and ${warnings.size - 8} more", color = Muted, fontSize = 10.sp)
            }
        }
    }
}

/** A QR source the user can pick. */
@Composable
private fun ImportOption(icon: ImageVector, title: String, detail: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(RowShape, enabled, label = "$title. $detail", onClick = onClick)
            .background(NeutralOverlay.copy(.045f)).border(1.dp, NeutralOverlay.copy(.09f), RowShape).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(Violet.copy(.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Violet2, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Muted, fontSize = 10.sp)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Muted, modifier = Modifier.size(17.dp))
    }
}
