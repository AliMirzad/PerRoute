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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.model.ConnectionMode
import app.perroute.model.ProxyNode
import app.perroute.model.RouteRule
import app.perroute.service.RouteRuleEvaluator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The sections of the Diagnostics screen. */
internal enum class DiagnosticsTab(val label: String) { Routes("Route checks"), Tester("Rule tester"), Loaded("Loaded rules") }

/** Route checks, the rule tester and the list of rules loaded in the running core. */
@Composable
internal fun DiagnosticsScreen(shell: Shell) {
    val controller = shell.controller
    val scope = rememberCoroutineScope()
    val now by rememberNow()
    var checking by remember { mutableStateOf(false) }
    var dns by remember { mutableStateOf(controller.dnsObservation()) }
    var loadedRuleIds by remember { mutableStateOf<Set<String>?>(null) }
    val rules = remember(shell.connected) { controller.rules() }

    fun runChecks(announce: Boolean = true) {
        if (checking) return
        scope.launch {
            checking = true
            try {
                val result = withContext(Dispatchers.IO) { controller.checkRoutes() }
                dns = controller.dnsObservation()
                shell.routeChecks = controller.routeChecks()
                if (announce) shell.notify(result)
            } finally { checking = false }
        }
    }
    fun recheck(key: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { controller.checkRoute(key) }
            shell.routeChecks = controller.routeChecks()
            if (!result.ok) shell.notify(result)
        }
    }
    LaunchedEffect(shell.connected) {
        loadedRuleIds = withContext(Dispatchers.IO) { controller.loadedRuleIds() }
        // The direct route is looked up once when the screen opens; servers are only checked on request.
        if (controller.routeChecks()[PerRouteController.DIRECT_ROUTE_KEY] == null) recheck(PerRouteController.DIRECT_ROUTE_KEY)
    }

    ScreenPanel(
        "Connection diagnostics", "Check your routes and see which rule applies.",
        actions = { PrimaryButton("Run checks", icon = Icons.Rounded.Refresh, busy = checking) { runChecks() } }
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val twoPane = maxWidth >= 820.dp
            val tabs = if (twoPane) listOf(DiagnosticsTab.Routes, DiagnosticsTab.Loaded) else DiagnosticsTab.entries.toList()
            val current = if (shell.diagnosticsTab in tabs) shell.diagnosticsTab else DiagnosticsTab.Routes
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Segmented(tabs, current, { it.label }, Modifier.fillMaxWidth(), icon = {
                        when (it) { DiagnosticsTab.Routes -> Icons.Rounded.Search; DiagnosticsTab.Tester -> Icons.Rounded.Description; DiagnosticsTab.Loaded -> Icons.Rounded.Description }
                    }) { shell.diagnosticsTab = it }
                    Spacer(Modifier.height(9.dp))
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (current) {
                            DiagnosticsTab.Routes -> RouteChecks(shell, dns, now, ::recheck)
                            DiagnosticsTab.Tester -> RuleTester(shell)
                            DiagnosticsTab.Loaded -> LoadedRules(shell, rules, loadedRuleIds)
                        }
                    }
                }
                if (twoPane) GlassCard(Modifier.width(300.dp).fillMaxHeight(), RowShape) { RuleTester(shell) }
            }
        }
    }
}

