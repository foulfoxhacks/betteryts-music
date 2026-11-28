//! BetterYTS Sync: end-to-end encrypted library sync with the website and other devices.
//!
//! The other half of this protocol is the website's `web/sync.js` (spec: betteryts `docs/sync.md`).
//! The server (`/api/sync/{vaultId}`) only ever stores ciphertext, so a sync code is the only
//! secret: it is turned into a vault ID, an auth secret and an AES-256-GCM key with SHA-256, and
//! never leaves the user's devices.
//!
//! Conflicts are resolved per item rather than per library: a ledger records, for every liked
//! song, subscription, dislike, playlist, smart playlist and the settings, when it last changed
//! and a hash of what it was. The newest change wins, so liking a song on the phone and renaming a
//! playlist on the website both survive. The play queue and play statistics are not synced.

use std::collections::{BTreeMap, HashMap};
use std::path::{Path, PathBuf};

use aes_gcm::aead::{Aead, Payload};
use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
use base64::Engine as _;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};

use crate::api::Api;
use crate::backup::{self, Mode};
use crate::library::Store;
use crate::plugins::Settings;

pub const FORMAT: &str = "betteryts-sync";
/// Removals are remembered for this long, so a device that was off for a while learns about them.
const TOMBSTONE_MS: u64 = 180 * 24 * 3600 * 1000;
/// The server refuses ciphertext over 3,000,000 base64 characters.
const MAX_CIPHERTEXT: usize = 3_000_000;
const CROCKFORD: &[u8; 32] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";
/// Rules' fields, in the order the website hashes them.
const RULE_FIELDS: [&str; 13] = ["source", "minSeconds", "maxSeconds", "artist", "title", "minPlays", "maxPlays", "playedWithinDays", "notPlayedForDays", "excludeLive", "excludeDevice", "sort", "limit"];

// ---------------------------------------------------------------------------------------------
// Keys and codes
// ---------------------------------------------------------------------------------------------

fn hex(bytes: &[u8]) -> String {
	bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn sha256(text: &str) -> [u8; 32] {
	let mut hasher = Sha256::new();
	hasher.update(text.as_bytes());
	let mut out = [0u8; 32];
	out.copy_from_slice(&hasher.finalize());
	out
}

fn sha256_hex(text: &str) -> String {
	hex(&sha256(text))
}

/// 32 fresh random bytes: the sync code behind them is the vault's only key.
pub fn new_key() -> [u8; 32] {
	let mut key = [0u8; 32];
	if getrandom::getrandom(&mut key).is_err() {
		// No OS randomness: an unpredictable-but-unique key beats panicking on startup. Sync
		// would be unsafe to use, so nothing is stored until the user asks for a code.
		let seed = format!("{}:{}:{}", std::process::id(), Store::now(), &key as *const _ as usize);
		key = sha256(&seed);
	}
	key
}

/// `BTS1-XXXX-XXXX-…`: 52 Crockford base32 characters in groups of four.
pub fn code_of(key: &[u8; 32]) -> String {
	let mut bits: u64 = 0;
	let mut n = 0u32;
	let mut out = String::with_capacity(52);
	for b in key {
		bits = (bits << 8) | u64::from(*b);
		n += 8;
		while n >= 5 {
			n -= 5;
			out.push(CROCKFORD[((bits >> n) & 31) as usize] as char);
		}
		bits &= (1 << n) - 1;
	}
	if n > 0 {
		out.push(CROCKFORD[((bits << (5 - n)) & 31) as usize] as char);
	}
	let groups: Vec<&str> = (0..out.len()).step_by(4).map(|i| &out[i..(i + 4).min(out.len())]).collect();
	format!("BTS1-{}", groups.join("-"))
}

/// Forgiving parse: any case, with or without dashes, O/I/L read as 0/1/1 like Crockford says.
pub fn parse_code(code: &str) -> Option<[u8; 32]> {
	let upper = code.trim().to_uppercase();
	let body = upper.strip_prefix("BTS1").unwrap_or(&upper);
	let chars: Vec<u8> = body
		.bytes()
		.filter(|b| b.is_ascii_alphanumeric())
		.map(|b| match b {
			b'O' => b'0',
			b'I' | b'L' => b'1',
			_ => b,
		})
		.collect();
	if chars.len() != 52 {
		return None;
	}
	let mut bits: u64 = 0;
	let mut n = 0u32;
	let mut out = Vec::with_capacity(32);
	for c in chars {
		let v = CROCKFORD.iter().position(|x| *x == c)? as u64;
		bits = (bits << 5) | v;
		n += 5;
		if n >= 8 {
			n -= 8;
			out.push(((bits >> n) & 0xff) as u8);
			bits &= (1 << n) - 1;
		}
	}
	out.truncate(32);
	(out.len() == 32).then(|| {
		let mut key = [0u8; 32];
		key.copy_from_slice(&out);
		key
	})
}

/// `BTS1-…` from the all-sevens test key. Both the app and the website assert this exact string.
pub fn vault_id(key: &[u8; 32]) -> String {
	sha256_hex(&format!("betteryts-sync:id:{}", hex(key)))[..32].to_string()
}

pub fn auth_of(key: &[u8; 32]) -> String {
	sha256_hex(&format!("betteryts-sync:auth:{}", hex(key)))
}

fn encryption_key(key: &[u8; 32]) -> [u8; 32] {
	sha256(&format!("betteryts-sync:enc:{}", hex(key)))
}

/// AES-256-GCM with a random IV and the vault ID as associated data.
pub fn encrypt(key: &[u8; 32], plaintext: &str) -> Result<(String, String), String> {
	let cipher = Aes256Gcm::new_from_slice(&encryption_key(key)).map_err(|_| "Couldn't encrypt the library".to_string())?;
	let mut iv = [0u8; 12];
	getrandom::getrandom(&mut iv).map_err(|_| "Couldn't encrypt the library".to_string())?;
	let sealed = cipher
		.encrypt(Nonce::from_slice(&iv), Payload { msg: plaintext.as_bytes(), aad: vault_id(key).as_bytes() })
		.map_err(|_| "Couldn't encrypt the library".to_string())?;
	Ok((base64::engine::general_purpose::STANDARD.encode(iv), base64::engine::general_purpose::STANDARD.encode(sealed)))
}

pub fn decrypt(key: &[u8; 32], iv: &str, data: &str) -> Result<String, String> {
	let cipher = Aes256Gcm::new_from_slice(&encryption_key(key)).map_err(|_| "The synced library couldn't be decrypted (wrong code?)".to_string())?;
	let iv = base64::engine::general_purpose::STANDARD.decode(iv).map_err(|_| "The synced library couldn't be decrypted (wrong code?)".to_string())?;
	let data = base64::engine::general_purpose::STANDARD.decode(data).map_err(|_| "The synced library couldn't be decrypted (wrong code?)".to_string())?;
	if iv.len() != 12 {
		return Err("The synced library couldn't be decrypted (wrong code?)".into());
	}
	let plain = cipher.decrypt(Nonce::from_slice(&iv), Payload { msg: &data, aad: vault_id(key).as_bytes() }).map_err(|_| "The synced library couldn't be decrypted (wrong code?)".to_string())?;
	String::from_utf8(plain).map_err(|_| "The synced library couldn't be decrypted (wrong code?)".into())
}

// ---------------------------------------------------------------------------------------------
// Items, ledger and merge
// ---------------------------------------------------------------------------------------------

/// FNV-1a 32 over UTF-8, hex: the content hash both implementations use.
pub fn fnv(text: &str) -> String {
	let mut h: u32 = 0x811c9dc5;
	for b in text.as_bytes() {
		h ^= u32::from(*b);
		h = h.wrapping_mul(0x01000193);
	}
	format!("{h:08x}")
}

/// Ledger: key → (when it changed, its content hash or None when removed).
pub type Ledger = BTreeMap<String, (u64, Option<String>)>;
pub type Snapshot = BTreeMap<String, String>;
/// key → (hash, value).
pub type Items = BTreeMap<String, (String, Value)>;

fn arr<'a>(v: &'a Value, key: &str) -> &'a [Value] {
	v.get(key).and_then(Value::as_array).map(Vec::as_slice).unwrap_or(&[])
}

