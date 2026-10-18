package app.perroute.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.backup.BackupCodec
import app.perroute.backup.BackupService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Background quality checks, Auto switching, route monitoring and the traffic gateway. */
@Composable
internal fun RoutingSettings(shell: Shell) {
    val controller = shell.controller
    var background by remember { mutableStateOf(controller.qualityBackground()) }
    var monitoring by remember { mutableStateOf(controller.routeMonitoring()) }
    var gateway by remember { mutableStateOf(controller.trafficGateway()) }
    var margin by remember { mutableStateOf(controller.autoSwitchMargin()) }
    var cooldown by remember { mutableStateOf(controller.autoCooldownMinutes()) }
    SettingsPage("Routing") {
        FieldLabel("Network quality")
        FeatureToggleRow(Icons.Rounded.Speed, "Background quality checks",
            "Measures up to 24 servers every 10 minutes, four at a time, with a plain port check. Always on while an app uses Auto." +
                controller.qualityLastPass().takeIf { it > 0 }?.let { " Last pass ${formatAgo(it)}." }.orEmpty(), background) {
            background = it; controller.setQualityBackground(it)
        }
        Spacer(Modifier.height(14.dp))
        FieldLabel("Automatic server selection")
        Column(
            Modifier.fillMaxWidth().clip(RowShape).background(NeutralOverlay.copy(.035f)).border(1.dp, NeutralOverlay.copy(.06f), RowShape).padding(11.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = Cyan, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(9.dp))
                Text("Auto picks each app's server from its quality scores and keeps a working route in place.",
                    color = White.copy(.86f), fontSize = 11.sp, lineHeight = 15.sp)
            }
            Spacer(Modifier.height(9.dp))
            Text("Change servers only when another one is better by", color = Muted, fontSize = 10.5.sp)
            Spacer(Modifier.height(5.dp))
            Segmented(listOf(5, 10, 15, 20), margin.takeIf { it in listOf(5, 10, 15, 20) } ?: 10, { "$it points" }, Modifier.fillMaxWidth()) {
                margin = it; controller.setAutoSwitchMargin(it)
            }
            Spacer(Modifier.height(9.dp))
            Text("Wait at least this long between changes for one app", color = Muted, fontSize = 10.5.sp)
            Spacer(Modifier.height(5.dp))
            Segmented(listOf(5, 10, 20, 30), cooldown.takeIf { it in listOf(5, 10, 20, 30) } ?: 10, { "$it min" }, Modifier.fillMaxWidth()) {
                cooldown = it; controller.setAutoCooldownMinutes(it)
            }
            Spacer(Modifier.height(8.dp))
            Text("A failing server is replaced right away, at most once a minute. Manual choices are never changed, and no app is ever moved to the direct route.",
                color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
        }
        Spacer(Modifier.height(14.dp))
        FieldLabel("Route recovery")
        FeatureToggleRow(Icons.Rounded.MonitorHeart, "Monitor and recover each route on its own",
            "Checks every live route through its own port once a minute. A failing route retries with growing delays (2 to 32 s), is rebuilt " +
                "in place, and is checked every two minutes after five failures. Other routes keep running.", monitoring) {
            monitoring = it; controller.setRouteMonitoring(it)
        }
        Spacer(Modifier.height(14.dp))
        FieldLabel("Traffic gateway")
        Column(Modifier.alpha(if (shell.locked) .75f else 1f)) {
            FeatureToggleRow(Icons.Rounded.AltRoute, "Carry per-app traffic through the PerRoute gateway",
                "Needed for bandwidth limits, and for byte counts and open/closed state in the connection inspector. It relays each app's " +
                    "connections to its own core inbound on this computer and never reads their contents. Smart per-app only.", gateway) { on ->
                val result = controller.setTrafficGateway(on)
                if (result.ok) gateway = on
                shell.notify(result)
            }
            if (shell.locked) {
                Spacer(Modifier.height(6.dp))
                InfoBanner("Disconnect to switch the traffic gateway; it sits in the data path.", Modifier.fillMaxWidth(), Amber, Icons.Rounded.Lock)
            }
        }
    }
}

