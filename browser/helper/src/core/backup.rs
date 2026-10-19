//! Portable backups: servers (with credentials), subscriptions, site rules, profiles and
//! settings in one file, optionally encrypted with a password.
//!
//! A backup is untrusted input when it is restored (it may come from another computer or have
//! been edited). Nothing in it is used as stored: every server goes through the strict JSON
//! import again, subscription URLs through the URL policy, rules through rule validation and
//! settings through the same checks as `setSettings`. Before a restore the current data is kept
//! as an encrypted snapshot, so one Undo puts everything back.

use crate::core::import;
use crate::core::profile::{ServerMeta, ServerSecrets};
use crate::core::routing::{self, SiteRule};
use crate::core::store::{self, Profile, Settings, StateFile, SecretsFile, Store, StoreError, SubscriptionMeta};
use crate::core::xray_config;
use base64::Engine;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

pub const FORMAT: &str = "perroute-browser-backup";
pub const VERSION: u32 = 1;
pub const SNAPSHOT: &str = "restore-snapshot.bin";
const KDF_ITERATIONS: u32 = 310_000;
const MAX_BACKUP_BYTES: usize = 20 * 1024 * 1024;

#[derive(Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct BackupServer {
    pub meta: ServerMeta,
    pub secrets: ServerSecrets,
}

#[derive(Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct BackupSubscription {
    pub meta: SubscriptionMeta,
    pub url: String,
}

