//! On-device library: favourites, listening history, playlists and followed artists.
//! Saved as one JSON file in the app's private storage, written atomically.

use std::fs;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};

use crate::model::{Artist, Track};

pub const MAX_HISTORY: usize = 500;
pub const MAX_PLAYLISTS: usize = 100;
pub const MAX_PLAYLIST_TRACKS: usize = 1000;
pub const MAX_FOLLOWED: usize = 300;
pub const MAX_FAVORITES: usize = 5000;
pub const MAX_SEARCHES: usize = 50;
pub const MAX_PINS: usize = 24;
/// Play statistics kept per track (least recently played dropped first).
pub const MAX_STATS: usize = 2000;
/// Days with listening, for streaks (about 13 months).
pub const MAX_DAYS: usize = 400;
pub const MAX_SMART: usize = 30;

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HistoryEntry {
	#[serde(flatten)]
	pub track: Track,
	pub played_at: u64,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct UserPlaylist {
	pub id: String,
	pub name: String,
	pub tracks: Vec<Track>,
	pub created_at: u64,
	/// Where an imported playlist came from ("youtube:PL…"), so re-importing updates it in place.
	#[serde(default, skip_serializing_if = "Option::is_none")]
	pub source: Option<String>,
}

/// What a YouTube import changed.
#[derive(Serialize, Debug, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct ImportSummary {
	pub playlists_added: usize,
	pub playlists_updated: usize,
	pub playlists_skipped: usize,
	pub favorites_added: usize,
	pub artists_added: usize,
}

/// How often and how long a track has been played. Only on the phone; never in backups or sent anywhere.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct PlayStat {
	pub track: Track,
	pub count: u32,
	pub first_played: u64,
	pub last_played: u64,
	/// Seconds actually listened (reported by the app as songs stop), not the song's length.
	#[serde(default)]
	pub seconds: u64,
}

/// When listening happens, for local insights: plays per local hour, and the local days with any plays.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct Listening {
	pub hours: [u32; 24],
	/// Local epoch days (days since 1970-01-01 in the phone's time zone), ascending, unique.
	pub days: Vec<u32>,
	pub seconds: u64,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SearchEntry {
	pub query: String,
	pub at: u64,
}

/// One "Send to a VRChat world", remembered on the phone (never the code itself).
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct WorldSend {
	pub at: u64,
	pub service: String,
	pub playlists: u64,
	pub tracks: u64,
	/// Kept for the next visit (30 days) rather than this visit only.
	pub saved: bool,
	#[serde(default)]
	pub expires_at: Option<u64>,
	/// Playlist names sent (for "what did I send?").
	#[serde(default)]
	pub names: Vec<String>,
}