fn id_of(v: &Value) -> String {
	v.get("id").and_then(Value::as_str).unwrap_or("").to_string()
}

fn name_of(v: &Value) -> String {
	v.get("name").and_then(Value::as_str).unwrap_or("").to_string()
}

fn rule_text(rule: &Value) -> String {
	RULE_FIELDS
		.iter()
		.map(|k| match rule.get(*k) {
			Some(Value::Number(n)) => n.as_f64().map(|f| format!("{}", f.round() as i64)).unwrap_or_default(),
			Some(Value::String(s)) => s.clone(),
			Some(Value::Bool(b)) => if *b { "1".into() } else { "0".into() },
			_ => String::new(),
		})
		.collect::<Vec<_>>()
		.join("|")
}

fn settings_text(st: &Value) -> String {
	let hidden = |k: &str| st.get(k).and_then(Value::as_str).unwrap_or("").to_string();
	let mut skip: Vec<String> = st
		.get("skip")
		.and_then(Value::as_object)
		.map(|o| o.iter().filter(|(_, v)| v.as_bool() == Some(true)).map(|(k, _)| k.clone()).collect())
		.unwrap_or_default();
	skip.sort();
	format!("{}|{}|{}", hidden("hiddenChannels"), hidden("hiddenWords"), skip.join(","))
}

