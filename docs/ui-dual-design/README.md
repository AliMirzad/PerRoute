# PerRoute: two interface designs (Simple and New)

PerRoute now has two complete interface designs over one shared application:

- **New** (default): charcoal, bone and lime; a narrow rail (Connect, Apps, Servers, then Tools and Settings at the bottom); a charcoal detail panel on the right of most pages. Built from the 22 approved images in `perroute-final-architecture`.
- **Simple**: light pages with a violet accent and a wide menu with all eight sections, plus its dark companion. Built from the 13 images in `perroute-simple-reference`.

Both designs work in **light and dark**. The design and the theme are separate choices.

## Choosing the design

`Settings > General > Interface style` (**Simple / New**) in both designs.

- Nothing saved means **New**, on a fresh install and after an upgrade alike. An unrecognised saved value also reads as New.
- The choice is stored in its own preference, `ui.style`, and survives restarts.
- Saving it writes only that key. The light/dark theme (`darkTheme`) and every other preference are untouched.
- Backups do not carry the design (it is a per-computer appearance choice), so restoring a backup never changes it.
- **Reset everything** clears all preferences, so the design returns to New.

## How it is built

- **One window, one `Shell`, one controller.** `PerRouteWindow` draws `NewWindow` or `SimpleWindow` depending on `Appearance.style`. Switching only redraws: it creates no new Shell or controller, makes no controller call, and does not reconnect, restart the core or change any route or setting.
- **Shared logic per screen** (`Shared*.kt`): `AppsModel`, `ServersModel` with `ImportSession`, `RulesModel`, `ProfilesModel`, `DiagnosticsModel` with `RuleTesterModel` and `InspectorModel`, `TrafficModel`, the settings models, `BackupModel` with `RestoreSession`, and `BandwidthForm`. Both designs call the same controller operations through them.
- **Screen state** lives in the Shell (`Shell.screen(...)`) for as long as the screen is open, exactly as before: leaving a screen drops it. Because it is not inside the design's composables, a design switch keeps it (open editors, typed text, filters, the Apps draft).
- **Design tokens** (`Theme.kt`): one palette per design and theme, plus the charcoal panel palette New uses in both themes. The shared components in `Design.kt` (buttons, fields, menus, dialogs, badges) read the tokens, so the same component draws in either design.
- **Unchanged:** the core, driver, proxy, quality, routing, gateway, inspector and backup logic. The controller gained only the `uiStyle()` / `setUiStyle()` preference.

## Where every feature lives

| Feature | New | Simple |
|---|---|---|
| Connect / disconnect, status, mode | Connect: charcoal column | Connection: status card |
| Live download, upload, session | Connect column | Connection stats row |
| Main server, change it | Connect column "Change", Servers, Quick Access | Connection "Change server" menu, Servers, Quick Access |
| Apps → servers at a glance | Connect: route graph (apps, Main/Custom/Auto, server, quality, health) | Connection: apps strip and Connection details |
| Independent route recovery (retry, change server, kill switch, no direct fallback, timeline) | Recovering route card on Connect | Connection details, opened automatically while a route recovers |
| Local proxy endpoints | Connect column | Connection card |
| App selection (installed/running, search, refresh, browse EXE) | Apps table | Apps card |
| Main / Manual / Auto per app, server picker, Auto pool, decision | APP CONFIGURATION panel | App dialog (from the row) |
| Advanced routing | Apps toolbar, Settings > Connection | Apps header, Settings > Connection |
| Unsaved draft, Cancel / Save changes | Apps bottom bar | Apps card footer |
| Bandwidth limits (saved immediately) and status | APP CONFIGURATION panel, Traffic panel | App dialog, Traffic row menu |
| Servers: filters, favorites, tags, search, sort, tests (ping, real tunnel, speed), hide failed | Servers table and menus | Servers card, tabs and menus |
| Quality score, confidence, age, Unknown, stale | SERVER DETAIL panel, table | Expanded row, table |
| Use as main server | Panel and row menu | Bottom bar and row menu |
| Subscriptions: update, copy, remove | Servers header and filter row | Servers header and tab row |
| Add servers (subscription, paste, QR camera/image/clipboard) with review | Full page with IMPORT SUMMARY | Dialog |
| Rules: order, enable, preset vs user, edit, delete, test a route | Tools > Rules, RULE EDITOR panel | Rules card, editor dialog |
| Presets with preview, saved profiles | Tools > Profiles, PRESET PREVIEW panel | Profiles cards, preview dialog |
| Route checks, public IP, DNS details | Tools > Diagnostics, ROUTE CHECK panel | Diagnostics card, expandable rows |
| Inspector (Configured / Observed / Verified kept apart) | CONNECTION DETAILS panel | Detail block under the table |
| Loaded rules | RUNTIME DETAIL panel | Card with reasons |
| Rule tester | PREDICTED RESULT panel | Card with result |
| Traffic history, charts, per-app bandwidth | Tools > Traffic, APP BANDWIDTH panel | Traffic page |
| Settings (8 categories) | Preferences panel on the right | Category list inside the card |
| Backup (sections, encryption) / restore (preview, path remap, Merge/Replace, Undo) | Backup page, full-page review with RESTORE PLAN | Backup page, review dialog |
| Updates, components, activity log, reset | Settings | Settings |
| Quick Access (tray) | Charcoal/bone panel | Single card |

