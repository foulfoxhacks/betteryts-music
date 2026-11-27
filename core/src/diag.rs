//! Diagnostics: the last requests the core made, for Settings › Diagnostics.
//!
//! Records hold only what helps explain a failure: the route *template* (IDs replaced by `{id}`),
//! status, timing, cache use, retries and the error code. Never query strings (searches), tokens,
//! room keys, activation codes or response bodies, so a copied report is safe to share.

use std::collections::VecDeque;
use std::sync::Mutex;

use serde::Serialize;

pub const MAX_RECORDS: usize = 60;

#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct RequestRecord {
	/// Unix ms.
	pub at: u64,
	pub method: &'static str,
	pub route: String,
	/// HTTP status; 0 when the request never got an answer.
	pub status: u16,
	pub ms: u64,
	pub cached: bool,
	pub retries: u32,
	#[serde(skip_serializing_if = "Option::is_none")]
	pub error: Option<String>,
	#[serde(skip_serializing_if = "Option::is_none")]
	pub request_id: Option<String>,
}

static LOG: Mutex<VecDeque<RequestRecord>> = Mutex::new(VecDeque::new());

pub fn record(r: RequestRecord) {
	let mut log = LOG.lock().unwrap_or_else(|p| p.into_inner());
	if log.len() >= MAX_RECORDS {
		log.pop_front();
	}
	log.push_back(r);
}

/// Newest first.
pub fn recent() -> Vec<RequestRecord> {
	LOG.lock().unwrap_or_else(|p| p.into_inner()).iter().rev().cloned().collect()
}

pub fn clear() {
	LOG.lock().unwrap_or_else(|p| p.into_inner()).clear();
}

#[derive(Serialize, Debug, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct Summary {
	pub requests: usize,
	pub failures: usize,
	pub cache_hits: usize,
	pub retries: u32,
	/// Median and slowest time of requests that went to the network.
	pub median_ms: u64,
	pub max_ms: u64,
	#[serde(skip_serializing_if = "Option::is_none")]
	pub last_error: Option<String>,
}

pub fn summarize(records: &[RequestRecord]) -> Summary {
	let mut timed: Vec<u64> = records.iter().filter(|r| !r.cached).map(|r| r.ms).collect();
	timed.sort_unstable();
	Summary {
		requests: records.len(),
		failures: records.iter().filter(|r| r.error.is_some()).count(),
		cache_hits: records.iter().filter(|r| r.cached).count(),
		retries: records.iter().map(|r| r.retries).sum(),
		median_ms: timed.get(timed.len() / 2).copied().unwrap_or(0),
		max_ms: timed.last().copied().unwrap_or(0),
		last_error: records.iter().find_map(|r| r.error.as_ref().map(|e| format!("{} {} → {e}", r.method, r.route))),
	}
}

/// `/api/video/dQw4w9WgXcQ?gl=GB` → `/api/video/{id}`. Only known route words are kept; any other
/// path segment becomes `{id}`, so nothing user-specific ends up in a report.
pub fn route_template(path_and_query: &str) -> String {
	const WORDS: &[&str] = &["api", "v1", "search", "suggest", "video", "videos", "playlist", "playlists", "channel", "channels", "segments", "dislikes", "spotify", "match", "inworld", "check", "complete", "rooms", "new", "ws", "health", "capabilities", "auth", "google", "token", "refresh", "revoke", "tracks", "vrchat", "pairings", "exports", "protv", "youtubei", "next"];
	let path = path_and_query.split(['?', '#']).next().unwrap_or("");
	let segs: Vec<String> = path.split('/').filter(|s| !s.is_empty()).map(|s| if WORDS.contains(&s) { s.to_string() } else { "{id}".to_string() }).collect();
	format!("/{}", segs.join("/"))
}

/// Strips the scheme and host: diagnostics show the BetterYTS route, or the provider host.
pub fn path_of(url: &str) -> &str {
	let rest = url.split_once("://").map(|(_, r)| r).unwrap_or(url);
	rest.find('/').map(|i| &rest[i..]).unwrap_or("/")
}

#[cfg(test)]
mod tests {
	use super::*;

	fn rec(route: &str, ms: u64, cached: bool, error: Option<&str>) -> RequestRecord {
		RequestRecord { at: 0, method: "GET", route: route.into(), status: if error.is_some() { 503 } else { 200 }, ms, cached, retries: 0, error: error.map(String::from), request_id: None }
	}

	#[test]
	fn routes_never_keep_ids_or_queries() {
		assert_eq!(route_template("/api/video/dQw4w9WgXcQ?gl=GB"), "/api/video/{id}");
		assert_eq!(route_template("/api/search?q=my+secret+crush"), "/api/search");
		assert_eq!(route_template("/api/rooms/ABC234/ws"), "/api/rooms/{id}/ws");
		assert_eq!(route_template("/api/inworld/check?code=K7QX-M2PA"), "/api/inworld/check");
		assert_eq!(route_template("/youtubei/v1/next?prettyPrint=false"), "/youtubei/v1/next");
		assert_eq!(path_of("https://yts.foulfoxhacks.dev/api/suggest?q=a"), "/api/suggest?q=a");
		assert_eq!(path_of("https://host"), "/");
	}

	#[test]
	fn ring_buffer_and_summary() {
		clear();
		for i in 0..(MAX_RECORDS + 5) {
			record(rec("/api/search", i as u64, false, None));
		}
		let all = recent();
		assert_eq!(all.len(), MAX_RECORDS);
		assert_eq!(all[0].ms, (MAX_RECORDS + 4) as u64, "newest first");
		clear();

		let s = summarize(&[rec("/a", 100, false, None), rec("/b", 0, true, None), rec("/c", 300, false, Some("UPSTREAM")), rec("/d", 200, false, None)]);
		assert_eq!((s.requests, s.failures, s.cache_hits, s.median_ms, s.max_ms), (4, 1, 1, 200, 300));
		assert_eq!(s.last_error.as_deref(), Some("GET /c → UPSTREAM"));
	}
}
