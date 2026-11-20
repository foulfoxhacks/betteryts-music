//! Backups in the website's format (web/backup.js), so a library moves both ways between the
//! site and the app: favorites <-> liked songs, playlists <-> playlists, subscriptions <->
//! followed artists, history, hidden lists and community plugins.
//!
//! Import merges (like the YouTube import): nothing already here is removed; playlists from a
//! backup are matched by their backup ID and replaced on a repeat import. Everything in the
//! file is checked like any other untrusted input.

use serde_json::{json, Value};

use crate::library::{HistoryEntry, Library, Store};
use crate::model::{Artist, Track};
use crate::plugins::{validate_manifest, Settings};

pub const FORMAT: &str = "betteryts-backup";
const MAX_BYTES: usize = 5_000_000;

fn is_video_id(s: &str) -> bool {
	s.len() == 11 && s.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

fn is_channel_id(s: &str) -> bool {
	s.len() == 24 && s.starts_with("UC") && s[2..].chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

fn clip(s: &str, n: usize) -> String {
	s.chars().filter(|c| !c.is_control()).take(n).collect::<String>().trim().to_string()
}

fn https(v: Option<&Value>) -> Option<String> {
	v.and_then(Value::as_str).filter(|u| u.starts_with("https://") && u.len() < 2000).map(String::from)
}

/// ISO 8601 (UTC) from Unix milliseconds, without a date library.
fn iso(ms: u64) -> String {
	let secs = ms / 1000;
	let days = (secs / 86_400) as i64;
	let rem = secs % 86_400;
	// Civil-from-days (Howard Hinnant).
	let z = days + 719_468;
	let era = z.div_euclid(146_097);
	let doe = z - era * 146_097;
	let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
	let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
	let mp = (5 * doy + 2) / 153;
	let d = doy - (153 * mp + 2) / 5 + 1;
	let m = if mp < 10 { mp + 3 } else { mp - 9 };
	let y = yoe + era * 400 + if m <= 2 { 1 } else { 0 };
	format!("{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}Z", rem / 3600, rem % 3600 / 60, rem % 60)
}

fn video(t: &Track) -> Value {
	json!({
		"id": t.id,
		"title": t.title,
		"channel": t.artist,
		"channelRef": t.artist_id,
		"thumbnail": t.thumbnail.clone().unwrap_or_else(|| format!("https://i.ytimg.com/vi/{}/mqdefault.jpg", t.id)),
		"duration": t.duration.clone().unwrap_or_default(),
	})
}

/// A site-format video → Track, or None if it isn't valid.
fn track(v: &Value) -> Option<Track> {
	let id = v.get("id")?.as_str()?;
	if !is_video_id(id) {
		return None;
	}
	let title = clip(v.get("title").and_then(Value::as_str).unwrap_or(id), 200);
	let artist = clip(v.get("channel").and_then(Value::as_str).unwrap_or(""), 100);
	let artist_id = v.get("channelRef").and_then(Value::as_str).map(|s| clip(s, 100)).filter(|s| !s.is_empty());
	let duration = v.get("duration").and_then(Value::as_str).map(|s| clip(s, 20)).filter(|s| !s.is_empty());
	serde_json::from_value(json!({ "id": id, "title": title, "artist": artist, "artistId": artist_id, "thumbnail": https(v.get("thumbnail")), "duration": duration })).ok()
}

fn list<'a>(v: &'a Value, key: &str) -> &'a [Value] {
	v.get(key).and_then(Value::as_array).map(Vec::as_slice).unwrap_or(&[])
}

pub fn export(lib: &Library, settings: &Settings, app_version: &str, now_ms: u64) -> Value {
	json!({
		"format": FORMAT,
		"version": 1,
		"site": format!("music-{app_version}"),
		"exportedAt": iso(now_ms),
		"settings": {
			"hiddenChannels": settings.hidden_channels.join("\n"),
			"hiddenWords": settings.hidden_words.join(", "),
		},
		"library": {
			"favorites": lib.favorites.iter().map(video).collect::<Vec<_>>(),
			"history": lib.history.iter().map(|h| {
				let mut v = video(&h.track);
				v["at"] = json!(h.played_at);
				v
			}).collect::<Vec<_>>(),
			"playlists": lib.playlists.iter().map(|p| json!({
				"id": p.id.chars().filter(char::is_ascii_alphanumeric).take(16).collect::<String>().to_lowercase(),
				"name": p.name,
				"items": p.tracks.iter().map(video).collect::<Vec<_>>(),
				"createdAt": p.created_at,
			})).collect::<Vec<_>>(),
			"queue": [],
			"subscriptions": lib.followed.iter().filter(|a| is_channel_id(&a.id)).map(|a| json!({
				"id": a.id, "title": a.name, "handle": a.handle, "avatar": a.thumbnail,
			})).collect::<Vec<_>>(),
		},
		"plugins": settings.community.iter().map(|p| {
			let mut v = serde_json::to_value(p).unwrap_or(Value::Null);
			v["enabled"] = json!(settings.switched_on(&p.id));
			v
		}).collect::<Vec<_>>(),
	})
}

