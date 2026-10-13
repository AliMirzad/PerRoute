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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.model.RouteRule

/** A rule action as it is named in the interface. */
internal fun actionLabel(action: RouteRule.Action): String = when (action) {
    RouteRule.Action.PROXY -> "Proxy"
    RouteRule.Action.DIRECT -> "Direct"
    RouteRule.Action.BLOCK -> "Block"
}

/** The icon that accompanies a rule action, so it is never told apart by colour alone. */
internal fun actionIcon(action: RouteRule.Action): ImageVector = when (action) {
    RouteRule.Action.PROXY -> Icons.Rounded.Shield
    RouteRule.Action.DIRECT -> Icons.Rounded.Public
    RouteRule.Action.BLOCK -> Icons.Rounded.Block
}

/** Violet for Proxy, teal for Direct, amber for Block. */
@Composable
internal fun actionColor(action: RouteRule.Action): Color = when (action) {
    RouteRule.Action.PROXY -> Violet2
    RouteRule.Action.DIRECT -> Cyan
    RouteRule.Action.BLOCK -> Amber
}

/** A match type as it is named in the interface. */
private fun matchLabel(type: RouteRule.MatchType): String = when (type) {
    RouteRule.MatchType.DOMAIN -> "Domain"
    RouteRule.MatchType.IP -> "IP"
    RouteRule.MatchType.PORT -> "Port"
}

/** The rule being created or changed. A blank id means a new rule. */
private class RuleEditor(rule: RouteRule?) {
    val id = rule?.id().orEmpty()
    var name by mutableStateOf(rule?.name().orEmpty())
    var app by mutableStateOf(rule?.applicationPath().orEmpty())
    var type by mutableStateOf(rule?.matchType() ?: RouteRule.MatchType.DOMAIN)
    var value by mutableStateOf(rule?.value().orEmpty())
    var action by mutableStateOf(rule?.action() ?: RouteRule.Action.DIRECT)
}

