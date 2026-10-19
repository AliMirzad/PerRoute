//! What the browser client offers beyond connect/disconnect: site rules, scenarios, profiles,
//! server tests and quality scores, automatic failover, traffic counters, the connection doctor
//! and backups. Everything here goes through the same store and config generator as the rest
//! of the Core.

use super::*;
use crate::core::backup::{self, RestoreMode, Sections};
use crate::core::quality::{self, Sample, SampleKind};
use crate::core::routing::{self, RoutingMode, RuleAction, RuleMatch, SiteRule};
use crate::core::store::Profile;
use crate::core::tester::{self, Endpoint, Instance};
use crate::core::xray_config::RoutingPlan;
use serde_json::{json, Value};
use std::collections::BTreeMap;

pub const TRAFFIC_FILE: &str = "traffic.json";
const ROUTING_CHECK: Duration = Duration::from_secs(2);
const HEALTH_INTERVAL: Duration = Duration::from_secs(30);
const STATS_INTERVAL: Duration = Duration::from_secs(5);
const TEST_PARALLEL: usize = 4;
const MAX_TEST_BATCH: usize = 200;
const FAILOVER_CANDIDATES: usize = 6;
const TRAFFIC_DAYS: usize = 400;

pub fn now_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

/// Runtime state of the features: what the running tunnel was built from, the live-route
/// monitor and the traffic counters.
#[derive(Default)]
pub(crate) struct LiveState {
    applied_revision: u64,
    last_routing_check: Option<Instant>,
    stats_port: Option<u16>,
    jetbrains: Option<JetbrainsPorts>,
    ide_auth: Option<ProxyCredentials>,
    last_health: Option<Instant>,
    health_failures: u32,
    health_in_flight: bool,
    failover_in_flight: bool,
    last_stats: Option<Instant>,
    stats_in_flight: bool,
    rate: [u64; 4],
    session: [u64; 4],
}

impl LiveState {
    pub(crate) fn tunnel_started(&mut self, revision: u64, stats_port: Option<u16>, jb: JetbrainsPorts, ide_auth: Option<ProxyCredentials>) {
        *self = LiveState { applied_revision: revision, stats_port, jetbrains: Some(jb), ide_auth, last_routing_check: Some(Instant::now()), ..Default::default() };
    }

    pub(crate) fn connected(&mut self) {
        self.last_health = Some(Instant::now());
        self.last_stats = Some(Instant::now());
        self.health_failures = 0;
    }

    pub(crate) fn stats_port(&self) -> Option<u16> {
        self.stats_port
    }

    pub(crate) fn tunnel_stopped(&mut self) {
        let revision = self.applied_revision;
        *self = LiveState { applied_revision: revision, ..Default::default() };
    }
}

/// A preset: settings plus a few rules on top of the user's own.
pub struct ScenarioInfo {
    pub id: &'static str,
    pub title: &'static str,
    pub summary: &'static str,
    pub highlights: &'static [&'static str],
    pub kill_switch: bool,
    pub auto_failover: bool,
    pub fastest_on_connect: bool,
    pub rules: &'static [(&'static str, RuleMatch, &'static str, RuleAction)],
}

pub const SCENARIOS: &[ScenarioInfo] = &[
    ScenarioInfo {
        id: "work",
        title: "Work",
        summary: "Stay connected and never fall back to an unprotected route.",
        highlights: &["Kill switch on", "Automatic failover", "Work tools always through the tunnel"],
        kill_switch: true,
        auto_failover: true,
        fastest_on_connect: false,
        rules: &[(
            "Work tools through the tunnel",
            RuleMatch::Domain,
            "slack.com, zoom.us, teams.microsoft.com, github.com, githubusercontent.com, atlassian.net, notion.so",
            RuleAction::Proxy,
        )],
    },
    ScenarioInfo {
        id: "streaming",
        title: "Streaming",
        summary: "Keep one stable exit address so sessions are not interrupted.",
        highlights: &["Keeps your main server", "Kill switch on", "Automatic failover", "Streaming sites always through the tunnel"],
        kill_switch: true,
        auto_failover: true,
        fastest_on_connect: false,
        rules: &[(
            "Streaming through the tunnel",
            RuleMatch::Domain,
            "netflix.com, nflxvideo.net, youtube.com, googlevideo.com, ytimg.com, twitch.tv, ttvnw.net, spotify.com, scdn.co",
            RuleAction::Proxy,
        )],
    },
    ScenarioInfo {
        id: "browsing",
        title: "Fast browsing",
        summary: "The fastest server on every connect; local and banking sites stay direct.",
        highlights: &["Fastest server on connect", "Automatic failover", "Kill switch off"],
        kill_switch: false,
        auto_failover: true,
        fastest_on_connect: true,
        rules: &[],
    },
];

/// Arguments of a new or changed rule, as the client sends them.
#[derive(Debug, Clone, Default)]
pub struct RuleInput {
    pub name: String,
    pub match_type: RuleMatch,
    pub text: String,
    pub action: RuleAction,
    pub server_id: Option<String>,
    pub minutes: Option<u32>,
    pub enabled: bool,
}

fn invalid(m: impl Into<String>) -> CoreError {
    CoreError::new(ErrorKind::InvalidRequest, m)
}

fn rule_json(r: &SiteRule, now: u64) -> Value {
    let mut v = serde_json::to_value(r).unwrap_or(Value::Null);
    v["active"] = json!(r.active(now));
    v
}

/// Days since 1970-01-01 to a civil date (UTC).
fn ymd(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (yoe + era * 400 + if m <= 2 { 1 } else { 0 }, m, d)
}

fn today() -> String {
    let (y, m, d) = ymd((store::now() / 86_400) as i64);
    format!("{y:04}-{m:02}-{d:02}")
}

/// `traffic.json`: per day [proxy up, proxy down, direct up, direct down] in bytes.
#[derive(serde::Serialize, serde::Deserialize, Default)]
struct TrafficFile {
    days: BTreeMap<String, [u64; 4]>,
}

/// Parses `xray api statsquery` output into [proxy up, proxy down, direct up, direct down].
pub fn parse_stats(text: &str) -> Option<[u64; 4]> {
    let v: Value = serde_json::from_str(text.trim()).ok()?;
    let mut out = [0u64; 4];
    for s in v.get("stat").and_then(Value::as_array).into_iter().flatten() {
        let name = s.get("name").and_then(Value::as_str).unwrap_or("");
        let value = match s.get("value") {
            Some(Value::String(x)) => x.parse().unwrap_or(0),
            Some(Value::Number(n)) => n.as_u64().unwrap_or(0),
            _ => 0,
        };
        let parts: Vec<&str> = name.split(">>>").collect();
        if parts.len() != 4 || parts[0] != "outbound" || parts[2] != "traffic" {
            continue;
        }
        let base = match parts[1] {
            t if t == "proxy" || t.starts_with("proxy-") => 0,
            "direct" => 2,
            _ => continue,
        };
        let i = base + usize::from(parts[3] == "downlink");
        out[i] = out[i].saturating_add(value);
    }
    Some(out)
}

impl Core {
    // ------------------------------------------------------------ periodic work

