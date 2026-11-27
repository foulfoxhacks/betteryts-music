//! Stations: endless radio as a first-class listening mode.
//!
//! A station grows from a song, an artist or a playlist and keeps going on YouTube Music's own
//! radio ([`crate::radio`]). The station owns everything the UI shouldn't have to: the
//! continuation token, which songs it already handed out (so a page never repeats one), "not for
//! this station" feedback, the familiar ↔ discovery tuning, and recovery when a continuation
//! expires (it re-seeds from the last song instead of stopping).
//!
//! Saved and recent stations live in `stations.json`, on the phone only.

use std::collections::HashSet;
use std::fs;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::model::Track;
use crate::radio::RadioPage;

pub const MAX_SEEN: usize = 1500;
pub const MAX_BLOCKED: usize = 300;
pub const MAX_SAVED: usize = 50;
pub const MAX_RECENT: usize = 12;

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum SeedKind {
	Track,
	Artist,
	/// A YouTube playlist.
	Playlist,
	/// One of the user's own playlists (its tracks are sent along, since YouTube doesn't know it).
	Library,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Seed {
	pub kind: SeedKind,
	pub id: String,
	pub title: String,
	#[serde(default)]
	pub art: Option<String>,
}

/// Familiar plays more of the artists you already listen to; Discover plays fewer of them.
#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq, Default)]
#[serde(rename_all = "lowercase")]
pub enum Mix {
	Familiar,
	#[default]
	Balanced,
	Discover,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Station {
	/// "track:<videoId>", "artist:<channel>", "playlist:<id>" or "library:<id>".
	pub id: String,
	pub seed: Seed,
	#[serde(default)]
	pub mix: Mix,
	/// The song the current radio grows from (the seed's, or the last "more like this").
	#[serde(default)]
	pub radio_from: Option<String>,
	/// YouTube Music's continuation. Opaque; it expires, so it's never trusted to still work.
	#[serde(default)]
	pub next: Option<String>,
	/// Songs already handed out, oldest first.
	#[serde(default)]
	pub seen: Vec<String>,
	#[serde(default)]
	pub blocked_tracks: Vec<String>,
	/// Compacted artist names the user said aren't for this station.
	#[serde(default)]
	pub blocked_artists: Vec<String>,
	/// One entry per "not for this station" on an artist's song; two strikes block the artist.
	#[serde(default)]
	pub strikes: Vec<String>,
	#[serde(default)]
	pub last_track: Option<String>,
	#[serde(default)]
	pub played: u32,
	#[serde(default)]
	pub skips: u32,
	pub created_at: u64,
	#[serde(default)]
	pub last_played_at: u64,
}

/// What a station page says beyond its songs, so the UI can explain itself.
#[derive(Serialize, Debug, Default, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Outcome {
	pub tracks: Vec<Track>,
	/// The continuation had expired (or failed) and the station started again from a recent song.
	pub recovered: bool,
	/// Songs dropped as repeats, blocked or live.
	pub filtered: usize,
}

/// Where station songs come from. The real one calls YouTube Music and BetterYTS; tests use fakes.
pub trait Source {
	fn radio(&self, video_id: &str, continuation: Option<&str>) -> Result<RadioPage, String>;
	/// A YouTube playlist's own radio, when YouTube Music has one.
	fn playlist_radio(&self, playlist_id: &str) -> Result<RadioPage, String>;
	fn artist_tracks(&self, artist_id: &str) -> Result<Vec<Track>, String>;
	fn playlist_tracks(&self, playlist_id: &str) -> Result<Vec<Track>, String>;
}

pub fn station_id(kind: SeedKind, id: &str) -> String {
	let k = match kind {
		SeedKind::Track => "track",
		SeedKind::Artist => "artist",
		SeedKind::Playlist => "playlist",
		SeedKind::Library => "library",
	};
	format!("{k}:{id}")
}

fn artist_key(t: &Track) -> String {
	crate::plugins::compact(&t.artist)
}

impl Station {
	pub fn new(seed: Seed, mix: Mix, now: u64) -> Station {
		let title: String = seed.title.trim().chars().take(100).collect();
		let seed = Seed { title: if title.is_empty() { "Radio".into() } else { title }, ..seed };
		Station {
			id: station_id(seed.kind, &seed.id),
			radio_from: (seed.kind == SeedKind::Track).then(|| seed.id.clone()),
			seed,
			mix,
			next: None,
			seen: Vec::new(),
			blocked_tracks: Vec::new(),
			blocked_artists: Vec::new(),
			strikes: Vec::new(),
			last_track: None,
			played: 0,
			skips: 0,
			created_at: now,
			last_played_at: now,
		}
	}

	fn mark_seen(&mut self, id: &str) {
		self.seen.push(id.to_string());
		if self.seen.len() > MAX_SEEN {
			let extra = self.seen.len() - MAX_SEEN;
			self.seen.drain(..extra);
		}
	}

	/// Keeps songs that are new to this station and not blocked, then applies the tuning.
	/// `known_artists` are the user's own (compacted names), for Familiar/Discover.
	fn take(&mut self, tracks: Vec<Track>, known_artists: &HashSet<String>, hidden: &dyn Fn(&Track) -> bool) -> (Vec<Track>, usize) {
		let mut seen: HashSet<String> = self.seen.iter().cloned().collect();
		let total = tracks.len();
		let fresh: Vec<Track> = tracks
			.into_iter()
			.filter(|t| !t.live && !self.blocked_tracks.contains(&t.id) && !self.blocked_artists.contains(&artist_key(t)) && !hidden(t))
			.filter(|t| seen.insert(t.id.clone()))
			.collect();
		let out = if self.mix == Mix::Balanced {
			// YouTube's own order.
			fresh
		} else {
			let (known, new): (Vec<Track>, Vec<Track>) = fresh.into_iter().partition(|t| known_artists.contains(&artist_key(t)));
			self.tuned(known, new)
		};
		for t in &out {
			self.mark_seen(&t.id);
		}
		let filtered = total - out.len();
		(out, filtered)
	}

	fn tuned(&self, known: Vec<Track>, new: Vec<Track>) -> Vec<Track> {
		match self.mix {
			Mix::Balanced | Mix::Familiar => known.into_iter().chain(new).collect(),
			// At most one familiar artist for every three new ones, and only after them.
			Mix::Discover => {
				let keep = new.len() / 3;
				let mut v = new;
				v.extend(known.into_iter().take(keep.max(if v.is_empty() { 3 } else { 0 })));
				v
			}
		}
	}
}

/// The songs a seed starts with, and the song its radio grows from.
fn first_page(station: &mut Station, src: &dyn Source) -> Result<(Vec<Track>, Option<String>), String> {
	match station.seed.kind {
		SeedKind::Track => {
			let page = src.radio(&station.seed.id, None)?;
			Ok((page.tracks, page.next))
		}
		SeedKind::Artist => {
			// The artist's own top songs first, then the radio of their top song.
			let top = src.artist_tracks(&station.seed.id)?;
			let first = top.first().ok_or("No songs from this artist to start a station")?.id.clone();
			station.radio_from = Some(first.clone());
			let page = src.radio(&first, None).unwrap_or_default();
			let mut tracks: Vec<Track> = top.into_iter().take(5).collect();
			tracks.extend(page.tracks);
			Ok((tracks, page.next))
		}
		SeedKind::Playlist => {
			if let Ok(page) = src.playlist_radio(&station.seed.id) {
				if !page.tracks.is_empty() {
					station.radio_from = page.tracks.first().map(|t| t.id.clone());
					return Ok((page.tracks, page.next));
				}
			}
			// No playlist radio: start from the playlist's own songs and grow from one of them.
			let own = src.playlist_tracks(&station.seed.id)?;
			seeded_from_list(station, src, own)
		}
		SeedKind::Library => Err("A library station needs its tracks".into()),
	}
}

/// Starts from a list (a playlist's songs): a few of them, then the radio of one of them.
fn seeded_from_list(station: &mut Station, src: &dyn Source, own: Vec<Track>) -> Result<(Vec<Track>, Option<String>), String> {
	let playable: Vec<Track> = own.into_iter().filter(|t| !t.live && !t.id.starts_with("local:")).collect();
	let pick = playable.get(playable.len() / 2).or(playable.first()).ok_or("This playlist has no YouTube songs to start a station")?.id.clone();
	station.radio_from = Some(pick.clone());
	let page = src.radio(&pick, None).unwrap_or_default();
	let mut tracks: Vec<Track> = playable.into_iter().take(4).collect();
	tracks.extend(page.tracks);
	Ok((tracks, page.next))
}

/// Starts (or restarts) a station. `own` carries a library playlist's songs.
pub fn start(station: &mut Station, src: &dyn Source, own: Option<Vec<Track>>, known_artists: &HashSet<String>, hidden: &dyn Fn(&Track) -> bool) -> Result<Outcome, String> {
	let (tracks, next) = match (station.seed.kind, own) {
		(SeedKind::Library, Some(own)) => seeded_from_list(station, src, own)?,
		(SeedKind::Library, None) => return Err("That playlist is empty".into()),
		_ => first_page(station, src)?,
	};
	station.next = next;
	let (tracks, filtered) = station.take(tracks, known_artists, hidden);
	if tracks.is_empty() {
		return Err("No radio for this one. Try a different song.".into());
	}
	Ok(Outcome { tracks, recovered: false, filtered })
}

/// More songs. An expired or failing continuation doesn't end the station: it starts a fresh radio
/// from the last song played (or the seed) and skips everything it already played.
pub fn more(station: &mut Station, src: &dyn Source, known_artists: &HashSet<String>, hidden: &dyn Fn(&Track) -> bool) -> Result<Outcome, String> {
	let from = station.radio_from.clone().or_else(|| station.last_track.clone()).or_else(|| (station.seed.kind == SeedKind::Track).then(|| station.seed.id.clone()));
	let mut recovered = false;
	let mut filtered = 0;
	// A continuation, then up to two fresh starts (the last song played, then the radio's own seed).
	let mut attempts: Vec<(String, Option<String>)> = Vec::new();
	if let (Some(f), Some(n)) = (&from, &station.next) {
		attempts.push((f.clone(), Some(n.clone())));
	}
	if let Some(last) = &station.last_track {
		attempts.push((last.clone(), None));
	}
	if let Some(f) = &from {
		if Some(f) != station.last_track.as_ref() {
			attempts.push((f.clone(), None));
		}
	}
	if attempts.is_empty() {
		return Err("This station has nothing to grow from yet".into());
	}
	let mut last_err = String::from("Radio: no more songs right now");
	for (i, (video, cont)) in attempts.into_iter().enumerate() {
		match src.radio(&video, cont.as_deref()) {
			Ok(page) => {
				let (tracks, f) = station.take(page.tracks, known_artists, hidden);
				filtered += f;
				if cont.is_none() {
					station.radio_from = Some(video.clone());
				}
				station.next = page.next;
				if !tracks.is_empty() {
					return Ok(Outcome { tracks, recovered: recovered || i > 0, filtered });
				}
				recovered = true;
			}
			Err(e) => {
				last_err = e;
				recovered = true;
			}
		}
	}
	station.next = None;
	Err(last_err)
}

#[derive(Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum Feedback {
	/// Skipped (counted, nothing else).
	Skip,
	/// Played (or played through): the station remembers it for recovery.
	Played,
	/// Never this song on this station again; twice for the same artist blocks the artist.
	NotForStation,
	/// The station's next songs grow from this one.
	MoreLikeThis,
}

