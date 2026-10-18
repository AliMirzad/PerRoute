# PerRoute 3.0: what was verified and what still needs a real machine

This page separates checks that were actually run from those that need Windows with the per-app driver
(ProxiFyre) installed and working proxy servers. Nothing in the last section has been run.

## 1. Automated tests (`.\test.ps1`)

1,016 assertions pass on Windows 11 with JDK 21 and Xray 26.3.27. The version 2 tests run unchanged
(the tunnel-check test was extended, see below).

### Building blocks (`V3DomainTests`)

| Area | What is checked |
|---|---|
| Quality: unknown | No samples gives Unknown, no score, zero confidence, not usable. |
| Quality: excellent | Steady low latency with tunnel samples scores 85+ (Excellent), confidence ≥ 0.9, explanation names the success rate; the same input always gives the same score. |
| Quality: unverified cap | Port checks alone are capped at 74 with confidence ≤ 0.6 and the explanation says the tunnel is not verified. |
| Quality: unavailable | The last two tunnel checks failing, or a single failed check, give Unavailable with score 0. |
| Quality: stale and forgotten | After 30 minutes the score is stale and loses confidence; after 24 hours it is Unknown again. |
| Quality: jitter and recovery | Large latency swings produce jitter and lower the score; a recent recovery costs points and lowers stability. |
| Quality history | Bounded to 64 samples per server, survives a restart, never writes sample details, forgets removed servers, ignores a damaged file. |
| Auto: initial choice | The best usable server is chosen; with no evidence (or too little confidence) the main server is used and says so; without a main server there is no choice. |
| Auto: hysteresis and cooldown | A 6-point gain does not switch, a 15-point gain does; nothing switches within the cooldown; ties keep the current server. |
| Auto: failover | A failing current server is replaced at once, but not twice within the failover interval; with no working alternative it is kept and never released to the direct route; an Unavailable score counts as failing; a removed server is re-decided. |
| Candidate pools | All / favorites / tag pools encode, decode and filter correctly. |
| Route health | Healthy routes are checked every 60 s; failures back off 2-4-8-16-32 s; exactly one rebuild at attempt 2; the sixth failure marks the route failed and checks continue every 120 s without new events; Retry now restarts attempts; a pass reports Recovered; the timeline is bounded. |
| Gateway TCP | A SOCKS5 CONNECT through the gateway reaches the target; bytes are counted per app; the destination, protocol and the upstream port Xray sees are recorded. |
| Gateway limits | Three connections together are held to a 2 MB/s app limit (measured 2.05–2.06 MB/s), two uploads to 1 MB/s (1.04–1.05 MB/s); removing the limit removes the throttling (≈ 200 MB/s). |
| Gateway UDP | UDP associations go through the gateway's own relay port; 8 KB datagrams are held to a 160 KB/s upload limit (measured 167 KB/s); payload bytes are counted; the destination and distinct-destination count are recorded. |
| Gateway failures | A refused SOCKS request is recorded as failed with its reason; a port conflict fails the whole start, names the app and leaves nothing listening; stopping closes open connections and frees the ports. |
| Inspector: log formats | Access, request, sniffed-domain, rule-hit (with and without a rule) and failure lines from Xray 26.3.27, including IPv6 and the older `>>` separator. |
| Inspector: joins | A gateway connection is joined with the core's access line (by source port) and session lines (by destination), giving the observed outbound, rule and domain, independent of the order the lines arrive in. |
| Inspector: bounds | Core-only mode works without the gateway, API traffic is ignored, entries expire, and the table never exceeds 1,500 entries. |
| Backup crypto | Encrypted files contain no plaintext credential; wrong password, missing password and a changed header are rejected; unencrypted files detect damage; newer formats are refused; short passwords are refused. |
| Backup restore plan | Preview counts, existing servers and same-name profiles; missing executables with a suggestion found by file name; Merge keeps existing items and preferences and maps server keys to identical saved servers; Replace replaces only the included parts; remapped paths follow into routes, Auto, limits and rules; unremapped missing apps are skipped. |
| State migration | A version 2 state loads as migrated with nothing automatic; version 3 fields (Auto apps, pools, selections, limits, profile Auto apps) round-trip; limits are clamped and decoded safely. |

### Controller (`V3ControllerTests`, fake machine)

