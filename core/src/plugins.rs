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

pub const THEMES: &[(&str, &str)] = &[("ember", "Ember · akasammythepuppy"), ("mello", "Mello · Mello Zone")];

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct Settings {
	pub theme: String,
	/// Only plugins the user has switched; the rest follow the catalog's default.
	pub plugins: BTreeMap<String, bool>,
}

impl Settings {
	pub fn theme(&self) -> &str {
		THEMES.iter().map(|(id, _)| *id).find(|id| *id == self.theme).unwrap_or("ember")
	}

	pub fn enabled(&self, id: &str) -> bool {
		self.plugins.get(id).copied().unwrap_or_else(|| CATALOG.iter().any(|p| p.id == id && p.default_enabled))
	}

	/// Returns false for an unknown plugin or theme.
	pub fn set_plugin(&mut self, id: &str, on: bool) -> bool {
		if !CATALOG.iter().any(|p| p.id == id) {
			return false;
		}
		self.plugins.insert(id.to_string(), on);
		true
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
				v["enabled"] = json!(self.enabled(p.id));
				v
			}).collect::<Vec<_>>(),
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
