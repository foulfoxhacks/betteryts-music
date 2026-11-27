//! Send music to a VRChat world from the app: the same activation-code flow as the website's
//! "Send music to a VRChat world" page (server: src/vrc/links.ts in the betteryts repo).
//!
//! This is a handoff to BetterYTS-compatible worlds, not a VRChat account integration: the app
//! never sees the user's VRChat account. Only song IDs, titles and channel names are sent.

use std::collections::HashSet;

use serde::Serialize;
use serde_json::{json, Value};

use crate::api::Api;
use crate::model::Track;

pub const MAX_PLAYLISTS: usize = 20;
pub const MAX_TRACKS: usize = 200;
/// Upper bound for one send. A full send (20 × 200 songs) with ordinary titles is ~0.5 MB.
pub const MAX_BYTES: usize = 1024 * 1024;

#[derive(Serialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct CodeInfo {
	pub code: String,
	pub service: String,
	/// What the world can do with a handoff. From the server when it says; otherwise what every
	/// compatible world supports for that service.
	pub capabilities: Vec<String>,
	/// Seconds until the code expires, when the server says.
	#[serde(skip_serializing_if = "Option::is_none")]
	pub expires_in: Option<u64>,
}

/// "k7qx m2pa" → "K7QX-M2PA", or None if it can't be a code (saves a request and a rate-limit hit).
pub fn normalize(input: &str) -> Option<String> {
	const ALPHABET: &str = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
	let c: String = input.chars().filter(char::is_ascii_alphanumeric).map(|c| c.to_ascii_uppercase()).collect();
	(c.len() == 8 && c.chars().all(|ch| ALPHABET.contains(ch))).then(|| format!("{}-{}", &c[..4], &c[4..]))
}

/// Only known capability names are kept, so a server typo can't light up a button.
const KNOWN: &[&str] = &["youtube_link", "spotify_link", "protv_playlist", "queue_replace", "queue_append", "saved_library", "temporary_library", "listen_together", "unlink", "now_playing"];

pub fn capabilities(v: &Value, service: &str) -> Vec<String> {
	if let Some(list) = v.get("capabilities").and_then(Value::as_array) {
		let caps: Vec<String> = list.iter().filter_map(Value::as_str).filter(|c| KNOWN.contains(c)).map(String::from).collect();
		if !caps.is_empty() {
			return caps;
		}
	}
	let link = if service == "spotify" { "spotify_link" } else { "youtube_link" };
	vec![link.into(), "temporary_library".into(), "saved_library".into(), "unlink".into()]
}

pub fn check(api: &Api, input: &str) -> Result<CodeInfo, String> {
	let code = normalize(input).ok_or("Codes look like ABCD-1234 (letters and numbers)")?;
	let v = api.get_json("/api/inworld/check", &[("code", &code)])?;
	let service = v.get("service").and_then(Value::as_str).filter(|s| *s == "youtube" || *s == "spotify").unwrap_or("youtube").to_string();
	Ok(CodeInfo {
		code: v.get("code").and_then(Value::as_str).and_then(normalize).unwrap_or(code),
		capabilities: capabilities(&v, &service),
		expires_in: v.get("expiresIn").and_then(Value::as_u64).filter(|s| *s <= 24 * 3600),
		service,
	})
}

fn is_video_id(s: &str) -> bool {
	s.len() == 11 && s.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

fn clean(s: &str, max: usize) -> String {
	// Tabs and newlines separate words; other control characters are dropped.
	let spaced: String = s.chars().map(|c| if c.is_whitespace() { ' ' } else { c }).filter(|c| !c.is_control()).collect();
	spaced.split_whitespace().collect::<Vec<_>>().join(" ").chars().take(max).collect()
}

#[derive(Serialize, Debug, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct ListPreview {
	pub name: String,
	pub tracks: usize,
	/// Songs on the phone, or with IDs a world can't play.
	pub unsupported: usize,
	pub duplicates: usize,
	/// Songs past the per-playlist limit.
	pub over_limit: usize,
}

/// Exactly what a send would contain, before it's sent.
#[derive(Serialize, Debug, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct Preview {
	pub playlists: Vec<ListPreview>,
	pub total_tracks: usize,
	pub unsupported: usize,
	pub duplicates: usize,
	pub over_limit: usize,
	/// Playlists past the 20-playlist limit.
	pub playlists_left_out: usize,
	pub bytes: usize,
	pub max_playlists: usize,
	pub max_tracks: usize,
	/// What leaves the phone, in words, for the privacy note.
	pub sends: String,
}

