//! Network quality score: the same explainable 0-100 score as the desktop app.
//!
//! A score comes from latency, jitter, success rate, stability and tunnel setup time, weighted by
//! age (half-life two hours). Servers that were never measured, or not for 24 hours, are Unknown,
//! never "healthy". A server whose tunnel was never verified end to end is capped at 74.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;

pub const STALE_AFTER_MS: u64 = 30 * 60_000;
pub const FORGET_AFTER_MS: u64 = 24 * 60 * 60_000;
const HALF_LIFE_MS: f64 = 2.0 * 60.0 * 60_000.0;
const RECOVERY_WINDOW_MS: u64 = 60 * 60_000;
const UNVERIFIED_CAP: i64 = 74;
/// Samples kept per server.
pub const MAX_SAMPLES: usize = 24;
pub const FILE: &str = "quality.json";

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum SampleKind {
    /// The server's port answered (TCP connect).
    Reachability,
    /// A real request went through the server.
    Tunnel,
    /// A live route recovered after a failure.
    Recovery,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Sample {
    pub at_ms: u64,
    pub kind: SampleKind,
    pub success: bool,
    /// -1 for failures and recoveries.
    pub latency_ms: i64,
}

impl Sample {
    pub fn new(at_ms: u64, kind: SampleKind, success: bool, latency_ms: i64) -> Sample {
        Sample { at_ms, kind, success, latency_ms: if success && kind != SampleKind::Recovery { latency_ms.max(0) } else { -1 } }
    }
}

/// `quality.json`: samples per server id.
pub type History = HashMap<String, Vec<Sample>>;

pub fn record(h: &mut History, server_id: &str, s: Sample) {
    let v = h.entry(server_id.to_string()).or_default();
    v.push(s);
    if v.len() > MAX_SAMPLES {
        let extra = v.len() - MAX_SAMPLES;
        v.drain(..extra);
    }
}

#[derive(Serialize, Clone, Debug, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct Components {
    pub latency_ms: Option<i64>,
    pub latency_score: Option<i64>,
    pub jitter_ms: Option<i64>,
    pub jitter_score: Option<i64>,
    pub success_rate: Option<f64>,
    pub success_score: Option<i64>,
    pub stability_score: Option<i64>,
    pub setup_ms: Option<i64>,
    pub setup_score: Option<i64>,
    pub recovery_penalty: i64,
    pub recent_failures: i64,
}

#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Score {
    pub score: Option<i64>,
    /// excellent | good | fair | poor | unavailable | unknown
    pub label: &'static str,
    pub confidence: f64,
    pub measured_at: u64,
    pub stale: bool,
    pub tunnel_verified: bool,
    pub components: Components,
    pub sample_count: usize,
    pub headline: String,
    pub explanation: String,
}

impl Score {
    fn unknown(measured_at: u64, headline: &str, explanation: &str) -> Score {
        Score {
            score: None,
            label: "unknown",
            confidence: 0.0,
            measured_at,
            stale: measured_at > 0,
            tunnel_verified: false,
            components: Components::default(),
            sample_count: 0,
            headline: headline.into(),
            explanation: explanation.into(),
        }
    }

    pub fn usable(&self) -> bool {
        self.score.is_some() && self.label != "unknown" && self.label != "unavailable"
    }
}

fn label_for(score: i64) -> &'static str {
    match score {
        s if s >= 85 => "excellent",
        s if s >= 70 => "good",
        s if s >= 50 => "fair",
        _ => "poor",
    }
}

fn linear(value: i64, best: i64, worst: i64) -> i64 {
    if value <= best {
        100
    } else if value >= worst {
        0
    } else {
        (100.0 * (worst - value) as f64 / (worst - best) as f64).round() as i64
    }
}

fn median(values: &[i64]) -> i64 {
    let mut v = values.to_vec();
    v.sort_unstable();
    let m = v.len() / 2;
    if v.len() % 2 == 1 {
        v[m]
    } else {
        ((v[m - 1] + v[m]) as f64 / 2.0).round() as i64
    }
}