/// Everything that syncs, with its hash: `f:` liked, `c:` subscriptions, `d:` disliked,
/// `p:` playlists, `s:` smart playlists, `settings`.
pub fn items(backup: &Value) -> Items {
	let mut m = Items::new();
	let lib = backup.get("library").filter(|l| l.is_object()).unwrap_or(&Value::Null);
	for (prefix, key) in [("f:", "favorites"), ("c:", "subscriptions"), ("d:", "disliked")] {
		for v in arr(lib, key) {
			let id = id_of(v);
			if !id.is_empty() {
				m.insert(format!("{prefix}{id}"), ("1".to_string(), v.clone()));
			}
		}
	}
	for p in arr(lib, "playlists") {
		let id = id_of(p);
		if !id.is_empty() {
			let ids: Vec<String> = arr(p, "items").iter().map(id_of).collect();
			m.insert(format!("p:{id}"), (fnv(&format!("{}\n{}", name_of(p), ids.join(","))), p.clone()));
		}
	}
	if let Some(music) = backup.get("music") {
		for r in arr(music, "smart") {
			let name = name_of(r).to_lowercase();
			if !name.is_empty() {
				let rule = r.get("rule").cloned().unwrap_or(Value::Null);
				m.insert(format!("s:{name}"), (fnv(&rule_text(&rule)), r.clone()));
			}
		}
	}
	if let Some(st) = backup.get("settings").filter(|s| s.is_object()) {
		m.insert("settings".to_string(), (fnv(&settings_text(st)), st.clone()));
	}
	m
}

/// Records local changes since the last sync: new content under its key, gone keys as removals.
pub fn update_ledger(ledger: &mut Ledger, snapshot: &Snapshot, now: &Items, at: u64) {
	for (k, (hash, _)) in now {
		if snapshot.get(k) != Some(hash) {
			ledger.insert(k.clone(), (at, Some(hash.clone())));
		}
	}
	for k in snapshot.keys() {
		if !now.contains_key(k) {
			ledger.insert(k.clone(), (at, None));
		}
	}
}

/// Whether `a` is a newer change than `b`: later wins; on a tie, present beats removed, then the
/// larger hash (so both sides pick the same winner).
fn newer(a: &(u64, Option<String>), b: &(u64, Option<String>)) -> bool {
	if a.0 != b.0 {
		return a.0 > b.0;
	}
	if a.1.is_some() != b.1.is_some() {
		return a.1.is_some();
	}
	a.1.clone().unwrap_or_default() > b.1.clone().unwrap_or_default()
}

pub fn merge_ledgers(a: &Ledger, b: &Ledger) -> Ledger {
	let mut out = a.clone();
	for (k, e) in b {
		match out.get(k) {
			Some(have) if !newer(e, have) => {}
			_ => {
				out.insert(k.clone(), e.clone());
			}
		}
	}
	out
}

/// Forgets removals older than 180 days.
pub fn prune(ledger: &mut Ledger, now_ms: u64) {
	ledger.retain(|_, (at, hash)| hash.is_some() || now_ms.saturating_sub(*at) < TOMBSTONE_MS);
}

/// The merged backup: `local`, with every synced section rebuilt from the ledger's winners.
pub fn compose(ledger: &Ledger, local: &Value, remote: &Value) -> Value {
	let li = items(local);
	let ri = items(remote);
	let pick = |k: &str, hash: &str| -> Option<Value> {
		if let Some((h, v)) = li.get(k) {
			if h == hash {
				return Some(v.clone());
			}
		}
		if let Some((h, v)) = ri.get(k) {
			if h == hash {
				return Some(v.clone());
			}
		}
		li.get(k).or_else(|| ri.get(k)).map(|(_, v)| v.clone())
	};
	let live = |prefix: &str| -> Vec<(u64, Value)> {
		let mut out: Vec<(u64, Value)> = ledger
			.iter()
			.filter(|(k, (_, hash))| k.starts_with(prefix) && hash.is_some())
			.filter_map(|(k, (at, hash))| pick(k, hash.as_deref()?).map(|v| (*at, v)))
			.collect();
		out.sort_by(|a, b| b.0.cmp(&a.0).then_with(|| id_of(&a.1).cmp(&id_of(&b.1))));
		out
	};
	let ordered = |prefix: &str, local_list: &[Value]| -> Vec<Value> {
		let live_items = live(prefix);
		let ids: Vec<String> = local_list.iter().map(id_of).collect();
		let mut out: Vec<Value> = live_items.iter().filter(|(_, v)| !ids.contains(&id_of(v))).map(|(_, v)| v.clone()).collect();
		for id in ids {
			if let Some((_, v)) = live_items.iter().find(|(_, v)| id_of(v) == id) {
				out.push(v.clone());
			}
		}
		out
	};
	let mut out = local.clone();
	if !out.is_object() {
		out = json!({});
	}
	let empty = json!({});
	let l = local.get("library").filter(|v| v.is_object()).unwrap_or(&empty);
	let mut library = json!({});
	if let Some(o) = library.as_object_mut() {
		o.insert("favorites".into(), json!(ordered("f:", arr(l, "favorites"))));
		o.insert("subscriptions".into(), json!(ordered("c:", arr(l, "subscriptions"))));
		o.insert("disliked".into(), json!(ordered("d:", arr(l, "disliked"))));
		o.insert("playlists".into(), json!(ordered("p:", arr(l, "playlists"))));
		let remote_lib = remote.get("library").filter(|v| v.is_object()).unwrap_or(&empty);
		let mut history: Vec<Value> = arr(l, "history").iter().chain(arr(remote_lib, "history").iter()).cloned().collect();
		history.sort_by(|a, b| b.get("at").and_then(Value::as_u64).unwrap_or(0).cmp(&a.get("at").and_then(Value::as_u64).unwrap_or(0)));
		let mut seen = std::collections::HashSet::new();
		history.retain(|h| !id_of(h).is_empty() && seen.insert(id_of(h)));
		history.truncate(300);
		o.insert("history".into(), json!(history));
	}
	let smart: Vec<Value> = live("s:").into_iter().map(|(_, v)| v).collect();
	let mut music = out.get("music").cloned().filter(|m| m.is_object()).unwrap_or_else(|| json!({}));
	if let Some(m) = music.as_object_mut() {
		m.insert("smart".into(), json!(smart));
	}
	let settings_patch = ledger.get("settings").and_then(|(_, hash)| hash.as_deref()).and_then(|hash| pick("settings", hash));
	if let Some(o) = out.as_object_mut() {
		o.insert("library".into(), library);
		o.insert("music".into(), music);
		if let Some(st) = settings_patch {
			let merged_settings = o.get_mut("settings").filter(|s| s.is_object());
			match merged_settings.and_then(Value::as_object_mut) {
				Some(s) => {
					for k in ["hiddenChannels", "hiddenWords", "skip"] {
						if let Some(v) = st.get(k) {
							s.insert(k.to_string(), v.clone());
						}
					}
				}
				None => {
					o.insert("settings".into(), st);
				}
			}
		}
	}
	out
}

