package app.perroute.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.model.ConnectionMode
import app.perroute.model.ConnectionProfile

/** The icon of a preset. */
private fun scenarioIcon(id: String) = when (id) {
    "work" -> Icons.Rounded.Work
    "gaming" -> Icons.Rounded.SportsEsports
    else -> Icons.Rounded.PlayCircle
}

/** A profile in one line: mode, apps, and whether it carries its own per-app servers. */
private fun profileSummary(shell: Shell, profile: ConnectionProfile): Pair<String, Int> {
    val apps = profile.applicationPaths()
    val keys = shell.nodes.mapTo(hashSetOf()) { it.key() }
    val perApp = profile.mode() == ConnectionMode.NATIVE_PER_APP
    val assigned = if (profile.advancedRouting() && perApp)
        apps.mapNotNull { profile.appRoutes()[app.perroute.model.AppRoutes.normalize(it)] }.filter { it != profile.nodeKey() }.distinct()
    else emptyList()
    val missing = assigned.count { it !in keys } + if (profile.nodeKey().isNotBlank() && profile.nodeKey() !in keys) 1 else 0
    val parts = buildList {
        add(modeLabel(profile.mode()))
        if (perApp) add(plural(apps.size, "app"))
        add(when {
            !perApp -> "1 server"
            profile.advancedRouting() -> "Advanced routing  •  ${plural(assigned.size + 1, "server")}"
            else -> "Main server only"
        })
        if (profile.rules().isNotEmpty()) add(plural(profile.rules().size, "rule"))
    }
    return parts.joinToString("  •  ") to missing
}