fn ok_latencies(s: &[&Sample]) -> Vec<i64> {
    s.iter().filter(|x| x.success && x.latency_ms >= 0).map(|x| x.latency_ms).collect()
}

pub fn score(history: &[Sample], now: u64) -> Score {
    let mut newest = 0;
    let mut samples: Vec<&Sample> = Vec::new();
    for s in history {
        if s.at_ms > now + 60_000 {
            continue;
        }
        newest = newest.max(s.at_ms);
        if now.saturating_sub(s.at_ms) <= FORGET_AFTER_MS {
            samples.push(s);
        }
    }
    samples.sort_by_key(|s| s.at_ms);
    let checks: Vec<&Sample> = samples.iter().copied().filter(|s| s.kind != SampleKind::Recovery).collect();
    if checks.is_empty() {
        return if newest == 0 {
            Score::unknown(0, "Not measured yet.", "Run a test or connect to rate this server.")
        } else {
            Score::unknown(newest, "Measured too long ago.", "The last measurement is older than 24 hours, so it is no longer rated.")
        };
    }
    let tunnel: Vec<&Sample> = checks.iter().copied().filter(|s| s.kind == SampleKind::Tunnel).collect();
    let reach: Vec<&Sample> = checks.iter().copied().filter(|s| s.kind == SampleKind::Reachability).collect();
    let verified = tunnel.iter().any(|s| s.success);
    let last = checks.last().unwrap().at_ms;
    let age = now.saturating_sub(last);
    let stale = age > STALE_AFTER_MS;
    let (mut attempts, mut successes) = (0.0, 0.0);
    for s in &checks {
        let w = 0.5f64.powf(now.saturating_sub(s.at_ms) as f64 / HALF_LIFE_MS) * if s.kind == SampleKind::Tunnel { 2.0 } else { 1.0 };
        attempts += w;
        if s.success {
            successes += w;
        }
    }
    let success_rate = if attempts <= 0.0 { 0.0 } else { successes / attempts };
    let recent_failures = checks.iter().filter(|s| !s.success && now.saturating_sub(s.at_ms) <= RECOVERY_WINDOW_MS).count() as i64;
    let decisive = if tunnel.is_empty() { &reach } else { &tunnel };
    let last_two_failed = decisive.len() >= 2 && !decisive[decisive.len() - 1].success && !decisive[decisive.len() - 2].success;
    let only_failure = decisive.len() == 1 && !decisive[0].success;
    let mostly_failing = checks.len() >= 3 && success_rate < 0.25;

    let mut c = Components { success_rate: Some(success_rate), success_score: Some((success_rate * 100.0).round() as i64), recent_failures, ..Default::default() };
    let lat = ok_latencies(if reach.is_empty() { &tunnel } else { &reach });
    if !lat.is_empty() {
        let m = median(&lat);
        c.latency_ms = Some(m);
        c.latency_score = Some(linear(m, 40, 600));
    }
    if lat.len() >= 3 {
        let total: i64 = lat.windows(2).map(|w| (w[1] - w[0]).abs()).sum();
        let j = (total as f64 / (lat.len() - 1) as f64).round() as i64;
        c.jitter_ms = Some(j);
        c.jitter_score = Some(linear(j, 5, 150));
    }
    if checks.len() >= 3 {
        let transitions = checks.windows(2).filter(|w| w[0].success != w[1].success).count();
        c.stability_score = Some((100.0 * (1.0 - transitions as f64 / (checks.len() - 1) as f64)).round() as i64);
    }
    let setup = ok_latencies(&tunnel);
    if !setup.is_empty() {
        let m = median(&setup);
        c.setup_ms = Some(m);
        c.setup_score = Some(linear(m, 150, 2_500));
    }
    let mut penalty = 0.0;
    let has_recovery = samples.iter().any(|s| s.kind == SampleKind::Recovery);
    for s in &samples {
        if s.kind == SampleKind::Recovery && now.saturating_sub(s.at_ms) <= RECOVERY_WINDOW_MS {
            penalty += 6.0 * (1.0 - now.saturating_sub(s.at_ms) as f64 / RECOVERY_WINDOW_MS as f64);
        }
    }
    if !has_recovery {
        for w in checks.windows(2) {
            if w[1].success && !w[0].success && now.saturating_sub(w[1].at_ms) <= RECOVERY_WINDOW_MS {
                penalty += 6.0 * (1.0 - now.saturating_sub(w[1].at_ms) as f64 / RECOVERY_WINDOW_MS as f64);
            }
        }
    }
    c.recovery_penalty = (penalty.round() as i64).min(15);
    let confidence = confidence(&checks, now, verified);

    if last_two_failed || only_failure || mostly_failing {
        let detail = format!(
            "{}{}{}",
            if last_two_failed { "The last two checks failed" } else if only_failure { "The only check failed" } else { "Most recent checks failed" },
            if tunnel.is_empty() { " to reach the server." } else { " to open a connection through the tunnel." },
            age_sentence(age)
        );
        return Score {
            score: Some(0),
            label: "unavailable",
            confidence,
            measured_at: last,
            stale,
            tunnel_verified: verified,
            components: c,
            sample_count: checks.len(),
            headline: "Not responding.".into(),
            explanation: detail.trim().into(),
        };
    }
    let (mut weighted, mut weights) = (0.35 * c.success_score.unwrap_or(0) as f64, 0.35);
    for (part, w) in [(c.latency_score, 0.25), (c.jitter_score, 0.15), (c.stability_score, 0.15), (c.setup_score, 0.10)] {
        if let Some(p) = part {
            weighted += w * p as f64;
            weights += w;
        }
    }
    let mut s = (weighted / weights).round() as i64 - c.recovery_penalty;
    if !verified {
        s = s.min(UNVERIFIED_CAP);
    }
    let s = s.clamp(0, 100);
    let label = label_for(s);
    Score {
        score: Some(s),
        label,
        confidence,
        measured_at: last,
        stale,
        tunnel_verified: verified,
        headline: headline(label, &c, verified, stale),
        explanation: explanation(&c, verified, age),
        components: c,
        sample_count: checks.len(),
    }
}