// ---------------------------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------------------------

/// What this device remembers between syncs. The code is the vault's only key; it is kept with
/// the rest of the app's private data (`sync.json`), never uploaded.
#[derive(Clone, Default, Serialize, Deserialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SyncState {
	#[serde(default)]
	pub code: Option<String>,
	#[serde(default)]
	pub version: u64,
	#[serde(default)]
	pub snapshot: Snapshot,
	#[serde(default)]
	pub ledger: Ledger,
	#[serde(default)]
	pub last_sync: Option<u64>,
}

impl SyncState {
	/// Moves to another code: the new vault starts empty, so the next sync uploads this library.
	pub fn set_code(&mut self, code: &str) -> Result<String, String> {
		let key = parse_code(code).ok_or_else(|| "That isn't a BetterYTS sync code (it looks like BTS1-…)".to_string())?;
		let normalized = code_of(&key);
		self.code = Some(normalized.clone());
		self.version = 0;
		self.snapshot.clear();
		self.ledger.clear();
		self.last_sync = None;
		Ok(normalized)
	}

	pub fn view(&self) -> Value {
		json!({
			"enabled": self.code.is_some(),
			"code": self.code,
			"version": self.version,
			"lastSync": self.last_sync,
			"items": self.snapshot.len(),
		})
	}
}

pub struct SyncStore {
	path: Option<PathBuf>,
	pub state: SyncState,
}

impl SyncStore {
	pub fn in_memory() -> Self {
		SyncStore { path: None, state: SyncState::default() }
	}

	pub fn open(dir: &Path) -> Self {
		let path = dir.join("sync.json");
		let state = std::fs::read_to_string(&path).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default();
		SyncStore { path: Some(path), state }
	}

	pub fn save(&self) -> Result<(), String> {
		let Some(path) = &self.path else { return Ok(()) };
		let tmp = path.with_extension("json.tmp");
		std::fs::write(&tmp, serde_json::to_string(&self.state).map_err(|e| e.to_string())?).map_err(|e| format!("Couldn't save sync settings: {e}"))?;
		std::fs::rename(&tmp, path).map_err(|e| format!("Couldn't save sync settings: {e}"))
	}
}

// ---------------------------------------------------------------------------------------------
// One sync round
// ---------------------------------------------------------------------------------------------

pub struct Outcome {
	/// True when this device's library gains something from the merge.
	pub changed: bool,
	pub version: u64,
	pub merged: Value,
}

fn request(api: &Api, method: &str, url: &str, auth: &str, body: Option<&Value>) -> Result<(u16, Value), String> {
	let agent = api.http();
	let req = match method {
		"GET" => agent.get(url),
		"PUT" => agent.put(url),
		"DELETE" => agent.delete(url),
		_ => return Err("Unsupported sync request".into()),
	};
	let req = req.set("Authorization", &format!("Bearer {auth}")).set("Cache-Control", "no-store").timeout(std::time::Duration::from_secs(60));
	let result = match body {
		Some(b) => req.set("Content-Type", "application/json").send_string(&b.to_string()),
		None => req.call(),
	};
	match result {
		Ok(r) => {
			let status = r.status();
			Ok((status, r.into_json().unwrap_or(Value::Null)))
		}
		Err(ureq::Error::Status(status, r)) => Ok((status, r.into_json().unwrap_or(Value::Null))),
		Err(_) => Err("Can't reach BetterYTS. Check your connection.".into()),
	}
}

fn failure(status: u16, body: &Value, fallback: &str) -> String {
	if let Some(message) = body.pointer("/error/message").and_then(Value::as_str).filter(|m| !m.is_empty()) {
		return message.to_string();
	}
	match status {
		403 => "That sync code belongs to another library. Use the code the other device shows.".into(),
		413 => "That library is too big to sync.".into(),
		429 => "Too many syncs this hour. Try again later.".into(),
		401 => "The sync server refused this code.".into(),
		_ => format!("{fallback} (HTTP {status})"),
	}
}

