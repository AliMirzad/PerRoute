# PerRoute 3.0 — engineering report

Branch `v3-dev`, based on `822c24d` (PerRoute v2.0.0). Windows 11 Pro, JDK 21.0.10, Xray 26.3.27.
An installed PerRoute was running and connected on the same machine throughout; nothing in this work
touched its ports, its core, the per-app driver service or Windows proxy settings.

## 1. Implemented features

| Feature | Status | Where |
|---|---|---|
| A. Network quality score | Complete. Explainable 0–100 score, six labels, confidence, freshness/staleness, bounded persisted history, deterministic scorer, bounded background measurement. | `quality/*`, `runtime/QualityService.kt`, Servers screen |
| B. Smart per-app server selection | Complete. Main / Manual / Auto per app; Auto per app with its own route, hysteresis margin, cooldown, failover interval, candidate pools, persisted choice, explanations; never direct; off by default for every app. | `routing/AutoServerSelector`, `compose/AutoRouting.kt`, Apps screen |
| C. Independent route recovery | Complete within one shared core (hybrid design, see §2). Per-route monitoring, bounded exponential backoff, in-place rebuild, failure state with slow re-checks, Auto failover in place, in-place main switch. | `routing/RouteHealthTracker`, `runtime/RouteRecoveryManager.kt`, controller, Connection screen |
| D. Per-app bandwidth control | Complete for Smart per-app mode. Aggregate per-app download/upload limits for TCP and UDP, enforced by a new loopback traffic gateway; live changes; enforcement status. | `gateway/*`, Apps and Traffic screens |
| E. Connection inspector | Complete. Live per-connection table from the gateway joined with the core's own log; configured, observed and verified facts kept apart; filters; 2 s refresh, pausable; bounded, in memory only. | `inspector/*`, `compose/InspectorView.kt`, `InspectorScreen.kt` |
| F. Portable profiles and backup | Complete. Versioned schema, optional authenticated encryption, preview, Merge/Replace, path remapping, atomic restore, rollback snapshot, undo, migration hook. | `backup/*`, Settings > Backup & Restore |

Also fixed: the tunnel health check reported success for dead servers (see §8).

## 2. Architecture changes

**Starting point.** One Xray process carries every route. ProxiFyre redirects each selected app to a
per-app SOCKS inbound (`127.0.0.1:18100+i`); Xray routes each inbound to the main server or a custom
server. Any change to a route (main switch, failover) regenerated the config and restarted the process,
interrupting every app.

**Options considered for route isolation.**

| Option | Assessment |
|---|---|
| Separate Xray process per route | True process isolation, but more processes and memory, stats and validation per process, moving an app between servers restarts two processes, and every rule/inbound must be duplicated. Large rewrite of a working system. |
| Supported runtime modification of the shared core | Xray 26.3.27 offers `HandlerService` (add/remove outbounds) and `RoutingService` (replace all rules in one call). Verified experimentally on this machine. Smallest change; keeps one process. |
| Hybrid | Shared core plus runtime changes for the common cases, restart only where runtime changes are unsafe. **Chosen.** |

**Chosen design.**

1. *Route slots.* Main and Manual apps keep v2's grouping (one route per server). Every app in Auto mode gets
   a route of its own, implemented as a copy of its server under a private key (`auto-slot:<path>`), so the
   generated config gives it its own outbound tag and check port.
2. *In-place route change.* To move one route: generate the complete new config, rename every other route's
   outbound to the tag the running core already uses, give the moving route a fresh tag (alternating
   `tag`/`tag~1`), validate with `xray run -test`, then `ado` the new outbound, `adrules` the complete rule
   list (one atomic swap), `rmo` the old outbound. Verified on the real core: no gap in which traffic falls
   to a default outbound (Xray falls back to the first outbound when a rule's tag is missing, which is why a
   remove-then-add swap was rejected). A change is refused (and the caller restarts or keeps waiting) when the
   two configs differ in anything but that one outbound: embedded profiles, balancers, a different route
   layout.
