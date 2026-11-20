//! Plugins and app settings.
//!
//! Plugins are built in (like Pear's), each one switchable in Settings → Plugins. Their data
//! comes through here: SponsorBlock segments and dislike counts from the BetterYTS API, synced
//! lyrics from LRCLIB, ProTV playlists for the VRChat bridge. What a plugin does on screen lives
//! in the app; this module decides what exists, what's on, and fetches what plugins need.

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::time::Duration;

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::api::{enc, Api};
use crate::model::{s, Track};

#[derive(Serialize, Clone, Copy, Debug)]
#[serde(rename_all = "camelCase")]
pub struct PluginInfo {
	pub id: &'static str,
	pub name: &'static str,
	pub description: &'static str,
	pub category: &'static str,
	pub default_enabled: bool,
}

pub const CATALOG: &[PluginInfo] = &[
	PluginInfo {
		id: "sponsorblock",
		name: "SponsorBlock for music",
		description: "Skips the parts of music videos that aren't music: intros, skits, outros. Uses community-submitted SponsorBlock segments.",
		category: "Playback",
		default_enabled: true,
	},
	PluginInfo {
		id: "background",
		name: "Background play",
		description: "Keep the music going with the screen off or in other apps, with lock-screen, notification, headset and car controls. Goes beyond YouTube's embedded-player terms, so it's for sideloaded builds.",
		category: "Playback",
		default_enabled: true,
	},
	PluginInfo {
		id: "radio",
		name: "Endless radio",
		description: "When your queue runs low, keep going with songs like the one playing, from YouTube Music's own radio. Adds \"Start radio\" to every song.",
		category: "Playback",
		default_enabled: true,
	},
	PluginInfo {
		id: "lyrics",
		name: "Synced lyrics",
		description: "Time-synced lyrics from LRCLIB in the player's Lyrics tab. Tap a line to jump to it.",
		category: "Player",
		default_enabled: true,
	},
	PluginInfo {
		id: "dislikes",
		name: "Return YouTube Dislike",
		description: "Shows like and dislike counts in the player.",
		category: "Player",
		default_enabled: true,
	},
	PluginInfo {
		id: "sleep",
		name: "Sleep timer",
		description: "Stops the music after 15 minutes to 2 hours, or at the end of the current song.",
		category: "Playback",
		default_enabled: true,
	},
	PluginInfo {
		id: "together",
		name: "Listen Together",
		description: "BetterYTS watch-party rooms: listen in sync with friends in the app, on the website, or in VRChat.",
		category: "BetterYTS",
		default_enabled: true,
	},
	PluginInfo {
		id: "vrchat",
		name: "VRChat bridge",
		description: "Copy the current song for any VRChat video player, or the whole queue as a ProTV playlist.",
		category: "BetterYTS",
		default_enabled: true,
	},
];

/// Plugin categories. Each has a whole-category switch; a plugin runs only if both it and its
/// category are on. `permissions` is what a *community* plugin in that category may ask for;
/// categories with `community: false` are for built-in plugins only, because what they do
/// (control playback, open rooms, talk to the network) can't be sandboxed safely.
#[derive(Serialize, Clone, Copy, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Category {
	pub id: &'static str,
	pub name: &'static str,
	pub description: &'static str,
	pub community: bool,
	pub permissions: &'static [&'static str],
}

pub const CATEGORIES: &[Category] = &[
	Category { id: "Playback", name: "Playback", description: "What plays and when. Built-in plugins only.", community: false, permissions: &[] },
	Category {
		id: "Player",
		name: "Player",
		description: "Extras shown with the song. Community plugins here can see what's playing and show a text panel.",
		community: true,
		permissions: &["nowPlaying", "panel"],
	},
	Category { id: "Look", name: "Look", description: "Colours and style. Community plugins here can change the accent colours.", community: true, permissions: &["palette"] },
	Category { id: "BetterYTS", name: "BetterYTS", description: "Rooms, VRChat and the rest of the ecosystem. Built-in plugins only.", community: false, permissions: &[] },
];

/// What each permission lets a community plugin do, in words for the install prompt.
pub const PERMISSIONS: &[(&str, &str)] = &[
	("nowPlaying", "See the title, artist and position of the song that's playing"),
	("panel", "Show a short text panel in the player"),
	("palette", "Change the accent colours"),
];

