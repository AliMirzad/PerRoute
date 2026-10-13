# Advanced routing: what was verified and what still needs a real machine

Advanced routing gives selected applications their own server. This page separates the checks that
were actually run from the ones that can only be done on Windows with the per-app driver installed
and at least two working servers. Nothing in the second list has been run.

## Verified automatically

Run with `.\test.ps1`. All of these pass.

| Area | What is checked | Test |
|---|---|---|
| Migration | A 1.0.0 state file (no Advanced flag, no assignments, a profile without the new fields) loads with Advanced routing off and stays off when saved again. | `Legacy state migration` |
| Persistence | The Advanced flag, assignments (keyed by lower-cased absolute path and server key) and last-known server names survive a restart. Turning Advanced off and saving keeps the assignments. | `Advanced routing persistence` |
| Planning | Advanced off, or any mode other than Smart per-app, ignores every assignment. Advanced on resolves each application to the main server or its custom server. An assignment to the server that is currently Main shares Main's route. A removed server is reported instead of being replaced. | `Route planner` |
| Config generation | One tagged outbound per distinct custom server, per-app inbounds mapped to their effective server, shared SOCKS/HTTP endpoints kept on Main, statistics and API routing unchanged, every inbound on loopback. With no custom server in effect the config has no extra outbound, inbound or rule, whether assignments are absent, null or all equal to Main. | `Multi-server config` |
| Rule precedence | A global Proxy rule is split per server at its original position; Direct and Block rules stay single rules; order and rule identity are unchanged; user rules never touch the route-check inbounds. | `Multi-server rule precedence` |
| Embedded profiles | An embedded Xray profile as Main with a custom server next to it: the profile's own rules are confined to the inbounds that follow the profile. Without custom servers its config is unchanged. | `Embedded profile with custom servers` |
| Embedded profile as a custom server | A profile with several outbounds, a balancer, an observatory, DNS and its own rules is used next to another such profile and next to a plain server. Its tags move into a namespace of their own, including balancer selectors, fallback, chained outbounds and observatory subjects; nothing of it matches the main profile's selectors; its rules apply only to its own applications; its DNS section is not adopted. | `Embedded profile as a custom server` |
| Controller | Advanced is off by default; disabling and re-enabling keeps assignments; the assignments reach the core only when in effect. | `Controller: Advanced routing is optional`, `…per-app servers reach the core` |
| Mutation locks | While connecting and while connected the controller rejects changes to Advanced, assignments, the app selection, rules and presets, as well as removing a server and applying a profile, and leaves the state untouched. | `Controller: mutation locks` |
| Missing server | An effective assignment to a removed server blocks connecting before anything is started, names the app and the server, and stops blocking once Advanced is off or a replacement is saved. A server whose config Xray rejects is named together with its app, and nothing is started. | `Controller: missing custom server` |
| Main switch | Switching the main server rebuilds the config with every custom server still in it. | `Controller: main switch keeps assignments` |
| Rollback | A config Xray rejects is never applied and the running connection is not touched. A core that fails to start is rolled back to the previous config, including its custom servers. | `Controller: switch rollback` |
| Profiles | Profiles store and restore the Advanced flag and assignments; a profile from before the feature turns Advanced off without erasing assignments. | `Controller: profiles store routing` |
| Subscriptions | A refreshed subscription keeps server keys, so assignments still resolve. A server the provider dropped is detected as unresolved and blocks only while it is in effect. | `Controller: subscription replacement` |
| Route checks | Each route is observed through its own port; a failed route keeps its own error; results are cleared on disconnect and reported stale after five minutes. | `Controller: route-specific checks` |
| Reset | Refused while connected. Afterwards no server, app, route, rule, profile or history is left, every preference is back at its default, the last generated config is deleted, and the empty state is what gets saved. | `Controller: reset to initial state` |
| Real Xray | With `tools/xray/xray.exe` present, the standard, multi-server, split-rule, profile-plus-custom and profile-as-custom configs are accepted by `xray run -test` (checked with Xray 26.3.27). Without the binary these validations are skipped. | `validateWithBundledXray` |