    pub(super) fn periodic(&mut self) {
        let due = self.live.last_routing_check.is_none_or(|t| t.elapsed() >= ROUTING_CHECK);
        if due {
            self.live.last_routing_check = Some(Instant::now());
            self.expire_rules();
            self.apply_routing_changes();
        }
        if matches!(self.session.state(), SessionState::Connected { .. }) {
            self.health_check();
            self.read_stats();
        }
    }

    /// Removes temporary rules whose time is up (whichever helper notices first).
    fn expire_rules(&mut self) {
        let now = store::now();
        let Ok(st) = self.store.state() else { return };
        if !st.rules.iter().any(|r| r.expires_at.is_some_and(|t| t <= now)) {
            return;
        }
        let removed = self
            .store
            .update_state(|st| {
                let gone: Vec<String> = st.rules.iter().filter(|r| r.expires_at.is_some_and(|t| t <= now)).map(|r| r.name.clone()).collect();
                st.rules.retain(|r| !r.expires_at.is_some_and(|t| t <= now));
                if !gone.is_empty() {
                    st.routing_revision += 1;
                }
                Ok(gone)
            })
            .unwrap_or_default();
        for name in removed {
            self.events.push(CoreEvent::Notice { kind: "ruleExpired", message: format!("The temporary rule \"{name}\" ended.") });
        }
        self.events.push(CoreEvent::DataChanged);
    }

    /// The routing plan of the current store contents, and its revision.
    pub(super) fn routing_plan(&self, main_id: &str) -> (RoutingPlan, u64) {
        let Ok(st) = self.store.state() else { return (RoutingPlan::default(), 0) };
        let now = store::now();
        let rules: Vec<SiteRule> = st.rules.into_iter().filter(|r| r.active(now)).collect();
        let mut ids: Vec<String> = Vec::new();
        for r in &rules {
            if let (RuleAction::Proxy, Some(id)) = (r.action, &r.server_id) {
                if id != main_id && !ids.contains(id) {
                    ids.push(id.clone());
                }
            }
        }
        let servers = self.store.servers_with_secrets(&ids).unwrap_or_default();
        let plan = RoutingPlan { mode: st.settings.routing_mode, local_names_direct: st.settings.local_names_direct, rules, servers };
        (plan, st.routing_revision)
    }

    /// Rebuilds the running tunnel when rules, routing mode or rule servers changed (here or in
    /// another browser profile). The local port and credentials stay the same.
    fn apply_routing_changes(&mut self) {
        let Ok(revision) = self.store.state().map(|s| s.routing_revision) else { return };
        if revision == self.live.applied_revision {
            return;
        }
        let connected = matches!(self.session.state(), SessionState::Connected { .. });
        let (profile_id, port, credentials) = match (&self.session.mode, connected) {
            (RuntimeMode::Tunnel { profile_id, port, credentials, .. }, true) => (profile_id.clone(), *port, credentials.clone()),
            _ => {
                self.live.applied_revision = revision;
                return;
            }
        };
        self.live.applied_revision = revision;
        self.events.push(CoreEvent::DataChanged);
        let Some(xray_path) = self.xray_path.clone() else { return };
        let Ok((meta, secrets)) = self.store.server_with_secrets(&profile_id) else { return };
        let (routing, revision) = self.routing_plan(&profile_id);
        self.live.applied_revision = revision;
        let Some(jb) = self.live.jetbrains else { return };
        let plan = RuntimePlan {
            browser_port: Some(port),
            browser_auth: Some(credentials.clone()),
            jetbrains: jb,
            ide_auth: self.live.ide_auth.clone(),
            log_level: Self::log_level(),
            stats_port: self.live.stats_port,
        };
        let cfg = SecretBytes(serde_json::to_vec(&xray_config::routed_config(&meta, &secrets, &routing, &plan)).unwrap_or_default());
        if let Err(reason) = xray::test_config(&xray_path, &cfg.0) {
            log::warn(format!("new rules rejected by Xray: {reason}"));
            self.events.push(CoreEvent::Notice { kind: "rulesRejected", message: "The new rules could not be applied; the previous ones are still in use.".into() });
            return;
        }
        if let Some(r) = self.session.run.take() {
            r.stop();
        }
        let wait = Duration::from_millis(1500);
        let _ = ports::is_free_soon(port, wait);
        match xray::spawn(&xray_path, &cfg.0, self.store.dir()) {
            Ok(r) if ports::wait_listening(port, self.timing.listen_timeout, || r.try_exit().is_none()) => {
                self.session.run = Some(r);
                self.session.mode = RuntimeMode::Tunnel { profile_id, port, config: cfg, credentials };
                log::info("routing rules applied to the live tunnel");
            }
            Ok(r) => {
                r.stop();
                self.fail(CoreError::new(ErrorKind::RuntimeFailure, "The tunnel could not be restarted with the new rules"), Some(profile_id));
            }
            Err(e) => self.fail(CoreError::from_runtime_security(&e).unwrap_or_else(|| CoreError::new(ErrorKind::RuntimeFailure, "The tunnel could not be restarted with the new rules")), Some(profile_id)),
        }
    }

    fn live_endpoint(&self) -> Option<Endpoint> {
        match (self.session.state(), &self.session.mode) {
            (SessionState::Connected { .. }, RuntimeMode::Tunnel { port, credentials, .. }) => Some(Endpoint { port: *port, auth: credentials.clone() }),
            _ => None,
        }
    }

    fn health_check(&mut self) {
        if self.live.health_in_flight || self.live.failover_in_flight || self.live.last_health.is_some_and(|t| t.elapsed() < HEALTH_INTERVAL) {
            return;
        }
        let Some(ep) = self.live_endpoint() else { return };
        self.live.last_health = Some(Instant::now());
        self.live.health_in_flight = true;
        let post = self.post.clone();
        let attempt = self.session.attempt;
        let timeout = self.timing.probe_timeout;
        std::thread::spawn(move || {
            let r = tester::real_delay(&ep, timeout);
            post(CoreMsg::HealthDone { attempt, ok: r.is_ok(), latency_ms: r.unwrap_or(0) });
        });
    }

    pub(super) fn on_health(&mut self, attempt: u64, ok: bool, latency_ms: u64) {
        self.live.health_in_flight = false;
        if attempt != self.session.attempt {
            return;
        }
        let Some(sid) = self.session.state().active_profile_id().map(str::to_string) else { return };
        self.record_samples(vec![(sid.clone(), Sample::new(now_ms(), SampleKind::Tunnel, ok, latency_ms as i64))]);
        if ok {
            if self.live.health_failures > 0 {
                self.record_samples(vec![(sid, Sample::new(now_ms(), SampleKind::Recovery, true, -1))]);
            }
            self.live.health_failures = 0;
            return;
        }
        self.live.health_failures += 1;
        // Check again soon instead of waiting a full interval.
        self.live.last_health = Instant::now().checked_sub(HEALTH_INTERVAL - Duration::from_secs(5));
        if self.live.health_failures < 2 {
            return;
        }
        if !self.settings().auto_failover {
            self.events.push(CoreEvent::Notice { kind: "routeDown", message: "The server is not responding.".into() });
            self.live.health_failures = 0;
            return;
        }
        self.start_failover(&sid);
    }

