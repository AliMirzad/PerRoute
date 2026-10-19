//! Generates the Xray runtime configuration from the normalized model.
//!
//! Invariants enforced here (and asserted in tests):
//! * every inbound listens on 127.0.0.1 only;
//! * no access log (no browsing history on disk);
//! * `domainStrategy: AsIs` and IP-literal-only routing rules, so the client never resolves
//!   destination hostnames locally; the remote server resolves them;
//! * no file paths, sockopt or other fields that reach outside the proxy data path. The only API
//!   is the read-only traffic counter service, on a loopback port, when stats are requested.

use crate::core::credentials::ProxyCredentials;
use crate::core::profile::*;
use crate::core::routing::{self, RoutingMode, RuleAction, SiteRule};
use serde_json::{json, Map, Value};

pub const LOOPBACK: &str = "127.0.0.1";

/// Private / special-purpose ranges that bypass the tunnel. Matching is on IP literals only.
pub const PRIVATE_CIDRS: &[&str] = &[
    "0.0.0.0/8",
    "10.0.0.0/8",
    "100.64.0.0/10",
    "127.0.0.0/8",
    "169.254.0.0/16",
    "172.16.0.0/12",
    "192.168.0.0/16",
    "::1/128",
    "fc00::/7",
    "fe80::/10",
];

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct JetbrainsPorts {
    pub socks: Option<u16>,
    pub http: Option<u16>,
}

#[derive(Debug, Clone)]
pub struct RuntimePlan {
    /// Browser-facing HTTP proxy inbound; `None` in passthrough mode. It requires
    /// `browser_auth` (per-connection random credentials that the extension answers proxy
    /// challenges with), so other local processes and users cannot use the tunnel.
    pub browser_port: Option<u16>,
    pub browser_auth: Option<ProxyCredentials>,
    pub jetbrains: JetbrainsPorts,
    /// `Some`: the IDE HTTP and SOCKS inbounds require these credentials.
    pub ide_auth: Option<ProxyCredentials>,
    pub log_level: &'static str,
    /// Loopback port of the traffic counter API (StatsService only), if enabled.
    pub stats_port: Option<u16>,
}

/// How a tunnel routes: the routing mode, the active site rules (in order) and the other servers
/// that Proxy rules point at.
#[derive(Clone, Default)]
pub struct RoutingPlan {
    pub mode: RoutingMode,
    pub local_names_direct: bool,
    pub rules: Vec<SiteRule>,
    pub servers: Vec<(ServerMeta, ServerSecrets)>,
}

impl std::fmt::Debug for RoutingPlan {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "RoutingPlan {{ mode: {:?}, rules: {}, servers: {} }}", self.mode, self.rules.len(), self.servers.len())
    }
}

fn socks_inbound(tag: &str, port: u16, auth: Option<&ProxyCredentials>) -> Value {
    let settings = match auth {
        Some(a) => json!({ "auth": "password", "accounts": [{ "user": a.user, "pass": a.pass }], "udp": false }),
        None => json!({ "auth": "noauth", "udp": false }),
    };
    json!({ "tag": tag, "listen": LOOPBACK, "port": port, "protocol": "socks", "settings": settings })
}

fn http_inbound(tag: &str, port: u16, auth: Option<&ProxyCredentials>) -> Value {
    let settings = match auth {
        Some(a) => json!({ "accounts": [{ "user": a.user, "pass": a.pass }], "allowTransparent": false }),
        None => json!({ "allowTransparent": false }),
    };
    json!({ "tag": tag, "listen": LOOPBACK, "port": port, "protocol": "http", "settings": settings })
}

fn inbounds(plan: &RuntimePlan) -> Vec<Value> {
    let mut v = Vec::new();
    if let Some(p) = plan.browser_port {
        v.push(http_inbound("browser-http", p, plan.browser_auth.as_ref()));
    }
    if let Some(p) = plan.jetbrains.socks {
        v.push(socks_inbound("ide-socks", p, plan.ide_auth.as_ref()));
    }
    if let Some(p) = plan.jetbrains.http {
        v.push(http_inbound("ide-http", p, plan.ide_auth.as_ref()));
    }
    v
}