3. *Per-route monitoring.* `RouteHealthTracker` (pure state machine) and `RouteRecoveryManager` (one
   scheduler thread, probes on virtual threads, at most 6 at once) check each route through its own loopback
   port. Healthy every 60 s; failures back off 2-4-8-16-32 s; rebuild in place at the second failure; after
   five, Failed and re-checked every 120 s; recovery is automatic.
4. *Traffic gateway.* An in-process SOCKS5-aware relay between ProxiFyre and the core: ProxiFyre keeps
   targeting `18100+i`, the gateway listens there and forwards to the app's inbound, which moves to `19100+i`.
   One token bucket per app and direction gives aggregate limits; UDP associations are carried through the
   gateway's own UDP relay. All listeners bind or none do. Routing decisions remain in the core.
5. *Inspector.* Gateway connections (destination, bytes, state, the loopback port used towards the core) are
   joined with the core's access line (by that port: observed outbound) and session lines (by destination
   and time: sniffed domain, rule). Configured facts are computed separately from rules and the route plan;
   verified facts come from route checks.
6. *Quality.* Samples come from pings, real tunnel tests, live route checks (every minute per route), the
   main server's latency checks (once a minute) and an optional background pass (≤ 24 port checks per 10 min,
   4 at a time; ≤ 2 tunnel verifications per pass, only while an app uses Auto).
7. *Backups.* Pure planning (`BackupService.plan`) on a deep copy of the state; the controller commits with a
   DPAPI-protected rollback snapshot first, an atomic state write, then the in-memory swap and preferences, and
   rolls everything back on failure.

**Responsibilities.** New logic lives in cohesive, UI-independent units: `quality`, `routing`, `gateway`,
`inspector`, `backup` (Java, pure or I/O-bounded) and `runtime` (Kotlin schedulers). The controller remains
the single coordinator that decides whether an action is allowed; it gained about 900 lines of glue (settings,
connect/switch integration, in-place change, bandwidth status, backup commit). This is the main structural
debt; extracting the backup commit and the in-place change into their own classes is the obvious next step.

**Runtime integration boundary.** `ConnectionBackend` gained `applyRuntimeChange`, gateway start/stop/limits/
counters/listener, with default implementations so existing backends keep compiling.

## 3. Files added or modified

56 files, +8,314 / −239 lines (source, tests, installer metadata), plus documentation.

Added (main): `backup/BackupCodec`, `backup/BackupService`, `gateway/AppGateway`, `gateway/GatewayConnection`,
`gateway/RateLimiter`, `inspector/ConnectionInspector`, `inspector/XrayLogParser`, `model/BandwidthPolicy`,
`quality/NetworkQualityScorer`, `quality/QualityHistoryStore`, `quality/QualityLabel`, `quality/QualitySample`,
`quality/QualityScore`, `routing/AppRouteMode`, `routing/AutoCandidatePool`, `routing/AutoSelection`,
`routing/AutoServerSelector`, `routing/RouteHealthTracker`, `service/PortOffset`, `runtime/QualityService.kt`,
`runtime/RouteRecoveryManager.kt`, `compose/AutoRouting.kt`, `compose/InspectorView.kt`,
`compose/InspectorScreen.kt`, `compose/QualityViews.kt`, `compose/SettingsV3.kt`.

Modified (main): `PerRouteController`, `ConnectionBackend`, `ComposeMain`, `AppsScreen` (rewritten around the
same structure), `ServersScreen`, `ConnectionScreen`, `DiagnosticsScreen`, `TrafficScreen`, `SettingsScreen`,
`XrayConfigGenerator`, `XrayProcessManager`, `RoutePlanner`, `ProxyHealthCheck`, `AppStateStore`, `AppState`,
`ConnectionProfile`, `AppProxyLauncher`, `SecureUpdateService`; version bump in `build.gradle.kts`,
`package.ps1`, installer sources and manifests.