#[derive(serde::Serialize, Default, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct BackupSummary {
	pub favorites_added: usize,
	pub playlists_added: usize,
	pub playlists_updated: usize,
	pub artists_added: usize,
	pub history_added: usize,
	pub plugins_installed: usize,
}

/// Checks a backup file and merges it into the library and settings.
pub fn import(text: &str, store: &mut Store, settings: &mut Settings) -> Result<BackupSummary, String> {
	if text.len() > MAX_BYTES {
		return Err("That file is too big to be a BetterYTS backup".into());
	}
	let v: Value = serde_json::from_str(text).map_err(|_| "That isn't a BetterYTS backup (expected JSON)".to_string())?;
	if v.get("format").and_then(Value::as_str) != Some(FORMAT) {
		return Err("That isn't a BetterYTS backup".into());
	}
	match v.get("version").and_then(Value::as_u64) {
		Some(1) => {}
		Some(n) => return Err(format!("This backup is from a newer version (format {n}); update the app and try again")),
		None => return Err("That isn't a BetterYTS backup".into()),
	}
	let empty = json!({});
	let lib = v.get("library").filter(|l| l.is_object()).unwrap_or(&empty);
	let mut sum = BackupSummary::default();

	let liked: Vec<Track> = list(lib, "favorites").iter().filter_map(track).collect();
	let followed: Vec<Artist> = list(lib, "subscriptions")
		.iter()
		.filter_map(|c| {
			let id = c.get("id")?.as_str()?;
			if !is_channel_id(id) {
				return None;
			}
			serde_json::from_value(json!({
				"id": id,
				"name": clip(c.get("title").and_then(Value::as_str).unwrap_or(id), 100),
				"handle": c.get("handle").and_then(Value::as_str).map(|h| clip(h, 100)),
				"thumbnail": https(c.get("avatar")),
			}))
			.ok()
		})
		.collect();
	let playlists: Vec<(String, String, Vec<Track>)> = list(lib, "playlists")
		.iter()
		.take(100)
		.filter_map(|p| {
			let id: String = p.get("id").and_then(Value::as_str).unwrap_or("").chars().filter(char::is_ascii_alphanumeric).take(16).collect();
			let name = clip(p.get("name").and_then(Value::as_str).unwrap_or("Playlist"), 80);
			let tracks: Vec<Track> = list(p, "items").iter().filter_map(track).collect();
			(!id.is_empty()).then_some((id, name, tracks))
		})
		.collect();

	// Reuse the YouTube import's merge rules, with the backup's own playlist IDs as the source.
	let yt = store.lib.import_youtube(Vec::new(), liked, followed);
	sum.favorites_added = yt.favorites_added;
	sum.artists_added = yt.artists_added;
	for (id, name, tracks) in playlists {
		let source = format!("backup:{id}");
		if let Some(p) = store.lib.playlists.iter_mut().find(|p| p.source.as_deref() == Some(source.as_str())) {
			p.name = name;
			p.tracks = tracks;
			sum.playlists_updated += 1;
		} else if let Some(p) = store.lib.create_playlist(&name, tracks) {
			if let Some(mine) = store.lib.playlist_mut(&p.id) {
				mine.source = Some(source);
			}
			sum.playlists_added += 1;
		}
	}
	for h in list(lib, "history").iter().take(300) {
		if let Some(t) = track(h) {
			if !store.lib.history.iter().any(|e| e.track.id == t.id) {
				let at = h.get("at").and_then(Value::as_u64).unwrap_or(0);
				store.lib.history.push(HistoryEntry { track: t, played_at: at });
				sum.history_added += 1;
			}
		}
	}
	store.lib.history.sort_by(|a, b| b.played_at.cmp(&a.played_at));
	store.lib.history.truncate(300);

	if let Some(s) = v.get("settings").filter(|s| s.is_object()) {
		let split = |key: &str| -> Vec<String> { s.get(key).and_then(Value::as_str).unwrap_or("").split([',', '\n']).map(String::from).collect() };
		let channels: Vec<String> = settings.hidden_channels.iter().cloned().chain(split("hiddenChannels")).collect();
		let words: Vec<String> = settings.hidden_words.iter().cloned().chain(split("hiddenWords")).collect();
		settings.set_hidden(&channels, &words);
	}
	for p in list(&v, "plugins") {
		if validate_manifest(&p.to_string()).is_ok() && settings.install(&p.to_string()).is_ok() {
			let id = p.get("id").and_then(Value::as_str).unwrap_or("");
			settings.set_plugin(id, p.get("enabled").and_then(Value::as_bool) != Some(false));
			sum.plugins_installed += 1;
		}
	}
	Ok(sum)
}

