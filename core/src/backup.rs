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
/// Version of the app-only "music" section. Older app versions ignore what they don't know.
pub const MUSIC_SCHEMA: u64 = 1;
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
	export_with_queue(lib, settings, app_version, now_ms, &[])
}

/// The website's SponsorBlock setting is a map of category → skip; the app keeps a list.
fn skip_map(settings: &Settings) -> Value {
	let chosen = crate::plugins::clean_categories(&settings.sponsor_categories);
	Value::Object(crate::plugins::SPONSOR_CATEGORIES.iter().map(|(id, _)| (id.to_string(), json!(chosen.iter().any(|c| c == id)))).collect())
}

/// Like [`export`], with the play queue (the website restores it on import).
pub fn export_with_queue(lib: &Library, settings: &Settings, app_version: &str, now_ms: u64, queue: &[Track]) -> Value {
	json!({
		"format": FORMAT,
		// The shared format's version: the website refuses newer ones, so app-only data goes in
		// the "music" section below (which the website ignores) instead of bumping this.
		"version": 1,
		"site": format!("music-{app_version}"),
		"exportedAt": iso(now_ms),
		"app": { "name": "BetterYTS Music", "platform": "android", "version": app_version, "musicSchema": MUSIC_SCHEMA },
		// App-only extras. Play statistics and search history never leave the phone.
		"music": {
			"smart": lib.smart,
			"pinned": lib.pinned.iter().filter(|k| k.starts_with("smart:")).collect::<Vec<_>>(),
		},
		"settings": {
			"hiddenChannels": settings.hidden_channels.join("\n"),
			"hiddenWords": settings.hidden_words.join(", "),
			"skip": skip_map(settings),
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
			"queue": queue.iter().filter(|t| is_video_id(&t.id)).take(300).map(video).collect::<Vec<_>>(),
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
	#[serde(default)]
	pub smart_added: usize,
	/// Liked songs and artists already in the library.
	#[serde(default)]
	pub duplicates: usize,
	/// Entries that failed validation (bad IDs, unsafe plugins) and were left out.
	#[serde(default)]
	pub invalid: usize,
	/// Playlists that didn't fit (the library's playlist limit).
	#[serde(default)]
	pub playlists_skipped: usize,
}

/// What a file contains, read before anything is applied (for the preview screen).
#[derive(serde::Serialize, Default, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct BackupInfo {
	/// "website" or "app", from the file's "site" field.
	pub made_by: String,
	pub app_version: Option<String>,
	pub exported_at: Option<String>,
	pub favorites: usize,
	pub playlists: usize,
	pub tracks_in_playlists: usize,
	pub artists: usize,
	pub history: usize,
	pub plugins: usize,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
	/// Add what's new; never remove anything.
	Merge,
	/// Liked songs, playlists, followed artists and history are replaced by the file's.
	Replace,
}

impl Mode {
	pub fn parse(s: Option<&str>) -> Mode {
		if s == Some("replace") {
			Mode::Replace
		} else {
			Mode::Merge
		}
	}
}

/// Checks the file and says what importing it would do, without changing anything.
pub fn preview(text: &str, store: &Store, settings: &Settings, mode: Mode) -> Result<(BackupInfo, BackupSummary), String> {
	let mut scratch = Store::in_memory();
	scratch.lib = store.lib.clone();
	let mut scratch_settings = settings.clone();
	let sum = import_with(text, &mut scratch, &mut scratch_settings, mode)?;
	Ok((info(text)?, sum))
}

fn parse(text: &str) -> Result<Value, String> {
	if text.len() > MAX_BYTES {
		return Err("That file is too big to be a BetterYTS backup".into());
	}
	let v: Value = serde_json::from_str(text).map_err(|_| "That isn't a BetterYTS backup (expected JSON)".to_string())?;
	if v.get("format").and_then(Value::as_str) != Some(FORMAT) {
		return Err("That isn't a BetterYTS backup".into());
	}
	match v.get("version").and_then(Value::as_u64) {
		Some(1) => Ok(v),
		Some(n) => Err(format!("This backup is from a newer version (format {n}); update the app and try again")),
		None => Err("That isn't a BetterYTS backup".into()),
	}
}

pub fn info(text: &str) -> Result<BackupInfo, String> {
	let v = parse(text)?;
	let empty = json!({});
	let lib = v.get("library").filter(|l| l.is_object()).unwrap_or(&empty);
	let site = v.get("site").and_then(Value::as_str).unwrap_or("");
	let pls = list(lib, "playlists");
	Ok(BackupInfo {
		made_by: if site.starts_with("music-") { "app" } else { "website" }.into(),
		app_version: site.strip_prefix("music-").filter(|x| !x.is_empty()).map(|x| clip(x, 20)).or_else(|| (!site.is_empty() && !site.starts_with("music-")).then(|| clip(site, 20))),
		exported_at: v.get("exportedAt").and_then(Value::as_str).map(|x| clip(x, 30)),
		favorites: list(lib, "favorites").len(),
		playlists: pls.len(),
		tracks_in_playlists: pls.iter().map(|p| list(p, "items").len()).sum(),
		artists: list(lib, "subscriptions").len(),
		history: list(lib, "history").len(),
		plugins: list(&v, "plugins").len(),
	})
}

/// Checks a backup file and merges it into the library and settings.
pub fn import(text: &str, store: &mut Store, settings: &mut Settings) -> Result<BackupSummary, String> {
	import_with(text, store, settings, Mode::Merge)
}

/// Checks the whole file first; only then changes the library (merging or replacing).
pub fn import_with(text: &str, store: &mut Store, settings: &mut Settings, mode: Mode) -> Result<BackupSummary, String> {
	let v = parse(text)?;
	let empty = json!({});
	let lib = v.get("library").filter(|l| l.is_object()).unwrap_or(&empty);
	let mut sum = BackupSummary::default();

	let raw_favorites = list(lib, "favorites");
	let liked: Vec<Track> = raw_favorites.iter().filter_map(track).collect();
	sum.invalid += raw_favorites.len() - liked.len();
	let raw_subs = list(lib, "subscriptions");
	let followed: Vec<Artist> = raw_subs
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
	sum.invalid += raw_subs.len() - followed.len();
	let mut bad_items = 0;
	let raw_playlists = list(lib, "playlists");
	let playlists: Vec<(String, String, Vec<Track>)> = raw_playlists
		.iter()
		.take(100)
		.filter_map(|p| {
			let id: String = p.get("id").and_then(Value::as_str).unwrap_or("").chars().filter(char::is_ascii_alphanumeric).take(16).collect();
			let name = clip(p.get("name").and_then(Value::as_str).unwrap_or("Playlist"), 80);
			let items = list(p, "items");
			let tracks: Vec<Track> = items.iter().filter_map(track).collect();
			bad_items += items.len() - tracks.len();
			(!id.is_empty()).then_some((id, name, tracks))
		})
		.collect();
	sum.invalid += bad_items + raw_playlists.len().min(100) - playlists.len();
	sum.playlists_skipped += raw_playlists.len().saturating_sub(100);

	// Everything is parsed and checked; only now is the library touched.
	if mode == Mode::Replace {
		let keep_pins: Vec<String> = store.lib.pinned.iter().filter(|k| k.starts_with("smart:") || k.starts_with("station:")).cloned().collect();
		store.lib.favorites.clear();
		store.lib.playlists.clear();
		store.lib.followed.clear();
		store.lib.history.clear();
		store.lib.pinned = keep_pins;
	}

	// Reuse the YouTube import's merge rules, with the backup's own playlist IDs as the source.
	let (n_liked, n_followed) = (liked.len(), followed.len());
	let yt = store.lib.import_youtube(Vec::new(), liked, followed);
	sum.favorites_added = yt.favorites_added;
	sum.artists_added = yt.artists_added;
	sum.duplicates = (n_liked - yt.favorites_added) + (n_followed - yt.artists_added);
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
		// SponsorBlock: the website's { category: skip } map, for categories the app knows.
		if let Some(skip) = s.get("skip").and_then(Value::as_object) {
			let on: Vec<String> = skip.iter().filter(|(_, v)| v.as_bool() == Some(true)).map(|(k, _)| k.clone()).collect();
			settings.sponsor_categories = crate::plugins::clean_categories(&on);
		}
	}
	for p in list(&v, "plugins") {
		if validate_manifest(&p.to_string()).is_ok() && settings.install(&p.to_string()).is_ok() {
			let id = p.get("id").and_then(Value::as_str).unwrap_or("");
			settings.set_plugin(id, p.get("enabled").and_then(Value::as_bool) != Some(false));
			sum.plugins_installed += 1;
		} else {
			sum.invalid += 1;
		}
	}
	// App-only extras: smart playlists (rules), matched by name so a repeat import adds nothing.
	for rule in list(v.get("music").unwrap_or(&empty), "smart").iter().take(crate::library::MAX_SMART) {
		let name = clip(rule.get("name").and_then(Value::as_str).unwrap_or(""), 80);
		let Some(r) = rule.get("rule").cloned().and_then(|r| serde_json::from_value::<crate::smart::Rule>(r).ok()) else {
			sum.invalid += 1;
			continue;
		};
		if !store.lib.smart.iter().any(|s| s.name.eq_ignore_ascii_case(&name)) && store.lib.save_smart(&name, r).is_some() {
			sum.smart_added += 1;
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
		assert_eq!(sum, BackupSummary { favorites_added: 1, playlists_added: 1, playlists_updated: 0, artists_added: 1, history_added: 1, plugins_installed: 0, ..Default::default() });
		assert_eq!(b.lib.playlists[0].tracks.len(), 2);
		assert_eq!(b.lib.followed[0].name, "Rick Astley");
		assert_eq!(sb.hidden_channels, vec!["Lofi Girl"]);
		// Importing again updates in place instead of duplicating.
		let again = import(&text, &mut b, &mut sb).unwrap();
		assert_eq!((again.favorites_added, again.playlists_added, again.playlists_updated, again.history_added), (0, 0, 1, 0));
		assert_eq!(b.lib.playlists.len(), 1);
	}

	#[test]
	fn preview_changes_nothing_and_matches_the_real_import() {
		let mut a = Store::in_memory();
		a.lib.toggle_favorite(t("dQw4w9WgXcQ"));
		a.lib.create_playlist("Road trip", vec![t("dQw4w9WgXcQ"), t("jNQXAC9IVRw")]);
		a.lib.save_smart("Short A", crate::smart::Rule { max_seconds: Some(180), ..Default::default() });
		let text = export(&a.lib, &Settings::default(), "0.10.0", 0).to_string();

		let mut b = Store::in_memory();
		b.lib.toggle_favorite(t("dQw4w9WgXcQ"));
		let before = b.lib.clone();
		let (info, would) = preview(&text, &b, &Settings::default(), Mode::Merge).unwrap();
		assert_eq!(b.lib, before, "a preview never touches the library");
		assert_eq!((info.made_by.as_str(), info.app_version.as_deref(), info.favorites, info.playlists, info.tracks_in_playlists), ("app", Some("0.10.0"), 1, 1, 2));
		assert_eq!((would.favorites_added, would.duplicates, would.playlists_added, would.smart_added), (0, 1, 1, 1));
		let did = import_with(&text, &mut b, &mut Settings::default(), Mode::Merge).unwrap();
		assert_eq!(did, would, "the preview predicts the import exactly");
		assert_eq!(b.lib.smart[0].name, "Short A", "smart playlists travel in the app-only section");
		assert_eq!(import(&text, &mut b, &mut Settings::default()).unwrap().smart_added, 0, "no duplicate on a repeat import");
	}

	#[test]
	fn replace_mode_swaps_the_library_but_keeps_stats() {
		let mut a = Store::in_memory();
		a.lib.toggle_favorite(t("jNQXAC9IVRw"));
		let text = export(&a.lib, &Settings::default(), "0.10.0", 0).to_string();
		let mut b = Store::in_memory();
		b.lib.toggle_favorite(t("dQw4w9WgXcQ"));
		b.lib.create_playlist("Mine", vec![]);
		b.lib.record_play(&t("dQw4w9WgXcQ"), 1, None, None);
		import_with(&text, &mut b, &mut Settings::default(), Mode::Replace).unwrap();
		assert_eq!(b.lib.favorites.iter().map(|x| x.id.as_str()).collect::<Vec<_>>(), ["jNQXAC9IVRw"]);
		assert!(b.lib.playlists.is_empty());
		assert_eq!(b.lib.plays_of("dQw4w9WgXcQ"), 1, "play statistics aren't part of a backup, so they stay");
		// A broken file fails before anything is replaced.
		assert!(import_with("{bad", &mut b, &mut Settings::default(), Mode::Replace).is_err());
		assert_eq!(b.lib.favorites.len(), 1);
		assert_eq!(Mode::parse(Some("replace")), Mode::Replace);
		assert_eq!(Mode::parse(None), Mode::Merge);
	}

	/// Every historical shape the app has written must keep importing.
	#[test]
	fn website_settings_and_queue_round_trip() {
		let mut s = Settings::default();
		s.sponsor_categories = vec!["sponsor".into(), "intro".into()];
		let text = export_with_queue(&Library::default(), &s, "0.10.0", 0, &[t("dQw4w9WgXcQ"), serde_json::from_value(json!({ "id": "local:4", "title": "x", "artist": "y" })).unwrap()]).to_string();
		let v: Value = serde_json::from_str(&text).unwrap();
		assert_eq!(v["settings"]["skip"]["sponsor"], true);
		assert_eq!(v["settings"]["skip"]["music_offtopic"], false);
		assert_eq!(v["library"]["queue"].as_array().unwrap().len(), 1, "phone songs aren't exported");
		// The website's map: hook/exclusive_access are site-only and ignored by the app.
		let site = json!({ "format": "betteryts-backup", "version": 1, "settings": { "skip": { "selfpromo": true, "hook": true, "sponsor": false } }, "library": {} });
		let mut st = Settings::default();
		import(&site.to_string(), &mut Store::in_memory(), &mut st).unwrap();
		assert_eq!(st.sponsor_categories, ["selfpromo"]);
	}

	#[test]
	fn historical_backups_still_import() {
		// App 0.8–0.9.4: version 1, no "app" or "music" sections.
		let v094 = r#"{"format":"betteryts-backup","version":1,"site":"music-0.9.4","exportedAt":"2026-11-20T03:46:53Z",
			"settings":{"hiddenChannels":"","hiddenWords":""},
			"library":{"favorites":[{"id":"dQw4w9WgXcQ","title":"Song","channel":"Artist","channelRef":"UCuAXFkgsw1L7xaCfnd5JJOw","thumbnail":"https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg","duration":"3:33"}],
			"history":[],"playlists":[{"id":"p18c","name":"Old","items":[],"createdAt":1}],"queue":[],"subscriptions":[]},"plugins":[]}"#;
		let mut s = Store::in_memory();
		let sum = import(v094, &mut s, &mut Settings::default()).unwrap();
		assert_eq!((sum.favorites_added, sum.playlists_added, sum.invalid), (1, 1, 0));
		let i = info(v094).unwrap();
		assert_eq!((i.made_by.as_str(), i.app_version.as_deref()), ("app", Some("0.9.4")));
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
		assert_eq!(sum.invalid, 3, "bad favourite, bad channel and an unsafe plugin are counted");
		assert_eq!(info(&site.to_string()).unwrap().made_by, "website");
		assert_eq!(s.lib.favorites[0].thumbnail, None, "unsafe URLs are dropped");
		assert_eq!(st.hidden_channels.len(), 2);
		assert!(import("nope", &mut s, &mut st).unwrap_err().contains("JSON"));
		assert!(import(r#"{"format":"x"}"#, &mut s, &mut st).unwrap_err().contains("isn't"));
		assert!(import(r#"{"format":"betteryts-backup","version":7}"#, &mut s, &mut st).unwrap_err().contains("newer"));
	}
}