The controller tests use a fake backend: no Xray process is started, and the per-app driver, Windows
proxy settings, the network and saved preferences are not touched.

## Verified by rendering

`.\gradlew.bat renderUiSnapshots` draws the real screens off-screen. The output was reviewed for:

- baseline size 940x710, narrow size 820x620, and 150% scaling;
- dark and light themes;
- disconnected, connected, locked, empty, missing-server and failed-check states;
- long server and application names (ellipsis, nothing clipped);
- simulated pointer input: opening a server picker does not toggle the application, choosing a row
  toggles it and marks the draft unsaved, menus and dialogs open, the unsaved-changes prompt appears
  when leaving Apps;
- simulated Tab presses: the focus outline is drawn on window buttons, navigation, list controls and
  dialog buttons, the order follows the layout, and focus stays inside an open dialog instead of
  reaching the screen behind it.

Rendering does not exercise a real window. The following were **not** verified and need a person at
the keyboard: Escape handling (it is wired at window level), activating controls with Enter and
Space, screen-reader output, tooltips on hover, the native file dialogs, dragging and resizing the
window, the tray panel as a real always-on-top window, and Windows display scaling other than the
150% render.

## Not verified: needs the driver and real servers

Do these on Windows with the Full Installer build and two servers that exit from different
addresses. Record the result next to each line.

| # | Check | How | Expected |
|---|---|---|---|
| 1 | Two distinct routes | Advanced on. App A on Main, app B on a custom server. Connect, then open a what-is-my-IP page in each. | A and B show different public addresses, matching Diagnostics. |
| 2 | Unselected app stays direct | With the same connection, check the address in an application that is not selected. | It shows your direct address. |
| 3 | TCP and UDP | In app B run something that uses UDP (a QUIC site or a voice call) and something that uses TCP. | Both work and both leave through B's server. |
| 4 | IPv4 and IPv6 | Open an IPv6-only and an IPv4-only test page in app B, on a network that has IPv6. | Neither leaks the direct address. Note what happens when the server has no IPv6. |
| 5 | Kill switch | Kill switch on. Connect, then stop the Xray process from Task Manager with auto reconnect off. | Apps A and B lose connectivity and do not fall back to the direct route; other apps are unaffected. |
| 6 | Reconnect | Auto reconnect on. Put the computer to sleep and wake it, or switch networks. | The connection returns with both routes; app B is on its custom server again. |
| 7 | One failed server | Assign app B to a server that is down, connect. | Connection and Diagnostics show the failure for B's server only; app A keeps working; B is not moved to Main or to the direct route. |
| 8 | Main switch while connected | With 1 running, choose another main server. | Both apps are interrupted briefly; afterwards A uses the new main server and B still uses its custom server. |
| 9 | Main failover | Auto failover on, make the main server unreachable. | Main moves to another server; B's custom server is unchanged. |
| 10 | Counters | Transfer a known amount in app A only. | Connection, Traffic and the per-app totals grow by about that amount, once. |
| 11 | Other modes | Switch to System proxy and to Local proxy with assignments saved. | They behave as before; assignments are ignored and still present afterwards. |

## Known limits

- All servers share one Xray process. Changing the main server, and automatic failover, restart it
  and briefly interrupt applications on custom servers as well.
- Custom servers are not failed over and cannot be switched while connected.
- Custom servers work in Smart per-app only.
- An embedded Xray profile used as a custom server brings its outbounds, balancers, observatory subjects and rules. Sections Xray has only once per process are not taken from it: DNS and policy come from the main server, and observatory probe settings from the main profile when it has an observatory.
- A profile that Xray rejects on its own cannot be used as a main or custom server; PerRoute names it instead of connecting.
- DNS is not measured per route. In Smart per-app mode applications normally resolve names through
  Windows before their connection is tunnelled.
- Traffic history holds byte totals per application and day. There is no per-server history.
- A route check opens one connection to a public service through that route. It is an observation
  at that moment, not proof that nothing leaks.