#[cfg(test)]
mod tests {
	use super::*;

	fn t(id: &str) -> Track {
		serde_json::from_value(json!({ "id": id, "title": "Song", "artist": "Artist", "artistId": "UCuAXFkgsw1L7xaCfnd5JJOw" })).unwrap()
	}

	#[test]
	fn iso_dates() {
		assert_eq!(iso(0), "1970-01-01T00:00:00Z");
		assert_eq!(iso(1_795_146_413_596), "2026-11-20T03:46:53Z");
		assert_eq!(iso(951_782_400_000), "2000-02-29T00:00:00Z");
	}

	#[test]
	fn round_trip_and_merge() {
		let mut a = Store::in_memory();
		a.lib.toggle_favorite(t("dQw4w9WgXcQ"));
		a.lib.create_playlist("Road trip", vec![t("dQw4w9WgXcQ"), t("jNQXAC9IVRw")]);
		a.lib.toggle_follow(serde_json::from_value(json!({ "id": "UCuAXFkgsw1L7xaCfnd5JJOw", "name": "Rick Astley" })).unwrap());
		a.lib.add_history(t("jNQXAC9IVRw"), 1_700_000_000_000);
		let mut sa = Settings::default();
		sa.set_hidden(&["Lofi Girl".into()], &["nightcore".into()]);
		let text = export(&a.lib, &sa, "0.8.0", 1_795_146_413_596).to_string();

		let mut b = Store::in_memory();
		let mut sb = Settings::default();
		let sum = import(&text, &mut b, &mut sb).unwrap();
		assert_eq!(sum, BackupSummary { favorites_added: 1, playlists_added: 1, playlists_updated: 0, artists_added: 1, history_added: 1, plugins_installed: 0 });
		assert_eq!(b.lib.playlists[0].tracks.len(), 2);
		assert_eq!(b.lib.followed[0].name, "Rick Astley");
		assert_eq!(sb.hidden_channels, vec!["Lofi Girl"]);
		// Importing again updates in place instead of duplicating.
		let again = import(&text, &mut b, &mut sb).unwrap();
		assert_eq!((again.favorites_added, again.playlists_added, again.playlists_updated, again.history_added), (0, 0, 1, 0));
		assert_eq!(b.lib.playlists.len(), 1);
	}

	#[test]
	fn reads_the_websites_backups_and_rejects_junk() {
		// Shape written by web/backup.js.
		let site = json!({
			"format": "betteryts-backup", "version": 1, "site": "1.1.0",
			"settings": { "hiddenChannels": "lofigirl\nucsj4gkvc6nrvii8umztf0ow", "hiddenWords": "", "hideShorts": true },
			"library": {
				"favorites": [{ "id": "dQw4w9WgXcQ", "title": "Never Gonna Give You Up", "channel": "Rick Astley", "channelRef": null, "thumbnail": "javascript:alert(1)", "duration": "3:33" }, { "id": "bad" }],
				"playlists": [{ "id": "abc123", "name": "Mix", "items": [{ "id": "jNQXAC9IVRw", "title": "Me at the zoo", "channel": "jawed" }], "createdAt": 1 }],
				"subscriptions": [{ "id": "not-a-channel", "title": "x" }],
				"history": [{ "id": "jNQXAC9IVRw", "title": "Me at the zoo", "channel": "jawed", "at": 5 }],
			},
			"plugins": [{ "id": "x1x", "name": "X", "category": "Playback", "permissions": ["panel"], "code": "1" }],
		});
		let mut s = Store::in_memory();
		let mut st = Settings::default();
		let sum = import(&site.to_string(), &mut s, &mut st).unwrap();
		assert_eq!((sum.favorites_added, sum.playlists_added, sum.artists_added, sum.plugins_installed), (1, 1, 0, 0));
		assert_eq!(s.lib.favorites[0].thumbnail, None, "unsafe URLs are dropped");
		assert_eq!(st.hidden_channels.len(), 2);
		assert!(import("nope", &mut s, &mut st).unwrap_err().contains("JSON"));
		assert!(import(r#"{"format":"x"}"#, &mut s, &mut st).unwrap_err().contains("isn't"));
		assert!(import(r#"{"format":"betteryts-backup","version":7}"#, &mut s, &mut st).unwrap_err().contains("newer"));
	}
}