    /// Servers worth trying instead of `current`: favorites first, then the same subscription,
    /// then by quality score.
    fn candidates(&self, exclude: &str, limit: usize) -> Vec<String> {
        let Ok(st) = self.store.state() else { return vec![] };
        let history: quality::History = self.store.read_aux(quality::FILE);
        let now = now_ms();
        let same_sub = st.servers.iter().find(|m| m.id == exclude).and_then(|m| m.subscription_id.clone());
        let mut list: Vec<(i64, String)> = st
            .servers
            .iter()
            .filter(|m| m.id != exclude)
            .map(|m| {
                let s = history.get(&m.id).map(|h| quality::score(h, now));
                let mut rank = s.as_ref().and_then(|s| s.score).unwrap_or(30);
                if s.as_ref().is_some_and(|s| s.label == "unavailable") {
                    rank -= 100;
                }
                if st.favorites.contains(&m.id) {
                    rank += 60;
                }
                if same_sub.is_some() && m.subscription_id == same_sub {
                    rank += 15;
                }
                (rank, m.id.clone())
            })
            .collect();
        list.sort_by(|a, b| b.0.cmp(&a.0));
        list.into_iter().take(limit).map(|(_, id)| id).collect()
    }

    fn start_failover(&mut self, current: &str) {
        let ids = self.candidates(current, FAILOVER_CANDIDATES);
        let Some(xray_path) = self.xray_path.clone() else { return };
        if ids.is_empty() {
            self.events.push(CoreEvent::Notice { kind: "routeDown", message: "The server is not responding and there is no other server to switch to.".into() });
            return;
        }
        let servers = self.store.servers_with_secrets(&ids).unwrap_or_default();
        self.live.failover_in_flight = true;
        log::warn(format!("route not responding; testing {} other servers", servers.len()));
        let post = self.post.clone();
        let attempt = self.session.attempt;
        let dir = self.store.dir().to_path_buf();
        std::thread::spawn(move || {
            let results = run_parallel(servers, move |(m, s)| {
                let r = Instance::start(&xray_path, &dir, &m, &s).and_then(|i| tester::real_delay(&i.endpoint, Duration::from_secs(6)));
                (m.id.clone(), r)
            });
            let samples = results.iter().map(|(id, r)| (id.clone(), Sample::new(now_ms(), SampleKind::Tunnel, r.is_ok(), *r.as_ref().unwrap_or(&0) as i64))).collect();
            let best = results.into_iter().filter_map(|(id, r)| r.ok().map(|ms| (ms, id))).min().map(|(_, id)| id);
            post(CoreMsg::FailoverDone { attempt, best, samples });
        });
    }

    pub(super) fn on_failover(&mut self, attempt: u64, best: Option<String>) {
        self.live.failover_in_flight = false;
        if attempt != self.session.attempt {
            return;
        }
        let from = self.session.state().active_profile_id().map(str::to_string);
        let name = |core: &Core, id: &str| core.store.state().ok().and_then(|s| s.servers.into_iter().find(|m| m.id == id)).map(|m| m.name).unwrap_or_default();
        match best {
            Some(id) => {
                let old = from.as_deref().map(|f| name(self, f)).unwrap_or_default();
                let new = name(self, &id);
                if self.start_session(&id).is_ok() {
                    self.events.push(CoreEvent::Notice { kind: "failover", message: format!("{old} stopped responding; switched to {new}.") });
                }
            }
            None => {
                self.live.health_failures = 0;
                self.events.push(CoreEvent::Notice { kind: "routeDown", message: "The server is not responding and no other server worked.".into() });
            }
        }
    }

    fn read_stats(&mut self) {
        let Some(port) = self.live.stats_port else { return };
        if self.live.stats_in_flight || self.live.last_stats.is_some_and(|t| t.elapsed() < STATS_INTERVAL) {
            return;
        }
        let Some(xray_path) = self.xray_path.clone() else { return };
        self.live.stats_in_flight = true;
        self.live.last_stats = Some(Instant::now());
        let post = self.post.clone();
        let attempt = self.session.attempt;
        std::thread::spawn(move || {
            let deltas = match xray::stats_query(&xray_path, port) {
                Ok(t) => {
                    let d = parse_stats(&t);
                    if d.is_none() {
                        log::debug(format!("traffic counters: unexpected output ({} bytes)", t.len()));
                    }
                    d
                }
                Err(e) => {
                    log::debug(format!("traffic counters unavailable: {e}"));
                    None
                }
            };
            post(CoreMsg::StatsDone { attempt, deltas });
        });
    }

    pub(super) fn on_stats(&mut self, attempt: u64, deltas: Option<[u64; 4]>) {
        self.live.stats_in_flight = false;
        let Some(d) = deltas else { return };
        if attempt != self.session.attempt {
            return;
        }
        let secs = STATS_INTERVAL.as_secs().max(1);
        for i in 0..4 {
            self.live.rate[i] = d[i] / secs;
            self.live.session[i] = self.live.session[i].saturating_add(d[i]);
        }
        if d.iter().any(|x| *x > 0) {
            let day = today();
            let _ = self.store.update_aux::<TrafficFile, _>(TRAFFIC_FILE, |t| {
                let e = t.days.entry(day).or_default();
                for i in 0..4 {
                    e[i] = e[i].saturating_add(d[i]);
                }
                while t.days.len() > TRAFFIC_DAYS {
                    let first = t.days.keys().next().cloned();
                    if let Some(k) = first {
                        t.days.remove(&k);
                    }
                }
            });
        }
    }

    pub(super) fn record_samples(&mut self, samples: Vec<(String, Sample)>) {
        if samples.is_empty() {
            return;
        }
        let _ = self.store.update_aux::<quality::History, _>(quality::FILE, |h| {
            for (id, s) in samples {
                quality::record(h, &id, s);
            }
            // Forget servers that no longer exist.
            h.retain(|_, v| !v.is_empty());
        });
    }

    // ------------------------------------------------------------ servers

    /// Favorites, tags and a short score per server, for the server list.
    pub fn server_extras(&self) -> Result<Value, CoreError> {
        let st = self.store.state()?;
        let history: quality::History = self.store.read_aux(quality::FILE);
        let now = now_ms();
        let scores: serde_json::Map<String, Value> = st
            .servers
            .iter()
            .filter_map(|m| {
                let s = quality::score(history.get(&m.id)?, now);
                let latency = s.components.latency_ms.or(s.components.setup_ms);
                Some((m.id.clone(), json!({ "score": s.score, "label": s.label, "latencyMs": latency, "stale": s.stale, "measuredAt": s.measured_at })))
            })
            .collect();
        Ok(json!({ "favorites": st.favorites, "tags": st.tags, "scores": scores }))
    }

    pub fn subscription_details(&self) -> Result<Value, CoreError> {
        let st = self.store.state()?;
        Ok(json!(st
            .subscriptions
            .iter()
            .map(|s| (s.id.clone(), json!({ "usage": s.usage, "updateIntervalHours": s.update_interval_hours, "history": s.history })))
            .collect::<serde_json::Map<String, Value>>()))
    }

