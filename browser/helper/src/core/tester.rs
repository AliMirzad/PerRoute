//! Server tests that never touch the live tunnel.
//!
//! * TCP ping: how long the server's port takes to accept a connection.
//! * Real test: a separate Xray with only this server, behind its own authenticated loopback
//!   inbound, and one HTTP request through it (the time a browser would wait).
//! * Speed test: a download through such an instance (or through the live tunnel).
//! * Exit check and site comparison: requests through an instance or the live tunnel.
//!
//! Every instance listens on 127.0.0.1 with random credentials and is stopped when dropped.

use crate::core::credentials::ProxyCredentials;
use crate::core::probe;
use crate::core::profile::{ServerMeta, ServerSecrets};
use crate::core::xray_config;
use crate::runtime::{ports, xray};
use std::io::Read;
use std::net::{TcpStream, ToSocketAddrs};
use std::path::Path;
use std::time::{Duration, Instant};

pub const SPEED_HOST: &str = "speed.cloudflare.com";
const SPEED_BYTES: u64 = 10_000_000;
const SPEED_MAX: Duration = Duration::from_secs(5);
const SPEED_WARMUP: Duration = Duration::from_millis(700);
pub const TRACE_URL: &str = "https://www.cloudflare.com/cdn-cgi/trace";

/// The port a test talks to: an isolated instance or the live tunnel.
#[derive(Clone)]
pub struct Endpoint {
    pub port: u16,
    pub auth: ProxyCredentials,
}

/// A separate Xray with one server. Stopped on drop.
pub struct Instance {
    run: Option<xray::Running>,
    pub endpoint: Endpoint,
}

impl Drop for Instance {
    fn drop(&mut self) {
        if let Some(r) = self.run.take() {
            r.stop();
        }
    }
}

impl Instance {
    pub fn start(xray_path: &Path, run_dir: &Path, meta: &ServerMeta, secrets: &ServerSecrets) -> Result<Instance, String> {
        crate::core::netpolicy::check_server_address(&meta.address)?;
        let auth = ProxyCredentials::random_for_browser();
        let mut last = String::from("Xray did not start");
        for _ in 0..2 {
            let port = ports::ephemeral_port().map_err(|e| format!("No free local port: {e}"))?;
            let cfg = serde_json::to_vec(&xray_config::test_config(meta, secrets, port, &auth)).unwrap_or_default();
            let r = xray::spawn(xray_path, &cfg, run_dir)?;
            if ports::wait_listening(port, Duration::from_secs(6), || r.try_exit().is_none()) {
                return Ok(Instance { run: Some(r), endpoint: Endpoint { port, auth } });
            }
            last = r.tail.lock().map(|t| t.lines()).unwrap_or_default().into_iter().rev().find(|l| !l.trim().is_empty()).unwrap_or(last);
            r.stop();
        }
        Err(crate::log::redact(last.rsplit(" > ").next().unwrap_or(&last)))
    }
}

/// Time for the server's port to accept a TCP connection.
pub fn tcp_ping(meta: &ServerMeta, timeout: Duration) -> Result<u64, String> {
    crate::core::netpolicy::check_server_address(&meta.address)?;
    let addrs: Vec<_> = (meta.address.as_str(), meta.port).to_socket_addrs().map_err(|_| "The server name could not be resolved".to_string())?.collect();
    let addr = addrs.first().ok_or("The server name could not be resolved")?;
    let started = Instant::now();
    TcpStream::connect_timeout(addr, timeout).map_err(|_| "The server did not answer".to_string())?;
    Ok(started.elapsed().as_millis() as u64)
}

/// One request through the endpoint, timed. The best of two attempts is reported, so a first
/// connection's setup cost does not decide the result alone.
pub fn real_delay(ep: &Endpoint, timeout: Duration) -> Result<u64, String> {
    let targets = probe::default_targets();
    let mut best: Option<u64> = None;
    let mut last_err = String::new();
    for t in targets.iter().cycle().take(2) {
        let started = Instant::now();
        match probe::probe_once(ep.port, &ep.auth, t, timeout) {
            Ok(_) => {
                let ms = started.elapsed().as_millis() as u64;
                best = Some(best.map_or(ms, |b| b.min(ms)));
            }
            Err(e) => last_err = e.to_string(),
        }
    }
    best.ok_or(if last_err.is_empty() { "The test request failed".into() } else { last_err })
}