/// Colours a Look plugin may set.
pub const PALETTE_KEYS: &[&str] = &["accent", "hot"];
pub const MAX_COMMUNITY: usize = 10;
pub const MAX_CODE_BYTES: usize = 20_000;

/// A community plugin, as installed from a manifest. Its code never runs in the app's own
/// context: the app runs it in a sandbox (a Worker in a WebView with network loads blocked) and
/// only sends it what its permissions allow.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct CommunityPlugin {
	pub id: String,
	pub name: String,
	#[serde(default)]
	pub version: String,
	#[serde(default)]
	pub author: String,
	#[serde(default)]
	pub description: String,
	pub category: String,
	pub permissions: Vec<String>,
	#[serde(default)]
	pub code: String,
	#[serde(default)]
	pub palette: BTreeMap<String, String>,
}

fn clean(v: &Value, key: &str, max: usize) -> Result<String, String> {
	let raw = match v.get(key) {
		None | Some(Value::Null) => "",
		Some(Value::String(s)) => s.as_str(),
		Some(_) => return Err(format!("`{key}` must be text")),
	};
	let t: String = raw.chars().filter(|c| !c.is_control()).collect::<String>().trim().to_string();
	if t.chars().count() > max {
		return Err(format!("`{key}` is longer than {max} characters"));
	}
	Ok(t)
}

fn is_hex_color(c: &str) -> bool {
	c.len() == 7 && c.starts_with('#') && c[1..].chars().all(|ch| ch.is_ascii_hexdigit())
}

/// Checks a community plugin manifest (JSON text). The rules match the website's
/// web/plugins.js, so a manifest that installs on one installs on the other.
pub fn validate_manifest(text: &str) -> Result<CommunityPlugin, String> {
	if text.len() > MAX_CODE_BYTES + 4_000 {
		return Err("That manifest is too big".into());
	}
	let v: Value = serde_json::from_str(text).map_err(|_| "That isn't a plugin manifest (expected JSON)".to_string())?;
	if !v.is_object() {
		return Err("That isn't a plugin manifest (expected a JSON object)".into());
	}
	let id = clean(&v, "id", 32)?;
	let id_ok = id.len() >= 3 && id.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-') && !id.starts_with('-');
	if !id_ok {
		return Err("`id` must be 3-32 characters: lowercase letters, digits and dashes".into());
	}
	if CATALOG.iter().any(|p| p.id == id) {
		return Err(format!("`{id}` is a built-in plugin"));
	}
	let name = clean(&v, "name", 40)?;
	if name.is_empty() {
		return Err("`name` is required".into());
	}
	let category = clean(&v, "category", 20)?;
	let cat = CATEGORIES.iter().find(|c| c.id == category).ok_or_else(|| format!("Unknown category `{category}`"))?;
	if !cat.community {
		let allowed: Vec<&str> = CATEGORIES.iter().filter(|c| c.community).map(|c| c.id).collect();
		return Err(format!("Community plugins can't be in {} (allowed: {})", cat.name, allowed.join(", ")));
	}
	let mut permissions: Vec<String> = Vec::new();
	for p in v.get("permissions").and_then(Value::as_array).ok_or("`permissions` must be a list")? {
		let p = p.as_str().ok_or("`permissions` must be a list of names")?;
		if !cat.permissions.contains(&p) {
			return Err(format!("A {} plugin can't ask for `{p}` (allowed: {})", cat.name, cat.permissions.join(", ")));
		}
		if !permissions.iter().any(|x| x == p) {
			permissions.push(p.to_string());
		}
	}
	if permissions.is_empty() {
		return Err("`permissions` is empty: the plugin couldn't do anything".into());
	}
	let code = match v.get("code") {
		None | Some(Value::Null) => String::new(),
		Some(Value::String(c)) => c.clone(),
		Some(_) => return Err("`code` must be text".into()),
	};
	let runs = permissions.iter().any(|p| p == "nowPlaying" || p == "panel");
	if runs && code.trim().is_empty() {
		return Err("This plugin needs `code`".into());
	}
	if !runs && !code.is_empty() {
		return Err("Only Player plugins can run code".into());
	}
	if code.len() > MAX_CODE_BYTES {
		return Err(format!("`code` is over {} KB", MAX_CODE_BYTES / 1000));
	}
	let mut palette = BTreeMap::new();
	if let Some(p) = v.get("palette").filter(|p| !p.is_null()) {
		let obj = p.as_object().ok_or("`palette` must be an object")?;
		for (k, c) in obj {
			if !PALETTE_KEYS.contains(&k.as_str()) {
				return Err(format!("`palette` can only set {}", PALETTE_KEYS.join(", ")));
			}
			let c = c.as_str().filter(|c| is_hex_color(c)).ok_or_else(|| format!("`palette.{k}` must be a colour like #ff8800"))?;
			palette.insert(k.clone(), c.to_ascii_lowercase());
		}
	}
	let wants_palette = permissions.iter().any(|p| p == "palette");
	if wants_palette && palette.is_empty() {
		return Err("A Look plugin needs a `palette`".into());
	}
	if !wants_palette && !palette.is_empty() {
		return Err("`palette` needs the `palette` permission".into());
	}
	Ok(CommunityPlugin {
		id,
		name,
		version: clean(&v, "version", 16)?,
		author: clean(&v, "author", 40)?,
		description: clean(&v, "description", 200)?,
		category,
		permissions,
		code,
		palette,
	})
}

