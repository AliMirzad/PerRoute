# PerRoute 3.0 — UI redesign brief

This document lists every screen, feature, state and action that the PerRoute interface must support. It
describes **what** the UI has to show and do, not how it should look. Use it to design a new interface;
everything listed here is backed by working code, so nothing in the design may be decorative or fake.

## 1. Product in one paragraph

PerRoute is a Windows desktop proxy client (VLESS, VMess, Trojan, Shadowsocks, Xray JSON profiles) built on
Xray-core. Its core idea: **only the applications the user picks go through the proxy; everything else stays
on the normal connection.** Optionally, each picked app can use its own server, picked by hand or
automatically by quality. Version 3 adds server quality scores, automatic per-app server selection,
independent recovery of each route, per-app bandwidth limits, a connection inspector, and backup/restore.

## 2. Platform and frame

- Windows 10/11 desktop app. Default window 940 × 710, minimum 820 × 620, resizable and maximizable. Layouts
  must work at the minimum size, at 150 % display scaling, and maximized.
- Custom title bar (no Windows frame): app icon, name, **real version badge** (currently 3.0.0), drag area,
  minimize / maximize-restore / close. Minimize and close send the app to the notification area (tray), they
  do not quit.
- **Dark and light themes**, switchable in Settings; both must be fully designed.
- Main navigation with **eight sections**: Connection, Apps, Servers, Rules, Profiles, Diagnostics, Traffic,
  Settings. Always labelled.
- A small footer credit: "Created by Ali Mirzad".
- Global feedback: a toast message (success or error, error stays longer, dismissable), modal dialogs that
  trap keyboard focus, Escape closes dialogs/menus.
- Keyboard: full Tab/arrow navigation with a visible focus ring for keyboard users only.
- Accessibility rule: **no state may be shown by colour alone** — always text and/or an icon as well.
- Second window: **Quick Access**, a compact tray panel (about 390 × 600, always on top), see §12.

## 3. Concepts the UI must explain

| Concept | Meaning |
|---|---|
| Server | One proxy endpoint. Has a name, protocol, source (a subscription, Manual, or Imported), optional favorite star, optional single tag, country (from the name), latency, download speed and a quality score. |
| Subscription | A URL that provides a list of servers; can be updated or removed. |
| Main server | The server every routed app uses by default. Chosen explicitly ("Use as main server"). |
| Connection mode | **Smart per-app** (only selected apps; needs the per-app driver from the full installer), **System proxy** (apps that follow Windows proxy settings), **Local proxy** (SOCKS5 127.0.0.1:17890 and HTTP 127.0.0.1:17891 the user configures in apps). |
| Selected apps | The apps routed in Smart per-app mode. Unselected apps are "Direct". |
| Advanced routing | Off by default. When on, each selected app has a **route mode**: **Main** (follows the main server), **Manual** (a server the user picks, shown as "Custom"), **Auto** (PerRoute picks and maintains a server for this app). |
| Route | A path through the core. The main server is one route; each Manual server is one route shared by its apps; every Auto app has a route of its own. |
| Kill switch | When on, selected apps stay offline whenever their tunnel is unavailable. |
| No direct fallback | A failing route never sends its apps to the normal connection; they wait. Always true. |
| Traffic gateway | An internal relay that makes bandwidth limits and detailed connection data possible. On/off in Settings, only while disconnected. |
| Quality score | 0–100 with a label: **Excellent ≥85, Good ≥70, Fair ≥50, Poor <50, Unavailable, Unknown**. Has a confidence (0–100 %), a "last measured" time, and can be **outdated** (older than 30 min). Unknown = not measured; never shown as healthy. |
| Configured / Observed / Verified | Three different kinds of facts in the inspector: what the setup *should* do (prediction), what the core *was seen* doing, what an external route check *confirmed*. Must always look distinct. |

### Status vocabulary (each needs its own visual treatment)

- Connection: Not connected, Connecting…, Connected, Disconnecting…, Complete your setup.
- Route check: Not checked yet, Checking…, Passed · N ms, Check failed, Stale (older than 5 min).
- Route health (v3): Healthy, **Recovering** (attempt X of 5, retry in N s), **Route failed** (checked again in N s).
- Quality label: Excellent, Good, Fair, Poor, Unavailable, Unknown (+ outdated marker).
- Auto decision summary: e.g. "Best quality · 92", "Main server until measured", "Failover · 78", "Stable route kept", "No working alternative".
- Bandwidth: **Unlimited, Enforced, Throttling, Pending, Not enforced** (+ a one-line reason), "Idle".
- Inspector connection: Active, Opening, Seen, Closed, Blocked, No response, Failed.
- Route kinds: Main (solid badge), Custom (outlined badge), Auto (distinct badge), Direct, Blocked.

