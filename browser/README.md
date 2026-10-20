# PerRoute for browsers

PerRoute in Chrome, Edge, Brave, Vivaldi and Chromium. Where the desktop app routes Windows
applications, the extension routes **sites**: each site uses the tunnel, opens directly or is
blocked, and can use a server of its own. It works without the desktop app.

```
Extension (MV3) ──native messaging──▶ perroute-browser-host ──stdin config──▶ Xray-core
  popup, dashboard,                    servers, rules, tests,                  VLESS, VMess, Trojan,
  service worker                       quality, backups, traffic               Shadowsocks
     │
     └── chrome.proxy ──▶ http://127.0.0.1:<random port> (per-connection password) ──▶ Xray routing ──▶ tunnel / direct / block
```

* **Extension** (`extension/`): the only user interface. The popup connects, switches servers,
  reads a server QR code on the current page and decides where the current site goes (Tunnel /
  Direct / Block, optionally for a while). The page's right-click menu and keyboard shortcuts
  (connect, switch the current site, fastest server) do the same without opening anything. The
  dashboard has everything else: servers and subscriptions (usage, expiry and change warnings,
  tags, score breakdowns, camera QR), trying a server for a while, comparing a server with the
  live route, rules with a site tester and a warning for rules that never apply, presets with a
  preview, recipes with Undo, profiles, traffic, the connection doctor and Safety Center, the
  request inspector and settings with backups.
* **Runtime** (`helper/`, Rust): started by the browser, no window. It validates imports, stores
  servers encrypted (key in Windows Credential Manager), generates the Xray configuration, runs and
  supervises Xray, applies rule changes to the live tunnel, tests servers in separate Xray instances,
  scores them, fails over to a working server and counts traffic.
* **Xray-core**: the official release, pinned by SHA-256 and checked before every start.

## Layout

| Path | Contents |
|---|---|
| `helper/` | The runtime (`perroute-browser-host`). `src/core` is the logic, `src/browser` the native messaging adapter, `src/runtime` Xray, `src/platform` OS security. |
| `helper/xray/xray.lock.json` | Pinned Xray release and hashes (`dist/` is downloaded, not committed). |
| `extension/` | The MV3 extension: `src/background` (service worker), `src/popup`, `src/dashboard`, `src/shared`. |
| `protocol/` | The message protocol (`PROTOCOL.md`, `types.ts`) and the pinned extension ID. |
| `scripts/` | Build helpers: Rust toolchain selection, Xray download and verification, license notices, extension key. |
| `licenses/` | Notices for everything shipped with the runtime and the extension. |

## Build

Requirements: Node.js 20+, Rust stable, and either Visual Studio Build Tools (C++) or
[llvm-mingw](https://github.com/mstorsjo/llvm-mingw) (set `LLVM_MINGW`, or unpack it into
`%USERPROFILE%\.perroute-tools`).

```bash
npm run setup          # extension dependencies + pinned Xray
npm run build:release  # helper (release) + extension (minified)
npm test               # helper unit and integration tests, extension type check and tests
```

`package.ps1` in the repository root builds everything and embeds the runtime in
`PerRoute-Setup-<version>.exe`, where it is one of the two components the user can choose.

## Trying a development build

1. `npm run build` (debug helper, extension with source maps in `extension/dist`).
2. Register the debug helper for your Windows user:
   `helper\target\debug\perroute-browser-host.exe install --source helper\xray\dist\windows-x64`
   (copies it with Xray to `%LOCALAPPDATA%\Programs\PerRoute Browser` and registers it for every
   Chromium browser under HKCU; `uninstall` removes it again).
3. Open `chrome://extensions`, turn on Developer mode, **Load unpacked**, choose `extension/dist`.

The extension ID is fixed by the key in `extension/manifest.key.json`
(`protocol/extension-id.txt`), so the runtime only ever talks to this extension. The private key
is kept outside the repository (`release-secrets/`).

## What the installer does

With the **Browser extension** component the installer:

* puts the runtime, the pinned Xray and the unpacked extension in `C:\Program Files\PerRoute Browser`
  (only administrators can change it);
* writes the native messaging manifest there, allowing only the PerRoute extension ID;
* registers it for Chrome, Chromium, Edge, Brave and Vivaldi for all users (HKLM, both registry views);
* shows how to add the extension (Developer mode, **Load unpacked**, the folder above).

Chromium only installs extensions from a web store without user action, so this last step is
manual until the extension is published in a store. Updates replace the files in the same folder;
the browser picks them up when it restarts.

## Security

* The runtime accepts a closed set of strictly typed commands from one extension ID; nothing runs
  a program, touches a path or contacts a host chosen by the caller (subscriptions and the site
  comparison go through URL policies).
* Imported links, JSON, subscriptions, backups and recipes are data: they pass field allow-lists and
  validators, and the Xray configuration is always generated from the validated model.
* The browser proxy listens on 127.0.0.1 with a random password per connection; the extension
  answers only that challenge. The local proxy for other apps requires a password by default.
* Xray runs with a restricted token in a job object, after its hash was verified. The traffic
  counters use Xray's read-only StatsService on a loopback port that the proxy itself blocks.
* Credentials are encrypted at rest; the data folder is private to the Windows user. Backups can
  be password-protected (PBKDF2-SHA256 + XChaCha20-Poly1305). Traffic history keeps totals only.
* The kill switch keeps tunnel sites offline while the tunnel is down; WebRTC protection stops
  WebRTC from revealing the real address. The extension cannot contact any origin (CSP).