pub const THEMES: &[(&str, &str)] = &[("ember", "Ember · akasammythepuppy"), ("mello", "Mello · Mello Zone")];

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct Settings {
	pub theme: String,
	/// Only plugins the user has switched; the rest follow the catalog's default.
	pub plugins: BTreeMap<String, bool>,
	/// Only categories the user has switched; the rest are on.
	pub categories: BTreeMap<String, bool>,
	pub community: Vec<CommunityPlugin>,
}

impl Settings {
	pub fn theme(&self) -> &str {
		THEMES.iter().map(|(id, _)| *id).find(|id| *id == self.theme).unwrap_or("ember")
	}

	pub fn category_on(&self, id: &str) -> bool {
		self.categories.get(id).copied().unwrap_or(true)
	}

	fn category_of(&self, id: &str) -> Option<&str> {
		CATALOG.iter().find(|p| p.id == id).map(|p| p.category).or_else(|| self.community.iter().find(|p| p.id == id).map(|p| p.category.as_str()))
	}

	/// The plugin's own switch (ignoring its category).
	pub fn switched_on(&self, id: &str) -> bool {
		self.plugins.get(id).copied().unwrap_or_else(|| CATALOG.iter().any(|p| p.id == id && p.default_enabled))
	}

	/// True if the plugin should run: it and its category are both on.
	pub fn enabled(&self, id: &str) -> bool {
		match self.category_of(id) {
			Some(cat) => self.category_on(cat) && self.switched_on(id),
			None => false,
		}
	}

	/// Returns false for an unknown plugin or theme.
	pub fn set_plugin(&mut self, id: &str, on: bool) -> bool {
		if self.category_of(id).is_none() {
			return false;
		}
		self.plugins.insert(id.to_string(), on);
		true
	}

	pub fn set_category(&mut self, id: &str, on: bool) -> bool {
		if !CATEGORIES.iter().any(|c| c.id == id) {
			return false;
		}
		self.categories.insert(id.to_string(), on);
		true
	}

	/// Installs (or updates, by id) a community plugin from its manifest; it starts switched on.
	pub fn install(&mut self, manifest: &str) -> Result<CommunityPlugin, String> {
		let p = validate_manifest(manifest)?;
		match self.community.iter().position(|x| x.id == p.id) {
			Some(i) => self.community[i] = p.clone(),
			None if self.community.len() >= MAX_COMMUNITY => return Err(format!("You can have up to {MAX_COMMUNITY} community plugins")),
			None => self.community.push(p.clone()),
		}
		self.plugins.insert(p.id.clone(), true);
		Ok(p)
	}

	pub fn remove(&mut self, id: &str) -> bool {
		let before = self.community.len();
		self.community.retain(|p| p.id != id);
		self.plugins.remove(id);
		self.community.len() != before
	}

	pub fn set_theme(&mut self, id: &str) -> bool {
		if !THEMES.iter().any(|(t, _)| *t == id) {
			return false;
		}
		self.theme = id.to_string();
		true
	}