/// Builds the request body and its preview from (name, tracks) lists. The body holds only what
/// the world needs; it's bounded in playlists, songs and bytes.
pub fn payload(code: &str, service: &str, save: bool, lists: &[(String, Vec<Track>)]) -> Result<(Value, Preview), String> {
	let mut pv = Preview { max_playlists: MAX_PLAYLISTS, max_tracks: MAX_TRACKS, sends: "Song IDs, titles and channel names. Nothing about your account.".into(), ..Default::default() };
	pv.playlists_left_out = lists.len().saturating_sub(MAX_PLAYLISTS);
	let mut out = Vec::new();
	for (name, tracks) in lists.iter().take(MAX_PLAYLISTS) {
		let mut lp = ListPreview { name: clean(name, 80), ..Default::default() };
		if lp.name.is_empty() {
			lp.name = "Playlist".into();
		}
		let mut seen = HashSet::new();
		let mut items = Vec::new();
		for t in tracks {
			if !is_video_id(&t.id) {
				lp.unsupported += 1;
			} else if !seen.insert(t.id.as_str()) {
				lp.duplicates += 1;
			} else if items.len() >= MAX_TRACKS {
				lp.over_limit += 1;
			} else {
				items.push(json!({ "id": t.id, "title": clean(&t.title, 200), "channel": clean(&t.artist, 100) }));
			}
		}
		lp.tracks = items.len();
		if items.is_empty() {
			pv.unsupported += lp.unsupported;
			pv.duplicates += lp.duplicates;
			pv.playlists.push(lp);
			continue;
		}
		out.push(json!({ "name": lp.name, "tracks": items }));
		pv.total_tracks += lp.tracks;
		pv.unsupported += lp.unsupported;
		pv.duplicates += lp.duplicates;
		pv.over_limit += lp.over_limit;
		pv.playlists.push(lp);
	}
	let body = json!({ "code": code, "service": service, "save": save, "library": { "playlists": out } });
	pv.bytes = body.to_string().len();
	if pv.bytes > MAX_BYTES {
		return Err(format!("That's too much to send at once ({} KB). Pick fewer playlists.", pv.bytes / 1024));
	}
	Ok((body, pv))
}

/// Sends playlists (name, tracks) to the world waiting on `code`. Returns (playlists, tracks) the
/// server accepted. `save`: keep it for the player's next visit (30 days); otherwise this visit only.
pub fn send(api: &Api, code: &str, service: &str, save: bool, playlists: &[(String, Vec<Track>)]) -> Result<(u64, u64), String> {
	let code = normalize(code).ok_or("Codes look like ABCD-1234 (letters and numbers)")?;
	let (body, pv) = payload(&code, service, save, playlists)?;
	if pv.total_tracks == 0 {
		return Err("None of those songs can be played in a world (songs on your phone can't be sent).".into());
	}
	let v = api.post_json("/api/inworld/complete", &body)?;
	Ok((v.get("playlists").and_then(Value::as_u64).unwrap_or(0), v.get("tracks").and_then(Value::as_u64).unwrap_or(0)))
}

#[cfg(test)]
mod tests {
	use super::*;

	fn t(id: &str, title: &str) -> Track {
		serde_json::from_value(json!({ "id": id, "title": title, "artist": "Artist\u{0}" })).unwrap()
	}

	#[test]
	fn codes() {
		assert_eq!(normalize(" k7qx m2pa ").as_deref(), Some("K7QX-M2PA"));
		assert_eq!(normalize("K7QX-M2PA").as_deref(), Some("K7QX-M2PA"));
		assert_eq!(normalize("K7QX-M2P0"), None, "0 isn't used");
		assert_eq!(normalize("K7QX"), None);
	}

	#[test]
	fn capabilities_are_known_names_only() {
		assert_eq!(capabilities(&json!({ "capabilities": ["listen_together", "rm -rf", "queue_append"] }), "youtube"), ["listen_together", "queue_append"]);
		assert!(capabilities(&json!({}), "spotify").contains(&"spotify_link".to_string()));
		assert!(capabilities(&json!({ "capabilities": [] }), "youtube").contains(&"youtube_link".to_string()), "empty means unknown: defaults");
	}

	#[test]
	fn payload_is_previewed_cleaned_and_bounded() {
		let mut big: Vec<Track> = (0..MAX_TRACKS + 3).map(|i| t(&format!("{i:0>11}"), "x")).collect();
		big.push(t("local:12", "phone song"));
		big.push(t("00000000000", "dupe"));
		let lists = vec![("  Road\n trip ".to_string(), vec![t("dQw4w9WgXcQ", "Never\tGonna"), t("dQw4w9WgXcQ", "again")]), ("Big".to_string(), big), ("Phone only".to_string(), vec![t("local:1", "x")])];
		let (body, pv) = payload("K7QX-M2PA", "youtube", false, &lists).unwrap();
		assert_eq!(pv.playlists[0], ListPreview { name: "Road trip".into(), tracks: 1, unsupported: 0, duplicates: 1, over_limit: 0 });
		assert_eq!((pv.playlists[1].tracks, pv.playlists[1].over_limit, pv.playlists[1].unsupported, pv.playlists[1].duplicates), (MAX_TRACKS, 3, 1, 1));
		assert_eq!((pv.total_tracks, pv.unsupported, pv.duplicates), (1 + MAX_TRACKS, 2, 2));
		let sent = body["library"]["playlists"].as_array().unwrap();
		assert_eq!(sent.len(), 2, "a playlist with nothing playable isn't sent");
		assert_eq!(sent[0]["tracks"][0], json!({ "id": "dQw4w9WgXcQ", "title": "Never Gonna", "channel": "Artist" }), "control characters stripped; only id, title, channel");
		assert_eq!(pv.bytes, body.to_string().len());
		let many: Vec<(String, Vec<Track>)> = (0..25).map(|i| (format!("P{i}"), vec![t("dQw4w9WgXcQ", "x")])).collect();
		assert_eq!(payload("K7QX-M2PA", "youtube", true, &many).unwrap().1.playlists_left_out, 5);
		let huge_title = "é".repeat(5000);
		let wide: Vec<(String, Vec<Track>)> = (0..20).map(|i| (format!("P{i}"), (0..200).map(|j| t(&format!("{i:0>5}{j:0>6}"), &huge_title)).collect())).collect();
		assert!(payload("K7QX-M2PA", "youtube", true, &wide).unwrap_err().contains("too much"), "total size is bounded too");
	}
}
