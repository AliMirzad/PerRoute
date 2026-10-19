# Extension ↔ runtime protocol (v4)

Transport: Chromium native messaging. Each message is a 32-bit length in native byte order
followed by UTF-8 JSON. The extension's service worker holds one long-lived
`chrome.runtime.connectNative("com.perroute.browser")` port per browser profile.

Implementations: [`helper/src/browser/protocol.rs`](../helper/src/browser/protocol.rs) (authoritative),
[`types.ts`](types.ts) (TypeScript mirror).

## Envelope

```jsonc
// request (extension -> helper)
{ "id": 7, "cmd": "connect", "args": { "serverId": "3f0c…" } }
// response (helper -> extension), exactly one per request id
{ "id": 7, "ok": true,  "result": { … } }
{ "id": 7, "ok": false, "error": { "code": "NOT_FOUND", "message": "Server not found" } }
// events (helper -> extension, unsolicited)
{ "event": "status", "status": { … } }
{ "event": "progress", "progress": { "id": 7, "value": { "serverId": "…", "ok": true, "ms": 120 } } }
{ "event": "notice", "notice": { "kind": "failover", "message": "…" } }
{ "event": "changed", "changed": {} }
{ "event": "protocolError", "error": { … } }   // a message without a usable id
```

Rules enforced by the helper:

* `id` is a positive u32. Top-level keys other than `id`, `cmd` and `args` are rejected.
* `cmd` must be one of the commands below. Unknown commands get `INVALID_REQUEST`.
* Every `args` object is strictly typed (`deny_unknown_fields`). IDs must be UUIDs.
* Messages over 8 MiB close the connection. Import text is capped at 5 MiB.
* No command executes a caller-chosen program, reads or writes a caller-chosen path, or contacts a
  caller-chosen host directly. `addSubscription` fetches a validated HTTPS URL with strict limits;
  `compare` opens validated public `https://` sites, only through the tunnel or a test instance.
* Chromium accepts at most 1 MiB per message from the helper. A large backup is returned in parts
  (`exportBackup` answers with `token` and `parts`; read them with `readExport`).

## Commands

### Connection and servers

| cmd | args | result |
|---|---|---|
| `hello` | `{protocolVersion, extensionVersion}` | `{nativeVersion, protocolVersion, xrayVersion, xrayAvailable, platform, keyStorage}`. `INCOMPATIBLE_VERSION` if the versions differ |
| `getStatus` | `{}` | Status (below) |
| `connect` | `{serverId}` | Status (immediately; progress arrives as `status` events) |
| `disconnect` | `{}` | Status. Idempotent |
| `connectFastest` | `{}` | Tests the best candidates in the background, connects to the fastest: `{serverId, ms}` |
| `listServers` | `{}` | `{servers, subscriptions, selectedServerId, favorites, tags, scores, canUndoRestore}`. **Never contains credentials** |
| `importText` | `{text, source: "paste"\|"qr"\|"file"}` | `{added, updated, serverIds, errors[], rejected, unsupported, warnings[]}` |
| `addSubscription` | `{name, url}` | `{subscriptionId, added, updated, removed, rejected, unsupported}` (answered after the download) |
| `updateSubscription` | `{id}` | same as above |
| `deleteSubscription` | `{id, deleteServers}` | `{}` |
| `renameServer` / `deleteServer` / `selectServer` | `{id[, name]}` | `{}` |
| `setFavorite` | `{id, favorite}` | `{}` |
| `setTag` | `{id, tag}` | `{}` (an empty tag removes it) |
| `getQuality` | `{id}` | Quality score with its parts and explanation |
| `testServers` | `{ids, kind: "tcp"\|"real"}` | `{results: [{serverId, ok, ms?, error?}]}`; each result is also sent as a `progress` event |
| `speedTest` | `{serverId?}` | `{bytesPerSecond, live}`; without `serverId` the live tunnel is measured |
| `exitInfo` | `{}` | `{ip, country, colo, latencyMs}` of the live tunnel |
| `compare` | `{candidateId, urls}` | `{live: [...], candidate: [...]}`: the same sites through the live route and through a test instance |
| `doctor` | `{host?}` | `{checks: [{id, status, title, detail, repair}]}` |
| `getTraffic` / `clearTraffic` | `{}` | Rates, session, today, month and the last 60 days (totals only) |

### Site rules, presets and profiles