pub fn feedback(station: &mut Station, track: &Track, kind: Feedback, now: u64) -> &'static str {
	match kind {
		Feedback::Skip => {
			station.skips += 1;
			"skipped"
		}
		Feedback::Played => {
			station.played += 1;
			station.last_track = Some(track.id.clone());
			station.last_played_at = now;
			"played"
		}
		Feedback::NotForStation => {
			let artist = artist_key(track);
			if !station.blocked_tracks.contains(&track.id) {
				station.blocked_tracks.push(track.id.clone());
				if station.blocked_tracks.len() > MAX_BLOCKED {
					station.blocked_tracks.remove(0);
				}
			}
			if artist.is_empty() {
				return "track blocked";
			}
			station.strikes.push(artist.clone());
			if station.strikes.len() > MAX_BLOCKED {
				station.strikes.remove(0);
			}
			let strikes = station.strikes.iter().filter(|a| **a == artist).count();
			if strikes >= 2 && !station.blocked_artists.contains(&artist) {
				station.blocked_artists.push(artist);
				if station.blocked_artists.len() > MAX_BLOCKED {
					station.blocked_artists.remove(0);
				}
				return "artist blocked";
			}
			"track blocked"
		}
		Feedback::MoreLikeThis => {
			station.radio_from = Some(track.id.clone());
			station.next = None;
			"retuned"
		}
	}
}