/// One sync of `local` (a backup object from [`backup::export`]) against the vault: merge the
/// ledgers, rebuild the library from the winners, and upload when the vault is behind.
pub fn sync_once(api: &Api, state: &mut SyncState, local: &Value, now_ms: u64) -> Result<Outcome, String> {
	let key = state.code.as_deref().and_then(parse_code).ok_or_else(|| "Sync isn't set up on this device".to_string())?;
	let url = format!("{}/api/sync/{}", api.base().trim_end_matches('/'), vault_id(&key));
	let auth = auth_of(&key);
	let mine = items(local);
	let mut ledger = std::mem::take(&mut state.ledger);
	update_ledger(&mut ledger, &state.snapshot, &mine, now_ms);

	for _ in 0..3 {
		let (status, body) = request(api, "GET", &url, &auth, None)?;
		let (remote_version, remote_backup, remote_ledger) = match status {
			200 => {
				let data = body.get("data").cloned().unwrap_or(Value::Null);
				let iv = data.get("iv").and_then(Value::as_str).ok_or("The sync server sent something unexpected")?;
				let ciphertext = data.get("data").and_then(Value::as_str).ok_or("The sync server sent something unexpected")?;
				if ciphertext.len() > MAX_CIPHERTEXT {
					return Err("That sync vault is too big to read".into());
				}
				let plain = decrypt(&key, iv, ciphertext)?;
				let payload: Value = serde_json::from_str(&plain).map_err(|_| "The synced library couldn't be read; try again from the other device".to_string())?;
				if payload.get("format").and_then(Value::as_str) != Some(FORMAT) {
					return Err("The synced library is from a newer version of BetterYTS; update the app".into());
				}
				let ledger: Ledger = match payload.get("ledger") {
					Some(v) => serde_json::from_value(v.clone()).unwrap_or_default(),
					None => Ledger::new(),
				};
				(data.get("version").and_then(Value::as_u64).unwrap_or(0), payload.get("backup").cloned().unwrap_or_else(|| json!({})), ledger)
			}
			404 => (0, json!({}), Ledger::new()),
			_ => return Err(failure(status, &body, "Sync failed")),
		};

		let mut merged = merge_ledgers(&ledger, &remote_ledger);
		prune(&mut merged, now_ms);
		let result = compose(&merged, local, &remote_backup);
		let merged_items = items(&result);
		let signature = |m: &Items| -> Vec<String> { m.iter().map(|(k, (h, _))| format!("{k}={h}")).collect() };
		let history_of = |v: &Value| serde_json::to_string(v.get("library").and_then(|l| l.get("history")).unwrap_or(&Value::Null)).unwrap_or_default();
		let changed = signature(&merged_items) != signature(&mine) || history_of(&result) != history_of(local);

		let mut version = remote_version;
		if remote_version == 0 || merged != remote_ledger {
			let payload = json!({ "format": FORMAT, "version": 1, "backup": result.clone(), "ledger": merged.clone() });
			let (iv, data) = encrypt(&key, &payload.to_string())?;
			if data.len() > MAX_CIPHERTEXT {
				return Err("That library is too big to sync".into());
			}
			let (status, body) = request(api, "PUT", &url, &auth, Some(&json!({ "iv": iv, "data": data, "baseVersion": remote_version })))?;
			match status {
				200 => version = body.pointer("/data/version").and_then(Value::as_u64).unwrap_or(remote_version + 1),
				409 => {
					// Another device wrote first: merge its version and try again.
					ledger = merged.clone();
					continue;
				}
				_ => return Err(failure(status, &body, "Couldn't save to the sync vault")),
			}
		}

		state.version = version;
		state.snapshot = merged_items.iter().map(|(k, (h, _))| (k.clone(), h.clone())).collect();
		state.ledger = merged;
		state.last_sync = Some(now_ms);
		return Ok(Outcome { changed, version, merged: result });
	}
	Err("Other devices kept syncing at the same time; try again in a moment".into())
}

/// Erases the vault (both devices keep their local library).
pub fn erase(api: &Api, state: &mut SyncState) -> Result<(), String> {
	let Some(key) = state.code.as_deref().and_then(parse_code) else { return Ok(()) };
	let url = format!("{}/api/sync/{}", api.base().trim_end_matches('/'), vault_id(&key));
	let (status, body) = request(api, "DELETE", &url, &auth_of(&key), None)?;
	if status == 200 || status == 404 {
		state.version = 0;
		state.snapshot.clear();
		state.ledger.clear();
		state.last_sync = None;
		return Ok(());
	}
	Err(failure(status, &body, "Couldn't erase the sync vault"))
}

/// Applies a merged backup: the synced sections become exactly what the merge decided, so
/// removals on another device reach this one. Play statistics, pins and the queue stay.
pub fn apply(merged: &Value, store: &mut Store, settings: &mut Settings) -> Result<(), String> {
	store.lib.smart.clear();
	store.lib.disliked.clear();
	if merged.get("settings").is_some() {
		settings.set_hidden(&[], &[]);
		settings.sponsor_categories.clear();
	}
	backup::import_with(&merged.to_string(), store, settings, Mode::Replace)?;
	Ok(())
}

