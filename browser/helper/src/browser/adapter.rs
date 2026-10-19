//! Native-messaging adapter: the browser extension's view of the Core.
//!
//! Receives already decoded, allowlisted commands (`protocol::decode`: closed command set,
//! `deny_unknown_fields`), calls the Core API, and turns Core types into protocol-v3 JSON
//! (`protocol/PROTOCOL.md`). It holds no connection logic of its own.
//!
//! Credential exposure: the per-connection browser proxy credentials appear only in the
//! `proxy` object of a `connected` status (the extension needs them to answer the local proxy's
//! 407 challenge). IDE credentials appear only in the `getIdeCredentials` /
//! `regenerateIdeCredentials` responses. No other response or event contains either.

use crate::browser::protocol::*;
use crate::core::api::{Core, CoreEvent, CoreMsg, IdeEndpointStatus, ImportKind, Poster, RuleInput, SessionStatus, SettingsUpdate, Timing, SCENARIOS};
use crate::core::error::{CoreError, ErrorKind};
use crate::core::session::{LocalProxyEndpoint, SessionState, StartPhase};
use crate::core::store::Store;
use crate::log;
use serde_json::{json, Value};
use std::path::PathBuf;
use std::sync::mpsc::{Receiver, Sender};
use std::sync::Arc;

pub enum Msg {
    Request(u32, Result<Request, ApiError>),
    Core(CoreMsg),
    Shutdown,
}

/// Maps a Core error onto the protocol's error codes (unchanged wire values).
pub fn api_error(e: CoreError) -> ApiError {
    let code = match e.kind {
        ErrorKind::InvalidRequest => ErrorCode::InvalidRequest,
        ErrorKind::NotFound => ErrorCode::NotFound,
        ErrorKind::InvalidProfile | ErrorKind::SecurityPolicyViolation => ErrorCode::InvalidConfig,
        ErrorKind::SubscriptionFailure => ErrorCode::SubscriptionFailed,
        ErrorKind::RuntimeUnavailable => ErrorCode::XrayMissing,
        ErrorKind::RuntimeIntegrityFailure | ErrorKind::RuntimeIsolationFailure | ErrorKind::RuntimeFailure => ErrorCode::XrayFailed,
        ErrorKind::ConfigRejected => ErrorCode::XrayConfigRejected,
        ErrorKind::PortUnavailable => ErrorCode::PortUnavailable,
        ErrorKind::ConnectionFailure => ErrorCode::ServerUnreachable,
        ErrorKind::SecureStorageUnavailable => ErrorCode::SecureStorageUnavailable,
        ErrorKind::Storage => ErrorCode::StoreError,
    };
    ApiError::new(code, e.message)
}

fn phase_name(p: StartPhase) -> &'static str {
    match p {
        StartPhase::Launching => "starting",
        StartPhase::Verifying => "verifying",
        StartPhase::Restarting => "restarting",
    }
}

fn ide_json(ide: &IdeEndpointStatus) -> Value {
    serde_json::to_value(ide).unwrap_or(Value::Null)
}

/// The protocol's status object. `proxy` (with credentials) is present only while connected.
pub fn status_json(status: &SessionStatus, proxy: Option<&LocalProxyEndpoint>) -> Value {
    let mut v = match &status.state {
        SessionState::Disconnected => json!({ "state": "disconnected" }),
        SessionState::Starting { profile_id, phase } => json!({ "state": "connecting", "serverId": profile_id, "phase": phase_name(*phase) }),
        SessionState::Connected { profile_id, port, since } => json!({ "state": "connected", "serverId": profile_id, "port": port, "since": since }),
        SessionState::Stopping => json!({ "state": "disconnecting" }),
        SessionState::Failed { error, profile_id } => {
            let e = api_error(error.clone());
            json!({ "state": "error", "code": e.code, "message": e.message, "serverId": profile_id, "error": { "code": e.code, "message": e.message } })
        }
    };
    if let (SessionState::Connected { .. }, Some(p)) = (&status.state, proxy) {
        // The extension answers the proxy's 407 challenge with these; it never shows or stores them.
        v["proxy"] = json!({ "scheme": "http", "host": p.host, "port": p.port, "username": p.credentials.user, "password": p.credentials.pass });
    }
    v["jetbrains"] = ide_json(&status.ide);
    v["xrayAvailable"] = json!(status.runtime_available);
    v
}