Tests: added `V3DomainTests`, `V3ControllerTests`, `RealCoreIntegrationTests`; extended `AllTests`,
`ControllerTests` (fake backend capabilities, retrying cleanup), `UiSnapshots` (v3 data and 13 new scenes).

Docs: `README.md`, `docs/releases/v3.0.0.md`, `docs/v3-verification.md`, this report.

## 4. Migration

- State format version 3 (`state.version=3`). New keys: Auto apps and pools, Auto selections, bandwidth
  limits, `state.migratedFromV2`, and `autoApps` inside profiles. Unknown keys are ignored, so older files
  load unchanged; a state without `state.version` is marked as migrated from version 2.
- Defaults on first start of 3.0: background quality checks, route monitoring and the traffic gateway are
  written **off** for migrated installations and **on** for fresh ones, once, so they never flip later.
- No app is in Auto mode and no app is limited after migration. Without monitoring or an Auto app the core
  config keeps v2's read-only statistics API and the main switch restarts as in v2.
- v2 profiles load (no Auto apps). Applying a profile with Advanced routing sets Auto apps from the profile.
- Downgrade: version 2 ignores the new keys and drops them on its next save.
- Backups have their own schema (1) with a migration hook; newer schemas and envelope versions are refused.

## 5. Tests executed and results

Command: `.\gradlew.bat runAllTests` (what `test.ps1` runs). Final run: **All tests passed (1016 assertions)**,
about 80–110 s.

| Suite | Tests | Notes |
|---|---|---|
| Version 2 suite (`AllTests`, `ControllerTests`) | 33 + 12 | All pass. Baseline before changes: 504 assertions passing, with one intermittent teardown failure (`DirectoryNotEmptyException`) caused by background writers; fixed by retrying temp cleanup. The tunnel health test was strengthened (see §8). |
| `V3DomainTests` | 22 | Quality, Auto, route health, gateway (real sockets: TCP, UDP, limits, failures), inspector, backup, migration. |
| `V3ControllerTests` | 14 | Auto, independent recovery, in-place change, gateway, migration defaults, kill switch, inspector, backup, rule precedence, removed servers. |
| `RealCoreIntegrationTests` | 1 scenario | Real Xray core and gateway on loopback (see §6). |
| UI snapshots | 80 scenes | `renderUiSnapshots` renders every screen with demonstration data, both themes, narrow and 150 % sizes; all 80 rendered without errors and the new screens were reviewed against the design references. |

## 6. Windows integration verification

| Kind | Status |
|---|---|
| Unit tests | Passed. |
| Integration tests (real sockets, real Xray `-test` validation, real gateway) | Passed. |
| Real core end to end on Windows (loopback servers, real Xray 26.3.27, real gateway, real controller) | Passed: per-app routing, in-place main switch with another app's open connection surviving, Auto failover after a server process was killed, 4 Mbps limit measured at 502–504 KB/s, inspector observation from the core's own log, clean shutdown. |
| Real Windows with the per-app driver (ProxiFyre) and real servers | **Not run.** It would have disrupted the user's live PerRoute session and needs working servers. Checklist in `docs/v3-verification.md` §3. |
| Installer and portable packaging | **Not run** (`package.ps1`). Versions were raised to 3.0.0. |

## 7. Performance comparison

All figures measured on this machine. Windows accounts process CPU in 15.6 ms ticks, so very small CPU
figures are bounded by that resolution.

**Controller overhead, v2 vs v3** (same headless harness compiled against both builds; fake machine; connected
Smart per-app session, 3 apps, one custom route; 30 s idle; 3 runs each; app JVM flags):

| Metric | v2.0.0 | v3.0 |
|---|---|---|
| Controller construction | 92–100 ms | 112–130 ms |
| Connect (fake core) | 288–290 ms | 285–295 ms |
| Idle CPU while connected | 0.52 ms/s (one tick per 30 s) | below one tick per 30 s |
| Live heap after GC | 3,108–3,203 KB | 3,275–3,282 KB |
| Platform threads | 16–17 | 17–19 |