pub const MAX_WORLD_SENDS: usize = 20;
pub const SAVED_WORLD_DAYS: u64 = 30;

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SavedSmart {
	pub id: String,
	pub name: String,
	pub rule: crate::smart::Rule,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct Library {
	pub favorites: Vec<Track>,
	pub history: Vec<HistoryEntry>,
	pub playlists: Vec<UserPlaylist>,
	pub followed: Vec<Artist>,
	pub stats: Vec<PlayStat>,
	pub listening: Listening,
	/// Recent searches, newest first. Local only.
	pub searches: Vec<SearchEntry>,
	/// Pinned items, in order: "playlist:<id>", "artist:<id>", "station:<id>", "smart:<id>".
	pub pinned: Vec<String>,
	/// The user's own smart playlists (rules, not tracks: they refresh as the library changes).
	pub smart: Vec<SavedSmart>,
	/// Recent sends to VRChat worlds, newest first.
	pub world_sends: Vec<WorldSend>,
	/// Songs you disliked: never played by stations, skipped in the queue when that's on.
	pub disliked: Vec<Track>,
}

fn now_ms() -> u64 {
	SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

fn clip(s: &str, max: usize) -> String {
	s.trim().chars().take(max).collect()
}

impl Library {
	pub fn is_favorite(&self, id: &str) -> bool {
		self.favorites.iter().any(|t| t.id == id)
	}

	/// Returns whether the track is now a favourite.
	pub fn toggle_favorite(&mut self, track: Track) -> bool {
		if self.is_favorite(&track.id) {
			self.favorites.retain(|t| t.id != track.id);
			false
		} else if self.favorites.len() >= MAX_FAVORITES {
			false
		} else {
			self.favorites.insert(0, track);
			true
		}
	}

	pub fn add_history(&mut self, track: Track, at: u64) {
		self.history.retain(|h| h.track.id != track.id);
		self.history.insert(0, HistoryEntry { track, played_at: at });
		self.history.truncate(MAX_HISTORY);
	}

	pub fn create_playlist(&mut self, name: &str, tracks: Vec<Track>) -> Option<UserPlaylist> {
		if self.playlists.len() >= MAX_PLAYLISTS {
			return None;
		}
		let created_at = now_ms();
		// Unique even when two are created in the same millisecond.
		let mut id = format!("p{created_at:x}");
		while self.playlists.iter().any(|p| p.id == id) {
			id.push('x');
		}
		let mut seen = std::collections::HashSet::new();
		let tracks = tracks.into_iter().filter(|t| seen.insert(t.id.clone())).take(MAX_PLAYLIST_TRACKS).collect();
		let name = clip(name, 80);
		let p = UserPlaylist { id, name: if name.is_empty() { "My playlist".into() } else { name }, tracks, created_at, source: None };
		self.playlists.insert(0, p.clone());
		Some(p)
	}

	pub fn playlist_mut(&mut self, id: &str) -> Option<&mut UserPlaylist> {
		self.playlists.iter_mut().find(|p| p.id == id)
	}

	pub fn rename_playlist(&mut self, id: &str, name: &str) -> bool {
		let name = clip(name, 80);
		match self.playlist_mut(id) {
			Some(p) if !name.is_empty() => {
				p.name = name;
				true
			}
			_ => false,
		}
	}

	pub fn delete_playlist(&mut self, id: &str) -> bool {
		let before = self.playlists.len();
		self.playlists.retain(|p| p.id != id);
		let key = format!("playlist:{id}");
		self.pinned.retain(|k| k != &key);
		self.playlists.len() != before
	}

	/// Returns false if the playlist doesn't exist, is full, or already has the track.
	pub fn add_to_playlist(&mut self, id: &str, track: Track) -> bool {
		match self.playlist_mut(id) {
			Some(p) if p.tracks.len() < MAX_PLAYLIST_TRACKS && !p.tracks.iter().any(|t| t.id == track.id) => {
				p.tracks.push(track);
				true
			}
			_ => false,
		}
	}

	pub fn remove_from_playlist(&mut self, id: &str, index: usize) -> bool {
		match self.playlist_mut(id) {
			Some(p) if index < p.tracks.len() => {
				p.tracks.remove(index);
				true
			}
			_ => false,
		}
	}

	/// Merges a YouTube import. Safe to repeat: imported playlists are matched by their YouTube ID
	/// and replaced, likes and subscriptions are only ever added (never removed), and anything the
	/// user made in the app is left alone.
	pub fn import_youtube(&mut self, playlists: Vec<(String, String, Vec<Track>)>, liked: Vec<Track>, followed: Vec<Artist>) -> ImportSummary {
		let mut sum = ImportSummary::default();
		for (yt_id, title, tracks) in playlists {
			let source = format!("youtube:{yt_id}");
			let mut seen = std::collections::HashSet::new();
			let tracks: Vec<Track> = tracks.into_iter().filter(|t| seen.insert(t.id.clone())).take(MAX_PLAYLIST_TRACKS).collect();
			if let Some(p) = self.playlists.iter_mut().find(|p| p.source.as_deref() == Some(source.as_str())) {
				p.name = clip(&title, 80);
				p.tracks = tracks;
				sum.playlists_updated += 1;
			} else if let Some(p) = self.create_playlist(&title, tracks) {
				if let Some(mine) = self.playlist_mut(&p.id) {
					mine.source = Some(source);
				}
				sum.playlists_added += 1;
			} else {
				sum.playlists_skipped += 1;
			}
		}
		for t in liked {
			if self.favorites.len() >= MAX_FAVORITES {
				break;
			}
			if !self.is_favorite(&t.id) {
				self.favorites.push(t);
				sum.favorites_added += 1;
			}
		}
		for a in followed {
			if self.followed.len() >= MAX_FOLLOWED {
				break;
			}
			if !self.is_following(&a.id) {
				self.followed.push(a);
				sum.artists_added += 1;
			}
		}
		sum
	}

	// ---- play statistics and insights ----

	/// Counts a play. `hour` (0–23) and `day` (local epoch day) come from the phone, which knows its
	/// time zone; without them only the count is kept.
	pub fn record_play(&mut self, track: &Track, at: u64, hour: Option<u32>, day: Option<u32>) {
		if let Some(s) = self.stats.iter_mut().find(|s| s.track.id == track.id) {
			s.count = s.count.saturating_add(1);
			s.last_played = at;
			s.track = track.clone();
		} else {
			if self.stats.len() >= MAX_STATS {
				// Forget whatever was played least recently.
				if let Some(i) = self.stats.iter().enumerate().min_by_key(|(_, s)| s.last_played).map(|(i, _)| i) {
					self.stats.swap_remove(i);
				}
			}
			self.stats.push(PlayStat { track: track.clone(), count: 1, first_played: at, last_played: at, seconds: 0 });
		}
		if let Some(h) = hour.filter(|h| *h < 24) {
			self.listening.hours[h as usize] = self.listening.hours[h as usize].saturating_add(1);
		}
		if let Some(d) = day {
			if let Err(i) = self.listening.days.binary_search(&d) {
				self.listening.days.insert(i, d);
				if self.listening.days.len() > MAX_DAYS {
					self.listening.days.remove(0);
				}
			}
		}
	}

	/// Adds listened time for a track (capped per report, so a stuck player can't inflate it).
	pub fn add_listened(&mut self, id: &str, seconds: u64) {
		let secs = seconds.min(6 * 3600);
		self.listening.seconds = self.listening.seconds.saturating_add(secs);
		if let Some(s) = self.stats.iter_mut().find(|s| s.track.id == id) {
			s.seconds = s.seconds.saturating_add(secs);
		}
	}

	pub fn plays_of(&self, id: &str) -> u32 {
		self.stats.iter().find(|s| s.track.id == id).map(|s| s.count).unwrap_or(0)
	}

	pub fn clear_stats(&mut self) {
		self.stats.clear();
		self.listening = Listening::default();
	}

	pub fn remove_history(&mut self, id: &str) -> bool {
		let before = self.history.len();
		self.history.retain(|h| h.track.id != id);
		self.history.len() != before
	}

	// ---- search history ----

	pub fn add_search(&mut self, query: &str, at: u64) {
		let q = clip(&query.split_whitespace().collect::<Vec<_>>().join(" "), 120);
		if q.is_empty() {
			return;
		}
		self.searches.retain(|e| !e.query.eq_ignore_ascii_case(&q));
		self.searches.insert(0, SearchEntry { query: q, at });
		self.searches.truncate(MAX_SEARCHES);
	}

	pub fn remove_search(&mut self, query: &str) -> bool {
		let before = self.searches.len();
		self.searches.retain(|e| !e.query.eq_ignore_ascii_case(query.trim()));
		self.searches.len() != before
	}

	// ---- pins ----

	/// Returns whether the item is now pinned. Keys are "kind:id" for playlist, artist, station or smart.
	pub fn toggle_pin(&mut self, key: &str) -> bool {
		let key = clip(key, 120);
		let valid = ["playlist:", "artist:", "station:", "smart:"].iter().any(|p| key.len() > p.len() && key.starts_with(p));
		if !valid {
			return false;
		}
		if self.pinned.contains(&key) {
			self.pinned.retain(|k| k != &key);
			false
		} else if self.pinned.len() >= MAX_PINS {
			false
		} else {
			self.pinned.insert(0, key);
			true
		}
	}

	// ---- playlist editing ----

	/// Moves a track inside a playlist.
	pub fn move_in_playlist(&mut self, id: &str, from: usize, to: usize) -> bool {
		match self.playlist_mut(id) {
			Some(p) if from < p.tracks.len() && to < p.tracks.len() && from != to => {
				let t = p.tracks.remove(from);
				p.tracks.insert(to, t);
				true
			}
			_ => false,
		}
	}

	/// Removes several positions at once (multi-select). Returns how many went.
	pub fn remove_many_from_playlist(&mut self, id: &str, indexes: &[usize]) -> usize {
		let Some(p) = self.playlist_mut(id) else { return 0 };
		let mut idx: Vec<usize> = indexes.iter().copied().filter(|i| *i < p.tracks.len()).collect();
		idx.sort_unstable();
		idx.dedup();
		for i in idx.iter().rev() {
			p.tracks.remove(*i);
		}
		idx.len()
	}

	/// Adds several tracks, skipping ones already there. Returns how many were added.
	pub fn add_many_to_playlist(&mut self, id: &str, tracks: Vec<Track>) -> usize {
		let Some(p) = self.playlist_mut(id) else { return 0 };
		let mut added = 0;
		for t in tracks {
			if p.tracks.len() >= MAX_PLAYLIST_TRACKS {
				break;
			}
			if !p.tracks.iter().any(|x| x.id == t.id) {
				p.tracks.push(t);
				added += 1;
			}
		}
		added
	}

	/// Drops repeated songs (same video, or the same title by the same artist). Returns how many went.
	pub fn dedupe_playlist(&mut self, id: &str) -> usize {
		let Some(p) = self.playlist_mut(id) else { return 0 };
		let before = p.tracks.len();
		let mut seen = std::collections::HashSet::new();
		p.tracks.retain(|t| {
			let by_id = seen.insert(format!("id:{}", t.id));
			let by_name = seen.insert(format!("name:{}|{}", crate::plugins::compact(&t.title), crate::plugins::compact(&t.artist)));
			by_id && by_name
		});
		before - p.tracks.len()
	}

	/// Copies `source`'s tracks into `target` (no duplicates); optionally deletes `source`.
	pub fn merge_playlists(&mut self, target: &str, source: &str, delete_source: bool) -> Option<usize> {
		if target == source {
			return None;
		}
		let tracks = self.playlists.iter().find(|p| p.id == source)?.tracks.clone();
		self.playlists.iter().find(|p| p.id == target)?;
		let added = self.add_many_to_playlist(target, tracks);
		if delete_source {
			self.delete_playlist(source);
			self.pinned.retain(|k| k != &format!("playlist:{source}"));
		}
		Some(added)
	}

	pub fn duplicate_playlist(&mut self, id: &str) -> Option<UserPlaylist> {
		let p = self.playlists.iter().find(|p| p.id == id)?.clone();
		self.create_playlist(&format!("{} (copy)", p.name), p.tracks)
	}

	// ---- dislikes and album actions ----

	pub fn is_disliked(&self, id: &str) -> bool {
		self.disliked.iter().any(|t| t.id == id)
	}

	/// Returns whether the song is now disliked. Disliking removes it from favourites.
	pub fn toggle_dislike(&mut self, track: Track) -> bool {
		if self.is_disliked(&track.id) {
			self.disliked.retain(|t| t.id != track.id);
			return false;
		}
		self.favorites.retain(|t| t.id != track.id);
		self.disliked.insert(0, track);
		self.disliked.truncate(MAX_FAVORITES);
		true
	}

	/// Likes every song in a list (an album or playlist page). Returns how many were new.
	pub fn like_many(&mut self, tracks: Vec<Track>) -> usize {
		let mut added = 0;
		for t in tracks.into_iter().rev() {
			if self.favorites.len() >= MAX_FAVORITES {
				break;
			}
			if !self.is_favorite(&t.id) {
				self.disliked.retain(|d| d.id != t.id);
				self.favorites.insert(0, t);
				added += 1;
			}
		}
		added
	}

	// ---- VRChat world sends ----

	pub fn record_world_send(&mut self, mut send: WorldSend) {
		if send.saved {
			send.expires_at = Some(send.at + SAVED_WORLD_DAYS * 86_400_000);
		}
		send.names = send.names.iter().take(crate::inworld::MAX_PLAYLISTS).map(|n| clip(n, 80)).collect();
		self.world_sends.insert(0, send);
		self.world_sends.truncate(MAX_WORLD_SENDS);
	}

	// ---- user smart playlists ----

	pub fn save_smart(&mut self, name: &str, rule: crate::smart::Rule) -> Option<SavedSmart> {
		if self.smart.len() >= MAX_SMART {
			return None;
		}
		let name = clip(name, 80);
		let mut id = format!("s{:x}", now_ms());
		while self.smart.iter().any(|s| s.id == id) {
			id.push('x');
		}
		let s = SavedSmart { id, name: if name.is_empty() { "Smart playlist".into() } else { name }, rule: rule.clamped() };
		self.smart.insert(0, s.clone());
		Some(s)
	}

	pub fn delete_smart(&mut self, id: &str) -> bool {
		let before = self.smart.len();
		self.smart.retain(|s| s.id != id);
		self.pinned.retain(|k| k != &format!("smart:{id}"));
		self.smart.len() != before
	}

	pub fn is_following(&self, id: &str) -> bool {
		self.followed.iter().any(|a| a.id == id || a.handle.as_deref() == Some(id))
	}

	/// Returns whether the artist is now followed.
	pub fn toggle_follow(&mut self, artist: Artist) -> bool {
		if self.is_following(&artist.id) || artist.handle.as_deref().is_some_and(|h| self.is_following(h)) {
			let handle = artist.handle.clone();
			self.followed.retain(|a| a.id != artist.id && (handle.is_none() || a.handle != handle));
			false
		} else if self.followed.len() >= MAX_FOLLOWED {
			false
		} else {
			self.followed.insert(0, artist);
			true
		}
	}
}

/// The library plus where it lives on disk.
pub struct Store {
	path: Option<PathBuf>,
	pub lib: Library,
}

impl Store {
	pub fn in_memory() -> Self {
		Store { path: None, lib: Library::default() }
	}

	/// Loads `library.json` from `dir`. A corrupt file is kept aside as `library.json.bad`
	/// instead of being overwritten, and the app starts with an empty library.
	pub fn open(dir: &Path) -> Self {
		let path = dir.join("library.json");
		let lib = match fs::read_to_string(&path) {
			Ok(text) => serde_json::from_str(&text).unwrap_or_else(|_| {
				let _ = fs::rename(&path, dir.join("library.json.bad"));
				Library::default()
			}),
			Err(_) => Library::default(),
		};
		Store { path: Some(path), lib }
	}

	pub fn save(&self) -> Result<(), String> {
		let Some(path) = &self.path else { return Ok(()) };
		let tmp = path.with_extension("json.tmp");
		let text = serde_json::to_string(&self.lib).map_err(|e| e.to_string())?;
		fs::write(&tmp, text).map_err(|e| format!("Couldn't save your library: {e}"))?;
		fs::rename(&tmp, path).map_err(|e| format!("Couldn't save your library: {e}"))
	}

	pub fn now() -> u64 {
		now_ms()
	}
}

#[cfg(test)]
mod tests {
	use super::*;

	fn t(id: &str) -> Track {
		Track { id: id.into(), title: id.into(), artist: "A".into(), artist_id: None, thumbnail: None, duration: None, duration_seconds: None, live: false, plays: None }
	}
	fn artist(id: &str, handle: Option<&str>) -> Artist {
		Artist { id: id.into(), name: id.into(), handle: handle.map(String::from), thumbnail: None, subtitle: None }
	}

	#[test]
	fn favorites_history_and_follows() {
		let mut l = Library::default();
		assert!(l.toggle_favorite(t("a")));
		assert!(l.is_favorite("a"));
		assert!(!l.toggle_favorite(t("a")));
		assert!(!l.is_favorite("a"));

		for i in 0..(MAX_HISTORY + 5) {
			l.add_history(t(&format!("{i:011}")), i as u64);
		}
		l.add_history(t("00000000003"), 9999);
		assert_eq!(l.history.len(), MAX_HISTORY);
		assert_eq!(l.history[0].track.id, "00000000003", "replaying moves a track to the top");
		assert_eq!(l.history.iter().filter(|h| h.track.id == "00000000003").count(), 1);

		assert!(l.toggle_follow(artist("UC1", Some("@one"))));
		assert!(l.is_following("@one"), "found by handle too");
		assert!(!l.toggle_follow(artist("@one", Some("@one"))), "unfollow from a handle-only reference");
		assert!(l.followed.is_empty());
	}

	#[test]
	fn playlists() {
		let mut l = Library::default();
		let p = l.create_playlist("  Road trip  ", vec![t("a"), t("a"), t("b")]).unwrap();
		assert_eq!(p.name, "Road trip");
		assert_eq!(p.tracks.len(), 2, "duplicates dropped");
		let q = l.create_playlist("", vec![]).unwrap();
		assert_ne!(p.id, q.id);
		assert_eq!(q.name, "My playlist");
		assert!(l.add_to_playlist(&p.id, t("c")));
		assert!(!l.add_to_playlist(&p.id, t("c")), "no duplicates");
		assert!(!l.add_to_playlist("nope", t("c")));
		assert!(l.remove_from_playlist(&p.id, 0));
		assert_eq!(l.playlists.iter().find(|x| x.id == p.id).unwrap().tracks.iter().map(|t| t.id.as_str()).collect::<Vec<_>>(), ["b", "c"]);
		assert!(l.rename_playlist(&p.id, "Drive"));
		assert!(!l.rename_playlist(&p.id, "   "));
		assert!(l.delete_playlist(&q.id));
		assert!(!l.delete_playlist(&q.id));
	}

	#[test]
	fn saves_atomically_and_survives_a_corrupt_file() {
		let dir = std::env::temp_dir().join(format!("btm-lib-{}", now_ms()));
		fs::create_dir_all(&dir).unwrap();
		let mut s = Store::open(&dir);
		s.lib.toggle_favorite(t("a"));
		s.save().unwrap();
		assert!(!dir.join("library.json.tmp").exists());
		assert!(Store::open(&dir).lib.is_favorite("a"));

		fs::write(dir.join("library.json"), "{not json").unwrap();
		let s = Store::open(&dir);
		assert!(s.lib.favorites.is_empty());
		assert!(dir.join("library.json.bad").exists(), "corrupt file kept for recovery");
		let _ = fs::remove_dir_all(&dir);
	}

	#[test]
	fn youtube_import_is_repeatable_and_keeps_local_data() {
		let mut l = Library::default();
		l.toggle_favorite(t("mine"));
		l.create_playlist("Made here", vec![t("a")]);
		let first = l.import_youtube(
			vec![("PL1".into(), "Road trip".into(), vec![t("a"), t("b"), t("b")])],
			vec![t("mine"), t("liked1")],
			vec![artist("UC1", None), artist("UC2", None)],
		);
		assert_eq!(first, ImportSummary { playlists_added: 1, playlists_updated: 0, playlists_skipped: 0, favorites_added: 1, artists_added: 2 });
		assert_eq!(l.favorites[0].id, "mine", "local favourites stay on top");
		let imported = l.playlists.iter().find(|p| p.source.as_deref() == Some("youtube:PL1")).unwrap();
		assert_eq!(imported.tracks.len(), 2, "duplicates dropped");

		let again = l.import_youtube(vec![("PL1".into(), "Road trip 2".into(), vec![t("c")])], vec![t("liked1")], vec![artist("UC1", None)]);
		assert_eq!(again, ImportSummary { playlists_updated: 1, ..Default::default() });
		assert_eq!(l.playlists.len(), 2, "no duplicate playlist on re-import");
		let imported = l.playlists.iter().find(|p| p.source.is_some()).unwrap();
		assert_eq!((imported.name.as_str(), imported.tracks.len()), ("Road trip 2", 1));
		assert!(l.playlists.iter().any(|p| p.name == "Made here" && p.source.is_none()), "app-made playlist untouched");
	}

	#[test]
	fn search_history_pins_and_history_removal() {
		let mut l = Library::default();
		l.add_search("  lofi   hip hop ", 1);
		l.add_search("daft punk", 2);
		l.add_search("LOFI HIP HOP", 3);
		assert_eq!(l.searches.iter().map(|e| e.query.as_str()).collect::<Vec<_>>(), ["LOFI HIP HOP", "daft punk"], "re-searching moves it to the top");
		l.add_search("   ", 4);
		assert_eq!(l.searches.len(), 2);
		for i in 0..80 {
			l.add_search(&format!("q{i}"), i);
		}
		assert_eq!(l.searches.len(), MAX_SEARCHES);
		assert!(l.remove_search("q79"));
		assert!(!l.remove_search("q79"));

		assert!(l.toggle_pin("playlist:p1"));
		assert!(!l.toggle_pin("playlist:"), "needs an id");
		assert!(!l.toggle_pin("anything:p1"), "unknown kinds refused");
		assert!(!l.toggle_pin("playlist:p1"), "toggles off");
		let p = l.create_playlist("x", vec![]).unwrap();
		l.toggle_pin(&format!("playlist:{}", p.id));
		l.delete_playlist(&p.id);
		assert!(l.pinned.is_empty(), "deleting a playlist unpins it");

		l.add_history(t("a"), 1);
		l.add_history(t("b"), 2);
		assert!(l.remove_history("a"));
		assert_eq!(l.history.len(), 1);
	}

	#[test]
	fn batch_playlist_editing() {
		let mut l = Library::default();
		let a = l.create_playlist("A", vec![t("1"), t("2"), t("3"), t("4")]).unwrap();
		assert!(l.move_in_playlist(&a.id, 0, 3));
		let order = |l: &Library, id: &str| l.playlists.iter().find(|p| p.id == id).unwrap().tracks.iter().map(|t| t.id.clone()).collect::<Vec<_>>();
		assert_eq!(order(&l, &a.id), ["2", "3", "4", "1"]);
		assert!(!l.move_in_playlist(&a.id, 0, 9));
		assert_eq!(l.remove_many_from_playlist(&a.id, &[3, 0, 0, 42]), 2);
		assert_eq!(order(&l, &a.id), ["3", "4"]);
		assert_eq!(l.add_many_to_playlist(&a.id, vec![t("4"), t("5"), t("5")]), 1);

		let mut same_song = t("6");
		same_song.title = "3".into(); // t("3") has title "3" and artist "A" too
		l.playlist_mut(&a.id).unwrap().tracks.push(same_song);
		assert_eq!(l.dedupe_playlist(&a.id), 1, "the same title by the same artist counts as a repeat");

		let b = l.create_playlist("B", vec![t("5"), t("7")]).unwrap();
		l.toggle_pin(&format!("playlist:{}", b.id));
		assert_eq!(l.merge_playlists(&a.id, &b.id, true), Some(1));
		assert!(l.playlists.iter().all(|p| p.id != b.id), "source deleted");
		assert!(l.pinned.is_empty());
		assert_eq!(l.merge_playlists(&a.id, &a.id, false), None);
		let copy = l.duplicate_playlist(&a.id).unwrap();
		assert_eq!((copy.name.as_str(), copy.tracks.len()), ("A (copy)", 4));
	}

	#[test]
	fn play_stats_are_bounded() {
		let mut l = Library::default();
		l.record_play(&t("a"), 10, Some(7), Some(5));
		l.record_play(&t("a"), 20, Some(7), Some(5));
		l.record_play(&t("b"), 30, Some(99), None);
		assert_eq!((l.plays_of("a"), l.plays_of("b"), l.plays_of("c")), (2, 1, 0));
		assert_eq!(l.listening.hours[7], 2);
		assert_eq!(l.listening.hours.iter().sum::<u32>(), 2, "a bad hour is ignored");
		assert_eq!(l.listening.days, [5]);
		for i in 0..MAX_STATS {
			l.record_play(&t(&format!("{i:011}")), 100 + i as u64, None, Some(i as u32));
		}
		assert_eq!(l.stats.len(), MAX_STATS);
		assert_eq!(l.plays_of("a"), 0, "least recently played forgotten first");
		assert_eq!(l.listening.days.len(), MAX_DAYS);
		l.clear_stats();
		assert!(l.stats.is_empty() && l.listening.days.is_empty());
	}

	#[test]
	fn dislikes_and_like_many() {
		let mut l = Library::default();
		l.toggle_favorite(t("a"));
		assert!(l.toggle_dislike(t("a")));
		assert!(!l.is_favorite("a"), "disliking unlikes");
		assert_eq!(l.like_many(vec![t("a"), t("b"), t("b")]), 2);
		assert!(!l.is_disliked("a"), "liking undislikes");
		assert_eq!(l.favorites.iter().map(|x| x.id.as_str()).collect::<Vec<_>>(), ["a", "b"], "keeps the list's order on top");
		assert!(l.toggle_dislike(t("c")));
		assert!(!l.toggle_dislike(t("c")));
	}

	#[test]
	fn old_files_without_new_fields_still_load() {
		let lib: Library = serde_json::from_str(r#"{"favorites":[{"id":"a","title":"A","artist":"B"}]}"#).unwrap();
		assert!(lib.is_favorite("a"));
		assert!(lib.playlists.is_empty());
	}
}