fn settings_update(p: SettingsPatch) -> SettingsUpdate {
    SettingsUpdate {
        jetbrains_enabled: p.jetbrains_enabled,
        jetbrains_socks_port: p.jetbrains_socks_port,
        jetbrains_http_port: p.jetbrains_http_port,
        passthrough_when_disconnected: p.passthrough_when_disconnected,
        debug_logging: p.debug_logging,
        allow_private_subscription_hosts: p.allow_private_subscription_hosts,
        ide_auth: p.ide_auth,
        routing_mode: p.routing_mode,
        local_names_direct: p.local_names_direct,
        auto_failover: p.auto_failover,
        fastest_on_connect: p.fastest_on_connect,
        traffic_stats: p.traffic_stats,
        kill_switch: p.kill_switch,
        webrtc_protection: p.webrtc_protection,
        auto_connect: p.auto_connect,
        subscription_update_hours: p.subscription_update_hours,
    }
}

fn rule_input(a: RuleArgs) -> RuleInput {
    RuleInput { name: a.name, match_type: a.match_type, text: a.text, action: a.action, server_id: a.server_id, minutes: a.minutes, enabled: a.enabled }
}

/// Chromium accepts at most 1 MiB per message from a native host; larger exports are sent in parts.
const CHUNK: usize = 512 * 1024;

pub struct Adapter {
    core: Core,
    out: Sender<Vec<u8>>,
    /// The last export, split into parts the client reads with `readExport`.
    export: Option<(u32, Vec<String>)>,
}

impl Adapter {
    /// `tx` is the adapter's own message channel; the Core's worker results come back through it.
    pub fn new(store: Store, xray_path: Option<PathBuf>, out: Sender<Vec<u8>>, tx: Sender<Msg>, timing: Timing) -> Adapter {
        let post: Poster = Arc::new(move |m| {
            let _ = tx.send(Msg::Core(m));
        });
        Adapter { core: Core::new(store, xray_path, post, timing), out, export: None }
    }

    /// Runs until stdin closes. Always stops Xray before returning.
    pub fn run_loop(mut self, rx: Receiver<Msg>) {
        self.core.start();
        self.flush_events();
        while let Ok(msg) = rx.recv() {
            match msg {
                Msg::Request(id, req) => {
                    let r = match req {
                        Ok(r) => self.handle(r, id),
                        Err(e) => Some(Err(e)),
                    };
                    // Status events caused by the request go out before its response, as before.
                    self.flush_events();
                    if let Some(r) = r {
                        self.send(encode_response(id, &r));
                    }
                }
                Msg::Core(m) => self.core.handle(m),
                Msg::Shutdown => break,
            }
            self.flush_events();
        }
        self.core.shutdown();
        log::info("helper exiting");
    }

    fn send(&self, bytes: Vec<u8>) {
        let _ = self.out.send(bytes);
    }