    pub fn set_favorite(&mut self, id: &str, favorite: bool) -> Result<(), CoreError> {
        valid_id(id)?;
        self.store.update_state(|st| {
            if !st.servers.iter().any(|m| m.id == id) {
                return Err(StoreError::NotFound);
            }
            st.favorites.retain(|f| f != id);
            if favorite {
                st.favorites.push(id.to_string());
            }
            Ok(())
        })?;
        Ok(())
    }

    pub fn set_tag(&mut self, id: &str, tag: &str) -> Result<(), CoreError> {
        valid_id(id)?;
        let tag = crate::core::validate::clean_name(tag, "");
        let tag = crate::core::validate::truncate(&tag, 30);
        self.store.update_state(|st| {
            if !st.servers.iter().any(|m| m.id == id) {
                return Err(StoreError::NotFound);
            }
            if tag.is_empty() {
                st.tags.remove(id);
            } else {
                st.tags.insert(id.to_string(), tag);
            }
            Ok(())
        })?;
        Ok(())
    }

    pub fn quality_of(&self, id: &str) -> Result<Value, CoreError> {
        valid_id(id)?;
        let history: quality::History = self.store.read_aux(quality::FILE);
        let s = quality::score(history.get(id).map(Vec::as_slice).unwrap_or(&[]), now_ms());
        Ok(serde_json::to_value(s).unwrap_or(Value::Null))
    }

    /// Tests servers in the background: `real` through a separate Xray each, otherwise a TCP
    /// ping. Each result is sent as progress; the response lists all of them.
    pub fn test_servers(&mut self, ids: &[String], real: bool, ticket: Ticket) -> Result<(), CoreError> {
        if ids.is_empty() || ids.len() > MAX_TEST_BATCH {
            return Err(invalid(format!("Select between 1 and {MAX_TEST_BATCH} servers")));
        }
        for id in ids {
            valid_id(id)?;
        }
        let servers = self.store.servers_with_secrets(ids)?;
        let xray_path = self.xray_path.clone();
        if real && xray_path.is_none() {
            return Err(CoreError::new(ErrorKind::RuntimeUnavailable, "Xray is missing from the runtime"));
        }
        let post = self.post.clone();
        let dir = self.store.dir().to_path_buf();
        std::thread::spawn(move || {
            let progress = post.clone();
            let results = run_parallel(servers, move |(m, s)| {
                let r = if real {
                    Instance::start(xray_path.as_deref().unwrap_or(std::path::Path::new("")), &dir, &m, &s).and_then(|i| tester::real_delay(&i.endpoint, Duration::from_secs(8)))
                } else {
                    tester::tcp_ping(&m, Duration::from_secs(4))
                };
                let v = match &r {
                    Ok(ms) => json!({ "serverId": m.id, "ok": true, "ms": ms }),
                    Err(e) => json!({ "serverId": m.id, "ok": false, "error": crate::core::validate::truncate(e, 160) }),
                };
                progress(CoreMsg::JobProgress { ticket, value: v.clone() });
                (m.id.clone(), r, v)
            });
            let kind = if real { SampleKind::Tunnel } else { SampleKind::Reachability };
            let samples = results.iter().map(|(id, r, _)| (id.clone(), Sample::new(now_ms(), kind, r.is_ok(), *r.as_ref().unwrap_or(&0) as i64))).collect();
            let list: Vec<Value> = results.into_iter().map(|(_, _, v)| v).collect();
            post(CoreMsg::JobDone { ticket, result: Ok(json!({ "results": list })), samples });
        });
        Ok(())
    }

    /// Speed of the live tunnel (no id, or the connected server) or of another server.
    pub fn speed_test(&mut self, id: Option<&str>, ticket: Ticket) -> Result<(), CoreError> {
        let live = self.live_endpoint();
        let active = self.session.state().active_profile_id().map(str::to_string);
        let target = match id {
            Some(i) => {
                valid_id(i)?;
                Some(i.to_string())
            }
            None => None,
        };
        let use_live = target.is_none() || (target == active && live.is_some());
        let post = self.post.clone();
        if use_live {
            let ep = live.ok_or_else(|| invalid("Connect first, or choose a server to test"))?;
            std::thread::spawn(move || {
                let r = tester::speed(&ep).map(|bps| json!({ "bytesPerSecond": bps, "live": true })).map_err(|e| CoreError::new(ErrorKind::ConnectionFailure, e));
                post(CoreMsg::JobDone { ticket, result: r, samples: vec![] });
            });
            return Ok(());
        }
        let id = target.unwrap_or_default();
        let (m, s) = self.store.server_with_secrets(&id).map_err(|e| match e {
            StoreError::NotFound => not_found("Server"),
            e => e.into(),
        })?;
        let xray_path = self.xray_path.clone().ok_or_else(|| CoreError::new(ErrorKind::RuntimeUnavailable, "Xray is missing from the runtime"))?;
        let dir = self.store.dir().to_path_buf();
        std::thread::spawn(move || {
            let r = Instance::start(&xray_path, &dir, &m, &s)
                .and_then(|i| tester::speed(&i.endpoint))
                .map(|bps| json!({ "bytesPerSecond": bps, "live": false }))
                .map_err(|e| CoreError::new(ErrorKind::ConnectionFailure, e));
            post(CoreMsg::JobDone { ticket, result: r, samples: vec![] });
        });
        Ok(())
    }

    /// The live tunnel's exit address and location.
    pub fn exit_info(&mut self, ticket: Ticket) -> Result<(), CoreError> {
        let ep = self.live_endpoint().ok_or_else(|| invalid("Not connected"))?;
        let post = self.post.clone();
        std::thread::spawn(move || {
            let r = tester::exit_info(&ep).map(|i| serde_json::to_value(i).unwrap_or(Value::Null)).map_err(|e| CoreError::new(ErrorKind::ConnectionFailure, e));
            post(CoreMsg::JobDone { ticket, result: r, samples: vec![] });
        });
        Ok(())
    }

    /// Opens the same sites through the live tunnel and through a candidate server, side by side,
    /// without changing the live route.
    pub fn compare(&mut self, candidate: &str, urls: &[String], ticket: Ticket) -> Result<(), CoreError> {
        valid_id(candidate)?;
        if urls.is_empty() || urls.len() > 8 {
            return Err(invalid("Choose between 1 and 8 sites"));
        }
        let urls: Vec<String> = urls.iter().map(|u| tester::check_site_url(u)).collect::<Result<_, _>>().map_err(invalid)?;
        let live = self.live_endpoint().ok_or_else(|| invalid("Connect first: the comparison runs next to the live route"))?;
        let (m, s) = self.store.server_with_secrets(candidate).map_err(|e| match e {
            StoreError::NotFound => not_found("Server"),
            e => e.into(),
        })?;
        let xray_path = self.xray_path.clone().ok_or_else(|| CoreError::new(ErrorKind::RuntimeUnavailable, "Xray is missing from the runtime"))?;
        let dir = self.store.dir().to_path_buf();
        let post = self.post.clone();
        std::thread::spawn(move || {
            let r = Instance::start(&xray_path, &dir, &m, &s).map(|inst| {
                let live_t: Vec<_> = urls.iter().map(|u| tester::time_site(&live, u)).collect();
                let cand_t: Vec<_> = urls.iter().map(|u| tester::time_site(&inst.endpoint, u)).collect();
                json!({ "live": live_t, "candidate": cand_t, "candidateId": m.id })
            });
            post(CoreMsg::JobDone { ticket, result: r.map_err(|e| CoreError::new(ErrorKind::ConnectionFailure, e)), samples: vec![] });
        });
        Ok(())
    }