	pub fn view(&self) -> Value {
		json!({
			"theme": self.theme(),
			"themes": THEMES.iter().map(|(id, name)| json!({ "id": id, "name": name })).collect::<Vec<_>>(),
			"plugins": CATALOG.iter().map(|p| {
				let mut v = serde_json::to_value(p).unwrap_or(Value::Null);
				v["enabled"] = json!(self.switched_on(p.id));
				v["active"] = json!(self.enabled(p.id));
				v
			}).chain(self.community.iter().map(|p| {
				let mut v = serde_json::to_value(p).unwrap_or(Value::Null);
				v["community"] = json!(true);
				v["enabled"] = json!(self.switched_on(&p.id));
				v["active"] = json!(self.enabled(&p.id));
				v
			})).collect::<Vec<_>>(),
			"categories": CATEGORIES.iter().map(|c| {
				let mut v = serde_json::to_value(c).unwrap_or(Value::Null);
				v["enabled"] = json!(self.category_on(c.id));
				v
			}).collect::<Vec<_>>(),
			"permissions": PERMISSIONS.iter().map(|(id, text)| json!({ "id": id, "text": text })).collect::<Vec<_>>(),
		})
	}
}

pub struct SettingsStore {
	path: Option<PathBuf>,
	pub settings: Settings,
}

impl SettingsStore {
	pub fn in_memory() -> Self {
		SettingsStore { path: None, settings: Settings::default() }
	}

	pub fn open(dir: &Path) -> Self {
		let path = dir.join("settings.json");
		let settings = fs::read_to_string(&path).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default();
		SettingsStore { path: Some(path), settings }
	}

	pub fn save(&self) -> Result<(), String> {
		let Some(path) = &self.path else { return Ok(()) };
		let tmp = path.with_extension("json.tmp");
		fs::write(&tmp, serde_json::to_string(&self.settings).map_err(|e| e.to_string())?).map_err(|e| format!("Couldn't save settings: {e}"))?;
		fs::rename(&tmp, path).map_err(|e| format!("Couldn't save settings: {e}"))
	}
}

// ---------------------------------------------------------------------------------------------
// SponsorBlock (via BetterYTS) and Return YouTube Dislike (via BetterYTS)
// ---------------------------------------------------------------------------------------------

#[derive(Serialize, Debug, PartialEq, Clone, Copy)]
pub struct Segment {
	pub start: f64,
	pub end: f64,
}

/// "music_offtopic" segments, merged and sorted. Tiny segments (under a second) are dropped.
pub fn parse_segments(v: &Value) -> Vec<Segment> {
	let mut segs: Vec<Segment> = v
		.as_array()
		.map(|a| {
			a.iter()
				.filter(|x| x.get("category").and_then(Value::as_str) == Some("music_offtopic"))
				.filter_map(|x| Some(Segment { start: x.get("start")?.as_f64()?, end: x.get("end")?.as_f64()? }))
				.filter(|s| s.end - s.start >= 1.0)
				.collect()
		})
		.unwrap_or_default();
	segs.sort_by(|a, b| a.start.total_cmp(&b.start));
	let mut merged: Vec<Segment> = Vec::new();
	for s in segs {
		match merged.last_mut() {
			Some(last) if s.start <= last.end + 0.5 => last.end = last.end.max(s.end),
			_ => merged.push(s),
		}
	}
	merged
}

pub fn segments(api: &Api, id: &str) -> Result<Vec<Segment>, String> {
	Ok(parse_segments(&api.get_json(&format!("/api/segments/{}", enc(id)), &[("categories", "music_offtopic")])?))
}

pub fn dislikes(api: &Api, id: &str) -> Result<Value, String> {
	match api.get_json(&format!("/api/dislikes/{}", enc(id)), &[]) {
		Ok(v) => Ok(json!({ "likes": v.get("likes"), "dislikes": v.get("dislikes") })),
		// No vote data for this video: not an error worth showing.
		Err(_) => Ok(Value::Null),
	}
}

// ---------------------------------------------------------------------------------------------
// Synced lyrics (LRCLIB, https://lrclib.net — free, no key)
// ---------------------------------------------------------------------------------------------

#[derive(Serialize, Debug, PartialEq, Clone)]
pub struct LyricLine {
	pub time: f64,
	pub text: String,
}

#[derive(Serialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Lyrics {
	pub synced: Vec<LyricLine>,
	pub plain: Option<String>,
	pub instrumental: bool,
}