Semantic colours used today (keep the meaning, the exact palette is free): violet = primary / selected /
editable; teal-mint = healthy / connected / verified / Auto; amber = degraded / retrying / stale /
throttling / attention; red = hard failure or destructive only.

### Global rule: locked while connected

While connecting or connected the user **cannot** change: connection mode, Advanced routing, the app
selection, route modes and Manual servers, rules, presets/profiles, the traffic gateway, removing servers or
subscriptions, restore, reset. Every locked control must say why ("Disconnect to …").
**Always allowed while connected:** switching the main server, bandwidth limits, favorites/tags, tests,
checks, creating a backup, all Settings toggles not listed above.

## 4. Connection (home)

Purpose: connect/disconnect and see the health of every route.

Shows:
- Status title + current mode; a large **Connect / Disconnect** button with in-progress states; summary
  "N apps • N servers".
- Live tiles: Download speed, Upload speed, Session duration, Main server latency.
- Local proxy mode only: the two endpoints (SOCKS5 and HTTP addresses), click to copy.
- **One row per route**: server (avatar/country code, name, Main / Custom / Auto badge), its applications
  (icons + names, a dot on apps sending traffic now, "+N" overflow), and its status (last check or health).
- A route that is **Recovering / Failed** expands: "Only this route is affected" explanation, "Kill switch
  active" (if on), "No direct fallback", last error, a **recovery timeline** (time + event: Failure detected,
  Retry scheduled, Route refreshed, Retries exhausted, Recovered, Failed over…), buttons **Retry now** and
  **Change server** (goes to Servers for the main route, Apps otherwise).
- A summary/banner line, one of: "N routes healthy • N recovering"; "N apps use their own route…";
  "All selected apps use the main server…"; mode explanation for System/Local proxy; a problem with a fix
  button (missing server → "Fix in Apps", no apps selected → "Manage apps", no servers → "Add servers").
- Two shortcut cards: Applications (count, active apps) → Manage apps; Main server (name, who uses it) →
  Choose main server.

States: no servers, no apps (Smart per-app), missing assigned server (connect blocked), disconnected,
connecting, connected standard, connected with several routes, a route recovering, Local proxy, System proxy.

## 5. Apps

Purpose: choose which apps are routed and how each one picks its server; set bandwidth limits.

- Header: "Applications" + explanation.
- **Advanced routing** switch with a sentence describing the current effect.
- Banners when relevant: locked while connected; not in Smart per-app mode (+ "Change mode"); N apps need a
  replacement server.
- Source chips **Installed apps / Running apps**, search box, refresh button; loading state; empty states
  (no match + Clear search; nothing found + Refresh / Browse).
- **App list** (each row): app icon, name, full path, checkbox (selected). When Advanced is on and the app is
  selected: a 3-way **route mode** control (Auto / Main / Manual) and a server cell:
  - Main → main server name + Main badge.
  - Manual → server picker (searchable when many; favorites first with star; each entry shows protocol,
    source, tag, latency, quality); amber states "Choose a server" and "Missing: <name>".
  - Auto → the chosen server + "Auto" badge + decision summary (e.g. "Best quality · 92"; amber when it is
    only the main server until measured).
  - Unselected → "Direct".
  - Advanced off but app has a limit → "Limited" badge.
- **Side panel** (one app focused) — Selected app card (icon, name, path, remove), **Route mode** control,
  **Current decision** card (server, quality pill + headline, explanation, Auto badge + summary, and for Auto
  a **"Choose from: All servers / Favorites / #tag"** pool menu), **Bandwidth limit** editor (Unlimited
  switch, Download and Upload in **Mbps**, Apply, status Enforced/Throttling/Pending/Not enforced with reason,
  validation message), note that route modes change while disconnected but limits apply immediately.
- Side panel (nothing focused) — list of selected apps with their route (click to focus), main server and who
  uses it, a hint sentence.
- Footer: status text (N apps selected • N routes / unsaved changes / locked), **Browse for an EXE**,
  **Cancel**, **Save changes**.
- Behaviour: selection, modes, Manual servers and pools are a **draft** until Save changes; leaving the
  screen with unsaved changes asks Keep editing / Discard / Save. Bandwidth limits are saved immediately.