/** The direct route and every server in use, each with its own observed result, plus system DNS details. */
@Composable
private fun RouteChecks(shell: Shell, dns: DnsObservation?, now: Long, recheck: (String) -> Unit) {
    var dnsOpen by remember { mutableStateOf(false) }
    val rows = routeRows(shell)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        RouteCheckCard(
            shell, null, "Direct connection",
            if (shell.perApp) "Everything you did not select (kept direct)" else "Everything PerRoute does not route",
            null, emptyList(), shell.routeChecks[PerRouteController.DIRECT_ROUTE_KEY], true, now
        ) { recheck(PerRouteController.DIRECT_ROUTE_KEY) }
        if (rows.isEmpty()) InfoBanner("Add a server to see its route here.", Modifier.fillMaxWidth(), Amber, Icons.Rounded.WarningAmber) {
            SecondaryButton("Add servers") { shell.addServersRequested = true; shell.navigate(Tab.Servers) }
        }
        rows.forEach { row ->
            RouteCheckCard(
                shell, row.node, serverName(row.node),
                when {
                    !shell.perApp -> if (shell.mode == ConnectionMode.SYSTEM_PROXY) "Apps that follow Windows proxy settings" else "Apps you point at the local proxy"
                    row.applications.isEmpty() -> "No selected app uses this server"
                    else -> joinNames(row.applications.map(::appName), 4)
                },
                row.main, row.applications, shell.routeChecks[row.node.key()], shell.connected, now
            ) { recheck(row.node.key()) }
        }
        if (!shell.connected && rows.isNotEmpty()) {
            InfoBanner("Not connected: servers can only be checked through the running connection. Their last results are cleared when you disconnect.",
                Modifier.fillMaxWidth())
        }
        Column(Modifier.fillMaxWidth().clip(RowShape).background(NeutralOverlay.copy(.035f)).border(1.dp, NeutralOverlay.copy(.06f), RowShape)) {
            Row(
                Modifier.fillMaxWidth().pressable(RowShape, label = if (dnsOpen) "Hide system DNS details" else "Show system DNS details") { dnsOpen = !dnsOpen }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Dns, null, tint = Muted, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("System DNS details", color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("The resolvers Windows is using. They describe this computer, not a single route.", color = Muted, fontSize = 10.sp)
                }
                Icon(if (dnsOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = Muted, modifier = Modifier.size(18.dp))
            }
            if (dnsOpen) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 11.dp)) {
                if (dns == null) Text("Not looked up yet. Select Run checks.", color = Muted, fontSize = 10.5.sp)
                else {
                    DetailLine("System DNS servers", dns.systemServers.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "Unknown")
                    DetailLine("Resolver seen by public services", dns.publicResolver ?: "Unknown")
                    DetailLine("Looked up", formatAgo(dns.atMillis, now))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    when (shell.mode) {
                        ConnectionMode.NATIVE_PER_APP -> "In Smart per-app mode selected apps usually ask Windows for names first, so lookups use the resolvers above and only the connection itself is tunnelled. PerRoute does not measure DNS separately for each server."
                        ConnectionMode.TUN_COMPATIBILITY -> "In TUN mode DNS is carried inside the tunnel. PerRoute does not measure DNS separately for each server."
                        else -> "Apps using the HTTP proxy hand names to the server; SOCKS clients may still resolve locally first. PerRoute does not measure DNS separately for each server."
                    },
                    color = Muted, fontSize = 10.sp, lineHeight = 14.sp
                )
            }
        }
        val killSwitch = shell.controller.killSwitch()
        Text(
            when {
                killSwitch && shell.perApp -> "Per-app kill switch is on: selected apps stay blocked while the tunnel is unavailable."
                killSwitch -> "Per-app kill switch is on, but it only acts in Smart per-app mode."
                else -> "Per-app kill switch is off: if the tunnel drops, selected apps fall back to the direct route."
            }, color = Muted, fontSize = 10.sp, lineHeight = 14.sp
        )
        InfoBanner("Checks show what public services observed at that moment. They are not proof that all traffic is leak-free; use a dedicated leak test for sensitive work.",
            Modifier.fillMaxWidth())
    }
}