/// Saved stations, recent ones, and the one playing now.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct Stations {
	pub current: Option<Station>,
	pub saved: Vec<Station>,
	pub recent: Vec<Station>,
}

impl Stations {
	/// Makes `s` the current station; the previous one goes to the top of the recent list.
	pub fn set_current(&mut self, s: Station) {
		if let Some(prev) = self.current.take() {
			if prev.id != s.id {
				self.push_recent(prev);
			}
		}
		self.recent.retain(|r| r.id != s.id);
		self.current = Some(s);
	}

	pub fn stop(&mut self) {
		if let Some(prev) = self.current.take() {
			self.push_recent(prev);
		}
	}

	fn push_recent(&mut self, s: Station) {
		self.recent.retain(|r| r.id != s.id);
		self.recent.insert(0, s);
		self.recent.truncate(MAX_RECENT);
	}

	/// Saves (or updates) the current station. Returns false when there's none or the list is full.
	pub fn save_current(&mut self) -> bool {
		let Some(cur) = self.current.clone() else { return false };
		if let Some(s) = self.saved.iter_mut().find(|s| s.id == cur.id) {
			*s = cur;
			return true;
		}
		if self.saved.len() >= MAX_SAVED {
			return false;
		}
		self.saved.insert(0, cur);
		true
	}