/// A rough count of what changed, for the "Synced: 3 new songs" message.
pub fn diff_counts(before: &Items, after: &Items) -> HashMap<&'static str, usize> {
	let mut out = HashMap::new();
	for prefix in ["f:", "c:", "d:", "p:", "s:"] {
		let count = |m: &Items, p: &str| m.keys().filter(|k| k.starts_with(p)).count();
		let (a, b) = (count(before, prefix), count(after, prefix));
		out.insert(prefix, b.saturating_sub(a));
	}
	out
}

#[cfg(test)]
mod tests {
	use super::*;
	use std::io::{Read, Write};
	use std::net::TcpListener;
	use std::sync::{Arc, Mutex};

	const SEVENS: [u8; 32] = [7u8; 32];

	fn t(id: &str) -> Value {
		json!({ "id": id, "title": id, "channel": "A" })
	}

	fn track(id: &str) -> crate::model::Track {
		serde_json::from_value(json!({ "id": id, "title": id, "artist": "A" })).unwrap()
	}

	fn backup(favorites: Vec<Value>, playlists: Vec<Value>) -> Value {
		json!({ "format": "betteryts-backup", "version": 1,
			"settings": { "hiddenChannels": "", "hiddenWords": "", "skip": { "sponsor": true } },
			"library": { "favorites": favorites, "playlists": playlists, "subscriptions": [], "disliked": [], "history": [] } })
	}

	#[test]
	fn fixed_vectors_match_the_website() {
		assert_eq!(code_of(&SEVENS), "BTS1-0W3G-E1R7-0W3G-E1R7-0W3G-E1R7-0W3G-E1R7-0W3G-E1R7-0W3G-E1R7-0W3G");
		assert_eq!(parse_code(&code_of(&SEVENS)), Some(SEVENS));
		assert_eq!(parse_code("bts1 0w3g e1r7 0w3g e1r7 0w3g e1r7 0w3g e1r7 0w3g e1r7 0w3g e1r7 0w3g"), Some(SEVENS), "forgiving input");
		assert_eq!(parse_code("BTS1-1234"), None);
		assert_eq!(vault_id(&SEVENS), "b0d631bf62a827a8f44dcdfce33b8177");
		assert_eq!(auth_of(&SEVENS), "7f5f4308e30827dc7bb18657ef48ee9b779115e2aff5efb0b9c8eec47ed7629a");
		assert_eq!(fnv("hello"), "4f9f2cab");
	}

	#[test]
	fn reads_ciphertext_the_website_made_and_back() {
		// Ciphertext the website stores for the all-sevens key (test/sync-client.test.ts).
		let plain = decrypt(&SEVENS, "eD8Uq7WeFxWw4mdH", "gvVU6w0s+iCYhegqGp/cR/ub9KLbRJnZRDNvmzaTYNBVDcls8TROfGQveIFZPsvGy36O07+JLA==").unwrap();
		assert_eq!(plain, "{\"format\":\"betteryts-sync\",\"version\":1}");
		let (iv, data) = encrypt(&SEVENS, "round trip").unwrap();
		assert_eq!(decrypt(&SEVENS, &iv, &data).unwrap(), "round trip");
		assert!(decrypt(&new_key(), &iv, &data).is_err(), "another key can't read it");
	}

	#[test]
	fn item_hashes_match_the_website() {
		let b = json!({
			"settings": { "hiddenChannels": "", "hiddenWords": "", "skip": { "sponsor": true } },
			"library": { "playlists": [{ "id": "p1", "name": "Road trip", "items": [t("aaaaaaaaaaa"), t("bbbbbbbbbbb")] }] }
		});
		let m = items(&b);
		assert_eq!(m["p:p1"].0, fnv("Road trip\naaaaaaaaaaa,bbbbbbbbbbb"));
		assert_eq!(m["settings"].0, fnv("||sponsor"));
	}

	#[test]
	fn ledger_merge_prefers_the_newer_change() {
		let mut a = Ledger::new();
		let mut b = Ledger::new();
		a.insert("f:one".into(), (100, Some("1".into())));
		b.insert("f:one".into(), (200, None));
		a.insert("f:two".into(), (300, None));
		assert_eq!(merge_ledgers(&a, &b)["f:one"], (200, None), "the later removal wins");
		assert_eq!(merge_ledgers(&a, &b)["f:two"], (300, None), "and is known to the other side");
		assert_eq!(merge_ledgers(&b, &a)["f:one"], (200, None), "both sides pick the same winner");
		// A tie goes to what still exists, then to the larger hash.
		a.insert("f:three".into(), (400, None));
		b.insert("f:three".into(), (400, Some("1".into())));
		assert_eq!(merge_ledgers(&a, &b)["f:three"].1.as_deref(), Some("1"));
	}