fn confidence(checks: &[&Sample], now: u64, verified: bool) -> f64 {
    let six_h = 6 * 60 * 60_000;
    let recent = checks.iter().filter(|s| now.saturating_sub(s.at_ms) <= six_h).count() as f64;
    let amount = (recent / 8.0).min(1.0);
    let age = now.saturating_sub(checks.last().unwrap().at_ms) as f64;
    let ten_min = 10.0 * 60_000.0;
    let freshness = if age <= ten_min {
        1.0
    } else if age <= six_h as f64 {
        1.0 - 0.7 * (age - ten_min) / (six_h as f64 - ten_min)
    } else {
        0.1
    };
    let evidence = if verified { 1.0 } else { 0.6 };
    (amount.max(0.05) * freshness * evidence * 100.0).round() / 100.0
}

fn headline(label: &str, c: &Components, verified: bool, stale: bool) -> String {
    if stale {
        return "Last result may be outdated.".into();
    }
    if !verified {
        return "Reachable; tunnel not verified yet.".into();
    }
    let flaky = c.stability_score.is_some_and(|s| s < 70) || c.recent_failures > 0;
    match label {
        "excellent" if flaky => "Fast, recovering from failures.",
        "excellent" => "Fast, stable, recently verified.",
        "good" if flaky => "Working, with occasional failures.",
        "good" => "Works well.",
        "fair" => "Usable, but slower or less steady.",
        _ => "Slow or unreliable.",
    }
    .into()
}