    /// Tests the best candidates and connects to the fastest working one.
    pub fn connect_fastest(&mut self, ticket: Ticket) -> Result<(), CoreError> {
        let current = self.session.state().active_profile_id().map(str::to_string).unwrap_or_default();
        let selected = self.store.state()?.selected_server_id.unwrap_or_default();
        let mut ids = self.candidates(&current, 8);
        if !current.is_empty() || ids.is_empty() {
            if let Some(s) = Some(selected).filter(|s| !s.is_empty() && !ids.contains(s) && *s != current) {
                ids.push(s);
            }
        }
        if ids.is_empty() {
            return Err(invalid("There is no other server to choose from"));
        }
        let servers = self.store.servers_with_secrets(&ids)?;
        let xray_path = self.xray_path.clone().ok_or_else(|| CoreError::new(ErrorKind::RuntimeUnavailable, "Xray is missing from the runtime"))?;
        let dir = self.store.dir().to_path_buf();
        let post = self.post.clone();
        self.live.failover_in_flight = true;
        std::thread::spawn(move || {
            let results = run_parallel(servers, move |(m, s)| {
                let r = Instance::start(&xray_path, &dir, &m, &s).and_then(|i| tester::real_delay(&i.endpoint, Duration::from_secs(6)));
                (m.id.clone(), r)
            });
            let samples: Vec<_> = results.iter().map(|(id, r)| (id.clone(), Sample::new(now_ms(), SampleKind::Tunnel, r.is_ok(), *r.as_ref().unwrap_or(&0) as i64))).collect();
            let best = results.iter().filter_map(|(id, r)| r.as_ref().ok().map(|ms| (*ms, id.clone()))).min();
            post(CoreMsg::FastestDone { ticket, best, samples });
        });
        Ok(())
    }

    pub(super) fn on_fastest(&mut self, ticket: Ticket, best: Option<(u64, String)>) {
        self.live.failover_in_flight = false;
        let result = match best {
            Some((ms, id)) => self.start_session(&id).map(|_| json!({ "serverId": id, "ms": ms })),
            None => Err(CoreError::new(ErrorKind::ConnectionFailure, "None of the tested servers worked")),
        };
        self.events.push(CoreEvent::JobDone { ticket, result });
    }

    // ------------------------------------------------------------ traffic

    pub fn traffic(&self) -> Value {
        let t: TrafficFile = self.store.read_aux(TRAFFIC_FILE);
        let day = today();
        let month = &day[..7];
        let sum = |pred: &dyn Fn(&str) -> bool| {
            let mut s = [0u64; 4];
            for (k, v) in &t.days {
                if pred(k) {
                    for i in 0..4 {
                        s[i] = s[i].saturating_add(v[i]);
                    }
                }
            }
            s
        };
        let pack = |a: [u64; 4]| json!({ "proxyUp": a[0], "proxyDown": a[1], "directUp": a[2], "directDown": a[3] });
        let days: Vec<Value> = t.days.iter().rev().take(60).map(|(k, v)| json!({ "day": k, "proxyUp": v[0], "proxyDown": v[1], "directUp": v[2], "directDown": v[3] })).collect();
        json!({
            "enabled": self.settings().traffic_stats,
            "counting": self.live.stats_port.is_some() && matches!(self.session.state(), SessionState::Connected { .. }),
            "rate": pack(self.live.rate),
            "session": pack(self.live.session),
            "today": pack(sum(&|k| k == day)),
            "month": pack(sum(&|k| k.starts_with(month))),
            "days": days,
        })
    }

    pub fn clear_traffic(&mut self) {
        self.store.remove_aux(TRAFFIC_FILE);
        self.live.session = [0; 4];
    }

    // ------------------------------------------------------------ rules

    fn bump(&mut self, f: impl FnOnce(&mut store::StateFile) -> Result<(), StoreError>) -> Result<(), CoreError> {
        self.store.update_state(|st| {
            f(st)?;
            st.routing_revision += 1;
            Ok(())
        })?;
        self.events.push(CoreEvent::DataChanged);
        // Apply right away instead of at the next periodic check.
        self.live.last_routing_check = None;
        Ok(())
    }

    pub fn rules(&self) -> Result<Value, CoreError> {
        let st = self.store.state()?;
        let now = store::now();
        Ok(json!({
            "rules": st.rules.iter().map(|r| rule_json(r, now)).collect::<Vec<_>>(),
            "mode": st.settings.routing_mode,
            "localNamesDirect": st.settings.local_names_direct,
            "activeScenario": st.active_scenario,
            "revision": st.routing_revision,
        }))
    }

    fn build_rule(&self, input: &RuleInput, id: Option<String>) -> Result<SiteRule, CoreError> {
        let values = routing::normalize_values(input.match_type, &input.text).map_err(invalid)?;
        if let Some(sid) = &input.server_id {
            valid_id(sid)?;
            if input.action != RuleAction::Proxy {
                return Err(invalid("Only tunnel rules can use another server"));
            }
            if !self.store.state()?.servers.iter().any(|m| &m.id == sid) {
                return Err(not_found("Server"));
            }
        }
        let expires_at = match input.minutes {
            Some(0) | None => None,
            Some(m) if m <= 7 * 24 * 60 => Some(store::now() + m as u64 * 60),
            Some(_) => return Err(invalid("A temporary rule can last at most a week")),
        };
        let fallback = values.first().cloned().unwrap_or_else(|| "Rule".into());
        Ok(SiteRule {
            id: id.unwrap_or_else(|| uuid::Uuid::new_v4().to_string()),
            name: crate::core::validate::clean_name(&input.name, &fallback),
            match_type: input.match_type,
            values,
            action: input.action,
            server_id: input.server_id.clone(),
            enabled: input.enabled,
            expires_at,
            scenario: None,
            created_at: store::now(),
        })
    }

    pub fn add_rule(&mut self, input: RuleInput) -> Result<Value, CoreError> {
        let rule = self.build_rule(&input, None)?;
        let out = rule_json(&rule, store::now());
        self.bump(|st| {
            if st.rules.len() >= routing::MAX_RULES {
                return Err(StoreError::Invalid(format!("At most {} rules", routing::MAX_RULES)));
            }
            // Scenario rules stay on top; a new rule goes after them.
            let at = st.rules.iter().take_while(|r| r.scenario.is_some()).count();
            st.rules.insert(at, rule);
            Ok(())
        })?;
        Ok(out)
    }

    pub fn update_rule(&mut self, id: &str, input: RuleInput) -> Result<Value, CoreError> {
        valid_id(id)?;
        let mut rule = self.build_rule(&input, Some(id.to_string()))?;
        let mut out = Value::Null;
        self.bump(|st| {
            let r = st.rules.iter_mut().find(|r| r.id == id).ok_or(StoreError::NotFound)?;
            if r.scenario.is_some() {
                return Err(StoreError::Invalid("Preset rules cannot be edited. Remove the preset or add your own rule.".into()));
            }
            rule.created_at = r.created_at;
            *r = rule;
            out = rule_json(r, store::now());
            Ok(())
        })?;
        Ok(out)
    }