/// Parses LRC: "[01:02.34]text", several timestamps per line allowed, metadata tags skipped.
pub fn parse_lrc(text: &str) -> Vec<LyricLine> {
	let mut out = Vec::new();
	for raw in text.lines() {
		let mut rest = raw.trim();
		let mut times = Vec::new();
		while let Some(after) = rest.strip_prefix('[') {
			let Some(end) = after.find(']') else { break };
			let tag = &after[..end];
			match lrc_time(tag) {
				Some(t) => times.push(t),
				None => break, // [ar:…], [ti:…] and other metadata
			}
			rest = after[end + 1..].trim_start();
		}
		for t in times {
			out.push(LyricLine { time: t, text: rest.trim().to_string() });
		}
	}
	out.sort_by(|a, b| a.time.total_cmp(&b.time));
	out
}

fn lrc_time(tag: &str) -> Option<f64> {
	let (m, s) = tag.split_once(':')?;
	let m: f64 = m.trim().parse().ok()?;
	let s: f64 = s.trim().parse().ok()?;
	(m >= 0.0 && (0.0..60.0).contains(&s)).then_some(m * 60.0 + s)
}

fn lyrics_from(v: &Value) -> Lyrics {
	Lyrics {
		synced: s(v, "syncedLyrics").map(|l| parse_lrc(&l)).unwrap_or_default(),
		plain: s(v, "plainLyrics"),
		instrumental: v.get("instrumental").and_then(Value::as_bool).unwrap_or(false),
	}
}

/// Looks the song up by artist + title (+ duration when known), falling back to a search.
pub fn lyrics(track: &Track) -> Result<Option<Lyrics>, String> {
	let agent = ureq::AgentBuilder::new()
		.timeout_connect(Duration::from_secs(10))
		.timeout(Duration::from_secs(20))
		// LRCLIB asks clients to identify themselves.
		.user_agent(concat!("BetterYTSMusic/", env!("CARGO_PKG_VERSION"), " (https://yts.foulfoxhacks.dev)"))
		.build();
	let get = |url: String| -> Result<Option<Value>, String> {
		match agent.get(&url).call() {
			Ok(r) => Ok(r.into_string().ok().and_then(|t| serde_json::from_str(&t).ok())),
			Err(ureq::Error::Status(404, _)) => Ok(None),
			Err(ureq::Error::Status(code, _)) => Err(format!("Lyrics service returned HTTP {code}")),
			Err(ureq::Error::Transport(t)) => Err(format!("Can't reach the lyrics service ({})", t.kind())),
		}
	};
	let mut url = format!("https://lrclib.net/api/get?track_name={}&artist_name={}", enc(&track.title), enc(&track.artist));
	if let Some(d) = track.duration_seconds {
		url.push_str(&format!("&duration={d}"));
	}
	if let Some(v) = get(url)? {
		return Ok(Some(lyrics_from(&v)));
	}
	let search = format!("https://lrclib.net/api/search?track_name={}&artist_name={}", enc(&track.title), enc(&track.artist));
	let Some(list) = get(search)? else { return Ok(None) };
	let candidates = list.as_array().cloned().unwrap_or_default();
	// Prefer synced lyrics with a matching length.
	let close = |v: &Value| match (track.duration_seconds, v.get("duration").and_then(Value::as_f64)) {
		(Some(want), Some(have)) => (have - want as f64).abs() <= 4.0,
		_ => true,
	};
	let best = candidates
		.iter()
		.find(|v| close(v) && s(v, "syncedLyrics").is_some())
		.or_else(|| candidates.iter().find(|v| close(v)))
		.or_else(|| candidates.first());
	Ok(best.map(lyrics_from))
}

// ---------------------------------------------------------------------------------------------
// VRChat bridge: ProTV playlist text (same format as BetterYTS's /vrc/protv.playlist)
// ---------------------------------------------------------------------------------------------