fn describe(score: Option<i64>, good: &'static str, middle: &'static str, bad: &'static str) -> &'static str {
    match score {
        None => middle,
        Some(s) if s >= 80 => good,
        Some(s) if s >= 45 => middle,
        Some(_) => bad,
    }
}

fn explanation(c: &Components, verified: bool, age: u64) -> String {
    let mut out: Vec<String> = Vec::new();
    if let Some(l) = c.latency_ms {
        let jitter = c.jitter_ms.map(|j| format!(", {} jitter ({j} ms)", describe(c.jitter_score, "minimal", "some", "high"))).unwrap_or_default();
        out.push(format!("{} latency ({l} ms){jitter}.", describe(c.latency_score, "Low", "Moderate", "High")));
    }
    let rate = (c.success_rate.unwrap_or(0.0) * 100.0).round() as i64;
    out.push(if c.recent_failures > 0 {
        format!("{rate}% of recent checks succeeded, {} failed in the last hour.", c.recent_failures)
    } else {
        format!("{rate}% of recent checks succeeded.")
    });
    if let Some(s) = c.setup_ms {
        out.push(format!("Connections through the tunnel open in about {s} ms."));
    }
    if !verified {
        out.push("Only the server's port answered; a real test or a connection will verify it.".into());
    }
    if c.recovery_penalty > 0 {
        out.push(format!("It recovered from a failure recently (-{}).", c.recovery_penalty));
    }
    let a = age_sentence(age);
    if !a.is_empty() {
        out.push(a.trim().into());
    }
    out.join(" ")
}

fn age_sentence(age: u64) -> String {
    if age <= STALE_AFTER_MS {
        return String::new();
    }
    let minutes = age / 60_000;
    if minutes < 120 {
        format!(" Measured {minutes} minutes ago.")
    } else {
        format!(" Measured {} hours ago.", minutes / 60)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const MIN: u64 = 60_000;

    #[test]
    fn unknown_until_measured_and_after_a_day() {
        assert_eq!(score(&[], 10 * MIN).label, "unknown");
        let old = [Sample::new(1, SampleKind::Tunnel, true, 100)];
        let s = score(&old, FORGET_AFTER_MS + MIN);
        assert_eq!((s.label, s.score), ("unknown", None));
        assert!(s.stale);
    }

    #[test]
    fn fast_verified_server_is_excellent_and_reachability_alone_is_capped() {
        let now = 100 * MIN;
        let good: Vec<Sample> = (0..6).map(|i| Sample::new(now - i * MIN, SampleKind::Tunnel, true, 120 + i as i64)).collect();
        let s = score(&good, now);
        assert_eq!(s.label, "excellent", "{s:?}");
        assert!(s.tunnel_verified && s.score.unwrap() >= 85);
        let reach: Vec<Sample> = (0..6).map(|i| Sample::new(now - i * MIN, SampleKind::Reachability, true, 30)).collect();
        let r = score(&reach, now);
        assert!(r.score.unwrap() <= 74 && !r.tunnel_verified, "{r:?}");
    }

    #[test]
    fn two_failures_make_it_unavailable() {
        let now = 100 * MIN;
        let h = [
            Sample::new(now - 3 * MIN, SampleKind::Tunnel, true, 200),
            Sample::new(now - 2 * MIN, SampleKind::Tunnel, false, 0),
            Sample::new(now - MIN, SampleKind::Tunnel, false, 0),
        ];
        let s = score(&h, now);
        assert_eq!((s.label, s.score), ("unavailable", Some(0)));
        assert!(!s.usable());
    }

    #[test]
    fn history_is_bounded() {
        let mut h = History::new();
        for i in 0..(MAX_SAMPLES as u64 + 10) {
            record(&mut h, "a", Sample::new(i, SampleKind::Reachability, true, 10));
        }
        assert_eq!(h["a"].len(), MAX_SAMPLES);
        assert_eq!(h["a"][0].at_ms, 10);
    }
}