    pub fn delete_rule(&mut self, id: &str) -> Result<(), CoreError> {
        valid_id(id)?;
        self.bump(|st| {
            let before = st.rules.len();
            st.rules.retain(|r| r.id != id);
            if st.rules.len() == before {
                return Err(StoreError::NotFound);
            }
            if !st.rules.iter().any(|r| r.scenario.is_some()) {
                st.active_scenario = None;
            }
            Ok(())
        })
    }

    pub fn move_rule(&mut self, id: &str, offset: i32) -> Result<(), CoreError> {
        valid_id(id)?;
        self.bump(|st| {
            let i = st.rules.iter().position(|r| r.id == id).ok_or(StoreError::NotFound)?;
            let first_own = st.rules.iter().take_while(|r| r.scenario.is_some()).count();
            if st.rules[i].scenario.is_some() {
                return Err(StoreError::Invalid("Preset rules keep their place above your own rules.".into()));
            }
            let j = (i as i64 + offset as i64).clamp(first_own as i64, st.rules.len() as i64 - 1) as usize;
            let r = st.rules.remove(i);
            st.rules.insert(j, r);
            Ok(())
        })
    }

    pub fn set_rule_enabled(&mut self, id: &str, enabled: bool) -> Result<(), CoreError> {
        valid_id(id)?;
        self.bump(|st| {
            st.rules.iter_mut().find(|r| r.id == id).ok_or(StoreError::NotFound)?.enabled = enabled;
            Ok(())
        })
    }

    /// The popup's one-click choice for the site in the current tab: a rule for exactly this
    /// site (its registrable part is up to the client), replacing an earlier one-click rule for
    /// it. `None` removes it, so the site follows the other rules again.
    pub fn set_site(&mut self, host: &str, action: Option<RuleAction>, server_id: Option<String>, minutes: Option<u32>) -> Result<Value, CoreError> {
        let values = routing::normalize_values(RuleMatch::Domain, host).map_err(invalid)?;
        if values.len() != 1 || values[0].contains(':') {
            return Err(invalid("Choose one site"));
        }
        let site = values[0].clone();
        let existing = self.store.state()?.rules.into_iter().find(|r| r.scenario.is_none() && r.match_type == RuleMatch::Domain && r.values == values);
        match (action, existing) {
            (None, Some(r)) => {
                self.delete_rule(&r.id)?;
                Ok(Value::Null)
            }
            (None, None) => Ok(Value::Null),
            (Some(a), existing) => {
                let input = RuleInput { name: site.clone(), match_type: RuleMatch::Domain, text: site, action: a, server_id, minutes, enabled: true };
                match existing {
                    Some(r) => {
                        let v = self.update_rule(&r.id, input)?;
                        // A one-click choice wins over other rules: move it to the top of the user's rules.
                        self.move_rule(&r.id, -(routing::MAX_RULES as i32))?;
                        Ok(v)
                    }
                    None => self.add_rule(input),
                }
            }
        }
    }

    /// Where a site's traffic goes now, and why.
    pub fn evaluate(&self, host: &str) -> Result<Value, CoreError> {
        let host = crate::core::validate::truncate(host.trim(), 253);
        let st = self.store.state()?;
        let now = store::now();
        let name_of = |id: &str| st.servers.iter().find(|m| m.id == id).map(|m| m.name.clone());
        let active = self.session.state().active_profile_id().map(str::to_string);
        let local = host == "localhost"
            || !host.contains('.')
            || matches!(crate::core::netpolicy::classify_host(&host), crate::core::netpolicy::Class::Loopback | crate::core::netpolicy::Class::Private)
            || routing::LOCAL_NAME_RULES.iter().filter_map(|r| r.strip_prefix("domain:")).any(|d| host == d || host.ends_with(&format!(".{d}")));
        let rule = routing::evaluate(&st.rules, &host, now);
        let (outcome, reason, server) = if local && st.settings.local_names_direct {
            ("direct", "Local address".to_string(), None)
        } else if let Some(r) = rule {
            let server = match (r.action, &r.server_id) {
                (RuleAction::Proxy, Some(id)) => name_of(id).or_else(|| active.as_deref().and_then(name_of)),
                (RuleAction::Proxy, None) => active.as_deref().and_then(name_of),
                _ => None,
            };
            let outcome = match r.action {
                RuleAction::Proxy => "proxy",
                RuleAction::Direct => "direct",
                RuleAction::Block => "block",
            };
            (outcome, format!("Rule \"{}\"", r.name), server)
        } else if st.settings.routing_mode == RoutingMode::Selected {
            ("direct", "Only selected sites use the tunnel".to_string(), None)
        } else {
            ("proxy", "Everything uses the tunnel".to_string(), active.as_deref().and_then(name_of))
        };
        let exact = st.rules.iter().find(|r| r.scenario.is_none() && r.match_type == RuleMatch::Domain && r.values.len() == 1 && routing::normalize_values(RuleMatch::Domain, &host).ok().is_some_and(|v| v == r.values));
        Ok(json!({
            "host": host,
            "outcome": outcome,
            "reason": reason,
            "server": server,
            "rule": rule.map(|r| rule_json(r, now)),
            "siteRule": exact.map(|r| rule_json(r, now)),
            "connected": matches!(self.session.state(), SessionState::Connected { .. }),
        }))
    }

    /// Replaces the user's own rules (recipes, rule import). Scenario rules are kept.
    pub fn replace_rules(&mut self, rules: Vec<RuleInput>, mode: Option<RoutingMode>, append: bool) -> Result<usize, CoreError> {
        if rules.len() > routing::MAX_RULES {
            return Err(invalid(format!("At most {} rules", routing::MAX_RULES)));
        }
        let built: Vec<SiteRule> = rules.iter().map(|r| self.build_rule(r, None)).collect::<Result<_, _>>()?;
        let n = built.len();
        self.bump(|st| {
            if !append {
                st.rules.retain(|r| r.scenario.is_some());
            }
            for r in built {
                let dup = st.rules.iter().any(|x| x.match_type == r.match_type && x.values == r.values && x.action == r.action);
                if !dup && st.rules.len() < routing::MAX_RULES {
                    st.rules.push(r);
                }
            }
            if let Some(m) = mode {
                st.settings.routing_mode = m;
            }
            Ok(())
        })?;
        Ok(n)
    }

    // ------------------------------------------------------------ scenarios and profiles