/// Ports of our own inbounds, as an Xray port list ("a,b,c").
fn own_ports(plan: &RuntimePlan) -> Option<String> {
    let ports: Vec<String> = [plan.browser_port, plan.jetbrains.socks, plan.jetbrains.http, plan.stats_port].into_iter().flatten().map(|p| p.to_string()).collect();
    (!ports.is_empty()).then(|| ports.join(","))
}

/// Read-only traffic counters: Xray's StatsService on a loopback port, nothing else of the API.
fn add_stats(cfg: &mut Value, plan: &RuntimePlan) {
    let Some(port) = plan.stats_port else { return };
    cfg["stats"] = json!({});
    cfg["api"] = json!({ "tag": "api", "services": ["StatsService"] });
    cfg["policy"] = json!({ "system": { "statsOutboundUplink": true, "statsOutboundDownlink": true } });
    if let Some(inb) = cfg["inbounds"].as_array_mut() {
        inb.push(json!({ "tag": "api", "listen": LOOPBACK, "port": port, "protocol": "dokodemo-door", "settings": { "address": LOOPBACK } }));
    }
    if let Some(rules) = cfg["routing"]["rules"].as_array_mut() {
        rules.insert(0, json!({ "type": "field", "inboundTag": ["api"], "outboundTag": "api" }));
    }
}

/// Blocks requests aimed back at our own inbounds (e.g. a web page or local program sending
/// `GET http://127.0.0.1:10809/` to the HTTP inbound). Without this, Xray would proxy the
/// request to itself recursively until it runs out of connections.
fn self_loop_rules(plan: &RuntimePlan) -> Vec<Value> {
    match own_ports(plan) {
        None => vec![],
        Some(ports) => vec![
            json!({ "type": "field", "ip": ["127.0.0.0/8", "::1/128"], "port": ports, "outboundTag": "block" }),
            json!({ "type": "field", "domain": ["full:localhost"], "port": ports, "outboundTag": "block" }),
        ],
    }
}

fn log(plan: &RuntimePlan) -> Value {
    json!({ "loglevel": plan.log_level, "access": "none", "dnsLog": false })
}

/// Tunnel mode: every inbound goes to the selected server, except private IP literals.
pub fn tunnel_config(meta: &ServerMeta, secrets: &ServerSecrets, plan: &RuntimePlan) -> Value {
    routed_config(meta, secrets, &RoutingPlan::default(), plan)
}

/// Tunnel with site rules. The main server is the first outbound (Xray's default route); each
/// other server used by a rule gets its own outbound. Order of the routing rules: our own ports
/// blocked, local addresses direct, local names direct (if enabled), the site rules in order,
/// then the routing mode decides the rest.
pub fn routed_config(meta: &ServerMeta, secrets: &ServerSecrets, routing: &RoutingPlan, plan: &RuntimePlan) -> Value {
    let mut rules = self_loop_rules(plan);
    rules.push(json!({ "type": "field", "domain": ["full:localhost"], "outboundTag": "direct" }));
    rules.push(json!({ "type": "field", "ip": PRIVATE_CIDRS, "outboundTag": "direct" }));
    if routing.local_names_direct {
        rules.push(json!({ "type": "field", "domain": routing::LOCAL_NAME_RULES, "outboundTag": "direct" }));
    }
    let mut outbounds = vec![tagged(proxy_outbound(meta, secrets), "proxy")];
    for (i, (m, s)) in routing.servers.iter().enumerate() {
        outbounds.push(tagged(proxy_outbound(m, s), &format!("proxy-{}", i + 1)));
    }
    for r in &routing.rules {
        let tag = match r.action {
            RuleAction::Direct => "direct".to_string(),
            RuleAction::Block => "block".to_string(),
            RuleAction::Proxy => r
                .server_id
                .as_ref()
                .and_then(|id| routing.servers.iter().position(|(m, _)| &m.id == id))
                .map(|i| format!("proxy-{}", i + 1))
                .unwrap_or_else(|| "proxy".into()),
        };
        let mut rule = routing::xray_matchers(r);
        rule["type"] = json!("field");
        rule["outboundTag"] = json!(tag);
        rules.push(rule);
    }
    if routing.mode == RoutingMode::Selected {
        rules.push(json!({ "type": "field", "network": "tcp,udp", "outboundTag": "direct" }));
    }
    outbounds.push(json!({ "tag": "direct", "protocol": "freedom", "settings": {} }));
    outbounds.push(json!({ "tag": "block", "protocol": "blackhole", "settings": {} }));
    let mut cfg = json!({
        "log": log(plan),
        "inbounds": inbounds(plan),
        "outbounds": outbounds,
        "routing": { "domainStrategy": "AsIs", "rules": rules }
    });
    add_stats(&mut cfg, plan);
    cfg
}