	pub fn is_saved(&self, id: &str) -> bool {
		self.saved.iter().any(|s| s.id == id)
	}

	pub fn delete(&mut self, id: &str) -> bool {
		let before = self.saved.len() + self.recent.len();
		self.saved.retain(|s| s.id != id);
		self.recent.retain(|s| s.id != id);
		before != self.saved.len() + self.recent.len()
	}

	/// A saved or recent station by ID (a copy, to start again).
	pub fn find(&self, id: &str) -> Option<Station> {
		self.saved.iter().chain(self.recent.iter()).chain(self.current.iter()).find(|s| s.id == id).cloned()
	}

	/// Keeps saved copies in step with the current station's tuning and feedback.
	pub fn sync_saved(&mut self) {
		if let Some(cur) = &self.current {
			if let Some(s) = self.saved.iter_mut().find(|s| s.id == cur.id) {
				*s = cur.clone();
			}
		}
	}
}

/// What the app sees of a station: no continuation token or seen-list.
#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct StationView {
	pub id: String,
	pub seed: Seed,
	pub mix: Mix,
	pub saved: bool,
	pub played: u32,
	pub skips: u32,
	pub blocked: usize,
	pub last_played_at: u64,
}

pub fn view(s: &Station, all: &Stations) -> StationView {
	StationView {
		id: s.id.clone(),
		seed: s.seed.clone(),
		mix: s.mix,
		saved: all.is_saved(&s.id),
		played: s.played,
		skips: s.skips,
		blocked: s.blocked_tracks.len() + s.blocked_artists.len(),
		last_played_at: s.last_played_at,
	}
}

pub struct StationStore {
	path: Option<PathBuf>,
	pub stations: Stations,
}

impl StationStore {
	pub fn in_memory() -> Self {
		StationStore { path: None, stations: Stations::default() }
	}

	pub fn open(dir: &Path) -> Self {
		let path = dir.join("stations.json");
		let stations = fs::read_to_string(&path).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default();
		StationStore { path: Some(path), stations }
	}

