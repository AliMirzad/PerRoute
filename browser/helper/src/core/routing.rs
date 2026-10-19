//! Site rules: which destinations use the tunnel, go direct or are blocked.
//!
//! PerRoute routes applications; in the browser the unit is the site. A rule matches a list of
//! domains, IP ranges or ports and sends them to the main server, another server, directly, or
//! nowhere. Rules are checked in order and the first match wins. What does not match follows the
//! routing mode: everything through the tunnel, or only the selected sites.
//!
//! Rule values are untrusted input (typed by the user, pasted from a recipe or restored from a
//! backup). They are normalized here and turned into Xray rule fields only by
//! [`xray_matchers`], so nothing from a rule can add other keys to the generated config.

use serde::{Deserialize, Serialize};
use std::net::IpAddr;

pub const MAX_RULES: usize = 500;
pub const MAX_VALUES: usize = 1000;

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq, Default)]
#[serde(rename_all = "lowercase")]
pub enum RuleMatch {
    #[default]
    Domain,
    Ip,
    Port,
}

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq, Default)]
#[serde(rename_all = "lowercase")]
pub enum RuleAction {
    #[default]
    Proxy,
    Direct,
    Block,
}

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq, Default)]
#[serde(rename_all = "lowercase")]
pub enum RoutingMode {
    /// Everything uses the tunnel except what a rule sends elsewhere.
    #[default]
    All,
    /// Only sites with a Proxy rule use the tunnel; everything else goes direct.
    Selected,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SiteRule {
    pub id: String,
    pub name: String,
    #[serde(rename = "match")]
    pub match_type: RuleMatch,
    pub values: Vec<String>,
    pub action: RuleAction,
    /// Proxy rules only: a server other than the main one. Missing servers fall back to the main one.
    #[serde(default)]
    pub server_id: Option<String>,
    #[serde(default = "yes")]
    pub enabled: bool,
    /// Temporary rules end by themselves at this time (Unix seconds).
    #[serde(default)]
    pub expires_at: Option<u64>,
    /// Set for rules that came from a scenario, so the scenario can be removed again.
    #[serde(default)]
    pub scenario: Option<String>,
    #[serde(default)]
    pub created_at: u64,
}

fn yes() -> bool {
    true
}

impl SiteRule {
    pub fn active(&self, now: u64) -> bool {
        self.enabled && self.expires_at.is_none_or(|t| t > now)
    }
}

/// Local names that never leave the computer when "keep local names local" is on.
pub const LOCAL_NAME_RULES: &[&str] = &[
    "domain:local",
    "domain:lan",
    "domain:home.arpa",
    "domain:internal",
    "domain:localdomain",
    "domain:home",
    "regexp:^[^.]+$",
];

/// Normalizes the text of a rule into a list of values. Domains accept URLs, `*.example.com`,
/// `www.example.com` and Xray's `full:`, `domain:`, `keyword:` and `regexp:` forms.
pub fn normalize_values(kind: RuleMatch, text: &str) -> Result<Vec<String>, String> {
    let mut out: Vec<String> = Vec::new();
    for raw in text.split(|c: char| c == ',' || c.is_whitespace()).filter(|s| !s.is_empty()) {
        let v = match kind {
            RuleMatch::Domain => domain_entry(raw)?,
            RuleMatch::Ip => ip_entry(raw)?,
            RuleMatch::Port => port_entry(raw)?,
        };
        if !v.is_empty() && !out.contains(&v) {
            out.push(v);
        }
        if out.len() > MAX_VALUES {
            return Err(format!("A rule can have at most {MAX_VALUES} entries"));
        }
    }
    if out.is_empty() {
        return Err("The rule has no entries".into());
    }
    Ok(out)
}

/// Re-validates stored values (a backup or recipe may have been edited by hand).
pub fn revalidate(kind: RuleMatch, values: &[String]) -> Result<Vec<String>, String> {
    normalize_values(kind, &values.join(","))
}

fn domain_entry(raw: &str) -> Result<String, String> {
    let text = raw.trim().trim_matches(|c| matches!(c, '"' | '\'' | '<' | '>'));
    if text.is_empty() {
        return Ok(String::new());
    }
    if text.len() > 253 + 10 {
        return Err("A domain entry is too long".into());
    }
    if !text.contains("://") {
        if let Some((prefix, rest)) = text.split_once(':') {
            let rest = rest.trim();
            match prefix.to_ascii_lowercase().as_str() {
                "full" => return host(rest, false).map(|h| if h.is_empty() { h } else { format!("full:{h}") }),
                "domain" => return host(rest, true),
                "keyword" => {
                    if rest.is_empty() || rest.len() > 100 || !rest.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '.' | '_')) {
                        return Err(format!("Invalid keyword \"{}\"", crate::core::validate::truncate(rest, 40)));
                    }
                    return Ok(format!("keyword:{}", rest.to_ascii_lowercase()));
                }
                "regexp" => {
                    if rest.is_empty() || rest.len() > 200 || rest.chars().any(|c| c.is_control()) {
                        return Err("Invalid regular expression".into());
                    }
                    return Ok(format!("regexp:{rest}"));
                }
                "geosite" | "ext" | "geoip" => return Err("geosite/geoip lists are not available in the browser; list the domains instead".into()),
                _ => {}
            }
        }
    }
    host(text, true)
}