/** Presets (automation and rules added on top of the setup) and saved profiles (complete setups), kept visibly apart. */
@Composable
internal fun ProfilesScreen(shell: Shell) {
    val controller = shell.controller
    val profiles = remember { mutableStateListOf<ConnectionProfile>().also { it.addAll(controller.profiles()) } }
    var activeScenario by remember { mutableStateOf(controller.activeScenarioId()) }
    var activeProfile by remember { mutableStateOf(controller.activeProfileId()) }
    val locked = shell.locked
    fun refreshProfiles() {
        profiles.clear(); profiles.addAll(controller.profiles())
        activeScenario = controller.activeScenarioId()
        activeProfile = controller.activeProfileId()
        shell.refresh()
    }

    fun previewPreset(scenario: Scenario) {
        val preview = controller.previewScenario(scenario.id) ?: return
        shell.showDialog {
            DialogCard("${scenario.title} preset", scenario.summary, shell::closeDialog, width = 540.dp, actions = {
                SecondaryButton("Cancel") { shell.closeDialog() }
                PrimaryButton(if (preview.alreadyActive) "Apply again" else "Apply preset", enabled = !locked) {
                    shell.closeDialog()
                    shell.notify(controller.applyScenario(scenario.id))
                    refreshProfiles()
                }
            }) {
                if (locked) {
                    InfoBanner("Disconnect to apply a preset. You can still review what it would change.", Modifier.fillMaxWidth(), Amber, Icons.Rounded.Lock)
                    Spacer(Modifier.height(10.dp))
                }
                SectionLabel("Automation")
                Spacer(Modifier.height(6.dp))
                preview.settings.forEach { change ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(change.label, color = White.copy(.88f), fontSize = 11.5.sp, modifier = Modifier.weight(1f))
                        if (change.from == change.to) Text("stays ${onOff(change.to)}", color = Muted, fontSize = 11.sp)
                        else {
                            Text(onOff(change.from), color = Muted, fontSize = 11.sp)
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Rounded.ArrowForward, "changes to", tint = Violet2, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(onOff(change.to), color = Violet2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(11.dp))
                SectionLabel("Rules added above yours")
                Spacer(Modifier.height(6.dp))
                preview.addedRules.forEach { rule ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(11.dp)).background(NeutralOverlay.copy(.04f)).padding(9.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(rule.name, color = White, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                            Text(rule.value, color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Badge(actionLabel(rule.action), actionColor(rule.action), icon = actionIcon(rule.action))
                    }
                }
                if (preview.removedRules.isNotEmpty()) {
                    Spacer(Modifier.height(11.dp))
                    SectionLabel("Rules of the current preset that are removed")
                    Spacer(Modifier.height(6.dp))
                    preview.removedRules.forEach { rule ->
                        Text("•  ${PerRouteController.ruleDisplayName(rule)}", color = White.copy(.8f), fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
                Spacer(Modifier.height(11.dp))
                InfoBanner("Your apps, servers, app routes, connection mode and own rules are kept exactly as they are.", Modifier.fillMaxWidth())
            }
        }
    }

    fun saveCurrent() {
        val name = mutableStateOf(profiles.firstOrNull { it.id() == activeProfile }?.name().orEmpty())
        shell.showDialog {
            val existing = profiles.any { it.name().equals(name.value.trim(), true) }
            DialogCard("Save current setup", "A profile stores your apps, main server, connection mode, Advanced routing with its app routes, and rules.",
                shell::closeDialog, width = 460.dp, actions = {
                    SecondaryButton("Cancel") { shell.closeDialog() }
                    PrimaryButton(if (existing) "Replace profile" else "Save profile", icon = Icons.Rounded.Save, enabled = name.value.isNotBlank()) {
                        shell.closeDialog()
                        shell.notify(controller.saveProfile(name.value))
                        refreshProfiles()
                    }
                }) {
                FieldLabel("Profile name")
                TextInput(name.value, { name.value = it.take(40) }, "For example: Office", icon = Icons.Rounded.Folder)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (existing) "A profile with this name exists and will be replaced."
                    else "Right now: ${modeLabel(shell.mode)}, ${plural(shell.apps.size, "app")}, " +
                        if (shell.advanced) "Advanced routing on with ${plural(shell.plan.routes().size, "server")}." else "main server only.",
                    color = if (existing) Amber else Muted, fontSize = 10.5.sp, lineHeight = 14.sp
                )
            }
        }
    }

    ScreenPanel(
        "Profiles & presets", "Save your setup, or apply a ready-made preset.",
        actions = { PrimaryButton("Save current setup", icon = Icons.Rounded.AddCircle) { saveCurrent() } }
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (locked) {
                InfoBanner("Disconnect to apply a profile or a preset. Saving the current setup is always possible.", Modifier.fillMaxWidth(), Amber, Icons.Rounded.Lock)
                Spacer(Modifier.height(10.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Presets", color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Ready-made automation and rule sets. They add to your setup instead of replacing it.", color = Muted, fontSize = 10.5.sp)
                }
                if (activeScenario.isNotBlank()) SecondaryButton("Remove preset rules", icon = Icons.Rounded.DeleteOutline, enabled = !locked) {
                    shell.notify(controller.clearScenario()); refreshProfiles()
                }
            }
            Spacer(Modifier.height(8.dp))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stacked = maxWidth < 640.dp
                val cards: @Composable (Modifier) -> Unit = { modifier ->
                    PerRouteController.SCENARIOS.forEach { scenario ->
                        PresetCard(modifier, scenario, activeScenario == scenario.id) { previewPreset(scenario) }
                    }
                }
                if (stacked) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { cards(Modifier.fillMaxWidth()) }
                else Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    PerRouteController.SCENARIOS.forEach { scenario ->
                        PresetCard(Modifier.weight(1f).fillMaxHeight(), scenario, activeScenario == scenario.id) { previewPreset(scenario) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            InfoBanner("Presets adjust automation and add rules. They keep your app and server choices.", Modifier.fillMaxWidth())

            Spacer(Modifier.height(16.dp))
            Text("Saved profiles", color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("Complete setups of your own. Applying one replaces the current apps, routes, mode and rules.", color = Muted, fontSize = 10.5.sp)
            Spacer(Modifier.height(8.dp))
            if (profiles.isEmpty()) {
                EmptyState(Icons.Rounded.Folder, "No saved profiles yet",
                    "Set up your apps, servers and rules the way you like, then save them as a profile to switch back with one click.", Modifier.fillMaxWidth()) {
                    SecondaryButton("Save current setup", icon = Icons.Rounded.Save) { saveCurrent() }
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                profiles.forEach { profile ->
                    val active = activeProfile == profile.id()
                    val (summary, missing) = profileSummary(shell, profile)
                    Row(
                        Modifier.fillMaxWidth().clip(RowShape).background(if (active) Cyan.copy(.08f) else NeutralOverlay.copy(.035f))
                            .border(1.dp, if (active) Cyan.copy(.30f) else NeutralOverlay.copy(.06f), RowShape).padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Violet.copy(.14f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Folder, null, tint = Violet2, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            EllipsisText(profile.name(), White, 12.5.sp, fontWeight = FontWeight.Bold)
                            EllipsisText(summary, Muted, 10.sp)
                            if (missing > 0) Text("${plural(missing, "server")} of this profile ${if (missing == 1) "is" else "are"} no longer in your list",
                                color = Amber, fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (active) { Spacer(Modifier.width(8.dp)); Badge("Active", Cyan, icon = Icons.Rounded.Check) }
                        Spacer(Modifier.width(8.dp))
                        SecondaryButton("Apply", Modifier.width(92.dp), enabled = !locked) {
                            shell.notify(controller.applyProfile(profile.id())); refreshProfiles()
                        }
                        Spacer(Modifier.width(6.dp))
                        MenuIcon(Icons.Rounded.MoreVert, "More actions for ${profile.name()}", listOf(
                            MenuEntry("Replace with current setup", Icons.Rounded.Save) {
                                shell.notify(controller.saveProfile(profile.name())); refreshProfiles()
                            },
                            MenuEntry("Delete profile", Icons.Rounded.DeleteOutline, danger = true) {
                                shell.showDialog {
                                    ConfirmDialog("Delete ${profile.name()}?", "The saved profile is removed. Your current setup is not changed.",
                                        "Delete profile", shell::closeDialog, danger = true) {
                                        shell.closeDialog(); controller.removeProfile(profile.id()); refreshProfiles()
                                    }
                                }
                            }
                        ))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            InfoBanner("Profiles save apps, servers, routing mode, advanced assignments, and rules.", Modifier.fillMaxWidth())
        }
    }
}

private fun onOff(value: Boolean) = if (value) "On" else "Off"

/** One preset with its highlights and a button that previews what it would change. */
@Composable
private fun PresetCard(modifier: Modifier, scenario: Scenario, active: Boolean, onPreview: () -> Unit) {
    val accent = if (active) Cyan else Violet2
    Column(
        modifier.clip(RowShape).background(if (active) Cyan.copy(.08f) else NeutralOverlay.copy(.035f))
            .border(1.dp, if (active) Cyan.copy(.32f) else NeutralOverlay.copy(.07f), RowShape).padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(accent.copy(.14f)), contentAlignment = Alignment.Center) {
                Icon(scenarioIcon(scenario.id), null, tint = accent, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(9.dp))
            Text(scenario.title, color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (active) Badge("Active", Cyan, icon = Icons.Rounded.Check)
        }
        Spacer(Modifier.height(8.dp))
        scenario.highlights.forEach { line ->
            Row(Modifier.padding(vertical = 1.5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Check, null, tint = accent, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(6.dp))
                Text(line, color = White.copy(.82f), fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(9.dp))
        SecondaryButton("Preview changes", Modifier.fillMaxWidth(), icon = Icons.Rounded.Visibility, onClick = onPreview)
    }
}