	pub fn save(&self) -> Result<(), String> {
		let Some(path) = &self.path else { return Ok(()) };
		let tmp = path.with_extension("json.tmp");
		let text = serde_json::to_string(&self.stations).map_err(|e| e.to_string())?;
		fs::write(&tmp, text).map_err(|e| format!("Couldn't save stations: {e}"))?;
		fs::rename(&tmp, path).map_err(|e| format!("Couldn't save stations: {e}"))
	}
}

/// The real source: YouTube Music radio straight from the phone, catalog data through BetterYTS.
pub struct Live<'a> {
	pub api: &'a crate::api::Api,
}

impl Source for Live<'_> {
	fn radio(&self, video_id: &str, continuation: Option<&str>) -> Result<RadioPage, String> {
		crate::radio::radio(video_id, continuation, &self.api.region)
	}
	fn playlist_radio(&self, playlist_id: &str) -> Result<RadioPage, String> {
		crate::radio::playlist_radio(playlist_id, &self.api.region)
	}
	fn artist_tracks(&self, artist_id: &str) -> Result<Vec<Track>, String> {
		Ok(self.api.artist(artist_id, None)?.tracks)
	}
	fn playlist_tracks(&self, playlist_id: &str) -> Result<Vec<Track>, String> {
		Ok(self.api.playlist(playlist_id, None)?.tracks)
	}
}

#[cfg(test)]
mod tests {
	use super::*;
	use serde_json::json;
	use std::cell::RefCell;

	fn t(id: &str, artist: &str) -> Track {
		serde_json::from_value(json!({ "id": id, "title": format!("Song {id}"), "artist": artist })).unwrap()
	}

	/// Pages keyed by (video, continuation); "EXPIRED" continuations fail like YouTube's do.
	#[derive(Default)]
	struct Fake {
		pages: Vec<(String, Option<String>, Vec<Track>, Option<String>)>,
		artist: Vec<Track>,
		playlist: Vec<Track>,
		playlist_radio: Vec<Track>,
		calls: RefCell<Vec<String>>,
	}

	impl Source for Fake {
		fn radio(&self, video_id: &str, continuation: Option<&str>) -> Result<RadioPage, String> {
			self.calls.borrow_mut().push(format!("{video_id}/{}", continuation.unwrap_or("-")));
			if continuation == Some("EXPIRED") {
				return Err("Radio: YouTube Music returned HTTP 400".into());
			}
			self.pages
				.iter()
				.find(|(v, c, ..)| v == video_id && c.as_deref() == continuation)
				.map(|(_, _, tracks, next)| RadioPage { tracks: tracks.clone(), next: next.clone() })
				.ok_or_else(|| "No radio for this song".into())
		}
		fn playlist_radio(&self, _: &str) -> Result<RadioPage, String> {
			Ok(RadioPage { tracks: self.playlist_radio.clone(), next: None })
		}
		fn artist_tracks(&self, _: &str) -> Result<Vec<Track>, String> {
			Ok(self.artist.clone())
		}
		fn playlist_tracks(&self, _: &str) -> Result<Vec<Track>, String> {
			Ok(self.playlist.clone())
		}
	}

	fn seed(kind: SeedKind, id: &str) -> Seed {
		Seed { kind, id: id.into(), title: "Seed".into(), art: None }
	}

	fn ids(o: &Outcome) -> Vec<&str> {
		o.tracks.iter().map(|t| t.id.as_str()).collect()
	}

	const NONE: fn(&Track) -> bool = |_| false;

	#[test]
	fn continuation_pages_never_repeat_a_song() {
		let src = Fake {
			pages: vec![
				("s".into(), None, vec![t("s", "A"), t("a", "A"), t("b", "B")], Some("C1".into())),
				("s".into(), Some("C1".into()), vec![t("b", "B"), t("c", "C"), t("a", "A")], Some("C2".into())),
			],
			..Default::default()
		};
		let mut st = Station::new(seed(SeedKind::Track, "s"), Mix::Balanced, 0);
		let first = start(&mut st, &src, None, &HashSet::new(), &NONE).unwrap();
		assert_eq!(ids(&first), ["s", "a", "b"]);
		let more = more(&mut st, &src, &HashSet::new(), &NONE).unwrap();
		assert_eq!(ids(&more), ["c"], "repeats across pages are dropped");
		assert_eq!(more.filtered, 2);
		assert_eq!(st.next.as_deref(), Some("C2"));
	}