/** The ordered rule list with search, reordering and a side editor. Read-only while connected. */
@Composable
internal fun RulesScreen(shell: Shell) {
    val controller = shell.controller
    val rules = remember { mutableStateListOf<RouteRule>().also { it.addAll(controller.rules()) } }
    var query by remember { mutableStateOf("") }
    var appFilter by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<RuleEditor?>(null) }
    val locked = shell.locked
    fun refreshRules() { rules.clear(); rules.addAll(controller.rules()) }
    fun apply(result: ActionResult, quiet: Boolean = true) {
        if (!result.ok || !quiet) shell.notify(result)
        refreshRules()
    }

    val filtering = query.isNotBlank() || appFilter != null
    val listState = rememberLazyListState()
    LaunchedEffect(query, appFilter) { listState.scrollToItem(0) }
    val visible = rules.withIndex().filter { (_, rule) ->
        (appFilter == null || (appFilter == "" && rule.applicationPath().isBlank()) || rule.applicationPath().equals(appFilter, true)) &&
            (query.isBlank() || rule.name().contains(query, true) || rule.value().contains(query, true))
    }
    val appChoices = (shell.apps + rules.map { it.applicationPath() }.filter { it.isNotBlank() }).distinctBy { it.lowercase() }

    ScreenPanel(
        "Routing rules", "Decide which connections use a proxy, stay direct, or are blocked.",
        actions = {
            SecondaryButton("Test a route", icon = Icons.Rounded.PlayArrow) { shell.diagnosticsTab = DiagnosticsTab.Tester; shell.navigate(Tab.Diagnostics) }
            PrimaryButton("Add rule", icon = Icons.Rounded.AddCircle, enabled = !locked) { editor = RuleEditor(null) }
        }
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val editorWidth = if (maxWidth >= 860.dp) 300.dp else 264.dp
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextInput(query, { query = it }, "Search rules…", Modifier.weight(1f), Icons.Rounded.Search)
                        MenuButton(
                            when (appFilter) { null -> "All rules"; "" -> "All routed apps"; else -> appName(appFilter!!) },
                            listOf(
                                MenuEntry("All rules", selected = appFilter == null) { appFilter = null },
                                MenuEntry("All routed apps", detail = "Rules that are not tied to one app", selected = appFilter == "") { appFilter = "" }
                            ) + appChoices.map { path -> MenuEntry(appName(path), selected = appFilter.equals(path, true)) { appFilter = path } },
                            Modifier.width(150.dp), icon = Icons.Rounded.Apps, accessibleLabel = "Filter rules by application"
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    if (locked) InfoBanner("Rules are read-only while connected. Disconnect to add, edit, reorder or switch rules.", Modifier.fillMaxWidth(), Amber, Icons.Rounded.Lock)
                    else InfoBanner("Rules run top to bottom. First match wins." + if (filtering) " Clear the filter to reorder rules." else "", Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            rules.isEmpty() -> EmptyState(Icons.Rounded.Description, "No routing rules yet",
                                "Without rules, every connection of a routed app uses its server. Add a rule to keep something direct or to block it, or apply a preset in Profiles.",
                                Modifier.fillMaxSize()) { if (!locked) PrimaryButton("Add rule", icon = Icons.Rounded.AddCircle) { editor = RuleEditor(null) } }
                            visible.isEmpty() -> EmptyState(Icons.Rounded.SearchOff, "No rules match", "Nothing fits the current search and filter.", Modifier.fillMaxSize()) {
                                SecondaryButton("Clear search and filter") { query = ""; appFilter = null }
                            }
                            else -> LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                itemsIndexed(visible, key = { _, item -> item.value.id() }) { _, item ->
                                    val rule = item.value
                                    val preset = PerRouteController.isScenarioRule(rule)
                                    val canMoveUp = !locked && !filtering && !preset && item.index > 0 && !PerRouteController.isScenarioRule(rules[item.index - 1])
                                    val canMoveDown = !locked && !filtering && !preset && item.index < rules.lastIndex
                                    RuleRow(
                                        number = item.index + 1, rule = rule, preset = preset, locked = locked, editing = editor?.id == rule.id(),
                                        canMoveUp = canMoveUp, canMoveDown = canMoveDown,
                                        onMove = { offset -> apply(controller.moveRule(rule.id(), offset)) },
                                        onToggle = { enabled -> apply(controller.setRuleEnabled(rule.id(), enabled)) },
                                        onEdit = { editor = RuleEditor(rule) },
                                        onDelete = {
                                            if (editor?.id == rule.id()) editor = null
                                            apply(controller.removeRule(rule.id()), quiet = false)
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    InfoBanner(
                        if (shell.plan.advancedEffective()) "Proxy uses each app's assigned server: its custom server, or the main server for apps without one."
                        else "Proxy uses each app's assigned server. In standard mode, this is the main server.",
                        Modifier.fillMaxWidth(), icon = Icons.Rounded.Shield
                    )
                }
                GlassCard(Modifier.width(editorWidth).fillMaxHeight(), RowShape) {
                    val current = editor
                    if (current == null) RuleGuide(locked) { editor = RuleEditor(null) }
                    else RuleEditorPanel(current, appChoices, locked, onCancel = { editor = null }) {
                        val result = if (current.id.isBlank()) controller.addRule(current.name, current.app, current.type, current.value, current.action)
                            else controller.updateRule(current.id, current.name, current.app, current.type, current.value, current.action)
                        apply(result, quiet = false)
                        if (result.ok) editor = null
                    }
                }
            }
        }
    }
}

/** One rule: position, reorder buttons, on/off switch, what it matches and what it does. */
@Composable
private fun RuleRow(
    number: Int,
    rule: RouteRule,
    preset: Boolean,
    locked: Boolean,
    editing: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val name = PerRouteController.ruleDisplayName(rule)
    Row(
        Modifier.fillMaxWidth().alpha(if (rule.enabled()) 1f else .66f).clip(RowShape)
            .background(if (editing) Violet.copy(.13f) else NeutralOverlay.copy(.035f))
            .border(1.dp, if (editing) Violet2.copy(.45f) else NeutralOverlay.copy(.06f), RowShape)
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconAction(Icons.Rounded.KeyboardArrowUp, "Move $name up", enabled = canMoveUp, size = 22.dp) { onMove(-1) }
            Spacer(Modifier.height(2.dp))
            IconAction(Icons.Rounded.KeyboardArrowDown, "Move $name down", enabled = canMoveDown, size = 22.dp) { onMove(1) }
        }
        Spacer(Modifier.width(7.dp))
        Text("$number", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(20.dp))
        ToggleSwitch(rule.enabled(), "Rule $name", !locked, onToggle)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EllipsisText(name, White, 12.5.sp, Modifier.weight(1f, fill = false), FontWeight.SemiBold)
                if (preset) { Spacer(Modifier.width(7.dp)); Badge("Preset", Muted) }
                if (!rule.enabled()) { Spacer(Modifier.width(7.dp)); Badge("Off", Muted) }
            }
            Spacer(Modifier.height(2.dp))
            EllipsisText(
                (if (rule.applicationPath().isBlank()) "All routed apps" else appName(rule.applicationPath())) +
                    "  •  ${matchLabel(rule.matchType())}: ${rule.value()}", Muted, 10.sp
            )
        }
        Spacer(Modifier.width(8.dp))
        Badge(actionLabel(rule.action()), actionColor(rule.action()), icon = actionIcon(rule.action()))
        Spacer(Modifier.width(7.dp))
        MenuIcon(Icons.Rounded.MoreVert, "More actions for $name", listOf(
            MenuEntry("Edit rule", Icons.Rounded.Edit, if (preset) "Preset rules cannot be edited" else "", enabled = !locked && !preset, onClick = onEdit),
            MenuEntry("Move up", Icons.Rounded.KeyboardArrowUp, enabled = canMoveUp) { onMove(-1) },
            MenuEntry("Move down", Icons.Rounded.KeyboardArrowDown, enabled = canMoveDown) { onMove(1) },
            MenuEntry("Delete rule", Icons.Rounded.DeleteOutline, enabled = !locked, danger = true, onClick = onDelete)
        ), size = 28.dp)
    }
}