| cmd | args | result |
|---|---|---|
| `getRules` | `{}` | `{rules, mode, localNamesDirect, activeScenario, revision}` |
| `addRule` | Rule input | The new rule |
| `updateRule` | `{id, rule: Rule input}` | The changed rule |
| `deleteRule` / `moveRule` / `setRuleEnabled` | `{id[, offset \| enabled]}` | `{}` |
| `setSite` | `{host, action: "proxy"\|"direct"\|"block"\|null, serverId?, minutes?}` | The site's own rule (`null` removes it) |
| `evaluate` | `{host}` | `{host, outcome, reason, server, rule, siteRule, connected}` |
| `replaceRules` | `{rules: [Rule input], mode?, append}` | `{count}` (recipes) |
| `listScenarios` / `applyScenario` / `clearScenario` | `{}` / `{id}` / `{}` | Presets: settings plus rules above the user's own |
| `listProfiles` / `saveProfile` / `applyProfile` / `deleteProfile` | `{}` / `{name}` / `{id}` / `{id}` | Saved server, routing mode and rules |

Rule input: `{name?, match: "domain"|"ip"|"port", text, action: "proxy"|"direct"|"block", serverId?, minutes?, enabled?}`.
`text` is normalized by the helper (URLs, `*.example.com`, `full:`, `domain:`, `keyword:`, `regexp:`;
CIDR ranges; ports and port ranges). Rules are checked in order and the first match wins. Every
rule change bumps `revision`; every running helper applies it to its live tunnel.

### Settings and backup

| cmd | args | result |
|---|---|---|
| `getSettings` | `{}` | Settings |
| `setSettings` | partial Settings | Settings + `reconnectRequired` |
| `getDiagnostics` | `{}` | Versions, paths, `xrayPid`, `statsPort`, key storage; recent Xray output only in debug mode |
| `getIdeCredentials` / `regenerateIdeCredentials` | `{}` | `{username, password, required, reconnectRequired}` of the local proxy for other apps |
| `exportBackup` | `{password?, servers, rules, settings}` | `{text, encrypted, fileName}` or `{token, parts, …}` |
| `readExport` | `{token, index}` | `{part}` |
| `previewBackup` | `{text, password?}` | `{encrypted, createdAt, servers, subscriptions, rules, profiles, hasSettings}` |
| `restoreBackup` | `{text, password?, mode: "merge"\|"replace"}` | `{serversAdded, serversUpdated, serversRejected, subscriptions, rules, profiles, settings, warnings}` |
| `undoRestore` | `{}` | `{}`; puts back the data from before the last restore |
| `resetAll` | `{confirm: true}` | `{}`. Deletes all servers, subscriptions, secrets, rules, history and the data key |

## Status

```jsonc
{
  "state": "disconnected" | "connecting" | "connected" | "disconnecting" | "error",
  "phase": "starting" | "verifying" | "restarting",      // connecting only
  "serverId": "…",
  "proxy": { "scheme": "http", "host": "127.0.0.1", "port": 53123, "username": "b…", "password": "…" },  // connected only
  "error": { "code": "SERVER_UNREACHABLE", "message": "…" },            // error only
  "jetbrains": { "enabled": true, "mode": "tunnel"|"direct"|"off", "socksPort": 10808, "httpPort": 10809, "issue": null, "authRequired": true },
  "xrayAvailable": true
}
```

The browser proxy is applied **only** in `connected` with a `proxy` port. It is kept during
`connecting/restarting` (same port and credentials). In every other state it is cleared, or,
with the kill switch on, pointed at a closed local port until the user disconnects.

`proxy.username`/`proxy.password` are random per connection (memory only). The service worker
answers the inbound's 407 challenge with them in `webRequest.onAuthRequired`, but only for challenger
`127.0.0.1:<port>` while its proxy setting is in effect. It strips them before the status reaches any
extension page.

## Error codes

`INVALID_REQUEST`, `INCOMPATIBLE_VERSION`, `NOT_FOUND`, `INVALID_CONFIG`, `XRAY_MISSING`,
`XRAY_FAILED`, `XRAY_CONFIG_REJECTED`, `PORT_UNAVAILABLE`, `SERVER_UNREACHABLE`,
`SUBSCRIPTION_FAILED`, `STORE_ERROR`, `SECURE_STORAGE_UNAVAILABLE`, `BUSY`, `INTERNAL`.

Messages are short and meant for the user. Technical detail goes to the helper log.

## Versioning

`PROTOCOL_VERSION` (currently 4) changes on any incompatible change. The extension sends its
version in `hello`. On a mismatch the helper answers `INCOMPATIBLE_VERSION`, naming the side to
update, and the extension shows **Update required**.