/// Host part of a URL or name, lowercased, IDN-encoded, without port, `*.`, `www.` (for whole
/// sites) or a trailing dot.
fn host(value: &str, whole_site: bool) -> Result<String, String> {
    let mut t = value.trim();
    if let Some((_, rest)) = t.split_once("://") {
        t = rest;
    }
    let end = t.find(['/', '?', '#', '\\']).unwrap_or(t.len());
    t = &t[..end];
    if let Some((_, h)) = t.rsplit_once('@') {
        t = h;
    }
    let t = match t.rsplit_once(':') {
        Some((h, p)) if !p.is_empty() && p.len() <= 5 && p.chars().all(|c| c.is_ascii_digit()) && !h.contains(':') => h,
        _ => t,
    };
    let mut h = t.trim_start_matches(['*', '.']).trim_end_matches('.').to_lowercase();
    if whole_site && h.starts_with("www.") && h[4..].contains('.') {
        h = h[4..].to_string();
    }
    if h.is_empty() {
        return Ok(h);
    }
    let ascii = url::Url::parse(&format!("http://{h}/"))
        .ok()
        .and_then(|u| u.host_str().map(str::to_string))
        .ok_or_else(|| format!("\"{}\" is not a valid domain", crate::core::validate::truncate(&h, 60)))?;
    if !ascii.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '.' | '_')) {
        return Err(format!("\"{}\" is not a valid domain", crate::core::validate::truncate(&h, 60)));
    }
    Ok(ascii)
}

fn ip_entry(raw: &str) -> Result<String, String> {
    let t = raw.trim();
    let (addr, prefix) = match t.split_once('/') {
        Some((a, p)) => (a, Some(p)),
        None => (t, None),
    };
    let ip: IpAddr = addr.trim_start_matches('[').trim_end_matches(']').parse().map_err(|_| format!("\"{}\" is not an IP address or range", crate::core::validate::truncate(t, 60)))?;
    let max = if ip.is_ipv4() { 32 } else { 128 };
    match prefix {
        None => Ok(ip.to_string()),
        Some(p) => {
            let n: u8 = p.parse().map_err(|_| format!("Invalid prefix length in \"{}\"", crate::core::validate::truncate(t, 60)))?;
            if n > max {
                return Err(format!("Invalid prefix length in \"{}\"", crate::core::validate::truncate(t, 60)));
            }
            Ok(format!("{ip}/{n}"))
        }
    }
}

fn port_entry(raw: &str) -> Result<String, String> {
    let t = raw.trim();
    let parse = |s: &str| -> Result<u16, String> {
        s.trim().parse::<u16>().ok().filter(|p| *p > 0).ok_or_else(|| format!("\"{}\" is not a port", crate::core::validate::truncate(t, 20)))
    };
    match t.split_once('-') {
        Some((a, b)) => {
            let (a, b) = (parse(a)?, parse(b)?);
            if a > b {
                return Err(format!("Invalid port range \"{t}\""));
            }
            Ok(format!("{a}-{b}"))
        }
        None => Ok(parse(t)?.to_string()),
    }
}

/// The Xray matcher fields of one rule (`domain`, `ip` or `port`), from already normalized values.
pub fn xray_matchers(rule: &SiteRule) -> serde_json::Value {
    match rule.match_type {
        RuleMatch::Domain => {
            let list: Vec<String> = rule.values.iter().map(|v| if v.contains(':') { v.clone() } else { format!("domain:{v}") }).collect();
            serde_json::json!({ "domain": list })
        }
        RuleMatch::Ip => serde_json::json!({ "ip": rule.values }),
        RuleMatch::Port => serde_json::json!({ "port": rule.values.join(",") }),
    }
}

/// Which rule a destination would match, for the extension's "this site" view and the doctor.
/// Domain matching follows Xray: `domain:` matches the name and its subdomains, `full:` only the
/// name, `keyword:` a substring. Regular expressions are reported as "may match".
pub fn evaluate<'a>(rules: &'a [SiteRule], host: &str, now: u64) -> Option<&'a SiteRule> {
    let host = host.trim_end_matches('.').to_ascii_lowercase();
    let ip: Option<IpAddr> = host.trim_start_matches('[').trim_end_matches(']').parse().ok();
    rules.iter().filter(|r| r.active(now)).find(|r| match r.match_type {
        RuleMatch::Domain if ip.is_none() => r.values.iter().any(|v| domain_matches(v, &host)),
        RuleMatch::Ip => ip.is_some_and(|ip| r.values.iter().any(|v| cidr_contains(v, ip))),
        _ => false,
    })
}