#[derive(Serialize, Deserialize, Clone, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct BackupData {
    pub servers: Vec<BackupServer>,
    pub subscriptions: Vec<BackupSubscription>,
    pub rules: Vec<SiteRule>,
    pub profiles: Vec<Profile>,
    pub settings: Option<Settings>,
    pub favorites: Vec<String>,
    pub tags: HashMap<String, String>,
    pub selected_server_id: Option<String>,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Sealed {
    kdf: String,
    iterations: u32,
    salt: String,
    blob: String,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct BackupFile {
    format: String,
    version: u32,
    created_at: u64,
    app_version: String,
    encrypted: bool,
    #[serde(default)]
    data: Option<BackupData>,
    #[serde(default)]
    sealed: Option<Sealed>,
}

#[derive(Debug, Clone, Copy, Default)]
pub struct Sections {
    pub servers: bool,
    pub rules: bool,
    pub settings: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum RestoreMode {
    /// Add to what is there; matching servers are updated.
    Merge,
    /// Replace servers, subscriptions, rules and profiles with the backup's.
    Replace,
}

#[derive(Serialize, Debug, Clone, Default)]
#[serde(rename_all = "camelCase")]
pub struct Preview {
    pub encrypted: bool,
    pub created_at: u64,
    pub app_version: String,
    pub servers: usize,
    pub subscriptions: usize,
    pub rules: usize,
    pub profiles: usize,
    pub has_settings: bool,
}

#[derive(Serialize, Debug, Clone, Default)]
#[serde(rename_all = "camelCase")]
pub struct RestoreReport {
    pub servers_added: usize,
    pub servers_updated: usize,
    pub servers_rejected: usize,
    pub subscriptions: usize,
    pub rules: usize,
    pub profiles: usize,
    pub settings: bool,
    pub warnings: Vec<String>,
}

fn derive_key(password: &str, salt: &[u8], iterations: u32) -> [u8; 32] {
    let mut key = [0u8; 32];
    pbkdf2::pbkdf2_hmac::<sha2::Sha256>(password.as_bytes(), salt, iterations, &mut key);
    key
}

/// Collects the current data into a backup.
pub fn collect(st: &StateFile, sec: &SecretsFile, sections: Sections) -> BackupData {
    let mut d = BackupData::default();
    if sections.servers {
        d.servers = st
            .servers
            .iter()
            .filter_map(|m| sec.servers.get(&m.id).map(|s| BackupServer { meta: m.clone(), secrets: s.clone() }))
            .collect();
        d.subscriptions = st
            .subscriptions
            .iter()
            .filter_map(|m| sec.subscriptions.get(&m.id).map(|u| BackupSubscription { meta: m.clone(), url: u.clone() }))
            .collect();
        d.favorites = st.favorites.clone();
        d.tags = st.tags.clone();
        d.selected_server_id = st.selected_server_id.clone();
    }
    if sections.rules {
        d.rules = st.rules.clone();
        d.profiles = st.profiles.clone();
    }
    if sections.settings {
        d.settings = Some(st.settings.clone());
    }
    d
}

/// The backup file's text. With a password the data is encrypted (PBKDF2-SHA256 +
/// XChaCha20-Poly1305); without one the file holds the server credentials in plain text.
pub fn encode(data: &BackupData, password: Option<&str>) -> Result<String, String> {
    let json = serde_json::to_vec(data).map_err(|e| e.to_string())?;
    let mut f = BackupFile {
        format: FORMAT.into(),
        version: VERSION,
        created_at: store::now(),
        app_version: crate::NATIVE_VERSION.into(),
        encrypted: false,
        data: None,
        sealed: None,
    };
    match password.filter(|p| !p.is_empty()) {
        Some(p) => {
            if p.chars().count() < 6 {
                return Err("Use a password of at least 6 characters".into());
            }
            let salt = crate::core::secrets::new_key();
            let key = derive_key(p, &salt, KDF_ITERATIONS);
            let b64 = base64::engine::general_purpose::STANDARD;
            f.encrypted = true;
            f.sealed = Some(Sealed { kdf: "pbkdf2-sha256".into(), iterations: KDF_ITERATIONS, salt: b64.encode(salt), blob: b64.encode(crate::core::secrets::encrypt(&key, &json)) });
        }
        None => f.data = Some(data.clone()),
    }
    serde_json::to_string_pretty(&f).map_err(|e| e.to_string())
}

/// Reads a backup file. `Err` with a message the user can act on (wrong password, not a backup).
pub fn decode(text: &str, password: Option<&str>) -> Result<(Preview, BackupData), String> {
    if text.len() > MAX_BACKUP_BYTES {
        return Err("The file is too large to be a backup".into());
    }
    let f: BackupFile = serde_json::from_str(text.trim_start_matches('\u{feff}')).map_err(|_| "This file is not a PerRoute browser backup".to_string())?;
    if f.format != FORMAT {
        return Err("This file is not a PerRoute browser backup".into());
    }
    if f.version > VERSION {
        return Err("This backup was made by a newer version; update PerRoute first".into());
    }
    let data = match (f.encrypted, f.data, f.sealed) {
        (false, Some(d), _) => d,
        (true, _, Some(s)) => {
            let p = password.filter(|p| !p.is_empty()).ok_or("This backup is protected with a password")?;
            if s.kdf != "pbkdf2-sha256" || !(100_000..=5_000_000).contains(&s.iterations) {
                return Err("This backup uses an unknown protection method".into());
            }
            let b64 = base64::engine::general_purpose::STANDARD;
            let salt = b64.decode(&s.salt).map_err(|_| "The backup is damaged")?;
            let blob = b64.decode(&s.blob).map_err(|_| "The backup is damaged")?;
            let plain = crate::core::secrets::decrypt(&derive_key(p, &salt, s.iterations), &blob).map_err(|_| "Wrong password, or the backup is damaged")?;
            serde_json::from_slice(&plain).map_err(|_| "The backup is damaged")?
        }
        _ => return Err("The backup is damaged".into()),
    };
    let preview = Preview {
        encrypted: f.encrypted,
        created_at: f.created_at,
        app_version: crate::core::validate::truncate(&f.app_version, 20),
        servers: data.servers.len(),
        subscriptions: data.subscriptions.len(),
        rules: data.rules.len(),
        profiles: data.profiles.len(),
        has_settings: data.settings.is_some(),
    };
    Ok((preview, data))
}

/// Runs a stored server through the strict import again (as an Xray outbound).
fn revalidate_server(b: &BackupServer) -> Result<crate::core::profile::ParsedServer, String> {
    let outbound = xray_config::proxy_outbound(&b.meta, &b.secrets);
    let doc = serde_json::json!({ "remarks": b.meta.name, "outbounds": [outbound] });
    let batch = import::parse_xray_json(&doc.to_string())?;
    batch.servers.into_iter().next().ok_or_else(|| batch.errors.first().map(|e| e.message.clone()).unwrap_or_else(|| "invalid server".into()))
}

fn clean_rule(r: &SiteRule) -> Result<SiteRule, String> {
    let values = routing::revalidate(r.match_type, &r.values)?;
    Ok(SiteRule {
        id: uuid::Uuid::new_v4().to_string(),
        name: crate::core::validate::clean_name(&r.name, "Rule"),
        values,
        server_id: r.server_id.clone().filter(|s| uuid::Uuid::parse_str(s).is_ok()),
        created_at: if r.created_at > 0 { r.created_at } else { store::now() },
        ..r.clone()
    })
}

pub fn check_settings(s: &Settings) -> Result<(), String> {
    if s.jetbrains_socks_port < 1024 || s.jetbrains_http_port < 1024 {
        return Err("Ports below 1024 are not allowed".into());
    }
    if s.jetbrains_socks_port == s.jetbrains_http_port {
        return Err("SOCKS and HTTP ports must differ".into());
    }
    if s.subscription_update_hours > 24 * 30 {
        return Err("Subscription update interval is too long".into());
    }
    Ok(())
}

/// Applies a decoded backup to the store.
pub fn apply(store: &Store, data: BackupData, mode: RestoreMode) -> Result<RestoreReport, StoreError> {
    let mut report = RestoreReport::default();
    let allow_private = store.state().map(|s| s.settings.allow_private_subscription_hosts).unwrap_or(false);
    let policy = crate::core::subscription::Policy { allow_private };

    // Validate everything before touching the store.
    let mut subs: Vec<(SubscriptionMeta, String)> = Vec::new();
    for s in &data.subscriptions {
        match crate::core::subscription::validate_url(&s.url, &policy) {
            Ok(u) => subs.push((s.meta.clone(), u.to_string())),
            Err(e) => report.warnings.push(format!("Subscription \"{}\" skipped: {e}", crate::core::validate::truncate(&s.meta.name, 40))),
        }
    }
    let mut servers: Vec<(Option<String>, String, crate::core::profile::ParsedServer)> = Vec::new();
    for b in &data.servers {
        match revalidate_server(b) {
            Ok(p) => servers.push((b.meta.subscription_id.clone(), b.meta.id.clone(), p)),
            Err(e) => {
                report.servers_rejected += 1;
                if report.warnings.len() < 10 {
                    report.warnings.push(format!("Server \"{}\" skipped: {e}", crate::core::validate::truncate(&b.meta.name, 40)));
                }
            }
        }
    }
    let rules: Vec<SiteRule> = data.rules.iter().filter_map(|r| clean_rule(r).map_err(|e| report.warnings.push(format!("Rule skipped: {e}"))).ok()).collect();
    let profiles: Vec<Profile> = data
        .profiles
        .iter()
        .take(store::MAX_PROFILES)
        .map(|p| Profile {
            id: uuid::Uuid::new_v4().to_string(),
            name: crate::core::validate::clean_name(&p.name, "Profile"),
            rules: p.rules.iter().filter_map(|r| clean_rule(r).ok()).collect(),
            server_id: p.server_id.clone().filter(|s| uuid::Uuid::parse_str(s).is_ok()),
            ..p.clone()
        })
        .collect();
    let settings = match &data.settings {
        Some(s) => match check_settings(s) {
            Ok(()) => Some(s.clone()),
            Err(e) => {
                report.warnings.push(format!("Settings skipped: {e}"));
                None
            }
        },
        None => None,
    };

    store.update_all(|st, sec| {
        if mode == RestoreMode::Replace {
            st.servers.clear();
            st.subscriptions.clear();
            st.rules.clear();
            st.profiles.clear();
            st.favorites.clear();
            st.tags.clear();
            st.selected_server_id = None;
            st.active_profile_id = None;
            st.active_scenario = None;
        }
        // Old id -> new id, for subscriptions, servers (rules, favorites and tags point at them).
        let mut sub_ids: HashMap<String, String> = HashMap::new();
        for (meta, url) in &subs {
            let new_id = uuid::Uuid::new_v4().to_string();
            sub_ids.insert(meta.id.clone(), new_id.clone());
            st.subscriptions.push(SubscriptionMeta {
                id: new_id.clone(),
                name: crate::core::validate::clean_name(&meta.name, &meta.host),
                host: url::Url::parse(url).map(|u| crate::core::subscription::display_host(&u)).unwrap_or_default(),
                last_updated: meta.last_updated,
                ..Default::default()
            });
            sec.subscriptions.insert(new_id, url.clone());
        }
        report.subscriptions = subs.len();
        let mut server_ids: HashMap<String, String> = HashMap::new();
        let mut groups: Vec<(Option<String>, Vec<(String, crate::core::profile::ParsedServer)>)> = Vec::new();
        for (sub, old_id, p) in servers.drain(..) {
            let sub = sub.and_then(|s| sub_ids.get(&s).cloned());
            match groups.iter_mut().find(|(s, _)| *s == sub) {
                Some((_, v)) => v.push((old_id, p)),
                None => groups.push((sub, vec![(old_id, p)])),
            }
        }
        for (sub, items) in groups {
            let (old, parsed): (Vec<String>, Vec<_>) = items.into_iter().unzip();
            // Merging a subscription group into an existing subscription would remove its other
            // servers; restored subscriptions are always new, so this only adds or updates.
            let r = store::merge(st, sec, parsed, sub.as_deref())?;
            report.servers_added += r.added;
            report.servers_updated += r.updated;
            for (o, n) in old.into_iter().zip(r.server_ids) {
                server_ids.insert(o, n);
            }
        }
        let map = |id: &Option<String>| id.as_ref().and_then(|i| server_ids.get(i).cloned());
        for mut r in rules.iter().cloned() {
            r.server_id = map(&r.server_id);
            let dup = st.rules.iter().any(|x| x.match_type == r.match_type && x.values == r.values && x.action == r.action);
            if !dup && st.rules.len() < routing::MAX_RULES {
                st.rules.push(r);
                report.rules += 1;
            }
        }
        for mut p in profiles.iter().cloned() {
            p.server_id = map(&p.server_id);
            for r in &mut p.rules {
                r.server_id = map(&r.server_id);
            }
            if st.profiles.len() < store::MAX_PROFILES {
                st.profiles.push(p);
                report.profiles += 1;
            }
        }
        for f in &data.favorites {
            if let Some(n) = server_ids.get(f) {
                if !st.favorites.contains(n) {
                    st.favorites.push(n.clone());
                }
            }
        }
        for (k, v) in &data.tags {
            if let Some(n) = server_ids.get(k) {
                st.tags.insert(n.clone(), crate::core::validate::clean_name(v, ""));
            }
        }
        if st.selected_server_id.is_none() {
            st.selected_server_id = map(&data.selected_server_id).or_else(|| st.servers.first().map(|m| m.id.clone()));
        }
        if let Some(s) = &settings {
            st.settings = s.clone();
            report.settings = true;
        }
        st.routing_revision += 1;
        Ok(())
    })?;
    Ok(report)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::import::parse_link;
    use crate::core::secrets::FileKeyProvider;

    fn store() -> (tempfile::TempDir, Store) {
        let d = tempfile::tempdir().unwrap();
        let s = Store::open(d.path().join("data"), Box::new(FileKeyProvider { path: d.path().join("k") })).unwrap();
        (d, s)
    }

    const LINKS: [&str; 3] = [
        "vless://b831381d-6324-4d53-ad4f-8cda48b30811@a.example.com:443?security=reality&sni=www.microsoft.com&fp=chrome&pbk=OFAMcMJ-9uns7MO5APwkUr8PfvouYrl-t8s7UIEn9mI&sid=ab12&type=tcp&flow=xtls-rprx-vision#Reality",
        "trojan://p%40ss@t.example.com:443?type=ws&path=%2Fws&sni=t.example.com#Trojan",
        "ss://MjAyMi1ibGFrZTMtYWVzLTEyOC1nY206cGFzc3dvcmQ@s.example.com:8388#SS",
    ];

    fn seeded() -> (tempfile::TempDir, Store) {
        let (d, s) = store();
        s.update_all(|st, sec| {
            let r = store::merge(st, sec, LINKS.iter().map(|l| parse_link(l).unwrap()).collect(), None)?;
            st.rules.push(SiteRule {
                id: "r".into(),
                name: "Video".into(),
                match_type: routing::RuleMatch::Domain,
                values: vec!["youtube.com".into()],
                action: routing::RuleAction::Proxy,
                server_id: Some(r.server_ids[1].clone()),
                enabled: true,
                expires_at: None,
                scenario: None,
                created_at: 1,
            });
            st.favorites.push(r.server_ids[0].clone());
            Ok(())
        })
        .unwrap();
        (d, s)
    }

    #[test]
    fn round_trip_with_password_keeps_servers_rules_and_links_between_them() {
        let (_d, a) = seeded();
        let (st, sec) = a.export_all().unwrap();
        let text = encode(&collect(&st, &sec, Sections { servers: true, rules: true, settings: true }), Some("correct horse")).unwrap();
        assert!(!text.contains("b831381d") && !text.contains("youtube"), "an encrypted backup reveals nothing");
        assert!(decode(&text, Some("wrong pass")).is_err());
        assert!(decode(&text, None).is_err());
        let (preview, data) = decode(&text, Some("correct horse")).unwrap();
        assert_eq!((preview.servers, preview.rules, preview.encrypted), (3, 1, true));

        let (_d2, b) = store();
        let r = apply(&b, data, RestoreMode::Merge).unwrap();
        assert_eq!((r.servers_added, r.servers_rejected, r.rules), (3, 0, 1), "{:?}", r.warnings);
        let st2 = b.state().unwrap();
        let trojan = st2.servers.iter().find(|m| m.name == "Trojan").unwrap();
        assert_eq!(st2.rules[0].server_id.as_deref(), Some(trojan.id.as_str()), "the rule follows its server to the new id");
        assert_eq!(st2.favorites.len(), 1);
        let (_m, s) = b.server_with_secrets(&trojan.id).unwrap();
        assert_eq!(s.password.as_deref(), Some("p@ss"));
        // Restoring the same backup again does not duplicate anything.
        let (_p, data) = decode(&text, Some("correct horse")).unwrap();
        let r = apply(&b, data, RestoreMode::Merge).unwrap();
        assert_eq!((r.servers_added, r.servers_updated, r.rules), (0, 3, 0));
    }

    #[test]
    fn hostile_backup_contents_are_rejected() {
        let (_d, a) = seeded();
        let (st, sec) = a.export_all().unwrap();
        let mut data = collect(&st, &sec, Sections { servers: true, rules: true, settings: false });
        data.servers[0].meta.address = "127.0.0.1".into();
        data.rules[0].values = vec!["geosite:cn".into()];
        data.subscriptions.push(BackupSubscription { meta: SubscriptionMeta { id: "x".into(), name: "x".into(), ..Default::default() }, url: "http://169.254.169.254/latest".into() });
        let text = encode(&data, None).unwrap();
        let (_p, data) = decode(&text, None).unwrap();
        let (_d2, b) = store();
        let r = apply(&b, data, RestoreMode::Replace).unwrap();
        assert_eq!((r.servers_added, r.servers_rejected, r.rules, r.subscriptions), (2, 1, 0, 0));
        assert!(decode("{\"format\":\"other\"}", None).is_err());
        assert!(decode("not json", None).is_err());
    }
}