	#[test]
	fn prune_forgets_old_removals_only() {
		let mut l = Ledger::new();
		l.insert("f:gone".into(), (0, None));
		l.insert("f:here".into(), (0, Some("1".into())));
		prune(&mut l, TOMBSTONE_MS + 1);
		assert!(!l.contains_key("f:gone"));
		assert!(l.contains_key("f:here"), "present items are never pruned");
	}

	#[test]
	fn compose_rebuilds_the_library_from_the_winners() {
		let local = backup(vec![t("a"), t("b")], vec![]);
		let mine = items(&local);
		let mut ledger = Ledger::new();
		update_ledger(&mut ledger, &Snapshot::new(), &mine, 1000);
		let out = compose(&ledger, &local, &Value::Null);
		assert_eq!(out["library"]["favorites"].as_array().unwrap().len(), 2);
		assert_eq!(out["library"]["disliked"].as_array().unwrap().len(), 0);
		// A removal recorded later drops the song, and it comes from the remote side if needed.
		ledger.insert("f:b".into(), (2000, None));
		let out = compose(&ledger, &local, &Value::Null);
		let ids: Vec<&str> = out["library"]["favorites"].as_array().unwrap().iter().map(|v| v["id"].as_str().unwrap()).collect();
		assert_eq!(ids, ["a"]);
		let remote = backup(vec![t("c")], vec![json!({ "id": "p9", "name": "Theirs", "items": [t("c")] })]);
		ledger.insert("p:p9".into(), (1500, Some(fnv("Theirs\nc"))));
		ledger.insert("f:c".into(), (1500, Some("1".into())));
		let out = compose(&ledger, &local, &remote);
		assert_eq!(out["library"]["favorites"].as_array().unwrap().len(), 2, "a and c");
		assert_eq!(out["library"]["playlists"][0]["name"], "Theirs", "a playlist from the other device");
	}

	/// A fake vault: GET/PUT with the same versioning rules as the website's SyncVault.
	struct Vault {
		version: u64,
		iv: String,
		data: String,
		/// Pretend another device wrote between our GET and PUT, this many times.
		conflicts: i32,
	}

	fn serve(vault: Arc<Mutex<Vault>>) -> String {
		let listener = TcpListener::bind("127.0.0.1:0").unwrap();
		let base = format!("http://{}", listener.local_addr().unwrap());
		std::thread::spawn(move || {
			for stream in listener.incoming() {
				let Ok(mut s) = stream else { continue };
				let mut raw = Vec::new();
				let mut buf = [0u8; 4096];
				while !raw.windows(4).any(|w| w == b"\r\n\r\n") {
					let Ok(n) = s.read(&mut buf) else { break };
					if n == 0 {
						break;
					}
					raw.extend_from_slice(&buf[..n]);
				}
				let head = String::from_utf8_lossy(&raw).to_string();
				let len = head.lines().find_map(|l| l.to_lowercase().strip_prefix("content-length:").map(|v| v.trim().parse::<usize>().unwrap_or(0))).unwrap_or(0);
				let start = raw.windows(4).position(|w| w == b"\r\n\r\n").map(|i| i + 4).unwrap_or(raw.len());
				while raw.len() < start + len {
					let Ok(n) = s.read(&mut buf) else { break };
					if n == 0 {
						break;
					}
					raw.extend_from_slice(&buf[..n]);
				}
				let body: Value = serde_json::from_slice(&raw[start..(start + len).min(raw.len())]).unwrap_or(Value::Null);
				let method = head.split_whitespace().next().unwrap_or("GET").to_string();
				let vault = vault.lock().unwrap();
				let (status, reply) = if method == "GET" {
					if vault.version == 0 {
						(404, json!({ "ok": false, "error": { "code": "NOT_FOUND", "message": "Nothing synced yet" } }))
					} else {
						(200, json!({ "ok": true, "data": { "version": vault.version, "updatedAt": 1, "iv": vault.iv, "data": vault.data } }))
					}
				} else if method == "PUT" {
					let base = body.get("baseVersion").and_then(Value::as_u64).unwrap_or(0);
					if vault.conflicts > 0 {
						let mut vault = vault;
						vault.conflicts -= 1;
						vault.version += 1;
						(409, json!({ "ok": false, "error": { "code": "CONFLICT", "message": "Another device synced first; merge and try again", "version": vault.version } }))
					} else if base != vault.version {
						(409, json!({ "ok": false, "error": { "code": "CONFLICT", "message": "Another device synced first; merge and try again", "version": vault.version } }))
					} else {
						let mut vault = vault;
						vault.version += 1;
						vault.iv = body.get("iv").and_then(Value::as_str).unwrap_or("").to_string();
						vault.data = body.get("data").and_then(Value::as_str).unwrap_or("").to_string();
						(200, json!({ "ok": true, "data": { "version": vault.version, "updatedAt": 2 } }))
					}
				} else {
					(405, json!({ "ok": false, "error": { "code": "METHOD_NOT_ALLOWED", "message": "Use GET, PUT or DELETE" } }))
				};
				let text = reply.to_string();
				let _ = write!(s, "HTTP/1.1 {status} OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{text}", text.len());
			}
		});
		base
	}