fn client(ep: &Endpoint, timeout: Duration) -> Result<reqwest::blocking::Client, String> {
    let proxy = reqwest::Proxy::all(format!("http://127.0.0.1:{}", ep.port)).map_err(|e| e.to_string())?.basic_auth(&ep.auth.user, &ep.auth.pass);
    reqwest::blocking::Client::builder()
        .proxy(proxy)
        .connect_timeout(Duration::from_secs(8).min(timeout))
        .timeout(timeout)
        .user_agent(crate::core::subscription::USER_AGENT)
        .redirect(reqwest::redirect::Policy::limited(3))
        .build()
        .map_err(|e| e.to_string())
}

/// Download speed in bytes per second, measured after a short warm-up.
pub fn speed(ep: &Endpoint) -> Result<u64, String> {
    let c = client(ep, SPEED_MAX + Duration::from_secs(10))?;
    let mut resp = c.get(format!("https://{SPEED_HOST}/__down?bytes={SPEED_BYTES}")).send().map_err(|_| "The speed test could not start through this server".to_string())?;
    if !resp.status().is_success() {
        return Err(format!("The speed endpoint answered HTTP {}", resp.status().as_u16()));
    }
    let mut buf = vec![0u8; 64 * 1024];
    let started = Instant::now();
    let (mut total, mut warm) = (0u64, None::<(u64, Instant)>);
    while total < SPEED_BYTES && started.elapsed() < SPEED_MAX {
        if warm.is_none() && started.elapsed() >= SPEED_WARMUP {
            warm = Some((total, Instant::now()));
        }
        match resp.read(&mut buf) {
            Ok(0) | Err(_) => break,
            Ok(n) => total += n as u64,
        }
    }
    let (bytes, secs) = match warm {
        Some((b, at)) if total > b => (total - b, at.elapsed().as_secs_f64()),
        _ => (total, started.elapsed().as_secs_f64()),
    };
    if bytes == 0 || secs <= 0.0 {
        return Err("No data arrived through this server".into());
    }
    Ok((bytes as f64 / secs) as u64)
}

/// Exit address and location as seen by Cloudflare.
#[derive(serde::Serialize, Debug, Clone, Default)]
#[serde(rename_all = "camelCase")]
pub struct ExitInfo {
    pub ip: String,
    pub country: String,
    pub colo: String,
    pub latency_ms: u64,
}

pub fn exit_info(ep: &Endpoint) -> Result<ExitInfo, String> {
    let c = client(ep, Duration::from_secs(10))?;
    let started = Instant::now();
    let body = c.get(TRACE_URL).send().and_then(|r| r.text()).map_err(|_| "The exit check failed through this connection".to_string())?;
    let mut info = ExitInfo { latency_ms: started.elapsed().as_millis() as u64, ..Default::default() };
    for line in body.lines().take(40) {
        match line.split_once('=') {
            Some(("ip", v)) => info.ip = crate::core::validate::truncate(v.trim(), 45),
            Some(("loc", v)) => info.country = crate::core::validate::truncate(v.trim(), 4),
            Some(("colo", v)) => info.colo = crate::core::validate::truncate(v.trim(), 8),
            _ => {}
        }
    }
    if info.ip.parse::<std::net::IpAddr>().is_err() {
        return Err("The exit check returned no address".into());
    }
    Ok(info)
}

/// Result of opening one site through one route.
#[derive(serde::Serialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct SiteTiming {
    pub url: String,
    pub ok: bool,
    pub status: Option<u16>,
    pub ms: Option<u64>,
    pub error: Option<String>,
}

/// Only `https://` URLs with a public host name: the comparison opens sites, never local services.
pub fn check_site_url(raw: &str) -> Result<String, String> {
    let u = url::Url::parse(raw.trim()).map_err(|_| "Not a valid address".to_string())?;
    if u.scheme() != "https" {
        return Err("Only https:// addresses can be compared".into());
    }
    let host = u.host_str().unwrap_or("");
    crate::core::netpolicy::check_subscription_host(host, false).map_err(|_| "Local and private addresses cannot be compared".to_string())?;
    Ok(u.to_string())
}

pub fn time_site(ep: &Endpoint, url: &str) -> SiteTiming {
    let started = Instant::now();
    let r = client(ep, Duration::from_secs(12)).and_then(|c| c.get(url).send().map_err(|e| if e.is_timeout() { "Timed out".to_string() } else { "Could not open".to_string() }));
    match r {
        Ok(resp) => SiteTiming { url: url.into(), ok: resp.status().as_u16() < 500, status: Some(resp.status().as_u16()), ms: Some(started.elapsed().as_millis() as u64), error: None },
        Err(e) => SiteTiming { url: url.into(), ok: false, status: None, ms: None, error: Some(e) },
    }
}