/** One route: what was last observed on it, how old that is, and a way to check it again. */
@Composable
private fun RouteCheckCard(
    shell: Shell,
    node: ProxyNode?,
    title: String,
    subtitle: String,
    main: Boolean?,
    applications: List<String>,
    check: RouteCheck?,
    checkable: Boolean,
    now: Long,
    onRecheck: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val failed = check?.state == RouteCheckState.FAILED
    val stale = check?.stale(now) == true
    Column(
        Modifier.fillMaxWidth().clip(RowShape).background(if (failed && checkable) Amber.copy(.06f) else NeutralOverlay.copy(.035f))
            .border(1.dp, if (failed && checkable) Amber.copy(.28f) else NeutralOverlay.copy(.06f), RowShape).padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (node == null) Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(NeutralOverlay.copy(.07f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Language, null, tint = Muted, modifier = Modifier.size(19.dp))
            } else ServerAvatar(node, 36.dp)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1.2f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EllipsisText(title, White, 12.5.sp, Modifier.weight(1f, fill = false), FontWeight.SemiBold)
                    if (main != null) { Spacer(Modifier.width(7.dp)); RouteBadge(main) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    applications.take(3).forEach { path -> AppAvatar(fileName(path), path, 16.dp); Spacer(Modifier.width(4.dp)) }
                    EllipsisText(subtitle, Muted, 10.sp)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(.8f)) {
                Text("PUBLIC IP", color = Muted, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, letterSpacing = .5.sp)
                EllipsisText(
                    when {
                        !checkable -> "—"
                        check?.publicIp != null -> check.publicIp + (check.country?.takeIf { it.isNotBlank() }?.let { "  $it" } ?: "")
                        check?.state == RouteCheckState.CHECKING -> "Checking…"
                        check?.state == RouteCheckState.PASSED -> "Not looked up yet"
                        else -> "—"
                    },
                    if (check?.publicIp != null && checkable) Cyan else Muted, if (check?.publicIp != null && checkable) 12.sp else 11.sp,
                    fontWeight = FontWeight.SemiBold, fontFamily = if (check?.publicIp != null && checkable) FontFamily.Monospace else null
                )
            }
            Spacer(Modifier.width(8.dp))
            RouteCheckStatus(check, checkable, now, Modifier.width(142.dp), showLatency = false)
            IconAction(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (open) "Hide details for $title" else "Show details for $title", size = 28.dp) { open = !open }
        }
        if (checkable && (failed || stale)) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (failed) "This route did not answer: ${check?.detail?.ifBlank { "no response" }}" else "This result is older than five minutes and may no longer be true.",
                    color = Amber, fontSize = 10.5.sp, lineHeight = 14.sp, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                SecondaryButton("Check again", icon = Icons.Rounded.Refresh, onClick = onRecheck)
            }
        }
        if (open) {
            Spacer(Modifier.height(8.dp))
            if (!checkable) Text("Connect, then select Run checks to observe this server.", color = Muted, fontSize = 10.5.sp)
            else {
                DetailLine("Status", when (check?.state ?: RouteCheckState.NOT_CHECKED) {
                    RouteCheckState.NOT_CHECKED -> "Not checked yet"
                    RouteCheckState.CHECKING -> "Checking now"
                    RouteCheckState.PASSED -> "Passed ${formatAgo(check!!.atMillis, now)}" + if (stale) " (stale)" else ""
                    RouteCheckState.FAILED -> "Failed ${formatAgo(check!!.atMillis, now)}" + if (stale) " (stale)" else ""
                })
                check?.latencyMillis?.let { DetailLine("Round trip of the check", "$it ms") }
                if (node != null) DetailLine("Server", "${protocolLabel(node)}  •  ${node.endpoint()}")
                if (!check?.detail.isNullOrBlank()) DetailLine("Detail", check!!.detail)
                if (check == null || check.state == RouteCheckState.NOT_CHECKED || (check.state == RouteCheckState.PASSED && check.publicIp == null)) {
                    Spacer(Modifier.height(4.dp))
                    SecondaryButton(if (check?.state == RouteCheckState.PASSED) "Look up public IP" else "Check this route", icon = Icons.Rounded.Refresh, onClick = onRecheck)
                }
            }
        }
    }
}

/** A label and its value on one line. */
@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = Muted, fontSize = 10.sp, modifier = Modifier.width(170.dp))
        Text(value, color = White.copy(.86f), fontSize = 10.5.sp, lineHeight = 14.sp, modifier = Modifier.weight(1f))
    }
}