    fn flush_events(&mut self) {
        for ev in self.core.take_events() {
            match ev {
                CoreEvent::StatusChanged { status, browser_proxy } => {
                    self.send(encode_event("status", "status", &status_json(&status, browser_proxy.as_ref())));
                }
                CoreEvent::SubscriptionDone { ticket, result } => {
                    let r = result
                        .map(|r| {
                            json!({
                                "subscriptionId": r.subscription_id, "added": r.added, "updated": r.updated,
                                "removed": r.removed, "rejected": r.rejected, "unsupported": r.unsupported,
                            })
                        })
                        .map_err(api_error);
                    self.send(encode_response(ticket as u32, &r));
                }
                CoreEvent::JobDone { ticket, result } => self.send(encode_response(ticket as u32, &result.map_err(api_error))),
                CoreEvent::JobProgress { ticket, value } => {
                    self.send(encode_event("progress", "progress", &json!({ "id": ticket, "value": value })));
                }
                CoreEvent::Notice { kind, message } => self.send(encode_event("notice", "notice", &json!({ "kind": kind, "message": message }))),
                CoreEvent::DataChanged => self.send(encode_event("changed", "changed", &json!({}))),
            }
        }
    }

    fn current_status(&self) -> Value {
        status_json(&self.core.session_status(), self.core.browser_proxy_endpoint().as_ref())
    }

