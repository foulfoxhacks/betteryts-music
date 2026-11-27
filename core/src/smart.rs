//! Smart playlists and listening insights, worked out on the phone from the library.
//!
//! A smart playlist is a [`Rule`], not a list of tracks: it refreshes itself as you like, play and
//! add songs. The built-in ones ("Most played", "Never played"…) are rules too, and the user can
//! write their own, previewing the result before saving. Nothing here touches the network.

use std::collections::{HashMap, HashSet};

use serde::{Deserialize, Serialize};

use crate::library::Library;
use crate::model::Track;

const DAY_MS: u64 = 86_400_000;
pub const MAX_LIMIT: u32 = 500;

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub enum Source {
	/// Liked songs, playlists, history and played songs together.
	#[default]
	All,
	Favorites,
	History,
	Playlists,
	/// Songs with play statistics.
	Played,
}

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub enum Sort {
	/// The source's own order (newest like, latest play…).
	#[default]
	Natural,
	RecentlyPlayed,
	MostPlayed,
	LeastPlayed,
	Title,
	Artist,
	Shortest,
	Longest,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct Rule {
	pub source: Source,
	pub min_seconds: Option<u32>,
	pub max_seconds: Option<u32>,
	/// Case-insensitive "contains" on the artist / title.
	pub artist: Option<String>,
	pub title: Option<String>,
	pub min_plays: Option<u32>,
	pub max_plays: Option<u32>,
	/// Last played within this many days.
	pub played_within_days: Option<u32>,
	/// Not played for at least this many days (never-played songs count as not played).
	pub not_played_for_days: Option<u32>,
	pub exclude_live: bool,
	/// Leave out songs stored on the phone.
	pub exclude_device: bool,
	pub sort: Sort,
	/// 0 = default (100).
	pub limit: u32,
}

impl Rule {
	/// Bounds every value, so a saved rule from an old or edited file can't do anything silly.
	pub fn clamped(mut self) -> Rule {
		let clean = |s: Option<String>| s.map(|x| x.trim().chars().take(80).collect::<String>()).filter(|x| !x.is_empty());
		self.artist = clean(self.artist);
		self.title = clean(self.title);
		self.limit = if self.limit == 0 { 100 } else { self.limit.min(MAX_LIMIT) };
		self.played_within_days = self.played_within_days.map(|d| d.min(3650));
		self.not_played_for_days = self.not_played_for_days.map(|d| d.min(3650));
		self
	}
}

/// A track in the pool, with what rules look at.
struct Candidate {
	track: Track,
	plays: u32,
	last_played: Option<u64>,
	/// Position in the source's own order (lower = first).
	order: usize,
}

fn pool(lib: &Library, source: Source) -> Vec<Candidate> {
	let stats: HashMap<&str, (u32, u64)> = lib.stats.iter().map(|s| (s.track.id.as_str(), (s.count, s.last_played))).collect();
	let heard_at: HashMap<&str, u64> = lib.history.iter().map(|h| (h.track.id.as_str(), h.played_at)).collect();
	let favorites = || lib.favorites.iter();
	let history = || lib.history.iter().map(|h| &h.track);
	let playlists = || lib.playlists.iter().flat_map(|p| p.tracks.iter());
	let tracks: Vec<&Track> = match source {
		Source::Favorites => favorites().collect(),
		Source::History => history().collect(),
		Source::Playlists => playlists().collect(),
		Source::Played => {
			let mut by_recent: Vec<_> = lib.stats.iter().collect();
			by_recent.sort_by(|a, b| b.last_played.cmp(&a.last_played));
			by_recent.into_iter().map(|s| &s.track).collect()
		}
		Source::All => favorites().chain(history()).chain(playlists()).chain(lib.stats.iter().map(|s| &s.track)).collect(),
	};
	let mut seen = HashSet::new();
	tracks
		.into_iter()
		.filter(|t| seen.insert(t.id.as_str()))
		.enumerate()
		.map(|(order, t)| {
			let stat = stats.get(t.id.as_str()).copied();
			let heard = heard_at.get(t.id.as_str()).copied();
			// Songs played before statistics existed are in the history: count them once.
			let plays = stat.map(|(c, _)| c).unwrap_or(if heard.is_some() { 1 } else { 0 });
			let last_played = match (stat.map(|(_, l)| l), heard) {
				(Some(a), Some(b)) => Some(a.max(b)),
				(a, b) => a.or(b),
			};
			Candidate { track: t.clone(), plays, last_played, order }
		})
		.collect()
}

fn contains(hay: &str, needle: &Option<String>) -> bool {
	needle.as_ref().is_none_or(|n| hay.to_lowercase().contains(&n.to_lowercase()))
}

/// Runs a rule over the library. `now` is Unix ms.
pub fn evaluate(lib: &Library, rule: &Rule, now: u64) -> Vec<Track> {
	let rule = rule.clone().clamped();
	let mut c: Vec<Candidate> = pool(lib, rule.source)
		.into_iter()
		.filter(|c| {
			let secs = c.track.duration_seconds;
			(rule.min_seconds.is_none() || secs.is_some_and(|s| s >= rule.min_seconds.unwrap_or(0)))
				&& (rule.max_seconds.is_none() || secs.is_some_and(|s| s <= rule.max_seconds.unwrap_or(u32::MAX)))
				&& contains(&c.track.artist, &rule.artist)
				&& contains(&c.track.title, &rule.title)
				&& rule.min_plays.is_none_or(|m| c.plays >= m)
				&& rule.max_plays.is_none_or(|m| c.plays <= m)
				&& rule.played_within_days.is_none_or(|d| c.last_played.is_some_and(|l| now.saturating_sub(l) <= d as u64 * DAY_MS))
				&& rule.not_played_for_days.is_none_or(|d| c.last_played.is_none_or(|l| now.saturating_sub(l) >= d as u64 * DAY_MS))
				&& !(rule.exclude_live && c.track.live)
				&& !(rule.exclude_device && c.track.id.starts_with("local:"))
		})
		.collect();
	match rule.sort {
		Sort::Natural => c.sort_by_key(|c| c.order),
		Sort::RecentlyPlayed => c.sort_by(|a, b| b.last_played.cmp(&a.last_played).then(a.order.cmp(&b.order))),
		Sort::MostPlayed => c.sort_by(|a, b| b.plays.cmp(&a.plays).then(b.last_played.cmp(&a.last_played))),
		Sort::LeastPlayed => c.sort_by(|a, b| a.plays.cmp(&b.plays).then(a.order.cmp(&b.order))),
		Sort::Title => c.sort_by_key(|c| c.track.title.to_lowercase()),
		Sort::Artist => c.sort_by(|a, b| a.track.artist.to_lowercase().cmp(&b.track.artist.to_lowercase()).then(a.track.title.to_lowercase().cmp(&b.track.title.to_lowercase()))),
		Sort::Shortest => c.sort_by_key(|c| c.track.duration_seconds.unwrap_or(u32::MAX)),
		Sort::Longest => c.sort_by(|a, b| b.track.duration_seconds.unwrap_or(0).cmp(&a.track.duration_seconds.unwrap_or(0))),
	}
	c.into_iter().take(rule.limit as usize).map(|c| c.track).collect()
}

/// The smart playlists every library has.
pub fn built_ins() -> Vec<(&'static str, &'static str, &'static str, Rule)> {
	vec![
		("most-played", "Most played", "Your top songs by plays", Rule { source: Source::All, min_plays: Some(2), sort: Sort::MostPlayed, limit: 50, ..Default::default() }),
		("recently-liked", "Recently liked", "The songs you liked last", Rule { source: Source::Favorites, limit: 50, ..Default::default() }),
		("on-repeat", "On repeat", "Played 3+ times in the last 30 days", Rule { source: Source::Played, min_plays: Some(3), played_within_days: Some(30), sort: Sort::MostPlayed, limit: 50, ..Default::default() }),
		("never-played", "Never played", "Saved but not played yet", Rule { source: Source::All, max_plays: Some(0), limit: 100, ..Default::default() }),
		("forgotten", "Forgotten favourites", "Liked songs you haven't played in 60 days", Rule { source: Source::Favorites, min_plays: Some(1), not_played_for_days: Some(60), sort: Sort::MostPlayed, limit: 50, ..Default::default() }),
		("short", "Quick ones", "Songs under 3 minutes", Rule { source: Source::All, max_seconds: Some(180), exclude_live: true, limit: 100, ..Default::default() }),
		("long", "Long mixes", "20 minutes or more", Rule { source: Source::All, min_seconds: Some(1200), limit: 100, ..Default::default() }),
	]
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct SmartSummary {
	pub id: String,
	pub name: String,
	pub description: String,
	pub built_in: bool,
	pub count: usize,
	/// Up to four covers, for a collage.
	pub art: Vec<String>,
	pub rule: Rule,
}

fn art(tracks: &[Track]) -> Vec<String> {
	let mut seen = HashSet::new();
	tracks
		.iter()
		.filter(|t| !t.id.starts_with("local:"))
		.filter(|t| seen.insert(t.artist.to_lowercase()))
		.take(4)
		.map(|t| t.thumbnail.clone().unwrap_or_else(|| format!("https://i.ytimg.com/vi/{}/mqdefault.jpg", t.id)))
		.collect()
}

/// Every smart playlist with its current size. Empty built-ins are left out; the user's own stay.
pub fn list(lib: &Library, now: u64) -> Vec<SmartSummary> {
	let mut out = Vec::new();
	for s in &lib.smart {
		let tracks = evaluate(lib, &s.rule, now);
		out.push(SmartSummary { id: s.id.clone(), name: s.name.clone(), description: describe(&s.rule), built_in: false, count: tracks.len(), art: art(&tracks), rule: s.rule.clone() });
	}
	for (id, name, description, rule) in built_ins() {
		let tracks = evaluate(lib, &rule, now);
		if !tracks.is_empty() {
			out.push(SmartSummary { id: id.into(), name: name.into(), description: description.into(), built_in: true, count: tracks.len(), art: art(&tracks), rule });
		}
	}
	out
}

pub fn rule_for(lib: &Library, id: &str) -> Option<Rule> {
	lib.smart.iter().find(|s| s.id == id).map(|s| s.rule.clone()).or_else(|| built_ins().into_iter().find(|(b, ..)| *b == id).map(|(.., r)| r))
}

/// A rule in words, for the user's own smart playlists ("Liked songs · under 3:00 · played 2+ times").
pub fn describe(rule: &Rule) -> String {
	let mut parts = vec![match rule.source {
		Source::All => "Everything",
		Source::Favorites => "Liked songs",
		Source::History => "History",
		Source::Playlists => "Your playlists",
		Source::Played => "Played songs",
	}
	.to_string()];
	let mmss = |s: u32| format!("{}:{:02}", s / 60, s % 60);
	if let Some(s) = rule.min_seconds {
		parts.push(format!("{} or longer", mmss(s)));
	}
	if let Some(s) = rule.max_seconds {
		parts.push(format!("up to {}", mmss(s)));
	}
	if let Some(a) = &rule.artist {
		parts.push(format!("artist has “{a}”"));
	}
	if let Some(t) = &rule.title {
		parts.push(format!("title has “{t}”"));
	}
	match (rule.min_plays, rule.max_plays) {
		(_, Some(0)) => parts.push("never played".into()),
		(Some(n), _) if n > 0 => parts.push(format!("played {n}+ times")),
		_ => {}
	}
	if let Some(d) = rule.played_within_days {
		parts.push(format!("played in the last {d} days"));
	}
	if let Some(d) = rule.not_played_for_days {
		parts.push(format!("not played for {d} days"));
	}
	parts.join(" · ")
}

// ---------------------------------------------------------------------------------------------
// Insights
// ---------------------------------------------------------------------------------------------

#[derive(Serialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Count {
	pub name: String,
	pub plays: u32,
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct TopTrack {
	pub track: Track,
	pub plays: u32,
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Insights {
	pub total_plays: u64,
	pub distinct_tracks: usize,
	pub distinct_artists: usize,
	pub minutes: u64,
	pub top_artists: Vec<Count>,
	pub top_tracks: Vec<TopTrack>,
	pub hours: [u32; 24],
	/// The hour with the most plays, when there are any.
	pub peak_hour: Option<u32>,
	/// "Night owl", "Morning person"… from when you listen.
	pub listener: Option<String>,
	pub current_streak: u32,
	pub longest_streak: u32,
	pub days_active: usize,
	pub since: Option<u64>,
}

/// Consecutive days ending today (or yesterday, so a streak doesn't vanish before you've listened
/// today), and the longest run ever. `days` is ascending and unique.
pub fn streaks(days: &[u32], today: u32) -> (u32, u32) {
	let mut longest = 0;
	let mut run = 0;
	let mut prev: Option<u32> = None;
	for &d in days {
		run = if prev.is_some_and(|p| p + 1 == d) { run + 1 } else { 1 };
		longest = longest.max(run);
		prev = Some(d);
	}
	let current = match days.last() {
		Some(&last) if last == today || last + 1 == today => run,
		_ => 0,
	};
	(current, longest)
}

pub fn insights(lib: &Library, today: u32) -> Insights {
	let mut by_artist: HashMap<String, (String, u32)> = HashMap::new();
	for s in &lib.stats {
		let e = by_artist.entry(s.track.artist.to_lowercase()).or_insert_with(|| (s.track.artist.clone(), 0));
		e.1 += s.count;
	}
	let mut top_artists: Vec<Count> = by_artist.into_values().filter(|(n, _)| !n.is_empty()).map(|(name, plays)| Count { name, plays }).collect();
	top_artists.sort_by(|a, b| b.plays.cmp(&a.plays).then(a.name.cmp(&b.name)));
	let distinct_artists = top_artists.len();
	top_artists.truncate(5);
	let mut ranked: Vec<_> = lib.stats.iter().collect();
	ranked.sort_by(|a, b| b.count.cmp(&a.count).then(b.last_played.cmp(&a.last_played)));
	let top_tracks = ranked.iter().take(5).map(|s| TopTrack { track: s.track.clone(), plays: s.count }).collect();
	let h = &lib.listening.hours;
	let peak_hour = (0..24u32).filter(|&i| h[i as usize] > 0).max_by_key(|&i| (h[i as usize], std::cmp::Reverse(i)));
	let sum = |r: std::ops::Range<usize>| r.map(|i| h[i] as u64).sum::<u64>();
	let parts = [("Night owl", sum(0..5) + h[22] as u64 + h[23] as u64), ("Early bird", sum(5..10)), ("Daytime listener", sum(10..17)), ("Evening listener", sum(17..22))];
	let total_hours: u64 = h.iter().map(|&x| x as u64).sum();
	let listener = parts.iter().max_by_key(|(_, n)| *n).filter(|_| total_hours >= 10).map(|(name, _)| name.to_string());
	let (current_streak, longest_streak) = streaks(&lib.listening.days, today);
	Insights {
		total_plays: lib.stats.iter().map(|s| s.count as u64).sum(),
		distinct_tracks: lib.stats.len(),
		distinct_artists,
		minutes: lib.listening.seconds / 60,
		top_artists,
		top_tracks,
		hours: *h,
		peak_hour,
		listener,
		current_streak,
		longest_streak,
		days_active: lib.listening.days.len(),
		since: lib.stats.iter().map(|s| s.first_played).min(),
	}
}

#[cfg(test)]
mod tests {
	use super::*;
	use serde_json::json;

	fn t(id: &str, artist: &str, secs: u32) -> Track {
		serde_json::from_value(json!({ "id": id, "title": format!("Song {id}"), "artist": artist, "durationSeconds": secs })).unwrap()
	}

	fn lib() -> Library {
		let mut l = Library::default();
		l.toggle_favorite(t("fav-long", "A", 1500));
		l.toggle_favorite(t("fav-short", "B", 120));
		l.toggle_favorite(t("fav-old", "A", 200));
		l.create_playlist("P", vec![t("pl-only", "C", 240), t("fav-short", "B", 120)]);
		let now = 100 * DAY_MS;
		for _ in 0..5 {
			l.record_play(&t("fav-short", "B", 120), now - DAY_MS, Some(23), Some(99));
		}
		l.record_play(&t("fav-old", "A", 200), now - 90 * DAY_MS, Some(8), Some(10));
		l.record_play(&t("fav-old", "A", 200), now - 90 * DAY_MS, Some(8), Some(10));
		l
	}

	fn ids(v: &[Track]) -> Vec<&str> {
		v.iter().map(|t| t.id.as_str()).collect()
	}

	#[test]
	fn built_in_rules() {
		let l = lib();
		let now = 100 * DAY_MS;
		let run = |id: &str| evaluate(&l, &rule_for(&l, id).unwrap(), now);
		assert_eq!(ids(&run("most-played")), ["fav-short", "fav-old"]);
		assert_eq!(ids(&run("recently-liked")), ["fav-old", "fav-short", "fav-long"]);
		assert_eq!(ids(&run("never-played")), ["fav-long", "pl-only"]);
		assert_eq!(ids(&run("forgotten")), ["fav-old"], "played, but 90 days ago");
		assert_eq!(ids(&run("on-repeat")), ["fav-short"]);
		assert_eq!(ids(&run("short")), ["fav-short"]);
		assert_eq!(ids(&run("long")), ["fav-long"]);
		let listed = list(&l, now);
		assert!(listed.iter().all(|s| s.count > 0 && s.built_in));
		assert_eq!(listed.iter().find(|s| s.id == "most-played").unwrap().art.len(), 2, "one cover per artist");
	}

	#[test]
	fn user_rules_and_clamping() {
		let mut l = lib();
		let rule = Rule { artist: Some("  a ".into()), sort: Sort::Longest, limit: 9999, ..Default::default() };
		assert_eq!(ids(&evaluate(&l, &rule, 0)), ["fav-long", "fav-old"], "case-insensitive, trimmed");
		let saved = l.save_smart("  ", rule).unwrap();
		assert_eq!((saved.name.as_str(), saved.rule.limit, saved.rule.artist.as_deref()), ("Smart playlist", MAX_LIMIT, Some("a")));
		assert_eq!(list(&l, 0)[0].id, saved.id, "the user's own come first");
		assert!(describe(&saved.rule).contains("artist has “a”"));
		assert!(l.toggle_pin(&format!("smart:{}", saved.id)));
		assert!(l.delete_smart(&saved.id));
		assert!(l.pinned.is_empty(), "deleting unpins");
		let unknown_length = Rule { max_seconds: Some(10_000), ..Default::default() };
		let mut l2 = Library::default();
		l2.toggle_favorite(serde_json::from_value(json!({ "id": "x", "title": "x", "artist": "x" })).unwrap());
		assert!(evaluate(&l2, &unknown_length, 0).is_empty(), "duration rules skip songs of unknown length");
		let old: Rule = serde_json::from_str(r#"{"source":"favorites"}"#).unwrap();
		assert_eq!(old.source, Source::Favorites);
	}

	#[test]
	fn streaks_and_insights() {
		assert_eq!(streaks(&[], 10), (0, 0));
		assert_eq!(streaks(&[1, 2, 3, 7, 8], 9), (2, 3), "yesterday still counts");
		assert_eq!(streaks(&[1, 2, 3, 7, 8], 8), (2, 3));
		assert_eq!(streaks(&[1, 2, 3], 10), (0, 3), "broken streak");
		let mut l = lib();
		l.add_listened("fav-short", 600);
		l.add_listened("fav-short", 999_999);
		let i = insights(&l, 100);
		assert_eq!((i.total_plays, i.distinct_tracks, i.distinct_artists), (7, 2, 2));
		assert_eq!(i.minutes, (600 + 6 * 3600) / 60, "one report is capped at 6 hours");
		assert_eq!(i.top_artists[0], Count { name: "B".into(), plays: 5 });
		assert_eq!(i.top_tracks[0].track.id, "fav-short");
		assert_eq!(i.peak_hour, Some(23));
		assert_eq!(i.listener, None, "needs a few plays first");
		assert_eq!((i.current_streak, i.longest_streak, i.days_active), (1, 1, 2));
	}
}