## 6. Servers

Purpose: manage servers and subscriptions, test them, choose the main server.

- Header actions: **Update subscriptions**, **Add servers**.
- Filters: All, Favorites, one chip per subscription, Manual, Imported; tag and country chips; search; sort
  (Default order, **Quality**, Lowest latency, Highest latency); **Test servers** menu (Ping all servers,
  Real tunnel test, Speed test listed servers / Stop speed test, Hide/Show failed servers).
- Count "N of M servers"; when a subscription filter is active: copy its URL, remove it (confirm).
- **Server row**: avatar (country), name, badges (Main, Custom, Favorite, tag), protocol • source, "Used by"
  apps (or "Not assigned" / "All selected apps"), latency chip (Testing…/Untested/No answer/N ms), speed chip
  (click to test this server; Mbps), **quality pill** (score + label, outdated marker; tooltip with
  confidence and age), favorite star, tag menu (existing tags, New tag…, No tag), more menu (Use as main
  server, Copy config, Remove server), expand.
- **Expanded quality details**: label + confidence; tiles Latency, Jitter, Success %, Stability %, Tunnel
  setup, Last tested; explanation box (headline, sentences, "Confidence N % from N measurements", recent
  recovery penalty); for Unknown a "how to measure" message.
- Footer: current main server + note, **Use <selected> as main server** (works while connected).
- Empty states: no servers (Add servers), no match (Clear filters).
- **Add servers dialog**: three methods — Subscription URL (Fetch servers), Paste links (share links,
  subscription content or Xray JSON; Paste from clipboard; Review links), QR code (from image file, clipboard,
  or camera with a time limit). Always a **review step before saving**: list of found servers (new vs already
  saved), parser warnings, how many will be removed when a subscription is replaced; then Add servers.
- **Tag dialog**: choose an existing tag or type a new one; remove tag.
- **Remove server** confirmation (mentions apps that will need a replacement).

## 7. Rules

Purpose: per-app or global routing rules; first match wins.

- List of rules in order with number, name, match type (Domain / IP / Port) and values, action (**Proxy,
  Direct, Block**), which app (or all routed apps), on/off switch, badges "Preset" and "Off"; menu Edit, Move
  up, Move down, Delete. Preset rules stay on top and cannot be edited or moved.
- Filter by app ("All rules" / one app), search; "Test a route" shortcut (to Diagnostics).
- **Rule editor panel**: Name, Application (all routed apps or one), Match type, Values (comma list with
  examples like `example.com`, `full:`, `keyword:`, `geosite:`, CIDR, `geoip:`, port ranges), Action; Save /
  Cancel. A "What a rule does" explanation.
- Read-only while connected; empty state.

## 8. Profiles

- **Presets**: Work, Gaming, Streaming — each with a summary and highlights. **Preview changes** dialog
  (automation switches before → after, rules added, rules removed) then Apply; **Remove preset rules**.
- **Saved profiles**: name, contents summary, Active badge, Apply, menu (Replace with current setup, Delete).
  **Save current setup** dialog (profile name). A profile saves apps, main server, mode, Advanced routing,
  per-app assignments (including Auto) and rules. Warnings for apps no longer on disk.
- Locked banners while connected (saving is always allowed).

## 9. Diagnostics

Three tabs (plus Rule tester as its own tab on narrow windows):

**Route checks** — the Direct connection plus every route in use, each with public IP + country, check status
and age, details (round trip, detail text), Check again / Look up public IP; failed and stale explanations;
collapsible System DNS details (Windows resolvers, resolver seen by public services, time); a kill-switch
sentence; a disclaimer that checks are not a leak test. Header button **Run checks**.

**Inspector (v3)** — live connections of selected apps (Smart per-app, connected only):
- Toolbar: search, filter by app, by route, by status (All / Active / Failed or blocked / Closed),
  **Live / Paused**, "Updates every 2 s • N connections", legend Configured / Observed / Verified.
- Banner when the traffic gateway is off (rows come from the core log only, no byte counts).
- Table columns: App (+protocol), Destination (+sniffed domain or "N destinations" for UDP), Route (one line
  **Configured**, one line **Observed**), Status, Live traffic (↓/↑), Last activity.
- **Details panel** for one connection: header (app, exe, PIDs, close), then three clearly separated blocks:
  1. *Configured* — route, matching rule, rule source, uncertainty note;
  2. *Observed* — process path, destination, sniffed domain, protocol, effective route, core outbound,
     matched rule, live traffic, totals, first seen, closed, failure;
  3. *Verified* — route, external IP + country, route test result, tested time, or "Not verified yet".
  Button **Copy details**.