## Behaviour that did not change

- Routing choices lock while connected. Each design states why and what can still change (bandwidth limits).
- The Apps screen is a draft until **Save changes**. Bandwidth limits save immediately. Leaving Apps with unsaved changes still asks first.
- Configured, Observed and Verified stay separate in the inspector.
- Unmeasured servers show **Unknown** with no number; nothing invents a score.
- No sample values are hard-coded. Every name, speed, date, count and version comes from the controller.

## Deviations from the images, and why

- **Flags:** servers show their country code without a flag graphic. Windows fonts cannot draw flag emoji, and no flag assets are bundled.
- **App subtitles:** the images show categories such as "Browser". PerRoute has no categories, so the executable name is shown instead of inventing one.
- **New in dark theme** has no reference image. It uses the same charcoal and lime tokens on a graphite page.
- **Interface style selector:** neither image set shows it. It sits in General in both designs, next to the light/dark setting.
- **Simple pages without an image** (app details, rule editor, preset preview, import, restore review, loaded rules, rule tester, connection/automation/updates/components settings) are dialogs or cards built from the same Simple components.
- **No "select all" checkbox** in the Simple Apps header: it would be a new action that is not in the product, and it is risky with hundreds of installed apps. No password reveal (eye) button either, for the same reason.
- **Server sort** now defaults to Quality, as both images show; the previous default was the list order.
- **Units:** New shows live speed in Mbps, as its images do; Simple Connection keeps MB/s, as its image does.
- **The logo** is the lime route mark of the New design in both designs (since 3.5): bare on New's charcoal rail and title bar, and on a charcoal tile in Simple, Quick Access and About. The window, taskbar, tray, installer and program icons are the same tile, drawn by `tools/IconRenderer.java`.
- **Small windows:** at the minimum size (820×620) the New detail panel narrows, headings put their buttons underneath, tables merge columns and "Browse for an EXE" keeps only its icon, so Save changes always fits.
- **Credit:** the footer reads "Created By Vexor", as requested. The copyright line in README.md is unchanged.
- **Window:** the default size is 1180×790 (the designs' 3:2 frame), centred and kept inside the screen. The minimum size is unchanged.

## Tests

Command: `gradlew runAllTests` (the real-core integration test included).

- Before the redesign: **all tests passed, 1052 assertions**.
- After: **all tests passed, 1086 assertions**. No test was removed or weakened.
- New tests:
  - **The interface style is its own preference:** New when unset; the saved choice survives a controller restart; saving it changes no other preference; it is independent of the theme; an unknown value reads as New; backup and restore leave it unchanged; Reset returns to New.
  - **Switching the design keeps the connection, routes and drafts:** renders the real window off-screen while connected and switches Simple and New several times. It checks: one Shell; still connected; the core is never started or stopped again; the same config and the same active routes; the Apps draft is kept and not saved; the open backup form keeps its typed password; no other preference changes.
- Off-screen renders of every page in both designs (`gradlew renderUiSnapshots`) are in `screenshots/`: 102 images covering connected, disconnected, empty, missing-server, locked, focused, dialog, dark, minimum-size (820×620), 150% scale and Quick Access states.

## Not verified

- Not run in a real desktop window by hand: dragging, maximizing, the tray icon and HiDPI on real monitors were not exercised. Layouts were checked in off-screen renders only.
- Actions that need the network or the machine were not exercised through the new screens: fetching subscriptions, the QR camera, file dialogs, downloading or installing updates, installing the core. They call the same controller functions as before.
- Keyboard traversal was rendered in one scene per design. Screen readers were not tested.
- The built installer was not installed or run.

## Other issues found

- After a backup was restored, the theme it carries was only applied to the open window after a restart. The window now reloads the appearance after a restore or Undo. This is a presentation-only change.
- With the traffic gateway on, the snapshot renderer's fake machine produces no inspector connections. That is a limitation of the test fixture, not of the product; the inspector scene uses the log-based path.
