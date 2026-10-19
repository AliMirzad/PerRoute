//! Shadowsocks share links. Accepted forms:
//! * SIP002: `ss://base64url(method:password)@host:port/?plugin=..#name`
//! * SIP002 with a percent-encoded user info (used by the 2022 methods):
//!   `ss://method:password@host:port#name`
//! * legacy: `ss://base64(method:password@host:port)#name`
//!
//! Plugins are rejected: they would need a separate program next to Xray.

use super::decode_base64_lenient;
use super::vless::{is_valid_pct, pct, query_map, split_host_port};
use crate::core::profile::*;
use crate::core::validate as v;

fn decode_text(s: &str) -> Option<String> {
    decode_base64_lenient(s).and_then(|b| String::from_utf8(b).ok())
}

pub fn parse_ss_uri(link: &str) -> Result<ParsedServer, String> {
    let link = link.trim();
    let rest = link.get(5..).filter(|_| link[..5].eq_ignore_ascii_case("ss://")).ok_or("Not an ss:// link")?;
    if rest.chars().any(|c| c.is_control() || c == ' ') {
        return Err("Link contains whitespace or control characters".into());
    }
    let (rest, fragment) = match rest.split_once('#') {
        Some((a, b)) => (a, Some(b)),
        None => (rest, None),
    };
    let (body, query) = match rest.split_once('?') {
        Some((a, q)) => (a, q),
        None => (rest, ""),
    };
    let body = body.trim_end_matches('/');
    let mut warnings = Vec::new();
    let q = query_map(query)?;
    super::fields::check_params(q.iter(), "link parameter", super::fields::SS_PARAMS, &mut warnings)?;

    // Legacy form: everything before '#' is base64 of "method:password@host:port".
    let decoded;
    let body = if body.contains('@') {
        body
    } else {
        decoded = decode_text(body).ok_or("Invalid Shadowsocks link")?;
        decoded.as_str()
    };
    let (userinfo, hostport) = body.rsplit_once('@').ok_or("Invalid Shadowsocks link")?;
    let userinfo = if userinfo.contains(':') {
        if !is_valid_pct(userinfo) {
            return Err("Link contains malformed percent-encoding".into());
        }
        pct(userinfo)?
    } else {
        decode_text(&pct(userinfo)?).ok_or("Invalid Shadowsocks credentials")?
    };
    let (method, password) = userinfo.split_once(':').ok_or("Invalid Shadowsocks credentials")?;
    let method = v::ss_method(method)?;
    let password = v::password(password)?;
    let (host, port) = split_host_port(hostport)?;
    let address = v::address(&host)?;
    let port = v::port_from_str(&port)?;

    let fallback = format!("{address}:{port}");
    let name = match fragment {
        Some(f) => v::clean_name(&pct(f).unwrap_or_default(), &fallback),
        None => fallback,
    };
    let mut p = ParsedServer {
        meta: ServerMeta {
            id: String::new(),
            name,
            protocol: Protocol::Shadowsocks,
            address,
            port,
            transport: Transport::Raw { header: RawHeader::None },
            security: Security::None,
            flow: String::new(),
            vmess_cipher: None,
            ss_method: Some(method),
            source: Source::Link,
            subscription_id: None,
            created_at: 0,
        },
        secrets: ServerSecrets { password: Some(password), ..Default::default() },
        warnings,
    };
    v::server(&mut p)?;
    Ok(p)
}