fn domain_matches(rule: &str, host: &str) -> bool {
    match rule.split_once(':') {
        Some(("full", d)) => host == d,
        Some(("keyword", k)) => host.contains(k),
        Some(("regexp", _)) => false,
        Some(("domain", d)) => host == d || host.ends_with(&format!(".{d}")),
        _ => host == rule || host.ends_with(&format!(".{rule}")),
    }
}

fn cidr_contains(rule: &str, ip: IpAddr) -> bool {
    let (addr, len) = match rule.split_once('/') {
        Some((a, l)) => (a, l.parse::<u32>().unwrap_or(0)),
        None => (rule, if ip.is_ipv4() { 32 } else { 128 }),
    };
    let Ok(net) = addr.parse::<IpAddr>() else { return false };
    match (net, ip) {
        (IpAddr::V4(n), IpAddr::V4(i)) => {
            let mask = if len == 0 { 0 } else { u32::MAX << (32 - len.min(32)) };
            (u32::from(n) & mask) == (u32::from(i) & mask)
        }
        (IpAddr::V6(n), IpAddr::V6(i)) => {
            let mask = if len == 0 { 0 } else { u128::MAX << (128 - len.min(128)) };
            (u128::from(n) & mask) == (u128::from(i) & mask)
        }
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rule(kind: RuleMatch, text: &str, action: RuleAction) -> SiteRule {
        SiteRule {
            id: "r".into(),
            name: "r".into(),
            match_type: kind,
            values: normalize_values(kind, text).unwrap(),
            action,
            server_id: None,
            enabled: true,
            expires_at: None,
            scenario: None,
            created_at: 0,
        }
    }

    #[test]
    fn domains_are_normalized_like_the_desktop_app() {
        let v = normalize_values(RuleMatch::Domain, "https://www.YouTube.com/watch?v=1, *.example.org:8443 full:api.example.net keyword:ads Bücher.de").unwrap();
        assert_eq!(v, vec!["youtube.com", "example.org", "full:api.example.net", "keyword:ads", "xn--bcher-kva.de"]);
        assert!(normalize_values(RuleMatch::Domain, "geosite:google").is_err());
        assert!(normalize_values(RuleMatch::Domain, "exa mple\u{7}.com").is_err());
        assert!(normalize_values(RuleMatch::Domain, "  ,  ").is_err());
    }

    #[test]
    fn ips_and_ports() {
        assert_eq!(normalize_values(RuleMatch::Ip, "10.0.0.0/8 1.1.1.1 [::1] 2001:db8::/32").unwrap(), vec!["10.0.0.0/8", "1.1.1.1", "::1", "2001:db8::/32"]);
        assert!(normalize_values(RuleMatch::Ip, "10.0.0.0/33").is_err());
        assert!(normalize_values(RuleMatch::Ip, "example.com").is_err());
        assert_eq!(normalize_values(RuleMatch::Port, "443, 8000-8100").unwrap(), vec!["443", "8000-8100"]);
        assert!(normalize_values(RuleMatch::Port, "0").is_err());
        assert!(normalize_values(RuleMatch::Port, "9-1").is_err());
    }

    #[test]
    fn matchers_and_evaluation() {
        let d = rule(RuleMatch::Domain, "youtube.com full:x.example.com keyword:ads", RuleAction::Direct);
        assert_eq!(xray_matchers(&d)["domain"][0], "domain:youtube.com");
        assert_eq!(xray_matchers(&d)["domain"][1], "full:x.example.com");
        let ip = rule(RuleMatch::Ip, "192.168.0.0/16", RuleAction::Block);
        let rules = vec![d, ip];
        assert_eq!(evaluate(&rules, "m.youtube.com", 0).map(|r| r.action), Some(RuleAction::Direct));
        assert!(evaluate(&rules, "notyoutube.com", 0).is_none());
        assert!(evaluate(&rules, "y.x.example.com", 0).is_none());
        assert!(evaluate(&rules, "myads.net", 0).is_some());
        assert_eq!(evaluate(&rules, "192.168.1.5", 0).map(|r| r.action), Some(RuleAction::Block));
    }

    #[test]
    fn temporary_rules_expire() {
        let mut r = rule(RuleMatch::Domain, "a.com", RuleAction::Proxy);
        r.expires_at = Some(100);
        assert!(r.active(99));
        assert!(!r.active(100));
        r.enabled = false;
        r.expires_at = None;
        assert!(!r.active(0));
    }
}