	#[test]
	fn two_devices_converge_removals_included() {
		let vault = Arc::new(Mutex::new(Vault { version: 0, iv: String::new(), data: String::new(), conflicts: 0 }));
		let base = serve(vault.clone());
		let api = Api::new(&base);
		let code = code_of(&new_key());
		let mut a = SyncState::default();
		a.set_code(&code).unwrap();
		let mut b = SyncState::default();
		b.set_code(&code).unwrap();

		let lib_a = backup(vec![t("a"), t("b")], vec![]);
		let first = sync_once(&api, &mut a, &lib_a, 1000).unwrap();
		assert_eq!((first.version, first.changed), (1, false), "the first upload changes nothing locally");

		let lib_b = backup(vec![], vec![json!({ "id": "p1", "name": "Mix", "items": [t("a")] })]);
		let second = sync_once(&api, &mut b, &lib_b, 2000).unwrap();
		assert!(second.changed);
		let mut ids: Vec<&str> = second.merged["library"]["favorites"].as_array().unwrap().iter().map(|v| v["id"].as_str().unwrap()).collect();
		ids.sort();
		assert_eq!(ids, ["a", "b"], "B gets A's likes");
		assert_eq!(second.merged["library"]["playlists"].as_array().unwrap().len(), 1);

		// A learns what B added before it removes "b", exactly like the website's own test.
		sync_once(&api, &mut a, &second.merged, 3000).unwrap();
		let mut lib_a2 = second.merged.clone();
		lib_a2["library"]["favorites"] = json!([t("a")]);
		sync_once(&api, &mut a, &lib_a2, 4000).unwrap();
		let third = sync_once(&api, &mut b, &second.merged, 5000).unwrap();
		let ids: Vec<&str> = third.merged["library"]["favorites"].as_array().unwrap().iter().map(|v| v["id"].as_str().unwrap()).collect();
		assert_eq!(ids, ["a"], "the removal reached B");

		// The vault only ever holds ciphertext with B's added playlist.
		let stored = vault.lock().unwrap().data.clone();
		assert!(!stored.contains("Mix"), "the server sees no plaintext");
		assert!(stored.len() > 20);
	}

	#[test]
	fn a_stale_write_is_retried_after_the_other_device_syncs() {
		let vault = Arc::new(Mutex::new(Vault { version: 0, iv: String::new(), data: String::new(), conflicts: 0 }));
		let base = serve(vault.clone());
		let api = Api::new(&base);
		let code = code_of(&new_key());
		let mut a = SyncState::default();
		a.set_code(&code).unwrap();
		let mut b = SyncState::default();
		b.set_code(&code).unwrap();
		sync_once(&api, &mut a, &backup(vec![t("a")], vec![]), 1000).unwrap();
		// Another device writes between our GET and our PUT: the sync must merge and try again.
		vault.lock().unwrap().conflicts = 1;
		let both = backup(vec![t("a"), t("b")], vec![]);
		let out = sync_once(&api, &mut b, &both, 3000).unwrap();
		assert_eq!(out.merged["library"]["favorites"].as_array().unwrap().len(), 2);
		assert_eq!(out.version, vault.lock().unwrap().version, "the retry landed on the newest version");
	}

	#[test]
	fn apply_replaces_the_synced_sections_and_drops_removals() {
		let mut store = Store::in_memory();
		let mut settings = Settings::default();
		store.lib.toggle_favorite(track("ooooooooooo"));
		store.lib.toggle_dislike(track("hhhhhhhhhhh"));
		store.lib.create_playlist("Gone", vec![track("ooooooooooo")]);
		settings.set_hidden(&["Hidden".into()], &[]);
		let merged = json!({
			"format": "betteryts-backup", "version": 1,
			"settings": { "hiddenChannels": "Only this", "hiddenWords": "", "skip": { "sponsor": true } },
			"library": { "favorites": [t("nnnnnnnnnnn")], "disliked": [t("hhhhhhhhhhh")], "playlists": [], "subscriptions": [], "history": [] },
		});
		apply(&merged, &mut store, &mut settings).unwrap();
		assert_eq!(store.lib.favorites.iter().map(|f| f.id.as_str()).collect::<Vec<_>>(), ["nnnnnnnnnnn"]);
		assert!(store.lib.is_disliked("hhhhhhhhhhh"));
		assert!(store.lib.playlists.is_empty(), "a playlist the merge dropped is gone");
		assert_eq!(settings.hidden_channels, ["Only this"]);
		assert_eq!(settings.sponsor_categories, ["sponsor"]);
	}

	#[test]
	fn a_new_code_starts_a_fresh_vault() {
		let mut s = SyncState::default();
		let code = s.set_code("bts1-1234").unwrap_err();
		assert!(code.contains("sync code"));
		let good = s.set_code(&code_of(&SEVENS)).unwrap();
		assert_eq!(good, code_of(&SEVENS), "stored in a canonical form");
		s.version = 7;
		s.snapshot.insert("f:a".into(), "1".into());
		s.set_code(&code_of(&new_key())).unwrap();
		assert_eq!((s.version, s.snapshot.len(), s.ledger.len()), (0, 0, 0));
	}
}