/// One server behind one authenticated loopback HTTP inbound, nothing else: used to test a
/// server without touching the live tunnel.
pub fn test_config(meta: &ServerMeta, secrets: &ServerSecrets, port: u16, auth: &ProxyCredentials) -> Value {
    let plan = RuntimePlan {
        browser_port: Some(port),
        browser_auth: Some(auth.clone()),
        jetbrains: JetbrainsPorts { socks: None, http: None },
        ide_auth: None,
        log_level: "warning",
        stats_port: None,
    };
    let mut rules = self_loop_rules(&plan);
    rules.push(json!({ "type": "field", "ip": PRIVATE_CIDRS, "outboundTag": "block" }));
    json!({
        "log": log(&plan),
        "inbounds": inbounds(&plan),
        "outbounds": [
            tagged(proxy_outbound(meta, secrets), "proxy"),
            { "tag": "block", "protocol": "blackhole", "settings": {} }
        ],
        "routing": { "domainStrategy": "AsIs", "rules": rules }
    })
}

fn tagged(mut outbound: Value, tag: &str) -> Value {
    outbound["tag"] = json!(tag);
    outbound
}

/// Passthrough mode (tunnel disconnected): the JetBrains endpoint stays up and connects directly.
pub fn passthrough_config(plan: &RuntimePlan) -> Value {
    json!({
        "log": log(plan),
        "inbounds": inbounds(plan),
        "outbounds": [
            { "tag": "direct", "protocol": "freedom", "settings": {} },
            { "tag": "block", "protocol": "blackhole", "settings": {} }
        ],
        "routing": { "domainStrategy": "AsIs", "rules": self_loop_rules(plan) }
    })
}

pub fn proxy_outbound(meta: &ServerMeta, secrets: &ServerSecrets) -> Value {
    let settings = match meta.protocol {
        Protocol::Vless => {
            let mut user = Map::new();
            user.insert("id".into(), secrets.user_id.clone().into());
            user.insert(
                "encryption".into(),
                secrets.vless_encryption.clone().unwrap_or_else(|| "none".into()).into(),
            );
            if !meta.flow.is_empty() {
                user.insert("flow".into(), meta.flow.clone().into());
            }
            json!({ "vnext": [ { "address": meta.address, "port": meta.port, "users": [ Value::Object(user) ] } ] })
        }
        Protocol::Vmess => json!({
            "vnext": [ {
                "address": meta.address,
                "port": meta.port,
                "users": [ {
                    "id": secrets.user_id,
                    "security": meta.vmess_cipher.clone().unwrap_or_else(|| "auto".into())
                } ]
            } ]
        }),
        Protocol::Trojan => json!({
            "servers": [ { "address": meta.address, "port": meta.port, "password": secrets.password.clone().unwrap_or_default() } ]
        }),
        Protocol::Shadowsocks => json!({
            "servers": [ {
                "address": meta.address,
                "port": meta.port,
                "method": meta.ss_method.clone().unwrap_or_default(),
                "password": secrets.password.clone().unwrap_or_default()
            } ]
        }),
    };
    json!({
        "tag": "proxy",
        "protocol": meta.protocol.as_str(),
        "settings": settings,
        "streamSettings": stream_settings(meta, secrets)
    })
}

fn put_opt(m: &mut Map<String, Value>, k: &str, v: &Option<String>) {
    if let Some(v) = v {
        m.insert(k.into(), Value::String(v.clone()));
    }
}

