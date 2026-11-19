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

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct Library {
	pub favorites: Vec<Track>,
	pub history: Vec<HistoryEntry>,
	pub playlists: Vec<UserPlaylist>,
	pub followed: Vec<Artist>,
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
	fn old_files_without_new_fields_still_load() {
		let lib: Library = serde_json::from_str(r#"{"favorites":[{"id":"a","title":"A","artist":"B"}]}"#).unwrap();
		assert!(lib.is_favorite("a"));
		assert!(lib.playlists.is_empty());
	}
}