    /// `None` means the response is sent later (asynchronous subscription work).
    fn handle(&mut self, req: Request, id: u32) -> Option<ApiResult> {
        let ok = |_: ()| json!({});
        Some(match req {
            Request::Hello(a) => self.hello(a),
            Request::GetStatus(_) => Ok(self.current_status()),
            Request::ListServers(_) => self.list_servers(),
            Request::ImportText(a) => self.import_text(a),
            Request::AddSubscription(a) => {
                return self.core.add_subscription(&a.name, &a.url, id as u64).err().map(|e| Err(api_error(e)));
            }
            Request::UpdateSubscription(a) => {
                return self.core.refresh_subscription(&a.id, id as u64).err().map(|e| Err(api_error(e)));
            }
            Request::DeleteSubscription(a) => self.core.remove_subscription(&a.id, a.delete_servers).map(ok).map_err(api_error),
            Request::RenameServer(a) => self.core.rename_profile(&a.id, &a.name).map(ok).map_err(api_error),
            Request::DeleteServer(a) => self.core.remove_profile(&a.id).map(ok).map_err(api_error),
            Request::SelectServer(a) => self.core.select_profile(&a.id).map(ok).map_err(api_error),
            Request::Connect(a) => self.core.start_session(&a.server_id).map(|_| self.current_status()).map_err(api_error),
            Request::Disconnect(_) => {
                self.core.stop_session();
                Ok(self.current_status())
            }
            Request::GetIdeCredentials(_) => self.ide_credentials(false),
            Request::RegenerateIdeCredentials(_) => self.ide_credentials(true),
            Request::GetSettings(_) => Ok(serde_json::to_value(self.core.settings()).unwrap_or(Value::Null)),
            Request::SetSettings(p) => self
                .core
                .update_settings(settings_update(p))
                .map(|(s, reconnect)| {
                    let mut v = serde_json::to_value(s).unwrap_or(Value::Null);
                    v["reconnectRequired"] = json!(reconnect);
                    v
                })
                .map_err(api_error),
            Request::GetDiagnostics(_) => {
                let mut v = serde_json::to_value(self.core.diagnostics()).unwrap_or(Value::Null);
                v["protocolVersion"] = json!(crate::PROTOCOL_VERSION);
                Ok(v)
            }
            Request::ResetAll(a) => {
                if !a.confirm {
                    return Some(Err(ApiError::new(ErrorCode::InvalidRequest, "Confirmation required")));
                }
                self.core.reset_all().map(ok).map_err(api_error)
            }
            Request::SetFavorite(a) => self.core.set_favorite(&a.id, a.favorite).map(ok).map_err(api_error),
            Request::SetTag(a) => self.core.set_tag(&a.id, &a.tag).map(ok).map_err(api_error),
            Request::GetQuality(a) => self.core.quality_of(&a.id).map_err(api_error),
            // Background work: the response is sent when it finishes.
            Request::TestServers(a) => return self.core.test_servers(&a.ids, a.kind == TestKind::Real, id as u64).err().map(|e| Err(api_error(e))),
            Request::SpeedTest(a) => return self.core.speed_test(a.server_id.as_deref(), id as u64).err().map(|e| Err(api_error(e))),
            Request::ConnectFastest(_) => return self.core.connect_fastest(id as u64).err().map(|e| Err(api_error(e))),
            Request::ExitInfo(_) => return self.core.exit_info(id as u64).err().map(|e| Err(api_error(e))),
            Request::Compare(a) => return self.core.compare(&a.candidate_id, &a.urls, id as u64).err().map(|e| Err(api_error(e))),
            Request::Doctor(a) => return self.core.doctor(a.host.as_deref(), id as u64).err().map(|e| Err(api_error(e))),
            Request::GetTraffic(_) => Ok(self.core.traffic()),
            Request::ClearTraffic(_) => {
                self.core.clear_traffic();
                Ok(json!({}))
            }
            Request::GetRules(_) => self.core.rules().map_err(api_error),
            Request::AddRule(a) => self.core.add_rule(rule_input(a)).map_err(api_error),
            Request::UpdateRule(a) => self.core.update_rule(&a.id, rule_input(a.rule)).map_err(api_error),
            Request::DeleteRule(a) => self.core.delete_rule(&a.id).map(ok).map_err(api_error),
            Request::MoveRule(a) => self.core.move_rule(&a.id, a.offset).map(ok).map_err(api_error),
            Request::SetRuleEnabled(a) => self.core.set_rule_enabled(&a.id, a.enabled).map(ok).map_err(api_error),
            Request::SetSite(a) => self.core.set_site(&a.host, a.action, a.server_id, a.minutes).map_err(api_error),
            Request::Evaluate(a) => self.core.evaluate(&a.host).map_err(api_error),
            Request::ReplaceRules(a) => {
                let rules = a.rules.into_iter().map(rule_input).collect();
                self.core.replace_rules(rules, a.mode, a.append).map(|n| json!({ "count": n })).map_err(api_error)
            }
            Request::ListScenarios(_) => Ok(json!(SCENARIOS
                .iter()
                .map(|s| json!({
                    "id": s.id, "title": s.title, "summary": s.summary, "highlights": s.highlights,
                    "killSwitch": s.kill_switch, "autoFailover": s.auto_failover, "fastestOnConnect": s.fastest_on_connect,
                    "rules": s.rules.iter().map(|(n, m, t, a)| json!({ "name": n, "match": m, "text": t, "action": a })).collect::<Vec<_>>(),
                }))
                .collect::<Vec<_>>())),
            Request::ApplyScenario(a) => self.core.apply_scenario(&a.id).map(ok).map_err(api_error),
            Request::ClearScenario(_) => self.core.clear_scenario().map(ok).map_err(api_error),
            Request::ListProfiles(_) => self.core.profiles().map_err(api_error),
            Request::SaveProfile(a) => self.core.save_profile(&a.name).map_err(api_error),
            Request::ApplyProfile(a) => self.core.apply_profile(&a.id).map(ok).map_err(api_error),
            Request::DeleteProfile(a) => self.core.delete_profile(&a.id).map(ok).map_err(api_error),
            Request::ExportBackup(a) => self.export_backup(a, id),
            Request::ReadExport(a) => match &self.export {
                Some((token, parts)) if *token == a.token && (a.index as usize) < parts.len() => Ok(json!({ "part": parts[a.index as usize] })),
                _ => Err(ApiError::new(ErrorCode::NotFound, "The export is no longer available; export again")),
            },
            Request::PreviewBackup(a) => self.core.preview_backup(&a.text, a.password.as_deref()).map_err(api_error),
            Request::RestoreBackup(a) => self.core.restore_backup(&a.text, a.password.as_deref(), a.mode).map_err(api_error),
            Request::UndoRestore(_) => self.core.undo_restore().map(ok).map_err(api_error),
        })
    }

