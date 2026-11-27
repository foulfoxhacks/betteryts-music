//! Smart recommendations: genre, tempo and key similarity, plus your own taste.
//!
//! Genres come from text (titles, channel names, file tags) through a keyword table; tempo and key
//! come from [`crate::analysis`] for songs we can analyse. Everything runs on the device; the
//! scores only reorder what a station or the library already offered, they never fetch anything.

use std::collections::{HashMap, HashSet};

use serde::Serialize;

use crate::analysis::Features;
use crate::library::Library;
use crate::model::Track;

/// (genre, keywords). Matched on whole words of the lower-cased title + artist.
const GENRES: &[(&str, &[&str])] = &[
	("lofi", &["lofi", "lo-fi", "chillhop", "study beats"]),
	("phonk", &["phonk", "drift phonk"]),
	("house", &["house", "deep house", "tech house"]),
	("techno", &["techno"]),
	("trance", &["trance", "psytrance"]),
	("dnb", &["dnb", "drum and bass", "drum & bass", "jungle", "liquid"]),
	("dubstep", &["dubstep", "riddim", "brostep"]),
	("edm", &["edm", "electro", "big room", "future bass", "hardstyle"]),
	("synthwave", &["synthwave", "retrowave", "outrun", "darksynth", "vaporwave"]),
	("hip hop", &["hip hop", "hip-hop", "rap", "trap", "drill", "boom bap", "freestyle"]),
	("r&b", &["r&b", "rnb", "soul", "neo soul"]),
	("pop", &["pop", "dance pop", "synthpop"]),
	("k-pop", &["kpop", "k-pop"]),
	("j-pop", &["jpop", "j-pop", "anime", "opening", "city pop", "vocaloid", "hatsune miku"]),
	("rock", &["rock", "alt rock", "grunge", "punk", "pop punk"]),
	("metal", &["metal", "metalcore", "deathcore", "djent"]),
	("indie", &["indie", "bedroom pop", "shoegaze", "dream pop"]),
	("jazz", &["jazz", "bossa nova", "swing", "smooth jazz"]),
	("classical", &["classical", "orchestra", "symphony", "sonata", "concerto", "piano solo", "chopin", "beethoven", "mozart", "bach"]),
	("ambient", &["ambient", "sleep music", "meditation", "drone", "relaxing"]),
	("soundtrack", &["ost", "soundtrack", "score", "theme", "main theme"]),
	("game", &["game music", "vgm", "8-bit", "chiptune", "minecraft", "undertale", "zelda"]),
	("latin", &["reggaeton", "latin", "bachata", "salsa", "cumbia", "dembow"]),
	("country", &["country", "bluegrass", "americana"]),
	("acoustic", &["acoustic", "unplugged", "folk", "singer-songwriter"]),
	("afrobeats", &["afrobeats", "afrobeat", "amapiano"]),
	("reggae", &["reggae", "dancehall", "dub"]),
];

fn words(s: &str) -> String {
	format!(" {} ", s.to_lowercase().chars().map(|c| if c.is_alphanumeric() || c == '&' || c == '-' { c } else { ' ' }).collect::<String>().split_whitespace().collect::<Vec<_>>().join(" "))
}

/// Genres a song's text suggests (title, artist, any extra tags such as a file's genre field).
pub fn genres(title: &str, artist: &str, tags: &[String]) -> Vec<&'static str> {
	let text = words(&format!("{title} {artist} {}", tags.join(" ")));
	GENRES.iter().filter(|(_, keys)| keys.iter().any(|k| text.contains(&format!(" {k} ")))).map(|(g, _)| *g).collect()
}

/// What you listen to: genre and artist weights from likes, plays and history.
#[derive(Default, Debug)]
pub struct Taste {
	pub genres: HashMap<&'static str, f32>,
	pub artists: HashMap<String, f32>,
}

pub fn taste(lib: &Library) -> Taste {
	let mut t = Taste::default();
	let mut add = |tr: &Track, w: f32| {
		for g in genres(&tr.title, &tr.artist, &[]) {
			*t.genres.entry(g).or_default() += w;
		}
		*t.artists.entry(crate::plugins::compact(&tr.artist)).or_default() += w;
	};
	lib.favorites.iter().take(500).for_each(|x| add(x, 3.0));
	lib.stats.iter().for_each(|s| add(&s.track, (s.count as f32).sqrt()));
	lib.history.iter().take(200).for_each(|h| add(&h.track, 1.0));
	let total: f32 = t.genres.values().sum::<f32>().max(1.0);
	t.genres.values_mut().for_each(|v| *v /= total);
	let total: f32 = t.artists.values().sum::<f32>().max(1.0);
	t.artists.values_mut().for_each(|v| *v /= total);
	t
}

/// 1.0 for the same tempo, also counting half/double time as close; 0 beyond ~15% apart.
pub fn tempo_similarity(a: f32, b: f32) -> f32 {
	let r = [b, b * 2.0, b / 2.0].iter().map(|x| (a / x).ln().abs()).fold(f32::MAX, f32::min);
	(1.0 - r / 0.15).max(0.0)
}

#[derive(Serialize, Debug, Clone, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Why {
	pub score: f32,
	/// Short reasons ("same genre: house", "124 BPM like the seed", "mixes with 8A").
	pub reasons: Vec<String>,
}