    pub fn apply_scenario(&mut self, id: &str) -> Result<(), CoreError> {
        let sc = SCENARIOS.iter().find(|s| s.id == id).ok_or_else(|| not_found("Preset"))?;
        let rules: Vec<SiteRule> = sc
            .rules
            .iter()
            .map(|(name, m, text, action)| SiteRule {
                id: uuid::Uuid::new_v4().to_string(),
                name: (*name).into(),
                match_type: *m,
                values: routing::normalize_values(*m, text).unwrap_or_default(),
                action: *action,
                server_id: None,
                enabled: true,
                expires_at: None,
                scenario: Some(sc.id.into()),
                created_at: store::now(),
            })
            .collect();
        self.bump(|st| {
            st.rules.retain(|r| r.scenario.is_none());
            for (i, r) in rules.into_iter().enumerate() {
                st.rules.insert(i, r);
            }
            st.settings.kill_switch = sc.kill_switch;
            st.settings.auto_failover = sc.auto_failover;
            st.settings.fastest_on_connect = sc.fastest_on_connect;
            st.active_scenario = Some(sc.id.into());
            Ok(())
        })
    }

    pub fn clear_scenario(&mut self) -> Result<(), CoreError> {
        self.bump(|st| {
            st.rules.retain(|r| r.scenario.is_none());
            st.active_scenario = None;
            Ok(())
        })
    }

    pub fn profiles(&self) -> Result<Value, CoreError> {
        let st = self.store.state()?;
        Ok(json!({
            "profiles": st.profiles.iter().map(|p| json!({ "id": p.id, "name": p.name, "serverId": p.server_id, "routingMode": p.routing_mode, "rules": p.rules.len(), "createdAt": p.created_at })).collect::<Vec<_>>(),
            "activeProfileId": st.active_profile_id,
        }))
    }

    pub fn save_profile(&mut self, name: &str) -> Result<Value, CoreError> {
        let name = crate::core::validate::clean_name(name, "");
        if name.is_empty() {
            return Err(invalid("Name the profile"));
        }
        let active = self.session.state().active_profile_id().map(str::to_string);
        let id = uuid::Uuid::new_v4().to_string();
        let out = id.clone();
        self.store.update_state(|st| {
            let profile = Profile {
                id: id.clone(),
                name: name.clone(),
                server_id: active.or_else(|| st.selected_server_id.clone()),
                routing_mode: st.settings.routing_mode,
                local_names_direct: st.settings.local_names_direct,
                rules: st.rules.iter().filter(|r| r.scenario.is_none() && r.expires_at.is_none()).cloned().collect(),
                created_at: store::now(),
            };
            match st.profiles.iter_mut().find(|p| p.name.eq_ignore_ascii_case(&name)) {
                Some(p) => {
                    let keep = p.id.clone();
                    *p = Profile { id: keep.clone(), ..profile };
                    st.active_profile_id = Some(keep);
                }
                None => {
                    if st.profiles.len() >= store::MAX_PROFILES {
                        return Err(StoreError::Invalid(format!("At most {} profiles", store::MAX_PROFILES)));
                    }
                    st.profiles.push(profile);
                    st.active_profile_id = Some(id.clone());
                }
            }
            Ok(())
        })?;
        Ok(json!({ "id": out }))
    }

    /// Switches to a profile: its rules and routing mode, and its server (reconnecting if connected).
    pub fn apply_profile(&mut self, id: &str) -> Result<(), CoreError> {
        valid_id(id)?;
        let p = self.store.state()?.profiles.into_iter().find(|p| p.id == id).ok_or_else(|| not_found("Profile"))?;
        let server = p.server_id.clone();
        self.bump(|st| {
            let scenario: Vec<SiteRule> = st.rules.iter().filter(|r| r.scenario.is_some()).cloned().collect();
            st.rules = scenario.into_iter().chain(p.rules.iter().cloned()).collect();
            st.settings.routing_mode = p.routing_mode;
            st.settings.local_names_direct = p.local_names_direct;
            st.active_profile_id = Some(p.id.clone());
            if let Some(s) = &server {
                if st.servers.iter().any(|m| &m.id == s) {
                    st.selected_server_id = Some(s.clone());
                }
            }
            Ok(())
        })?;
        let connected = self.session.state().active_profile_id().map(str::to_string);
        if let (Some(s), Some(c)) = (server, connected) {
            if s != c && self.store.state()?.servers.iter().any(|m| m.id == s) {
                self.start_session(&s)?;
            }
        }
        Ok(())
    }

    pub fn delete_profile(&mut self, id: &str) -> Result<(), CoreError> {
        valid_id(id)?;
        self.store.update_state(|st| {
            let before = st.profiles.len();
            st.profiles.retain(|p| p.id != id);
            if before == st.profiles.len() {
                return Err(StoreError::NotFound);
            }
            if st.active_profile_id.as_deref() == Some(id) {
                st.active_profile_id = None;
            }
            Ok(())
        })?;
        Ok(())
    }

    // ------------------------------------------------------------ backup

    pub fn export_backup(&self, password: Option<&str>, sections: Sections) -> Result<Value, CoreError> {
        if !(sections.servers || sections.rules || sections.settings) {
            return Err(invalid("Choose at least one part to back up"));
        }
        let (st, sec) = self.store.export_all()?;
        let text = backup::encode(&backup::collect(&st, &sec, sections), password).map_err(invalid)?;
        let (y, m, d) = ymd((store::now() / 86_400) as i64);
        Ok(json!({ "text": text, "encrypted": password.is_some_and(|p| !p.is_empty()), "fileName": format!("perroute-browser-{y:04}-{m:02}-{d:02}.json") }))
    }

    pub fn preview_backup(&self, text: &str, password: Option<&str>) -> Result<Value, CoreError> {
        let (p, _) = backup::decode(text, password).map_err(|m| CoreError::new(ErrorKind::InvalidProfile, m))?;
        Ok(serde_json::to_value(p).unwrap_or(Value::Null))
    }

    /// Restores a backup after keeping the current data as an encrypted snapshot (for Undo).
    pub fn restore_backup(&mut self, text: &str, password: Option<&str>, mode: RestoreMode) -> Result<Value, CoreError> {
        let (_, data) = backup::decode(text, password).map_err(|m| CoreError::new(ErrorKind::InvalidProfile, m))?;
        let (st, sec) = self.store.export_all()?;
        let snapshot = backup::encode(&backup::collect(&st, &sec, Sections { servers: true, rules: true, settings: true }), None).map_err(invalid)?;
        self.store.write_sealed(backup::SNAPSHOT, snapshot.as_bytes())?;
        let active = self.session.state().active_profile_id().map(str::to_string);
        if mode == RestoreMode::Replace && active.is_some() {
            self.stop_session();
        }
        let report = backup::apply(&self.store, data, mode)?;
        self.events.push(CoreEvent::DataChanged);
        Ok(serde_json::to_value(report).unwrap_or(Value::Null))
    }

    pub fn can_undo_restore(&self) -> bool {
        self.store.has_aux(backup::SNAPSHOT)
    }

    pub fn undo_restore(&mut self) -> Result<(), CoreError> {
        let bytes = self.store.read_sealed(backup::SNAPSHOT)?.ok_or_else(|| invalid("There is no restore to undo"))?;
        let text = String::from_utf8(bytes).map_err(|_| invalid("The snapshot is damaged"))?;
        let (_, data) = backup::decode(&text, None).map_err(invalid)?;
        self.stop_session();
        backup::apply(&self.store, data, RestoreMode::Replace)?;
        self.store.remove_aux(backup::SNAPSHOT);
        self.events.push(CoreEvent::DataChanged);
        Ok(())
    }

    // ------------------------------------------------------------ doctor

