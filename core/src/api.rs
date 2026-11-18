//! Client for the BetterYTS API (https://yts.foulfoxhacks.dev/docs).

use std::time::Duration;

use serde::Serialize;
use serde_json::Value;

use crate::model::{self, Artist, Page, Track};

#[cfg(test)]
use crate::model::Item;

pub const DEFAULT_BASE: &str = "https://yts.foulfoxhacks.dev";

#[derive(Clone)]
pub struct Api {
	base: String,
	agent: ureq::Agent,
	pub region: String,
}

/// What a search should return. "songs" maps to videos, "artists" to channels.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Filter {
	All,
	Songs,
	Playlists,
	Artists,
}

impl Filter {
	pub fn parse(s: &str) -> Filter {
		match s {
			"songs" => Filter::Songs,
			"playlists" => Filter::Playlists,
			"artists" => Filter::Artists,
			_ => Filter::All,
		}
	}
	fn api_type(self) -> Option<&'static str> {
		match self {
			Filter::All => None,
			Filter::Songs => Some("video"),
			Filter::Playlists => Some("playlist"),
			Filter::Artists => Some("channel"),
		}
	}
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct CollectionPage {
	pub id: String,
	pub title: String,
	pub subtitle: String,
	pub thumbnail: Option<String>,
	pub tracks: Vec<Track>,
	pub next: Option<String>,
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct ArtistPage {
	pub artist: Artist,
	pub banner: Option<String>,
	pub description: Option<String>,
	pub tracks: Vec<Track>,
	pub next: Option<String>,
}

/// Percent-encodes a query value.
pub fn enc(s: &str) -> String {
	let mut o = String::with_capacity(s.len());
	for b in s.bytes() {
		match b {
			b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => o.push(b as char),
			_ => o.push_str(&format!("%{b:02X}")),
		}
	}
	o
}

/// YouTube only gives relative dates ("3 days ago"). Approximate age in seconds, for sorting.
pub fn age_seconds(text: &str) -> u64 {
	let lower = text.to_lowercase();
	let words: Vec<&str> = lower.split_whitespace().collect();
	for w in words.windows(3) {
		if w[2] != "ago" {
			continue;
		}
		let Ok(n) = w[0].parse::<u64>() else { continue };
		let unit = w[1].trim_end_matches('s');
		let secs = match unit {
			"second" => 1,
			"minute" => 60,
			"hour" => 3600,
			"day" => 86_400,
			"week" => 604_800,
			"month" => 2_629_800,
			"year" => 31_557_600,
			_ => continue,
		};
		return n * secs;
	}
	u64::MAX
}

impl Api {
	pub fn new(base: &str) -> Api {
		let agent = ureq::AgentBuilder::new()
			.timeout_connect(Duration::from_secs(10))
			.timeout(Duration::from_secs(25))
			.user_agent(concat!("BetterYTSMusic/", env!("CARGO_PKG_VERSION"), " (Android)"))
			.build();
		Api { base: base.trim_end_matches('/').to_string(), agent, region: String::new() }
	}

	pub fn base(&self) -> &str {
		&self.base
	}

	pub fn url(&self, path: &str, query: &[(&str, &str)]) -> String {
		let mut url = format!("{}{}", self.base, path);
		let mut first = true;
		let region = [("gl", self.region.as_str())];
		for (k, v) in query.iter().chain(region.iter().filter(|(_, v)| !v.is_empty())) {
			if v.is_empty() {
				continue;
			}
			url.push(if first { '?' } else { '&' });
			first = false;
			url.push_str(k);
			url.push('=');
			url.push_str(&enc(v));
		}
		url
	}

	/// Raw JSON from a BetterYTS endpoint (used by plugins).
	pub fn get_json(&self, path: &str, query: &[(&str, &str)]) -> Result<Value, String> {
		self.get(path, query)
	}

	fn get(&self, path: &str, query: &[(&str, &str)]) -> Result<Value, String> {
		let url = self.url(path, query);
		match self.agent.get(&url).call() {
			Ok(res) => {
				let text = res.into_string().map_err(|e| format!("Couldn't read the response: {e}"))?;
				serde_json::from_str(&text).map_err(|_| "BetterYTS sent something unexpected. Try again in a moment.".to_string())
			}
			Err(ureq::Error::Status(code, res)) => {
				// The Worker answers {"error":{"status":…,"message":"…"}}.
				let msg = res.into_string().ok().and_then(|t| serde_json::from_str::<Value>(&t).ok()).and_then(|v| crate::google::error_message(&v));
				Err(msg.unwrap_or_else(|| format!("BetterYTS returned HTTP {code}")))
			}
			Err(ureq::Error::Transport(t)) => Err(format!("Can't reach BetterYTS. Check your connection. ({})", t.kind())),
		}
	}

	pub fn search(&self, query: &str, filter: Filter, page: Option<&str>) -> Result<Page, String> {
		let mut q = vec![("q", query)];
		if let Some(t) = filter.api_type() {
			q.push(("type", t));
		}
		if let Some(p) = page {
			q.push(("page", p));
		}
		let v = self.get("/api/search", &q)?;
		Ok(Page { items: results(&v).iter().filter_map(model::item_from).collect(), next: model::s(&v, "next") })
	}

	pub fn suggest(&self, query: &str) -> Result<Vec<String>, String> {
		if query.trim().is_empty() {
			return Ok(Vec::new());
		}
		let v = self.get("/api/suggest", &[("q", query)])?;
		Ok(v.as_array().map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).take(10).collect()).unwrap_or_default())
	}

	pub fn video(&self, id: &str) -> Result<Track, String> {
		let v = self.get(&format!("/api/video/{}", enc(id)), &[])?;
		let author = model::s(&v, "author").unwrap_or_default();
		let raw = model::s(&v, "title").unwrap_or_else(|| id.to_string());
		let (title, artist) = crate::music::split_title(&raw, &author);
		let secs = v.get("lengthSeconds").and_then(|x| x.as_u64().or_else(|| x.as_str().and_then(|s| s.parse().ok())));
		Ok(Track {
			id: id.to_string(),
			title,
			artist,
			artist_id: model::s(&v, "channelId"),
			thumbnail: model::s(&v, "thumbnail").filter(|u| u.starts_with("https://")),
			duration: secs.filter(|s| *s > 0).map(fmt_duration),
			duration_seconds: secs.map(|s| s as u32).filter(|s| *s > 0),
			live: v.get("isLive").and_then(Value::as_bool).unwrap_or(false),
		})
	}

	pub fn playlist(&self, id: &str, page: Option<&str>) -> Result<CollectionPage, String> {
		let v = self.get(&format!("/api/playlist/{}", enc(id)), &page.map(|p| vec![("page", p)]).unwrap_or_default())?;
		let tracks = results(&v).iter().filter_map(|x| model::track_from(x, None)).collect();
		let owner = model::s(&v, "owner").map(|o| crate::music::clean_artist(&o));
		let subtitle = [owner, model::s(&v, "videoCount")].into_iter().flatten().collect::<Vec<_>>().join(" · ");
		Ok(CollectionPage {
			id: id.to_string(),
			title: model::s(&v, "title").unwrap_or_else(|| "Playlist".into()),
			subtitle,
			thumbnail: model::s(&v, "thumbnail"),
			tracks,
			next: model::s(&v, "next"),
		})
	}

	pub fn artist(&self, reference: &str, page: Option<&str>) -> Result<ArtistPage, String> {
		let mut q = vec![("tab", "videos")];
		if let Some(p) = page {
			q.push(("page", p));
		}
		let v = self.get(&format!("/api/channel/{}", enc(reference)), &q)?;
		let id = model::s(&v, "id").unwrap_or_else(|| reference.to_string());
		let name = crate::music::clean_artist(&model::s(&v, "title").unwrap_or_else(|| reference.to_string()));
		let tracks = results(&v).iter().filter_map(|x| model::track_from(x, Some((&name, &id)))).collect();
		let subtitle = [model::s(&v, "subscribers"), model::s(&v, "videoCount")].into_iter().flatten().collect::<Vec<_>>().join(" · ");
		Ok(ArtistPage {
			artist: Artist { id, name, handle: model::s(&v, "handle"), thumbnail: model::s(&v, "avatar"), subtitle: Some(subtitle).filter(|s| !s.is_empty()) },
			banner: model::s(&v, "banner"),
			description: model::s(&v, "description"),
			tracks,
			next: model::s(&v, "next"),
		})
	}

	/// Latest uploads from followed artists, newest first. Fetches 4 channels at a time; channels
	/// that fail are skipped. Returns the tracks and how many channels failed.
	pub fn feed(&self, artists: &[Artist], limit: usize) -> (Vec<Track>, usize) {
		let mut dated: Vec<(u64, usize, Track)> = Vec::new();
		let mut failed = 0;
		for chunk in artists.chunks(4) {
			let pages: Vec<Option<Value>> = std::thread::scope(|scope| {
				let handles: Vec<_> = chunk.iter().map(|a| scope.spawn(move || self.get(&format!("/api/channel/{}", enc(&a.id)), &[("tab", "videos")]).ok())).collect();
				handles.into_iter().map(|h| h.join().ok().flatten()).collect()
			});
			for (artist, page) in chunk.iter().zip(pages) {
				let Some(page) = page else {
					failed += 1;
					continue;
				};
				for (i, x) in results(&page).iter().enumerate().take(15) {
					if let Some(t) = model::track_from(x, Some((&artist.name, &artist.id))) {
						dated.push((model::s(x, "published").map(|p| age_seconds(&p)).unwrap_or(u64::MAX), i, t));
					}
				}
			}
		}
		dated.sort_by(|a, b| a.0.cmp(&b.0).then(a.1.cmp(&b.1)));
		let mut seen = std::collections::HashSet::new();
		let tracks = dated.into_iter().map(|(_, _, t)| t).filter(|t| seen.insert(t.id.clone())).take(limit).collect();
		(tracks, failed)
	}
}