| Area | What is checked |
|---|---|
| Auto route | An Auto app gets its own route and check port; others keep following Main; the decision is saved and survives a reconnect; a slightly better server does not take over. |
| Provisional main | An Auto app without measurements uses the main server; no app is left without a server. |
| Independent recovery | One failing route goes through recovery to Failed while the others stay healthy and passed; the core is never restarted; the failing route's outbound is rebuilt in place (`perroute-route-0` → `perroute-route-0~1`) with every other route's tag unchanged; the Manual assignment is untouched; it recovers by itself and the recovery lowers the server's quality. |
| Auto failover | When an Auto app's route fails, it moves to the next best server in place, without a restart; the new choice is saved; the decision says Failover; other routes are untouched. |
| In-place main switch | Switching the main server replaces only the main outbound (`proxy` ↔ `proxy~1`), every rule follows, custom routes keep their tags, no restart. Switching to a server that is also a custom route uses the restart path. |
| Refused runtime change | If the core refuses an in-place change, the switch falls back to the restart path and stays connected. |
| Gateway and limits | The gateway is started on the per-app ports and forwards to the shifted core inbounds; limits are applied at start, changed live and removed live; the status goes Pending → Enforced → Throttling (from the gateway's own counters) → Unlimited; limits persist; System proxy reports Not enforced. |
| Gateway failure | A gateway that cannot start fails the connection, stops the core and releases the per-app driver. |
| Migrated defaults | A version 2 installation starts with the gateway, monitoring and background checks off; the core keeps the read-only statistics API; limits report Not enforced with the reason; route checks run once; the gateway can only be switched while disconnected. |
| Kill switch | With the kill switch on, disconnecting and a core crash stop the gateway and never release the held apps. |
| Inspector | Configured, observed and verified facts are composed separately: a Direct rule observed in the core, a Proxy route observed on the main server, and verification from the direct route check; rows are cleared on disconnect. |
| Backup restore | Export encrypts credentials; a wrong password is refused; restoring is refused while connected; Replace restores servers, rules, Auto mode and limits and leaves a snapshot; Undo brings the previous setup back; a failure while writing rolls memory, the state file and preferences back. |
| Rule precedence | A global Proxy rule is split per route (main, custom, Auto) at one position, before the app routes; Block rules cover Auto apps. |
| Removed Auto server | Removing an Auto app's server never blocks connecting; Auto picks again and explains that the previous choice is gone. |

### Version 2 behavior

All version 2 tests still pass. One was extended rather than changed in meaning: **Tunnel health check**
now also requires an HTTP answer through the tunnel and gained a negative case (a SOCKS acknowledgement
followed by silence must fail), which is exactly the false positive of version 2.

## 2. End to end with the real core (`RealCoreIntegrationTests`)

Runs in the normal suite when `tools/xray/xray.exe` is present. Local Xray processes act as VLESS servers
whose outbound redirects every connection to a local HTTP endpoint that answers with the server's name,
so each response proves which server carried it. The real controller drives the real core and the real
traffic gateway; the test opens the connections the per-app driver would open. All ports are shifted by
20,000, the per-app driver and Windows proxy settings are not touched, and an installed PerRoute can keep
running (it did, throughout).

| Step | Result |
|---|---|
| Per-app routing | Browser → server A (Main), Chat → server B (Auto, best quality), Game → server C (Manual). |
| Route monitoring | All three routes reach Healthy through the stricter HTTP check. |
| In-place main switch | Main A → D with no core restart; Chat's already-open keep-alive connection kept working on B; Browser now reaches D; Game still reaches C. |
| Auto failover | Server B's process was killed. Only Chat's route recovered and failed over, in place, to another measured server; Browser and Game were unaffected; no restart. |
| Bandwidth | Browser limited to 4 Mbps: 1.5 MB downloaded at 502–504 KB/s through the real core; unlimited afterwards ≈ 160–206 MB/s. |
| Inspector | Chat's request to `integration.test` was observed in the core's own log on the expected server and outbound, with byte counts from the gateway. |
| Cleanup | Disconnect stopped the gateway and the core; the per-app port was free again. No core process was left behind. |

## 3. Not verified: needs the per-app driver and real servers

These have **not** been run. They need a Windows machine with the Full Installer's per-app engine, at
least three working servers, and a way to make one server fail (for example a firewall rule).

1. **ProxiFyre with the gateway.** Select two apps, gateway on, connect. Both apps work; *Diagnostics >
   Inspector* lists their connections with byte counts. Repeat with the gateway off.
2. **UDP through ProxiFyre.** With the gateway on, use an app that relies on UDP (voice call, QUIC in a
   browser). It works, and a low upload limit visibly slows its UDP traffic. The gateway's UDP relay was
   only tested with a SOCKS5 client written for the tests, not with ProxiFyre's own UDP client.
3. **Bandwidth on real traffic.** Limit a browser to 8 Mbps download, run a speed test site: about 8 Mbps.
   Remove the limit while connected: full speed without reconnecting.
4. **Kill switch.** Kill switch on, gateway on, connect, then end PerRoute in Task Manager: selected apps
   have no connection, other apps are unaffected. Start PerRoute again: they recover after connecting.
5. **Independent recovery on a real server.** Block one custom server's address in Windows Firewall while
   connected. Only its route shows Recovering, its app has no connection (never direct), other apps keep
   working without a reconnect. Remove the block: the route recovers by itself.
6. **Auto failover on a real server.** One app in Auto; block its current server. After the retries it moves
   to another server, other apps keep their connections.
7. **In-place main switch on real servers.** Start a long download in an app on a custom server, then switch
   the main server: the download continues.
8. **Network change.** Switch Wi-Fi networks while connected with route monitoring on: routes recover on
   their own; the core is only restarted if none answers.
9. **Backup across computers.** Back up on one computer, restore on another with different install paths:
   the preview lists the missing apps; remapping them carries their routes and limits over.
10. **Upgrade.** Install 3.0.0 over a 2.0.0 installation with servers, custom routes, rules and profiles:
    everything is kept, nothing is in Auto mode, the new automatic features are off, connecting behaves as
    before.
11. **Installer and portable packages.** `package.ps1` was not run in this cycle; the versions were raised
    to 3.0.0 in the build, installer and packaging script.

Performance on real servers (idle CPU of the packaged application, memory over a day, gateway cost at
real bandwidths) has also not been measured; the measurements in the engineering report were taken on
loopback and with headless harnesses.