/// How well `cand` follows `seed`, with reasons. Weights: genre 40 %, tempo 20 %, key 15 %,
/// taste 25 % (your genres and artists).
pub fn score(seed: &Track, seed_f: Option<&Features>, cand: &Track, cand_f: Option<&Features>, taste: &Taste) -> Why {
	let mut reasons = Vec::new();
	let sg: HashSet<&str> = genres(&seed.title, &seed.artist, &[]).into_iter().collect();
	let cg: HashSet<&str> = genres(&cand.title, &cand.artist, &[]).into_iter().collect();
	let genre = if sg.is_empty() || cg.is_empty() {
		0.5 // unknown: neutral
	} else {
		let shared: Vec<&&str> = sg.intersection(&cg).collect();
		if let Some(g) = shared.first() {
			reasons.push(format!("same genre: {g}"));
		}
		shared.len() as f32 / sg.union(&cg).count() as f32
	};
	let (tempo, key) = match (seed_f, cand_f) {
		(Some(a), Some(b)) => {
			let t = match (a.bpm, b.bpm) {
				(Some(x), Some(y)) => {
					let s = tempo_similarity(x, y);
					if s > 0.6 {
						reasons.push(format!("{y:.0} BPM, close to {x:.0}"));
					}
					s
				}
				_ => 0.5,
			};
			let k = match (a.key, b.key) {
				(Some(x), Some(y)) if a.key_confidence > 0.2 && b.key_confidence > 0.2 => {
					let c = x.compatibility(&y);
					if c > 0.0 {
						let (n, l) = y.camelot();
						reasons.push(format!("in a matching key ({n}{l})"));
					}
					c
				}
				_ => 0.5,
			};
			(t, k)
		}
		_ => (0.5, 0.5),
	};
	let taste_g: f32 = cg.iter().map(|g| taste.genres.get(g).copied().unwrap_or(0.0)).sum::<f32>().min(1.0);
	let taste_a = taste.artists.get(&crate::plugins::compact(&cand.artist)).copied().unwrap_or(0.0);
	let mine = (taste_g * 2.0 + taste_a * 10.0).min(1.0);
	if taste_a > 0.0 {
		reasons.push("an artist you play".into());
	}
	Why { score: 0.40 * genre + 0.20 * tempo + 0.15 * key + 0.25 * mine, reasons }
}

/// Reorders candidates by score, keeping the original order among near-ties so YouTube's own
/// ranking still counts. `features` holds analysed songs by ID.
pub fn rerank(seed: &Track, cands: Vec<Track>, features: &HashMap<String, Features>, taste: &Taste) -> Vec<(Track, Why)> {
	let sf = features.get(&seed.id);
	let n = cands.len().max(1) as f32;
	let mut scored: Vec<(f32, Track, Why)> = cands
		.into_iter()
		.enumerate()
		.map(|(i, t)| {
			let w = score(seed, sf, &t, features.get(&t.id), taste);
			// Up to 0.1 bonus for being early in the source's order.
			(w.score + 0.1 * (1.0 - i as f32 / n), t, w)
		})
		.collect();
	scored.sort_by(|a, b| b.0.total_cmp(&a.0));
	scored.into_iter().map(|(_, t, w)| (t, w)).collect()
}

#[cfg(test)]
mod tests {
	use super::*;
	use crate::analysis::Key;
	use serde_json::json;

	fn t(id: &str, title: &str, artist: &str) -> Track {
		serde_json::from_value(json!({ "id": id, "title": title, "artist": artist })).unwrap()
	}

	#[test]
	fn genre_keywords() {
		assert_eq!(genres("Snowfall (lofi hip hop)", "Øneheart", &[]), vec!["lofi", "hip hop"]);
		assert_eq!(genres("Nightcall", "Kavinsky", &["Synthwave".into()]), vec!["synthwave"]);
		assert!(genres("Trapped in a box", "Someone", &[]).is_empty(), "whole words only (trap ≠ trapped)");
		assert!(genres("Drop", "DJ Rocky", &[]).is_empty(), "rock ≠ rocky");
	}

	#[test]
	fn tempo_counts_half_and_double_time() {
		assert_eq!(tempo_similarity(120.0, 120.0), 1.0);
		assert!(tempo_similarity(120.0, 60.0) > 0.99);
		assert!(tempo_similarity(120.0, 126.0) > 0.6);
		assert_eq!(tempo_similarity(120.0, 90.0), 0.0);
	}

	#[test]
	fn rerank_prefers_genre_tempo_key_and_taste() {
		let seed = t("s", "Deep house mix", "A");
		let cands = vec![t("1", "Metal anthem", "B"), t("2", "Sunset deep house", "C"), t("3", "House groove", "D")];
		let f = |bpm, tonic, minor| Features { bpm: Some(bpm), key: Some(Key { tonic, minor }), key_confidence: 0.9, energy: 0.5 };
		let mut feats = HashMap::new();
		feats.insert("s".to_string(), f(124.0, 9, true));
		feats.insert("2".to_string(), f(95.0, 6, false));
		feats.insert("3".to_string(), f(125.0, 0, false));
		let ranked = rerank(&seed, cands.clone(), &feats, &Taste::default());
		let ids: Vec<&str> = ranked.iter().map(|(t, _)| t.id.as_str()).collect();
		assert_eq!(ids, ["3", "2", "1"], "same genre, close tempo and a mixable key wins");
		assert!(ranked[0].1.reasons.iter().any(|r| r.contains("BPM")));
		assert!(ranked[0].1.reasons.iter().any(|r| r.contains("8B")), "C major is 8B, next to A minor 8A");

		let mut lib = Library::default();
		lib.toggle_favorite(t("x", "Heavy metal song", "B"));
		let ranked = rerank(&t("s", "Song", "Z"), cands, &HashMap::new(), &taste(&lib));
		assert_eq!(ranked[0].0.id, "1", "with no genre for the seed, your taste decides");
	}
}