    /// Backups larger than one message are kept here and read in parts with `readExport`.
    fn export_backup(&mut self, a: ExportArgs, token: u32) -> ApiResult {
        let sections = crate::core::backup::Sections { servers: a.servers, rules: a.rules, settings: a.settings };
        let mut v = self.core.export_backup(a.password.as_deref(), sections).map_err(api_error)?;
        let text = v["text"].as_str().unwrap_or_default().to_string();
        if text.len() <= CHUNK {
            self.export = None;
            return Ok(v);
        }
        let mut parts = Vec::new();
        let mut rest = text.as_str();
        while !rest.is_empty() {
            let mut cut = rest.len().min(CHUNK);
            while !rest.is_char_boundary(cut) {
                cut -= 1;
            }
            parts.push(rest[..cut].to_string());
            rest = &rest[cut..];
        }
        v["text"] = Value::Null;
        v["token"] = json!(token);
        v["parts"] = json!(parts.len());
        self.export = Some((token, parts));
        Ok(v)
    }

    fn hello(&mut self, a: HelloArgs) -> ApiResult {
        log::info(format!("hello from extension {} (protocol {})", crate::core::validate::truncate(&a.extension_version, 20), a.protocol_version));
        let mut info = serde_json::to_value(self.core.info()).unwrap_or(Value::Null);
        info["protocolVersion"] = json!(crate::PROTOCOL_VERSION);
        if a.protocol_version != crate::PROTOCOL_VERSION {
            return Err(ApiError::new(
                ErrorCode::IncompatibleVersion,
                format!(
                    "The native runtime ({}) uses protocol v{} but the extension uses v{}. Update {}.",
                    crate::NATIVE_VERSION,
                    crate::PROTOCOL_VERSION,
                    a.protocol_version,
                    if a.protocol_version > crate::PROTOCOL_VERSION { "the native runtime" } else { "the extension" }
                ),
            ));
        }
        Ok(info)
    }

    fn list_servers(&self) -> ApiResult {
        let l = self.core.list_profiles().map_err(api_error)?;
        let subs: Vec<Value> = l
            .subscriptions
            .iter()
            .map(|s| json!({ "id": s.id, "name": s.name, "host": s.host, "lastUpdated": s.last_updated, "lastError": s.last_error, "serverCount": s.profile_count }))
            .collect();
        let extras = self.core.server_extras().map_err(api_error)?;
        let details = self.core.subscription_details().map_err(api_error)?;
        let subs: Vec<Value> = subs
            .into_iter()
            .map(|mut s| {
                if let Some(d) = s["id"].as_str().and_then(|id| details.get(id)) {
                    s["usage"] = d["usage"].clone();
                    s["updateIntervalHours"] = d["updateIntervalHours"].clone();
                    s["history"] = d["history"].clone();
                }
                s
            })
            .collect();
        Ok(json!({
            "servers": l.profiles, "subscriptions": subs, "selectedServerId": l.selected_profile_id,
            "favorites": extras["favorites"], "tags": extras["tags"], "scores": extras["scores"],
            "canUndoRestore": self.core.can_undo_restore(),
        }))
    }

    fn import_text(&mut self, a: ImportArgs) -> ApiResult {
        let kind = if a.source == ImportSource::Qr { ImportKind::Qr } else { ImportKind::Text };
        let r = self.core.import_profiles(&a.text, kind).map_err(api_error)?;
        Ok(json!({
            "added": r.added, "updated": r.updated, "serverIds": r.profile_ids,
            "errors": r.errors, "rejected": r.rejected, "unsupported": r.unsupported, "warnings": r.warnings,
        }))
    }