    /// Connection doctor: what works, what does not, and what to do about it. Runs the slow
    /// checks (live route, server port, exit address) in the background.
    pub fn doctor(&mut self, host: Option<&str>, ticket: Ticket) -> Result<(), CoreError> {
        let st = self.store.state()?;
        let mut checks: Vec<Value> = Vec::new();
        let check = |id: &str, status: &str, title: &str, detail: String, repair: Option<&str>| json!({ "id": id, "status": status, "title": title, "detail": detail, "repair": repair });
        checks.push(match &self.xray_version {
            Some(v) => check("runtime", "ok", "Runtime", format!("Xray {v} is installed and verified."), None),
            None => check("runtime", "fail", "Runtime", "Xray is missing or does not match the pinned release. Reinstall PerRoute's browser runtime.".into(), None),
        });
        checks.push(match crate::platform::harden::verify_private_dir(self.store.dir()) {
            Ok(()) => check("storage", "ok", "Protected storage", "Servers and credentials are stored encrypted in a private folder.".into(), None),
            Err(e) => check("storage", "fail", "Protected storage", format!("The data folder is not private: {e}"), None),
        });
        if st.servers.is_empty() {
            checks.push(check("servers", "fail", "Servers", "No servers yet. Add a subscription or paste a link.".into(), Some("addServers")));
        }
        let state = self.session.state().clone();
        let active = state.active_profile_id().map(str::to_string);
        match &state {
            SessionState::Connected { .. } => {}
            SessionState::Failed { error, .. } => checks.push(check("session", "fail", "Connection", error.message.clone(), Some("reconnect"))),
            SessionState::Starting { .. } => checks.push(check("session", "warn", "Connection", "Still connecting.".into(), None)),
            _ => checks.push(check("session", "warn", "Connection", "Not connected.".into(), Some("connect"))),
        }
        if let Some(h) = host.filter(|h| !h.trim().is_empty()) {
            if let Ok(v) = self.evaluate(h) {
                let outcome = v["outcome"].as_str().unwrap_or("");
                let status = if outcome == "block" { "warn" } else { "ok" };
                let text = match outcome {
                    "block" => format!("{} is blocked by {}.", v["host"].as_str().unwrap_or(h), v["reason"].as_str().unwrap_or("a rule")),
                    "direct" => format!("{} opens directly ({}).", v["host"].as_str().unwrap_or(h), v["reason"].as_str().unwrap_or("")),
                    _ => format!("{} uses the tunnel ({}).", v["host"].as_str().unwrap_or(h), v["reason"].as_str().unwrap_or("")),
                };
                let repair = (outcome == "block").then_some("openRules");
                checks.push(check("site", status, "This site", text, repair));
            }
        }
        let live = self.live_endpoint();
        let server = active.as_deref().and_then(|id| self.store.server_with_secrets(id).ok());
        let post = self.post.clone();
        let probe_timeout = self.timing.probe_timeout;
        std::thread::spawn(move || {
            let mut checks = checks;
            let check = |id: &str, status: &str, title: &str, detail: String, repair: Option<&str>| json!({ "id": id, "status": status, "title": title, "detail": detail, "repair": repair });
            let mut samples = Vec::new();
            if let Some((m, _)) = &server {
                checks.push(match tester::tcp_ping(m, Duration::from_secs(4)) {
                    Ok(ms) => check("server", "ok", "Server", format!("{} answers in {ms} ms.", m.name), None),
                    Err(e) => check("server", "fail", "Server", format!("{}: {e}.", m.name), Some("switchFastest")),
                });
            }
            if let Some(ep) = &live {
                match tester::real_delay(ep, probe_timeout) {
                    Ok(ms) => {
                        checks.push(check("route", "ok", "Live route", format!("Pages open through the tunnel ({ms} ms)."), None));
                        if let Some((m, _)) = &server {
                            samples.push((m.id.clone(), Sample::new(now_ms(), SampleKind::Tunnel, true, ms as i64)));
                        }
                    }
                    Err(e) => checks.push(check("route", "fail", "Live route", format!("Requests through the tunnel fail: {e}."), Some("switchFastest"))),
                }
                checks.push(match tester::exit_info(ep) {
                    Ok(i) => check("exit", "ok", "Exit address", format!("Sites see {} ({}).", i.ip, if i.country.is_empty() { "unknown location".into() } else { i.country.clone() }), None),
                    Err(e) => check("exit", "warn", "Exit address", e, None),
                });
            }
            post(CoreMsg::JobDone { ticket, result: Ok(json!({ "checks": checks })), samples });
        });
        Ok(())
    }
}

/// Runs `f` over `items` with at most [`TEST_PARALLEL`] at a time, keeping the input order.
fn run_parallel<T: Send + 'static, R: Send + 'static>(items: Vec<T>, f: impl Fn(T) -> R + Send + Sync + 'static) -> Vec<R> {
    let f = Arc::new(f);
    let queue = Arc::new(std::sync::Mutex::new(items.into_iter().enumerate().collect::<Vec<_>>()));
    let results = Arc::new(std::sync::Mutex::new(Vec::<(usize, R)>::new()));
    let workers: Vec<_> = (0..TEST_PARALLEL)
        .map(|_| {
            let (f, queue, results) = (f.clone(), queue.clone(), results.clone());
            std::thread::spawn(move || loop {
                let next = queue.lock().unwrap_or_else(|e| e.into_inner()).pop();
                let Some((i, item)) = next else { break };
                let r = f(item);
                results.lock().unwrap_or_else(|e| e.into_inner()).push((i, r));
            })
        })
        .collect();
    for w in workers {
        let _ = w.join();
    }
    let mut v = std::mem::take(&mut *results.lock().unwrap_or_else(|e| e.into_inner()));
    v.sort_by_key(|(i, _)| *i);
    v.into_iter().map(|(_, r)| r).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stats_output_is_summed_per_route() {
        let out = r#"{"stat":[
            {"name":"outbound>>>proxy>>>traffic>>>uplink","value":"100"},
            {"name":"outbound>>>proxy-1>>>traffic>>>downlink","value":250},
            {"name":"outbound>>>proxy>>>traffic>>>downlink","value":"50"},
            {"name":"outbound>>>direct>>>traffic>>>downlink","value":"7"},
            {"name":"outbound>>>api>>>traffic>>>downlink","value":"9"},
            {"name":"outbound>>>block>>>traffic>>>uplink"}
        ]}"#;
        assert_eq!(parse_stats(out), Some([100, 300, 0, 7]));
        assert_eq!(parse_stats("{}"), Some([0; 4]));
        assert_eq!(parse_stats("not json"), None);
    }

    #[test]
    fn civil_dates() {
        assert_eq!(ymd(0), (1970, 1, 1));
        assert_eq!(ymd(20_745), (2026, 10, 19));
        assert_eq!(ymd(11_016), (2000, 2, 29));
    }

    #[test]
    fn parallel_keeps_order() {
        let r = run_parallel((0..20).collect(), |i: i32| {
            std::thread::sleep(Duration::from_millis((20 - i) as u64));
            i * 2
        });
        assert_eq!(r, (0..20).map(|i| i * 2).collect::<Vec<_>>());
    }
}