fn stream_settings(meta: &ServerMeta, secrets: &ServerSecrets) -> Value {
    let mut ss = Map::new();
    ss.insert("network".into(), meta.transport.name().into());
    ss.insert("security".into(), meta.security.name().into());
    match &meta.transport {
        Transport::Raw { header } => {
            if let RawHeader::Http { host, path } = header {
                let mut req = json!({ "path": if path.is_empty() { vec!["/".to_string()] } else { path.clone() } });
                if !host.is_empty() {
                    req["headers"] = json!({ "Host": host });
                }
                ss.insert("rawSettings".into(), json!({ "header": { "type": "http", "request": req } }));
            }
        }
        Transport::Ws { path, host } => {
            let mut w = Map::new();
            w.insert("path".into(), path.clone().into());
            put_opt(&mut w, "host", host);
            ss.insert("wsSettings".into(), Value::Object(w));
        }
        Transport::Grpc { service_name, authority, multi_mode } => {
            let mut g = Map::new();
            g.insert("serviceName".into(), service_name.clone().into());
            put_opt(&mut g, "authority", authority);
            if *multi_mode {
                g.insert("multiMode".into(), true.into());
            }
            ss.insert("grpcSettings".into(), Value::Object(g));
        }
        Transport::Httpupgrade { path, host } => {
            let mut h = Map::new();
            h.insert("path".into(), path.clone().into());
            put_opt(&mut h, "host", host);
            ss.insert("httpupgradeSettings".into(), Value::Object(h));
        }
        Transport::Xhttp { path, host, mode, extra } => {
            let mut x = Map::new();
            x.insert("path".into(), path.clone().into());
            put_opt(&mut x, "host", host);
            x.insert("mode".into(), mode.clone().into());
            if let Some(e) = extra {
                x.insert("extra".into(), e.clone());
            }
            ss.insert("xhttpSettings".into(), Value::Object(x));
        }
    }
    match &meta.security {
        Security::None => {}
        Security::Tls { server_name, alpn, fingerprint, pinned_peer_cert_sha256, ech_config_list } => {
            let mut t = Map::new();
            put_opt(&mut t, "serverName", server_name);
            if !alpn.is_empty() {
                t.insert("alpn".into(), alpn.clone().into());
            }
            put_opt(&mut t, "fingerprint", fingerprint);
            put_opt(&mut t, "pinnedPeerCertSha256", pinned_peer_cert_sha256);
            put_opt(&mut t, "echConfigList", ech_config_list);
            ss.insert("tlsSettings".into(), Value::Object(t));
        }
        Security::Reality { server_name, fingerprint, spider_x, mldsa65_verify } => {
            let mut r = Map::new();
            r.insert("serverName".into(), server_name.clone().into());
            r.insert("fingerprint".into(), fingerprint.clone().into());
            put_opt(&mut r, "password", &secrets.reality_password);
            r.insert("shortId".into(), secrets.reality_short_id.clone().unwrap_or_default().into());
            put_opt(&mut r, "spiderX", spider_x);
            put_opt(&mut r, "mldsa65Verify", mldsa65_verify);
            ss.insert("realitySettings".into(), Value::Object(r));
        }
    }
    Value::Object(ss)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::import::parse_link;

    fn plan() -> RuntimePlan {
        RuntimePlan {
            browser_port: Some(50000),
            jetbrains: JetbrainsPorts { socks: Some(10808), http: Some(10809) },
            ide_auth: None,
            browser_auth: Some(ProxyCredentials { user: "b".into(), pass: "p".into() }),
            log_level: "warning",
            stats_port: None,
        }
    }

    fn all_listen_loopback(cfg: &Value) -> bool {
        cfg["inbounds"].as_array().unwrap().iter().all(|i| i["listen"] == LOOPBACK)
    }

    #[test]
    fn reality_vision_config() {
        let p = parse_link("vless://b831381d-6324-4d53-ad4f-8cda48b30811@1.2.3.4:443?security=reality&sni=www.microsoft.com&fp=chrome&pbk=OFAMcMJ-9uns7MO5APwkUr8PfvouYrl-t8s7UIEn9mI&sid=ab12&type=tcp&flow=xtls-rprx-vision#DE").unwrap();
        let cfg = tunnel_config(&p.meta, &p.secrets, &plan());
        let out = &cfg["outbounds"][0];
        assert_eq!(out["protocol"], "vless");
        assert_eq!(out["settings"]["vnext"][0]["users"][0]["flow"], "xtls-rprx-vision");
        assert_eq!(out["settings"]["vnext"][0]["users"][0]["encryption"], "none");
        let rs = &out["streamSettings"]["realitySettings"];
        assert_eq!(rs["password"], "OFAMcMJ-9uns7MO5APwkUr8PfvouYrl-t8s7UIEn9mI");
        assert_eq!(rs["shortId"], "ab12");
        assert_eq!(out["streamSettings"]["network"], "raw");
        assert!(all_listen_loopback(&cfg));
        assert_eq!(cfg["log"]["access"], "none");
        assert_eq!(cfg["routing"]["domainStrategy"], "AsIs");
        assert!(cfg.get("dns").is_none());
        // self-loop protection comes first and covers all our inbound ports
        assert_eq!(cfg["routing"]["rules"][0]["outboundTag"], "block");
        assert_eq!(cfg["routing"]["rules"][0]["port"], "50000,10808,10809");
    }

    #[test]
    fn generation_is_deterministic_and_every_listener_is_authenticated_on_loopback() {
        let p = parse_link("vless://b831381d-6324-4d53-ad4f-8cda48b30811@h.example.com:443?security=tls&type=ws&path=%2Fws&sni=h.example.com#a").unwrap();
        let mut pl = plan();
        pl.ide_auth = Some(ProxyCredentials { user: "perroute".into(), pass: "idepass".into() });
        let a = serde_json::to_vec(&tunnel_config(&p.meta, &p.secrets, &pl)).unwrap();
        let b = serde_json::to_vec(&tunnel_config(&p.meta, &p.secrets, &pl)).unwrap();
        assert_eq!(a, b, "same profile + plan must give byte-identical config");
        let cfg: Value = serde_json::from_slice(&a).unwrap();
        let inbounds = cfg["inbounds"].as_array().unwrap();
        assert_eq!(inbounds.len(), 3, "browser + IDE HTTP + IDE SOCKS");
        assert!(all_listen_loopback(&cfg));
        for i in inbounds {
            let s = &i["settings"];
            let open = match i["protocol"].as_str() {
                Some("http") => s["allowTransparent"] != false,
                Some("socks") => s["auth"] != "password" || s["udp"] != false,
                p => panic!("unexpected inbound protocol {p:?}"),
            };
            assert!(!open && s["accounts"].as_array().is_some_and(|a| a.len() == 1), "unauthenticated inbound: {i}");
        }
        // Only the generator's own sections: no API, stats, DNS, reverse or observatory.
        for section in ["api", "stats", "dns", "reverse", "observatory", "policy"] {
            assert!(cfg.get(section).is_none(), "{section}");
        }
    }

    #[test]
    fn passthrough_has_only_direct() {
        let mut pl = plan();
        pl.browser_port = None;
        let cfg = passthrough_config(&pl);
        assert_eq!(cfg["outbounds"][0]["protocol"], "freedom");
        assert!(cfg["outbounds"].as_array().unwrap().iter().all(|o| o["protocol"] == "freedom" || o["protocol"] == "blackhole"));
        assert_eq!(cfg["routing"]["rules"][0]["outboundTag"], "block");
        assert_eq!(cfg["routing"]["rules"][0]["port"], "10808,10809");
        assert_eq!(cfg["inbounds"].as_array().unwrap().len(), 2);
        assert!(all_listen_loopback(&cfg));
    }

    fn site_rule(text: &str, action: RuleAction, server_id: Option<&str>) -> SiteRule {
        SiteRule {
            id: "r".into(),
            name: "r".into(),
            match_type: routing::RuleMatch::Domain,
            values: routing::normalize_values(routing::RuleMatch::Domain, text).unwrap(),
            action,
            server_id: server_id.map(Into::into),
            enabled: true,
            expires_at: None,
            scenario: None,
            created_at: 0,
        }
    }

    #[test]
    fn site_rules_route_to_their_outbounds_in_order() {
        let main = parse_link("vless://b831381d-6324-4d53-ad4f-8cda48b30811@h.example.com:443?security=tls#a").unwrap();
        let mut other = parse_link("trojan://secret@t.example.com:443?sni=t.example.com#t").unwrap();
        other.meta.id = "other".into();
        let routing = RoutingPlan {
            mode: RoutingMode::Selected,
            local_names_direct: true,
            rules: vec![
                site_rule("youtube.com", RuleAction::Proxy, Some("other")),
                site_rule("bank.example", RuleAction::Direct, None),
                site_rule("ads.example", RuleAction::Block, None),
                site_rule("news.example", RuleAction::Proxy, Some("deleted")),
            ],
            servers: vec![(other.meta.clone(), other.secrets.clone())],
        };
        let cfg = routed_config(&main.meta, &main.secrets, &routing, &plan());
        let outs = cfg["outbounds"].as_array().unwrap();
        assert_eq!(outs.iter().map(|o| o["tag"].as_str().unwrap()).collect::<Vec<_>>(), ["proxy", "proxy-1", "direct", "block"]);
        assert_eq!(outs[1]["protocol"], "trojan");
        assert_eq!(outs[1]["settings"]["servers"][0]["password"], "secret");
        assert_eq!(outs[1]["streamSettings"]["security"], "tls");
        let rules = cfg["routing"]["rules"].as_array().unwrap();
        let by_domain = |d: &str| rules.iter().find(|r| r["domain"].as_array().is_some_and(|a| a.iter().any(|x| x == d))).unwrap()["outboundTag"].clone();
        assert_eq!(by_domain("domain:youtube.com"), "proxy-1");
        assert_eq!(by_domain("domain:bank.example"), "direct");
        assert_eq!(by_domain("domain:ads.example"), "block");
        assert_eq!(by_domain("domain:news.example"), "proxy", "a rule whose server was deleted uses the main server");
        assert_eq!(by_domain("domain:local"), "direct");
        assert_eq!(rules.last().unwrap()["outboundTag"], "direct", "selected mode: the rest goes direct");
        assert_eq!(rules[0]["outboundTag"], "block", "own ports come first");
        assert_eq!(cfg["routing"]["domainStrategy"], "AsIs");
        assert!(cfg.get("api").is_none());
    }

    #[test]
    fn stats_api_is_read_only_and_on_loopback() {
        let p = parse_link("ss://YWVzLTI1Ni1nY206cGFzcw@s.example.com:8388#s").unwrap();
        let mut pl = plan();
        pl.stats_port = Some(50001);
        let cfg = routed_config(&p.meta, &p.secrets, &RoutingPlan::default(), &pl);
        assert_eq!(cfg["api"]["services"], json!(["StatsService"]));
        assert_eq!(cfg["outbounds"][0]["settings"]["servers"][0]["method"], "aes-256-gcm");
        assert!(all_listen_loopback(&cfg));
        assert_eq!(cfg["routing"]["rules"][0]["inboundTag"], json!(["api"]));
        assert!(cfg["routing"]["rules"][1]["port"].as_str().unwrap().contains("50001"), "the API port is not reachable through the proxy");
        let t = test_config(&p.meta, &p.secrets, 50002, &ProxyCredentials { user: "u".into(), pass: "p".into() });
        assert_eq!(t["inbounds"].as_array().unwrap().len(), 1);
        assert!(all_listen_loopback(&t));
    }

    #[test]
    fn no_dangerous_fields() {
        let links = [
            "vless://b831381d-6324-4d53-ad4f-8cda48b30811@h.example.com:443?security=tls&type=ws&path=%2Fws&host=h.example.com&sni=h.example.com#a",
            "vless://b831381d-6324-4d53-ad4f-8cda48b30811@h.example.com:443?security=tls&type=grpc&serviceName=svc&mode=multi#b",
            "vless://b831381d-6324-4d53-ad4f-8cda48b30811@h.example.com:443?security=tls&type=xhttp&path=%2Fx&mode=packet-up#c",
        ];
        for l in links {
            let p = parse_link(l).unwrap();
            let s = tunnel_config(&p.meta, &p.secrets, &plan()).to_string();
            for bad in ["sockopt", "masterKeyLog", "certificateFile", "allowInsecure", "0.0.0.0\"", "\"api\"", "access.log"] {
                assert!(!s.contains(bad), "{bad} in {s}");
            }
        }
    }
}