	#[test]
	fn an_expired_continuation_recovers_from_the_last_song() {
		let src = Fake {
			pages: vec![
				("s".into(), None, vec![t("s", "A"), t("a", "A")], Some("EXPIRED".into())),
				("a".into(), None, vec![t("a", "A"), t("x", "X"), t("y", "Y")], Some("C9".into())),
			],
			..Default::default()
		};
		let mut st = Station::new(seed(SeedKind::Track, "s"), Mix::Balanced, 0);
		start(&mut st, &src, None, &HashSet::new(), &NONE).unwrap();
		feedback(&mut st, &t("a", "A"), Feedback::Played, 5);
		let o = more(&mut st, &src, &HashSet::new(), &NONE).unwrap();
		assert!(o.recovered);
		assert_eq!(ids(&o), ["x", "y"]);
		assert_eq!((st.radio_from.as_deref(), st.next.as_deref()), (Some("a"), Some("C9")), "the station now grows from the recovered radio");
		assert_eq!(*src.calls.borrow(), ["s/-", "s/EXPIRED", "a/-"]);
	}

	#[test]
	fn feedback_blocks_songs_then_artists_and_retunes() {
		let src = Fake {
			pages: vec![
				("s".into(), None, vec![t("s", "A")], Some("C1".into())),
				("s".into(), Some("C1".into()), vec![t("b1", "Bad Band"), t("b2", "Bad Band"), t("b3", "bad band"), t("ok", "Good")], None),
				("ok".into(), None, vec![t("ok2", "Good")], None),
			],
			..Default::default()
		};
		let mut st = Station::new(seed(SeedKind::Track, "s"), Mix::Balanced, 0);
		start(&mut st, &src, None, &HashSet::new(), &NONE).unwrap();
		assert_eq!(feedback(&mut st, &t("x1", "Bad Band"), Feedback::NotForStation, 0), "track blocked");
		assert_eq!(feedback(&mut st, &t("x2", "Bad Band"), Feedback::NotForStation, 0), "artist blocked");
		let o = more(&mut st, &src, &HashSet::new(), &NONE).unwrap();
		assert_eq!(ids(&o), ["ok"], "a blocked artist is blocked under any spelling");
		assert_eq!(view(&st, &Stations::default()).blocked, 3);
		assert_eq!(feedback(&mut st, &t("ok", "Good"), Feedback::MoreLikeThis, 0), "retuned");
		let o = more(&mut st, &src, &HashSet::new(), &NONE).unwrap();
		assert_eq!(ids(&o), ["ok2"]);
	}

	#[test]
	fn tuning_reorders_familiar_and_discovery() {
		let page = vec![t("1", "Known"), t("2", "New1"), t("3", "Known"), t("4", "New2"), t("5", "New3"), t("6", "New4")];
		let src = Fake { pages: vec![("s".into(), None, page, None)], ..Default::default() };
		let known: HashSet<String> = [crate::plugins::compact("Known")].into_iter().collect();
		let mut fam = Station::new(seed(SeedKind::Track, "s"), Mix::Familiar, 0);
		assert_eq!(ids(&start(&mut fam, &src, None, &known, &NONE).unwrap()), ["1", "3", "2", "4", "5", "6"]);
		let mut disc = Station::new(seed(SeedKind::Track, "s"), Mix::Discover, 0);
		assert_eq!(ids(&start(&mut disc, &src, None, &known, &NONE).unwrap()), ["2", "4", "5", "6", "1"], "one familiar song per three new ones, at the end");
		let mut bal = Station::new(seed(SeedKind::Track, "s"), Mix::Balanced, 0);
		assert_eq!(ids(&start(&mut bal, &src, None, &known, &NONE).unwrap()).len(), 6);
	}