/** Predicts which rule a destination would hit. It evaluates saved rules; it does not open a connection. */
@Composable
private fun RuleTester(shell: Shell) {
    val controller = shell.controller
    var destination by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("443") }
    var app by remember { mutableStateOf("") }
    var verdict by remember { mutableStateOf<RouteRuleEvaluator.Verdict?>(null) }
    val now by rememberNow()
    Column(Modifier.fillMaxSize().padding(13.dp).verticalScroll(rememberScrollState())) {
        Text("Test a destination", color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("See which route and rule would be used.", color = Muted, fontSize = 10.5.sp)
        Spacer(Modifier.height(10.dp))
        FieldLabel("App")
        MenuButton(
            if (app.isBlank()) "Any routed app" else appName(app),
            listOf(MenuEntry("Any routed app", selected = app.isBlank()) { app = ""; verdict = null }) +
                shell.apps.map { path -> MenuEntry(appName(path), selected = app.equals(path, true)) { app = path; verdict = null } },
            Modifier.fillMaxWidth(), icon = Icons.Rounded.Apps, accessibleLabel = "Application to test"
        )
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                FieldLabel("Domain or IP")
                TextInput(destination, { destination = it; verdict = null }, "github.com", icon = Icons.Rounded.Public)
            }
            Column(Modifier.width(78.dp)) {
                FieldLabel("Port")
                TextInput(portText, { value -> portText = value.filter(Char::isDigit).take(5); verdict = null }, "443")
            }
        }
        Spacer(Modifier.height(10.dp))
        PrimaryButton("Check route", Modifier.fillMaxWidth(), icon = Icons.Rounded.Search, enabled = destination.isNotBlank()) {
            verdict = controller.evaluateRoute(app, destination, portText.toIntOrNull() ?: 0)
        }
        verdict?.let { result ->
            val target = if (app.isBlank()) shell.mainNode else shell.plan.nodeFor(app) ?: shell.mainNode
            val missing = app.isNotBlank() && shell.plan.missing().any { it.applicationPath().equals(app, true) }
            val color = actionColor(result.action())
            Spacer(Modifier.height(11.dp))
            Column(Modifier.fillMaxWidth().clip(RowShape).background(color.copy(.09f)).border(1.dp, color.copy(.28f), RowShape).padding(11.dp)) {
                Text("Route result", color = Muted, fontSize = 10.sp)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(actionIcon(result.action()), null, tint = color, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        (if (result.certain()) "" else "Probably ") + when (result.action()) {
                            RouteRule.Action.PROXY -> "Proxy → " + when {
                                missing -> "missing custom server"
                                target != null -> serverName(target)
                                else -> "no server selected"
                            }
                            RouteRule.Action.DIRECT -> "Direct"
                            RouteRule.Action.BLOCK -> "Blocked"
                        }, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(result.rule()?.let { "Matched: ${PerRouteController.ruleDisplayName(it)}" } ?: result.reason(), color = White.copy(.84f), fontSize = 10.5.sp, lineHeight = 14.sp)
                if (result.action() == RouteRule.Action.PROXY && app.isBlank() && shell.plan.advancedEffective()) {
                    Spacer(Modifier.height(3.dp))
                    Text("Shown for apps on the main server. Choose an app to see its own server.", color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
                }
                if (!result.certain()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Could not be evaluated locally and may match first: " + result.uncertain().joinToString(", ") { PerRouteController.ruleDisplayName(it) },
                        color = Amber, fontSize = 10.sp, lineHeight = 14.sp)
                }
            }
            val check = target?.let { shell.routeChecks[it.key()] }
            if (result.action() == RouteRule.Action.PROXY) {
                Spacer(Modifier.height(7.dp))
                when {
                    missing -> InfoBanner("Routing decision only. This app's custom server is missing, so it cannot connect until you choose a replacement.",
                        Modifier.fillMaxWidth(), Amber, Icons.Rounded.WarningAmber)
                    shell.connected && check?.state == RouteCheckState.FAILED -> InfoBanner("Routing decision only. This server's connection check failed ${formatAgo(check.atMillis, now)}.",
                        Modifier.fillMaxWidth(), Amber, Icons.Rounded.WarningAmber)
                    else -> InfoBanner("Routing decision only. It predicts the rule, not whether the server is reachable.", Modifier.fillMaxWidth())
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("The test uses your saved rules in order and the first match wins. Unselected apps are never routed, whatever the rules say.",
            color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
    }
}

/** Saved rules compared with the ones the running core was started with. */
@Composable
private fun LoadedRules(shell: Shell, rules: List<RouteRule>, loadedRuleIds: Set<String>?) {
    Column(Modifier.fillMaxSize()) {
        InfoBanner(
            if (shell.connected) "Compared with the config the running core was started with."
            else "Not connected: nothing is loaded. This is the saved list that will be loaded on the next connection.",
            Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        if (rules.isEmpty()) {
            EmptyState(Icons.Rounded.Description, "No routing rules", "Add rules in Rules or apply a preset in Profiles.", Modifier.fillMaxSize()) {
                SecondaryButton("Open Rules") { shell.navigate(Tab.Rules) }
            }
        } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rules.forEachIndexed { index, rule ->
                val (label, color, why) = when {
                    !rule.enabled() -> Triple("Off", Muted, "Switched off, so it is not sent to the core.")
                    loadedRuleIds == null -> Triple("Saved", Muted, "Will be loaded when you connect.")
                    rule.id() in loadedRuleIds -> Triple("Loaded", Cyan, "Active in the running core.")
                    else -> Triple("Not loaded", Amber, "It targets an app that is not in the running selection, or it was added after connecting.")
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(NeutralOverlay.copy(.04f)).padding(horizontal = 11.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}", color = Muted, fontSize = 11.sp, modifier = Modifier.width(22.dp))
                    Column(Modifier.weight(1f)) {
                        EllipsisText(PerRouteController.ruleDisplayName(rule), White, 11.5.sp, fontWeight = FontWeight.SemiBold)
                        EllipsisText("${if (rule.applicationPath().isBlank()) "All routed apps" else appName(rule.applicationPath())}  •  ${rule.value()}  →  ${actionLabel(rule.action())}", Muted, 9.5.sp)
                        if (label == "Not loaded") Text(why, color = Amber, fontSize = 9.5.sp, lineHeight = 13.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Tooltip(why) { Badge(label, color) }
                }
            }
        }
    }
}