    /// Credentials for the IDE endpoint, shown in the popup so the user can enter them in the IDE.
    fn ide_credentials(&mut self, regenerate: bool) -> ApiResult {
        let c = self.core.ide_credentials(regenerate).map_err(api_error)?;
        Ok(json!({ "username": c.username, "password": c.password, "required": c.required, "reconnectRequired": c.reconnect_required }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::credentials::ProxyCredentials;

    fn status(state: SessionState) -> SessionStatus {
        SessionStatus { state, ide: IdeEndpointStatus { mode: "off", ..Default::default() }, runtime_available: true }
    }

    #[test]
    fn every_core_error_kind_maps_to_a_protocol_code() {
        use ErrorKind::*;
        let cases = [
            (InvalidRequest, "\"INVALID_REQUEST\""),
            (NotFound, "\"NOT_FOUND\""),
            (InvalidProfile, "\"INVALID_CONFIG\""),
            (SecurityPolicyViolation, "\"INVALID_CONFIG\""),
            (SubscriptionFailure, "\"SUBSCRIPTION_FAILED\""),
            (RuntimeUnavailable, "\"XRAY_MISSING\""),
            (RuntimeIntegrityFailure, "\"XRAY_FAILED\""),
            (RuntimeIsolationFailure, "\"XRAY_FAILED\""),
            (RuntimeFailure, "\"XRAY_FAILED\""),
            (ConfigRejected, "\"XRAY_CONFIG_REJECTED\""),
            (PortUnavailable, "\"PORT_UNAVAILABLE\""),
            (ConnectionFailure, "\"SERVER_UNREACHABLE\""),
            (SecureStorageUnavailable, "\"SECURE_STORAGE_UNAVAILABLE\""),
            (Storage, "\"STORE_ERROR\""),
        ];
        for (k, wire) in cases {
            let e = api_error(CoreError::new(k, "m"));
            assert_eq!(serde_json::to_string(&e.code).unwrap(), wire, "{k:?}");
            assert_eq!(e.message, "m");
        }
    }

    #[test]
    fn status_json_is_protocol_v3_and_carries_credentials_only_when_connected() {
        let creds = ProxyCredentials::random_for_browser();
        let ep = LocalProxyEndpoint { host: "127.0.0.1", port: 5555, credentials: creds.clone() };
        let connected = status_json(&status(SessionState::Connected { profile_id: "p".into(), port: 5555, since: 7 }), Some(&ep));
        assert_eq!(connected["state"], "connected");
        assert_eq!(connected["serverId"], "p");
        assert_eq!(connected["proxy"], json!({ "scheme": "http", "host": "127.0.0.1", "port": 5555, "username": creds.user, "password": creds.pass }));
        assert_eq!(connected["xrayAvailable"], true);
        assert_eq!(connected["jetbrains"]["mode"], "off");

        // Not connected: never credentials, even if an endpoint were passed by mistake.
        for s in [
            SessionState::Disconnected,
            SessionState::Starting { profile_id: "p".into(), phase: StartPhase::Verifying },
            SessionState::Stopping,
            SessionState::Failed { error: CoreError::new(ErrorKind::ConnectionFailure, "Could not connect through this server."), profile_id: Some("p".into()) },
        ] {
            let v = status_json(&status(s), Some(&ep));
            assert!(v.get("proxy").is_none(), "{v}");
            assert!(!v.to_string().contains(&creds.pass), "{v}");
        }
        let starting = status_json(&status(SessionState::Starting { profile_id: "p".into(), phase: StartPhase::Restarting }), None);
        assert_eq!((starting["state"].as_str(), starting["phase"].as_str()), (Some("connecting"), Some("restarting")));
        let failed = status_json(
            &status(SessionState::Failed { error: CoreError::new(ErrorKind::RuntimeIsolationFailure, "Runtime security check failed: x. The connection was not started."), profile_id: None }),
            None,
        );
        assert_eq!(failed["state"], "error");
        assert_eq!(failed["code"], "XRAY_FAILED");
        assert_eq!(failed["error"]["code"], "XRAY_FAILED");
        assert!(failed["serverId"].is_null());
        assert_eq!(status_json(&status(SessionState::Stopping), None)["state"], "disconnecting");
    }
}