The harness's fake core answers statistics instantly, so this isolates PerRoute's own overhead. The added
threads are virtual-thread carriers used by the route monitor and quality service.

**Telemetry in a real session** (real core, loopback):

| Path | Cost per second while connected |
|---|---|
| v2 / v3 without gateway: `xray api statsquery` | one `xray.exe` launch; 63 ms wall; 1.6 ms PerRoute CPU + 4.7–5.5 ms `xray.exe` CPU (≈ 0.6–0.7 % of one core); 3,600 process launches per connected hour |
| v3 with gateway: in-process counters | 0.0005 ms; no process launch |

**Traffic gateway overhead** (real core, local VLESS server, 256 MB downloads, 3 rounds; 300 sequential
connections):

| Metric | Direct to core inbound | Through gateway |
|---|---|---|
| Throughput | 83–90 MB/s | 81–92 MB/s (no measurable loss; the core is the bottleneck) |
| PerRoute JVM CPU per MB | 0.0–0.8 ms | 0.4–1.9 ms (difference ≈ 0.1–0.5 ms/MB; noisy at this resolution) |
| Connection setup (SOCKS + HTTP through the core) | 1.66–1.70 ms | 2.37–2.99 ms (+0.7–1.3 ms) |

**Rate limiter accuracy** (gateway, real sockets): 2.05–2.06 MB/s for a 2 MB/s limit over three connections;
1.04–1.05 MB/s for 1 MB/s upload over two; 167 KB/s for a 160 KB/s UDP limit; 502–504 KB/s for 4 Mbps end to
end through the real core.

**Network and disk activity added by v3** (by construction, not measured on real servers):

- Route monitoring: per live route, one SOCKS CONNECT and one HTTP `HEAD` through the tunnel per minute
  (well under 1 KB each way); faster only while a route is recovering (5 retries, then every 2 minutes).
- Background quality: at most 24 TCP handshakes per 10 minutes, 4 at a time; at most 2 short-lived core
  processes per 10 minutes for tunnel verification, only while an app uses Auto.
- Main server latency checks: unchanged (every 5 s); one in twelve is also recorded as quality evidence.
- Disk: quality history saved at most once a minute and only after new samples; Auto choices saved only
  when they change; state writes keep v2's debounced writer.
- UI: the inspector refreshes every 2 s only while visible and paused stops it; server scores refresh every
  15 s only while Servers is open; route health updates on the existing once-a-second tick.

Startup of the packaged application and long-running memory were not measured.

## 8. Security and reliability findings

1. **Tunnel check false positive (pre-existing, fixed).** Xray's SOCKS inbound acknowledges CONNECT before
   dialling the server. v2 counted that acknowledgement as a healthy tunnel, so "Tunnel health … reached
   cloudflare.com" was logged for a server that did not exist (reproduced against a VLESS outbound pointing at
   a closed port). The check now requires an HTTP status line through the tunnel. This also affected the
   network-change reconnect decision.
2. **Default-outbound fallback.** When a routing rule names a missing outbound, Xray uses the first outbound.
   A remove-then-add swap would briefly send an app through the main server (or, for some embedded profiles,
   a direct outbound). The in-place change therefore adds the new outbound first, swaps all rules atomically,
   and removes the old one last; it refuses configs with balancers or a changed outbound set.
3. **Unauthenticated core API.** `HandlerService` and `RoutingService` can change routing, and loopback ports
   are reachable by other Windows accounts. They are enabled only while route monitoring or an Auto app is in
   use (fresh installations: monitoring on), never for migrated installations by default. A Unix-domain socket
   for the API was tested and is not supported by Xray on Windows. On shared multi-user machines this is a
   residual risk; v2 already exposed unauthenticated SOCKS/HTTP endpoints on loopback.
4. **Credentials at rest.** Runtime-change files (which contain the moved outbound) are written to the data
   folder and deleted immediately, like v2's `current-config.json`. Backups encrypt credentials by default;
   unencrypted backups carry a warning in the UI. Passwords are wiped from memory after use and never logged.
