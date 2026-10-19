//! Trojan share links: `trojan://<password>@<host>:<port>?type=..&security=..&...#<name>`.
//! The transport and security parameters are the same as for VLESS; TLS is the default.

use super::stream::{self, StreamParams};
use super::vless::{is_valid_pct, pct, query_map, split_host_port};
use crate::core::profile::*;
use crate::core::validate as v;

pub fn parse_trojan_uri(link: &str) -> Result<ParsedServer, String> {
    let link = link.trim();
    let rest = link
        .get(9..)
        .filter(|_| link[..9].eq_ignore_ascii_case("trojan://"))
        .ok_or("Not a trojan:// link")?;
    if rest.chars().any(|c| c.is_control() || c == ' ') {
        return Err("Link contains whitespace or control characters".into());
    }
    let (rest, fragment) = match rest.split_once('#') {
        Some((a, b)) => (a, Some(b)),
        None => (rest, None),
    };
    let (authority, query) = match rest.split_once('?') {
        Some((a, q)) => (a, q),
        None => (rest, ""),
    };
    let authority = authority.trim_end_matches('/');
    let (userinfo, hostport) = authority.rsplit_once('@').ok_or("Link is missing the password")?;
    if !is_valid_pct(userinfo) {
        return Err("Link contains malformed percent-encoding".into());
    }
    let password = v::password(&pct(userinfo)?)?;
    let (host, port) = split_host_port(hostport)?;
    let address = v::address(&host)?;
    let port = v::port_from_str(&port)?;
    let q = query_map(query)?;
    let g = |k: &str| q.get(k).cloned();

    let mut warnings = Vec::new();
    super::fields::check_params(q.iter(), "link parameter", super::fields::TROJAN_PARAMS, &mut warnings)?;
    let params = StreamParams {
        net: g("type"),
        header_type: g("headerType"),
        host: g("host"),
        path: g("path"),
        service_name: g("serviceName"),
        authority: g("authority"),
        mode: g("mode"),
        security: g("security").filter(|s| !s.trim().is_empty()).or_else(|| Some("tls".into())),
        sni: g("sni").or_else(|| g("peer")),
        alpn: g("alpn"),
        fp: g("fp"),
        pbk: g("pbk"),
        sid: g("sid"),
        spx: g("spx"),
        pqv: g("pqv"),
        pcs: g("pcs"),
        ech: g("ech"),
        extra: g("extra"),
        allow_insecure: [g("allowInsecure"), g("insecure")].iter().any(|v| matches!(v.as_deref(), Some("1") | Some("true"))),
    };
    let st = stream::build(&params, &mut warnings)?;

    let fallback = format!("{address}:{port}");
    let name = match fragment {
        Some(f) => v::clean_name(&pct(f).unwrap_or_default(), &fallback),
        None => fallback,
    };
    let mut p = ParsedServer {
        meta: ServerMeta {
            id: String::new(),
            name,
            protocol: Protocol::Trojan,
            address,
            port,
            transport: st.transport,
            security: st.security,
            flow: String::new(),
            vmess_cipher: None,
            ss_method: None,
            source: Source::Link,
            subscription_id: None,
            created_at: 0,
        },
        secrets: ServerSecrets {
            user_id: String::new(),
            password: Some(password),
            vless_encryption: None,
            reality_password: st.reality_password,
            reality_short_id: st.reality_short_id,
        },
        warnings,
    };
    v::server(&mut p)?;
    Ok(p)
}