/** Portable, optionally encrypted backups, and restoring them with a preview, path remapping and a rollback snapshot. */
@Composable
internal fun BackupSettings(shell: Shell) {
    val controller = shell.controller
    val sections = remember { mutableStateMapOf<BackupService.Section, Boolean>().apply { BackupService.Section.entries.forEach { put(it, true) } } }
    var encrypt by remember { mutableStateOf(true) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var hasSnapshot by remember { mutableStateOf(controller.hasRestoreSnapshot()) }
    val chosen = sections.filterValues { it }.keys
    val passwordProblem = when {
        !encrypt -> null
        password.length < BackupCodec.MIN_PASSWORD_LENGTH -> "Use at least ${BackupCodec.MIN_PASSWORD_LENGTH} characters."
        password != confirm -> "The passwords do not match."
        else -> null
    }

    fun createBackup() {
        val owner = shell.hostWindow ?: return
        val dialog = FileDialog(owner, "Save PerRoute backup", FileDialog.SAVE).apply {
            file = "perroute-backup-${LocalDate.now()}.prb"
            isVisible = true
        }
        val name = dialog.file ?: return
        val target = Path.of(dialog.directory, if (name.endsWith(".prb", true)) name else "$name.prb")
        val secret = if (encrypt) password.toCharArray() else null
        shell.run { controller.exportBackup(target, chosen.toSet(), secret) }
        password = ""; confirm = ""
    }

    fun openBackup() {
        val owner = shell.hostWindow ?: return
        val dialog = FileDialog(owner, "Choose a PerRoute backup", FileDialog.LOAD).apply {
            filenameFilter = java.io.FilenameFilter { _, file -> file.endsWith(".prb", true) }
            isVisible = true
        }
        val name = dialog.file ?: return
        startRestore(shell, Path.of(dialog.directory, name)) { hasSnapshot = controller.hasRestoreSnapshot() }
    }

    SettingsPage("Backup & Restore") {
        Text("Create portable backups of your setup and restore them on this or another computer.", color = Muted, fontSize = 10.5.sp)
        Spacer(Modifier.height(10.dp))
        BackupCard(Icons.Rounded.Upload, "Create backup", "Choose what to include.") {
            BackupService.Section.entries.forEach { section ->
                val checked = sections[section] == true
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .toggleable(checked, role = Role.Checkbox) { sections[section] = it }
                        .padding(vertical = 5.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CheckMark(checked)
                    Spacer(Modifier.width(9.dp))
                    Column {
                        Text(section.title(), color = White, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                        Text(section.detail(), color = Muted, fontSize = 9.5.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, null, tint = if (encrypt) Cyan else Amber, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Encrypt with a password", color = White, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (encrypt) "AES-256-GCM with a key derived from the password (PBKDF2, 600,000 rounds). The password cannot be recovered."
                        else "Not encrypted: anyone with the file can read and use your server credentials.",
                        color = if (encrypt) Muted else Amber, fontSize = 9.5.sp, lineHeight = 13.sp)
                }
                ToggleSwitch(encrypt, "Encrypt the backup with a password") { encrypt = it }
            }
            if (encrypt) {
                Spacer(Modifier.height(7.dp))
                PasswordField(password, { password = it }, "Password")
                Spacer(Modifier.height(6.dp))
                PasswordField(confirm, { confirm = it }, "Repeat the password")
                passwordProblem?.takeIf { password.isNotEmpty() || confirm.isNotEmpty() }?.let {
                    Spacer(Modifier.height(4.dp)); Text(it, color = Amber, fontSize = 9.5.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            PrimaryButton("Create backup", Modifier.fillMaxWidth(), icon = Icons.Rounded.Backup,
                enabled = chosen.isNotEmpty() && passwordProblem == null && !shell.busy) { createBackup() }
        }
        Spacer(Modifier.height(10.dp))
        BackupCard(Icons.Rounded.Restore, "Restore backup", "Nothing changes until you confirm the preview.") {
            if (shell.locked) {
                InfoBanner("Disconnect to restore a backup. Creating one works at any time.", Modifier.fillMaxWidth(), Amber, Icons.Rounded.Lock)
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton("Choose backup…", icon = Icons.Rounded.Folder, enabled = !shell.locked && !shell.busy) { openBackup() }
                if (hasSnapshot) SecondaryButton("Undo last restore", icon = Icons.Rounded.Undo, enabled = !shell.locked && !shell.busy) {
                    shell.showDialog {
                        ConfirmDialog("Undo the last restore?", "Your servers, apps, rules, profiles and preferences go back to how they were right before the last restore.",
                            "Undo restore", shell::closeDialog) {
                            shell.closeDialog()
                            shell.run(done = { result -> if (result.ok) { hasSnapshot = controller.hasRestoreSnapshot(); shell.draft.load(shell) } }) { controller.undoRestore() }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("A rollback snapshot of your current setup is written before anything is restored, and the restore is applied in one step: " +
                "if it fails, everything is put back.", color = Muted, fontSize = 9.5.sp, lineHeight = 13.sp)
        }
    }
}

/** Asks for the password when needed, reads the backup and shows the preview. */
private fun startRestore(shell: Shell, file: Path, done: () -> Unit) {
    val header = runCatching { BackupCodec.header(java.nio.file.Files.readAllBytes(file)) }
    if (header.isFailure) { shell.notify(ActionResult(false, header.exceptionOrNull()?.message ?: "This is not a PerRoute backup.")); return }
    fun open(password: CharArray?) {
        // Deriving the key takes a moment by design, so the backup is opened off the UI thread.
        shell.scope.launch {
            shell.busy = true
            val (request, error) = try {
                withContext(Dispatchers.IO) { shell.controller.openBackup(file, password) }
            } finally { shell.busy = false }
            if (request != null) shell.showDialog { RestorePreviewDialog(shell, request, done) }
            else shell.notify(ActionResult(false, error ?: "The backup could not be opened."))
        }
    }
    if (header.getOrThrow().encrypted()) {
        val text = mutableStateOf("")
        shell.showDialog {
            DialogCard("Backup password", "${file.fileName} is encrypted. Enter the password used when it was created.", shell::closeDialog, width = 440.dp,
                actions = {
                    SecondaryButton("Cancel") { shell.closeDialog() }
                    PrimaryButton("Open", enabled = text.value.isNotEmpty()) { val secret = text.value.toCharArray(); text.value = ""; shell.closeDialog(); open(secret) }
                }) {
                PasswordField(text.value, { text.value = it }, "Password")
            }
        }
    } else open(null)
}

/** What a restore would change: contents, compatibility, missing apps with remapping, Merge or Replace. */
@Composable
internal fun RestorePreviewDialog(shell: Shell, request: RestoreRequest, done: () -> Unit) {
    val preview = request.preview
    var mode by remember { mutableStateOf(BackupService.Mode.MERGE) }
    val remap = remember { mutableStateMapOf<String, String>().apply { preview.missingPaths().forEach { issue -> issue.suggestion()?.let { put(issue.originalPath(), it) } } } }
    val created = Instant.ofEpochMilli(preview.header().createdAt()).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MMM d, yyyy, HH:mm"))
    DialogCard("Import preview", "${request.file.fileName}  •  Created $created  •  ${preview.header().application().ifBlank { "PerRoute" }}", shell::closeDialog,
        width = 600.dp, actions = {
            SecondaryButton("Cancel") { shell.closeDialog() }
            PrimaryButton("Restore backup", icon = Icons.Rounded.Restore, enabled = !shell.locked) {
                shell.closeDialog()
                val choices = HashMap(remap)
                shell.run(done = { result -> if (result.ok) shell.draft.load(shell); done() }) { shell.controller.restoreBackup(request, mode, choices) }
            }
        }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Badge(if (preview.compatibility() == BackupService.Compatibility.COMPATIBLE) "Compatible" else "Upgraded from an older backup", Cyan, icon = Icons.Rounded.CheckCircle)
            Spacer(Modifier.width(8.dp))
            Text(if (preview.header().encrypted()) "Encrypted and verified" else "Not encrypted; checksum verified", color = Muted, fontSize = 10.sp)
        }
        Spacer(Modifier.height(10.dp))
        Text("This backup contains:", color = White, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        val counts = preview.counts()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            CountTile(Modifier.weight(1f), counts.subscriptions(), "Subscriptions")
            CountTile(Modifier.weight(1f), counts.servers(), "Servers")
            CountTile(Modifier.weight(1f), counts.applications(), "App assignments")
        }
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            CountTile(Modifier.weight(1f), counts.rules(), "Rules")
            CountTile(Modifier.weight(1f), counts.profiles(), "Profiles")
            CountTile(Modifier.weight(1f), counts.preferences(), "Preferences")
        }
        if (preview.serversAlreadyPresent() > 0 || preview.profilesWithSameName() > 0) {
            Spacer(Modifier.height(7.dp))
            Text(listOfNotNull(
                preview.serversAlreadyPresent().takeIf { it > 0 }?.let { "${plural(it, "server")} already saved here" },
                preview.profilesWithSameName().takeIf { it > 0 }?.let { "${plural(it, "profile")} with a name already in use (kept as is)" }
            ).joinToString("  •  "), color = Muted, fontSize = 10.sp)
        }
        preview.warnings().forEach { warning ->
            Spacer(Modifier.height(5.dp)); InfoBanner(warning, Modifier.fillMaxWidth(), Amber, Icons.Rounded.WarningAmber)
        }
        if (preview.missingPaths().isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            val shape = RoundedCornerShape(14.dp)
            Column(Modifier.fillMaxWidth().clip(shape).background(Amber.copy(.07f)).border(1.dp, Amber.copy(.35f), shape).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.WarningAmber, null, tint = Amber, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Path remapping", color = Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Badge(plural(preview.missingPaths().size, "item"), Amber)
                }
                Text("These applications are not on this computer. Choose where they are, or they are skipped.", color = Muted, fontSize = 10.sp)
                Spacer(Modifier.height(6.dp))
                preview.missingPaths().forEach { issue ->
                    val replacement = remap[issue.originalPath()]
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            EllipsisText(issue.originalPath(), White.copy(.86f), 10.sp)
                            EllipsisText(replacement?.let { "→ $it" } ?: "Will be skipped", if (replacement != null) Cyan else Muted, 9.5.sp)
                        }
                        Spacer(Modifier.width(6.dp))
                        SecondaryButton("Choose path…", Modifier.height(32.dp)) {
                            val owner = shell.hostWindow ?: return@SecondaryButton
                            val dialog = FileDialog(owner, "Where is ${fileName(issue.originalPath())}?", FileDialog.LOAD).apply {
                                filenameFilter = java.io.FilenameFilter { _, name -> name.endsWith(".exe", true) }
                                isVisible = true
                            }
                            dialog.file?.let { remap[issue.originalPath()] = Path.of(dialog.directory, it).toAbsolutePath().normalize().toString() }
                        }
                        if (replacement != null) {
                            Spacer(Modifier.width(5.dp))
                            SecondaryButton("Skip", Modifier.height(32.dp)) { remap.remove(issue.originalPath()) }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Import behavior", color = White, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChoice(Modifier.weight(1f), mode == BackupService.Mode.MERGE, "Merge",
                "Adds what this backup has to your setup. Everything you already have is kept, including your preferences.") { mode = BackupService.Mode.MERGE }
            ModeChoice(Modifier.weight(1f), mode == BackupService.Mode.REPLACE, "Replace",
                "Replaces the parts this backup contains with its versions. Parts it does not contain stay as they are.") { mode = BackupService.Mode.REPLACE }
        }
        Spacer(Modifier.height(8.dp))
        InfoBanner("A rollback snapshot is written before anything changes, and Undo last restore brings it back.", Modifier.fillMaxWidth())
    }
}

@Composable
private fun CountTile(modifier: Modifier, count: Int, label: String) {
    val shape = RoundedCornerShape(12.dp)
    Row(modifier.clip(shape).background(NeutralOverlay.copy(.045f)).border(1.dp, NeutralOverlay.copy(.08f), shape).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Description, null, tint = Violet2, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(count.toString(), color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(label, color = Muted, fontSize = 9.5.sp)
        }
    }
}

@Composable
private fun ModeChoice(modifier: Modifier, selected: Boolean, title: String, detail: String, onSelect: () -> Unit) {
    val shape = RoundedCornerShape(13.dp)
    Row(
        modifier.focusRing(shape).clip(shape).selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .background(if (selected) Violet.copy(.13f) else NeutralOverlay.copy(.035f))
            .border(1.dp, if (selected) Violet2.copy(.55f) else NeutralOverlay.copy(.08f), shape).padding(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        RadioMark(selected)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, color = White, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Muted, fontSize = 9.5.sp, lineHeight = 13.sp)
        }
    }
}

@Composable
private fun BackupCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(GlassShape).background(NeutralOverlay.copy(.035f)).border(1.dp, NeutralOverlay.copy(.07f), GlassShape).padding(13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(Violet.copy(.14f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Violet2, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Muted, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/** A password field whose content is never shown. */
@Composable
internal fun PasswordField(value: String, onValueChange: (String) -> Unit, hint: String) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).focusRing(ControlShape).clip(ControlShape)
            .background(NeutralOverlay.copy(.045f)).border(1.dp, NeutralOverlay.copy(.10f), ControlShape).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Lock, null, tint = Muted, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value, { onValueChange(it.take(128)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            textStyle = TextStyle(color = White, fontSize = 12.sp, fontFamily = UiFont), cursorBrush = SolidColor(Violet2),
            modifier = Modifier.weight(1f).semantics { contentDescription = hint },
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, color = Muted.copy(.75f), fontSize = 11.5.sp); inner() } }
        )
    }
}