5. **Fail-closed behavior.** No path routes an app to the direct route on failure: unresolved Auto choices
   block connecting; failing routes wait; a gateway that cannot start aborts the connection and releases the
   driver only when the kill switch is off; with the gateway, a PerRoute crash leaves selected apps without a
   connection rather than direct.
6. **Bounded work.** Route probes ≤ 6 concurrent, quality pass ≤ 24 servers per 10 min, gateway ≤ 4,096
   connections per app, inspector ≤ 1,500 entries, quality history ≤ 64 samples per server, recovery timeline
   ≤ 12 events, retries bounded with a 2-minute floor afterwards.
7. **Process lifecycle.** The gateway runs in-process and stops on disconnect, crash detection, shutdown hook
   and close. Background verification cores are destroyed in `finally`. The integration tests verified that no
   core process was left behind.
8. **Input validation.** Backups are size-limited (32 MB), schema- and envelope-versioned, authenticated when
   encrypted, checksummed otherwise; every server, rule and profile entry is re-validated through the state
   codecs; preferences are restored only from a whitelist with type checks; subscription URLs must be http(s).

## 9. Known limitations

- Isolation is per route inside one shared core, not per process. A crash of the core itself still affects
  every route (it is restarted by auto reconnect with all assignments preserved).
- In-place changes are not available for embedded Xray profiles, configs with balancers, or a main switch to
  a server that is also another app's custom route; these restart the core as in v2.
- Changing an app's route mode, its Manual server or the app selection still requires disconnecting.
- Bandwidth limits apply only in Smart per-app mode, only to selected apps, and need the traffic gateway.
  No priority classes. Limits below 64 kbit/s are raised to 64 kbit/s.
- The gateway adds about 1 ms when a connection opens and a small CPU cost per megabyte.
- The inspector shows the destination the app asked for (usually an IP); the domain appears only when the
  core sniffed one. Observed rules are joined to connections by destination and time and are omitted when the
  core logged no rule. Without the gateway there are no byte counts or close events. PIDs are the running
  instances of the app's executable, not of individual connections (the driver does not report them).
- Verified facts describe a route's last external check, not the individual connection.
- Quality scores use port checks for latency and jitter; a server never tunnel-tested is capped at Good.
- Unencrypted backups are checksummed, not authenticated.

## 10. Remaining blockers

None for the implemented scope. Not done in this cycle:

- Real-machine verification with ProxiFyre and real servers (`docs/v3-verification.md` §3), including UDP
  through ProxiFyre's own client, and the upgrade path from an installed 2.0.0.
- Building and smoke-testing the installer and portable packages.
- Splitting the controller's new glue into dedicated classes.

## 11. Git commits

```
d6055ed v3: enable the core's routing control API only when a feature needs it
97aa40f v3: UI for quality scores, per-app Auto/Main/Manual, route recovery, inspector, bandwidth and backups
4783caf v3: real end-to-end health checks and a real-core integration test
21a42dc v3: controller tests for Auto, recovery, in-place changes, gateway, inspector, backups and migration
16449e6 v3: integrate quality, Auto routing, route recovery, gateway, inspector and backups into the controller
fe0a203 v3: domain tests; fix SOCKS address relay; stabilize fixture teardown
25f68be v3: domain layer for quality scoring, auto selection, route health, gateway, inspector and backups
```
(plus the documentation commit). Nothing was pushed, merged or released.

## 12. Readiness assessment

The six features are implemented and pass 1,016 automated assertions, including an end-to-end run with the
real core and the real gateway. Version 2 behavior is preserved by its unchanged test suite and opt-in
defaults for upgraded installations.

**Not production-ready yet.** Before release: run the real-machine checklist with the per-app driver and
real servers (especially ProxiFyre's UDP path through the gateway, the kill switch with the gateway, and the
upgrade from an installed 2.0.0), build and smoke-test the installer and portable packages, and measure the
packaged application's idle CPU and memory over a longer session.