	#[test]
	fn artist_playlist_and_library_seeds() {
		let src = Fake {
			pages: vec![("top".into(), None, vec![t("top", "A"), t("r1", "R")], None), ("mid".into(), None, vec![t("r2", "R")], None)],
			artist: vec![t("top", "A"), t("a2", "A")],
			playlist: vec![t("p1", "P"), t("mid", "P"), t("p3", "P")],
			..Default::default()
		};
		let mut a = Station::new(seed(SeedKind::Artist, "UC1"), Mix::Balanced, 0);
		assert_eq!(ids(&start(&mut a, &src, None, &HashSet::new(), &NONE).unwrap()), ["top", "a2", "r1"]);
		assert_eq!(a.id, "artist:UC1");
		let mut p = Station::new(seed(SeedKind::Playlist, "PL1"), Mix::Balanced, 0);
		assert_eq!(ids(&start(&mut p, &src, None, &HashSet::new(), &NONE).unwrap()), ["p1", "mid", "p3", "r2"], "no playlist radio: its songs, then a radio from one of them");
		let with_radio = Fake { playlist_radio: vec![t("pr", "Z")], ..Default::default() };
		let mut p2 = Station::new(seed(SeedKind::Playlist, "PL1"), Mix::Balanced, 0);
		assert_eq!(ids(&start(&mut p2, &with_radio, None, &HashSet::new(), &NONE).unwrap()), ["pr"]);
		let mut lib = Station::new(seed(SeedKind::Library, "p123"), Mix::Balanced, 0);
		let own = vec![t("local:5", "Me"), t("mid", "P")];
		assert_eq!(ids(&start(&mut lib, &src, Some(own), &HashSet::new(), &NONE).unwrap()), ["mid", "r2"], "device files can't seed YouTube radio");
		assert!(start(&mut Station::new(seed(SeedKind::Library, "x"), Mix::Balanced, 0), &src, None, &HashSet::new(), &NONE).is_err());
	}

	#[test]
	fn hidden_and_live_songs_are_filtered() {
		let mut live = t("l", "L");
		live.live = true;
		let src = Fake { pages: vec![("s".into(), None, vec![t("s", "A"), live, t("h", "Hidden")], None)], ..Default::default() };
		let mut st = Station::new(seed(SeedKind::Track, "s"), Mix::Balanced, 0);
		let hidden = |x: &Track| x.artist == "Hidden";
		let o = start(&mut st, &src, None, &HashSet::new(), &hidden).unwrap();
		assert_eq!((ids(&o), o.filtered), (vec!["s"], 2));
	}

	#[test]
	fn saved_and_recent_stations() {
		let mut all = Stations::default();
		all.set_current(Station::new(seed(SeedKind::Track, "a"), Mix::Balanced, 1));
		assert!(all.save_current());
		all.set_current(Station::new(seed(SeedKind::Track, "b"), Mix::Balanced, 2));
		assert_eq!(all.recent.iter().map(|s| s.id.as_str()).collect::<Vec<_>>(), ["track:a"]);
		all.set_current(Station::new(seed(SeedKind::Track, "a"), Mix::Discover, 3));
		assert_eq!(all.recent.iter().map(|s| s.id.as_str()).collect::<Vec<_>>(), ["track:b"], "the current one isn't also recent");
		all.sync_saved();
		assert_eq!(all.saved[0].mix, Mix::Discover, "saved copy follows the tuning");
		assert!(view(all.current.as_ref().unwrap(), &all).saved);
		for i in 0..20 {
			all.set_current(Station::new(seed(SeedKind::Track, &format!("x{i}")), Mix::Balanced, 0));
		}
		assert_eq!(all.recent.len(), MAX_RECENT);
		assert!(all.find("track:a").is_some(), "saved stations stay findable");
		assert!(all.delete("track:a"));
		all.stop();
		assert!(all.current.is_none());

		let dir = std::env::temp_dir().join(format!("btm-stations-{}", crate::library::Store::now()));
		fs::create_dir_all(&dir).unwrap();
		let mut s = StationStore::open(&dir);
		s.stations = all;
		s.save().unwrap();
		assert_eq!(StationStore::open(&dir).stations.recent.len(), MAX_RECENT);
		let _ = fs::remove_dir_all(&dir);
	}
}