/** Shown in the side panel while no rule is being edited: what each action means. */
@Composable
private fun RuleGuide(locked: Boolean, onAdd: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState())) {
        Text("What a rule does", color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(9.dp))
        RouteRule.Action.values().toList().forEach { action ->
            Row(Modifier.padding(vertical = 5.dp)) {
                Box(Modifier.size(28.dp).clip(ControlShape).background(actionColor(action).copy(.13f)), contentAlignment = Alignment.Center) {
                    Icon(actionIcon(action), null, tint = actionColor(action), modifier = Modifier.size(15.dp))
                }
                Spacer(Modifier.width(9.dp))
                Column {
                    Text(actionLabel(action), color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(actionExplanation(action), color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
                }
            }
        }
        Spacer(Modifier.height(9.dp))
        Text("Only routed apps are affected. Applications you did not select never reach these rules, and private and LAN addresses always stay direct.",
            color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
        Spacer(Modifier.height(12.dp))
        if (!locked) SecondaryButton("Add rule", Modifier.fillMaxWidth(), icon = Icons.Rounded.AddCircle, onClick = onAdd)
        else Text("Select Edit on a rule after disconnecting to change it.", color = Muted, fontSize = 10.sp)
    }
}

/** One sentence on what an action does to a matching connection. */
private fun actionExplanation(action: RouteRule.Action): String = when (action) {
    RouteRule.Action.PROXY -> "Send matching connections through the app's assigned server."
    RouteRule.Action.DIRECT -> "Let matching connections use your regular connection."
    RouteRule.Action.BLOCK -> "Drop matching connections for the routed app."
}

/** The form for a new or existing rule, with fixed Cancel and Save buttons. */
@Composable
private fun RuleEditorPanel(editor: RuleEditor, apps: List<String>, locked: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Text(if (editor.id.isBlank()) "New rule" else "Edit rule", color = White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            FieldLabel("Name")
            TextInput(editor.name, { editor.name = it.take(60) }, "For example: Local banking", enabled = !locked)
            Spacer(Modifier.height(7.dp))
            FieldLabel("Application")
            MenuButton(
                if (editor.app.isBlank()) "All routed apps" else appName(editor.app),
                listOf(MenuEntry("All routed apps", selected = editor.app.isBlank()) { editor.app = "" }) +
                    apps.map { path -> MenuEntry(appName(path), selected = editor.app.equals(path, true)) { editor.app = path } },
                Modifier.fillMaxWidth(), icon = Icons.Rounded.Apps, accessibleLabel = "Application this rule applies to", enabled = !locked
            )
            Spacer(Modifier.height(7.dp))
            FieldLabel("Match type")
            Segmented(RouteRule.MatchType.values().toList(), editor.type, ::matchLabel, Modifier.fillMaxWidth(), enabled = !locked) { editor.type = it }
            Spacer(Modifier.height(7.dp))
            FieldLabel("Values")
            TextArea(editor.value, { editor.value = it }, when (editor.type) {
                RouteRule.MatchType.DOMAIN -> "example.com, full:api.example.com"
                RouteRule.MatchType.IP -> "203.0.113.0/24, geoip:ir"
                RouteRule.MatchType.PORT -> "80, 443, 1000-2000"
            }, Modifier.fillMaxWidth().height(54.dp), enabled = !locked)
            Spacer(Modifier.height(4.dp))
            Text(when (editor.type) {
                RouteRule.MatchType.DOMAIN -> "The domain and its subdomains. Also full:, keyword:, geosite:. Comma-separated."
                RouteRule.MatchType.IP -> "An address, a CIDR range or geoip:code. Comma-separated."
                RouteRule.MatchType.PORT -> "Single ports and ranges. Comma-separated."
            }, color = Muted, fontSize = 9.5.sp, lineHeight = 13.sp)
            Spacer(Modifier.height(7.dp))
            FieldLabel("Action")
            Segmented(RouteRule.Action.values().toList(), editor.action, ::actionLabel, Modifier.fillMaxWidth(), icon = ::actionIcon, enabled = !locked) { editor.action = it }
            Spacer(Modifier.height(4.dp))
            Text(actionExplanation(editor.action), color = Muted, fontSize = 9.5.sp, lineHeight = 13.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Cancel", Modifier.weight(1f), onClick = onCancel)
            PrimaryButton("Save rule", Modifier.weight(1.3f), enabled = !locked && editor.value.isNotBlank(), onClick = onSave)
        }
    }
}