fn flat(s: &str) -> String {
	s.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// A free-text ProTV line must not start with a prefix symbol, or ProTV would misread it.
fn protv_line(s: &str) -> String {
	let f = flat(s);
	if f.starts_with(['@', '^', '/', '#', '~', '?']) {
		format!("\u{200b}{f}")
	} else {
		f
	}
}

pub fn protv_playlist(name: &str, tracks: &[Track]) -> String {
	let header = protv_line(name);
	let mut out = vec![if header.is_empty() { "BetterYTS Music queue".to_string() } else { header }, String::new()];
	for t in tracks {
		out.push(format!("@https://www.youtube.com/watch?v={}", t.id));
		out.push(format!("~{}", flat(&t.title)));
		out.push(protv_line(&[t.artist.as_str(), if t.live { "LIVE" } else { t.duration.as_deref().unwrap_or("") }].iter().filter(|x| !x.is_empty()).copied().collect::<Vec<_>>().join(" · ")));
		out.push(String::new());
	}
	format!("{}\n", out.join("\n").trim_end())
}

#[cfg(test)]
mod tests {
	const PANEL: &str = r#"{"id":"song-clock","name":"Song clock","category":"Player","permissions":["nowPlaying","panel"],"code":"betteryts.on('nowPlaying', n => betteryts.panel(n.title))"}"#;

	#[test]
	fn categories_gate_their_plugins() {
		let mut s = Settings::default();
		assert!(s.enabled("lyrics"));
		assert!(s.set_category("Player", false));
		assert!(!s.enabled("lyrics"), "a switched-off category turns its plugins off");
		assert!(s.switched_on("lyrics"), "but remembers each plugin's own switch");
		assert!(s.enabled("sponsorblock"), "other categories are untouched");
		assert!(s.set_category("Player", true));
		assert!(s.enabled("lyrics"));
		assert!(!s.set_category("Nope", false));
		let v = s.view();
		assert_eq!(v["categories"].as_array().unwrap().len(), CATEGORIES.len());
		assert!(CATALOG.iter().all(|p| CATEGORIES.iter().any(|c| c.id == p.category)), "every built-in has a real category");
	}

	#[test]
	fn community_manifests_are_limited_to_safe_categories_and_permissions() {
		let ok = validate_manifest(PANEL).unwrap();
		assert_eq!(ok.permissions, vec!["nowPlaying", "panel"]);
		let look = validate_manifest(r##"{"id":"sunset","name":"Sunset","category":"Look","permissions":["palette"],"palette":{"accent":"#FF8800","hot":"#aa0044"}}"##).unwrap();
		assert_eq!(look.palette["accent"], "#ff8800");

		let bad = |m: &str| validate_manifest(m).unwrap_err();
		assert!(bad(r##"{"id":"x1x","name":"X","category":"Playback","permissions":["panel"],"code":"1"}"##).contains("can't be in Playback"));
		assert!(bad(r##"{"id":"x1x","name":"X","category":"BetterYTS","permissions":["panel"],"code":"1"}"##).contains("can't be in BetterYTS"));
		assert!(bad(r##"{"id":"x1x","name":"X","category":"Player","permissions":["palette"],"code":"1"}"##).contains("can't ask for `palette`"));
		assert!(bad(r##"{"id":"x1x","name":"X","category":"Look","permissions":["nowPlaying"]}"##).contains("can't ask for `nowPlaying`"));
		assert!(bad(r##"{"id":"x1x","name":"X","category":"Look","permissions":["palette"],"palette":{"accent":"#fff"}}"##).contains("colour"));
		assert!(bad(r##"{"id":"x1x","name":"X","category":"Look","permissions":["palette"],"palette":{"accent":"#ffffff"},"code":"evil()"}"##).contains("Only Player"));
		assert!(bad(r##"{"id":"x1x","name":"X","category":"Look","permissions":["palette"],"palette":{"background":"#ffffff"}}"##).contains("can only set"));
		assert!(bad(r##"{"id":"lyrics","name":"X","category":"Player","permissions":["panel"],"code":"1"}"##).contains("built-in"));
		assert!(bad(r##"{"id":"Bad Id","name":"X","category":"Player","permissions":["panel"],"code":"1"}"##).contains("`id`"));
		assert!(bad(r##"{"id":"x1x","name":"X","category":"Player","permissions":[]}"##).contains("empty"));
		assert!(bad(r##"{"id":"x1x","name":"X","category":"Player","permissions":["panel"]}"##).contains("needs `code`"));
		assert!(bad("not json").contains("JSON"));
		let huge = format!(r#"{{"id":"x1x","name":"X","category":"Player","permissions":["panel"],"code":"{}"}}"#, "a".repeat(MAX_CODE_BYTES + 1));
		assert!(bad(&huge).contains("KB"));
	}

	#[test]
	fn community_plugins_install_switch_and_remove() {
		let mut s = Settings::default();
		s.install(PANEL).unwrap();
		assert!(s.enabled("song-clock"));
		s.set_category("Player", false);
		assert!(!s.enabled("song-clock"), "community plugins follow their category too");
		s.set_category("Player", true);
		assert!(s.set_plugin("song-clock", false));
		assert!(!s.enabled("song-clock"));
		s.install(PANEL).unwrap();
		assert_eq!(s.community.len(), 1, "reinstalling updates in place");
		let v = s.view();
		assert_eq!(v["plugins"].as_array().unwrap().iter().filter(|p| p["community"] == true).count(), 1);
		for i in 0..MAX_COMMUNITY {
			let _ = s.install(&PANEL.replace("song-clock", &format!("clock-{i}")));
		}
		assert_eq!(s.community.len(), MAX_COMMUNITY);
		assert!(s.remove("song-clock"));
		assert!(!s.enabled("song-clock"));
		assert!(!s.remove("song-clock"));
		// Settings saved before categories existed still load.
		let old: Settings = serde_json::from_str(r#"{"theme":"mello","plugins":{"lyrics":false}}"#).unwrap();
		assert!(!old.enabled("lyrics") && old.enabled("sponsorblock"));
	}

	use super::*;

	#[test]
	fn settings_defaults_and_switches() {
		let mut s = Settings::default();
		assert_eq!(s.theme(), "ember");
		assert!(s.enabled("lyrics"));
		assert!(!s.enabled("nope"));
		assert!(s.set_plugin("lyrics", false));
		assert!(!s.enabled("lyrics"));
		assert!(!s.set_plugin("nope", true));
		assert!(s.set_theme("mello"));
		assert!(!s.set_theme("neon"));
		assert_eq!(s.theme(), "mello");
		let v = s.view();
		assert_eq!(v["plugins"].as_array().unwrap().len(), CATALOG.len());
		assert_eq!(v["plugins"].as_array().unwrap().iter().find(|p| p["id"] == "lyrics").unwrap()["enabled"], false);
		// A settings file written by a newer version with an unknown theme falls back.
		let old: Settings = serde_json::from_str(r#"{"theme":"retro"}"#).unwrap();
		assert_eq!(old.theme(), "ember");
	}

	#[test]
	fn segments_are_filtered_merged_and_sorted() {
		let v = json!([
			{ "category": "music_offtopic", "start": 305.7, "end": 321.4 },
			{ "category": "sponsor", "start": 10.0, "end": 20.0 },
			{ "category": "music_offtopic", "start": 0.0, "end": 8.0 },
			{ "category": "music_offtopic", "start": 8.2, "end": 12.0 },
			{ "category": "music_offtopic", "start": 50.0, "end": 50.4 }
		]);
		assert_eq!(parse_segments(&v), vec![Segment { start: 0.0, end: 12.0 }, Segment { start: 305.7, end: 321.4 }]);
		assert!(parse_segments(&json!({})).is_empty());
	}

	#[test]
	fn lrc_parsing() {
		let lrc = "[ar:Daft Punk]\n[00:01.50]One more time\n[00:05.00][01:05.00]We're gonna celebrate\n\n[bad]x\n[00:03.25]";
		let lines = parse_lrc(lrc);
		assert_eq!(lines.iter().map(|l| l.time).collect::<Vec<_>>(), vec![1.5, 3.25, 5.0, 65.0]);
		assert_eq!(lines[0].text, "One more time");
		assert_eq!(lines[1].text, "", "instrumental gaps keep their timestamp");
		assert_eq!(lines[3].text, "We're gonna celebrate");
	}

	#[test]
	fn protv_text() {
		let t = Track { id: "FGBhQbmPwH8".into(), title: "One More\nTime".into(), artist: "#Daft Punk".into(), artist_id: None, thumbnail: None, duration: Some("5:21".into()), duration_seconds: None, live: false, plays: None };
		let txt = protv_playlist("@queue", &[t]);
		assert_eq!(txt, "\u{200b}@queue\n\n@https://www.youtube.com/watch?v=FGBhQbmPwH8\n~One More Time\n\u{200b}#Daft Punk · 5:21\n");
	}

	/// Hits LRCLIB. Run with `cargo test -- --ignored`.
	#[test]
	#[ignore]
	fn live_lyrics() {
		let t = Track { id: "FGBhQbmPwH8".into(), title: "One More Time".into(), artist: "Daft Punk".into(), artist_id: None, thumbnail: None, duration: None, duration_seconds: Some(320), live: false, plays: None };
		let l = lyrics(&t).unwrap().expect("lyrics found");
		assert!(l.synced.len() > 10);
	}
}