- Empty states: not connected; not Smart per-app; no connections yet; no match.

**Loaded rules** — every rule with whether it is active in the running core, not loaded, or switched off.

**Rule tester** (side panel on wide windows) — app, domain or IP, port → Check route: action, matched rule,
server; note that it predicts the decision only.

## 10. Traffic

- Segmented: Today / This month / Daily totals; app filter; Clear traffic history (confirm) in a menu.
- Tiles: Today download, Today upload, Month download, Month upload.
- **Live speed chart** (last 90 s, download + upload, hover shows exact values; empty/connect messages).
- **Bandwidth by app (v3)**: for every selected app — current ↓/↑ (Mbps), session totals, limits ↓/↑ or
  Unlimited, utilization bars with %, status (Enforced / Throttling / Pending / Not enforced / Unlimited /
  Idle), menu **Edit limits…** (dialog with the same bandwidth editor) and **Set unlimited**; header with
  active-app and throttling counts; red reason line when limits are not enforced.
- Usage lists: per app (today or month) with download/upload bars, or per day of the month.
- Note: only byte counts are stored; never destinations.

## 11. Settings (category list + page)

1. **General** — Appearance (Dark/Light), Window note (close = tray), **Reset everything…** (confirm; only
   while disconnected), About (version, slogan, credit).
2. **Connection** — mode cards (Smart per-app / Local proxy / System proxy; Smart per-app disabled with a
   reason when the driver is missing), Advanced routing switch, count of saved assignments, Manage app routes.
3. **Routing (v3)** — Background quality checks (switch, last pass time); Automatic server selection: switch
   margin (5 / 10 / 15 / 20 points) and cooldown (5 / 10 / 20 / 30 min) with explanation; Route recovery
   switch (monitor each route on its own); Traffic gateway switch (locked while connected, explanation).
4. **Automation** — Auto reconnect, Main server failover, Per-app kill switch, Fastest main server on connect,
   Automatic speed tests (each a switch with a description).
5. **Updates** — signed HTTPS update feed URL, Check for updates, available version, download progress,
   Install update.
6. **Components** — Xray core and Per-app driver status (installed / missing / checking) with Install and
   Check again.
7. **Backup & Restore (v3)** — Create backup: checklist of Subscriptions, Manual servers, App assignments,
   Rules, Profiles, Preferences; **Encrypt with password** switch (on by default) with password + repeat and
   validation, warning when off; Create backup (save-file dialog, `.prb`). Restore backup: Choose backup
   (password prompt if encrypted), **Undo last restore**, locked note while connected, rollback explanation.
   **Import preview dialog**: file name, created date, app version, Compatible badge, encryption/checksum
   status, counts (Subscriptions, Servers, App assignments, Rules, Profiles, Preferences), already-present
   notes, warnings, **Path remapping** list (missing app paths, Choose path…, Skip, suggestion), **Merge vs
   Replace** choice with explanations, rollback note, Cancel / Restore backup.
8. **Activity log** — last 200 events, selectable text, Copy all, empty state.

## 12. Quick Access (tray window)

Opened by clicking the tray icon while the main window is hidden; closes when it loses focus or on Escape.
- Header: PerRoute + connection state; overflow menu: Switch to the fastest main server, Quit PerRoute.
- Main server card: name, Main badge, endpoint, live down/up and last check.
- Advanced routing note (N apps • N servers, edit in main window) or missing-server warning.
- Connect / Disconnect button.
- "Main server choices": servers in use first, then favorites, then the rest (radio, star, Main/Custom badge,
  protocol); choosing one switches the main server (works while connected).
- Open PerRoute button. Empty state when there are no servers.

## 13. Data the design must handle well

- Very long server names and app paths (truncate with full text in a tooltip).
- 0 to hundreds of servers; dozens of apps; 0 to ~1,500 inspector rows.
- Country flags/emoji inside server names (the current UI shows a two-letter country code instead).
- Numbers that change every second (speeds, countdowns) without the layout jumping.
- All the empty, loading, locked, error and disconnected states listed above.

## 14. Things the new UI must not do

- Show a measurement where only a prediction exists (inspector), or an unmeasured server as good.
- Offer controls that do nothing (for example bandwidth priority classes, which are not supported).
- Hide why something is disabled or not enforced.
- Change routing settings while connected (except the always-allowed list in §3).
- Use colour as the only signal.