fn results(v: &Value) -> Vec<Value> {
	v.get("results").and_then(Value::as_array).cloned().unwrap_or_default()
}

pub fn fmt_duration(secs: u64) -> String {
	let (h, m, s) = (secs / 3600, (secs % 3600) / 60, secs % 60);
	if h > 0 {
		format!("{h}:{m:02}:{s:02}")
	} else {
		format!("{m}:{s:02}")
	}
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn builds_urls_with_encoding_and_region() {
		let mut api = Api::new("https://yts.foulfoxhacks.dev/");
		assert_eq!(api.url("/api/search", &[("q", "lofi hip hop & chill/ü"), ("page", "")]), "https://yts.foulfoxhacks.dev/api/search?q=lofi%20hip%20hop%20%26%20chill%2F%C3%BC");
		api.region = "GB".into();
		assert_eq!(api.url("/api/suggest", &[("q", "a")]), "https://yts.foulfoxhacks.dev/api/suggest?q=a&gl=GB");
		assert_eq!(api.url("/api/health", &[]), "https://yts.foulfoxhacks.dev/api/health?gl=GB");
	}

	#[test]
	fn relative_ages_and_durations() {
		assert_eq!(age_seconds("3 days ago"), 3 * 86_400);
		assert_eq!(age_seconds("Streamed 2 weeks ago"), 2 * 604_800);
		assert_eq!(age_seconds("1 year ago"), 31_557_600);
		assert_eq!(age_seconds(""), u64::MAX);
		assert!(age_seconds("5 hours ago") < age_seconds("1 day ago"));
		assert_eq!(fmt_duration(214), "3:34");
		assert_eq!(fmt_duration(3725), "1:02:05");
	}

	#[test]
	fn filters() {
		assert_eq!(Filter::parse("songs").api_type(), Some("video"));
		assert_eq!(Filter::parse("artists").api_type(), Some("channel"));
		assert_eq!(Filter::parse("anything").api_type(), None);
	}

	/// Hits the live API. Run with `cargo test -- --ignored`.
	#[test]
	#[ignore]
	fn live_api() {
		let api = Api::new(DEFAULT_BASE);
		let page = api.search("lofi hip hop", Filter::Songs, None).unwrap();
		assert!(page.items.iter().any(|i| matches!(i, Item::Track(_))), "songs search returns tracks");
		assert!(!api.suggest("lofi").unwrap().is_empty());
		let a = api.artist("@LofiGirl", None).unwrap();
		assert_eq!(a.artist.name, "Lofi Girl");
		assert!(!a.tracks.is_empty());
		assert!(a.tracks.iter().all(|t| t.artist_id.as_deref() == Some(a.artist.id.as_str())), "channel tracks link back to the channel");
		let credited = a.tracks.iter().filter(|t| t.artist == "Lofi Girl").count();
		assert!(credited * 2 >= a.tracks.len(), "most uploads credit the channel, got {credited}/{}", a.tracks.len());
		let v = api.video("jfKfPfyJRdk").unwrap();
		assert!(!v.title.is_empty());
		let (feed, failed) = api.feed(&[a.artist.clone()], 10);
		assert_eq!(failed, 0);
		assert!(!feed.is_empty());
	}
}
