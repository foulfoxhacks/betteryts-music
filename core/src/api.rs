//! Client for the BetterYTS API (https://yts.foulfoxhacks.dev/docs).

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use serde::Serialize;
use serde_json::Value;

use crate::error::{code, CoreError};
use crate::model::{self, Artist, Page, Track};

#[cfg(test)]
use crate::model::Item;

pub const DEFAULT_BASE: &str = "https://yts.foulfoxhacks.dev";

#[derive(Clone)]
pub struct Api {
	base: String,
	agent: ureq::Agent,
	pub region: String,
	/// Shared by every clone, so all calls (and the feed's worker threads) reuse responses.
	cache: Arc<Mutex<ResponseCache>>,
}

/// Responses kept in memory for as long as the Worker's `Cache-Control: max-age` allows.
/// The Worker caches the same responses at the edge for that long, so this never shows
/// anything staler than the server would; it just skips the request. Errors are never cached.
pub struct ResponseCache {
	entries: HashMap<String, (Instant, Value)>,
	capacity: usize,
}

const CACHE_ENTRIES: usize = 128;
const CACHE_MAX_TTL_SECS: u64 = 3600;

impl ResponseCache {
	pub fn new(capacity: usize) -> ResponseCache {
		ResponseCache { entries: HashMap::new(), capacity }
	}

	pub fn get(&mut self, url: &str, now: Instant) -> Option<Value> {
		match self.entries.get(url) {
			Some((expires, v)) if *expires > now => Some(v.clone()),
			Some(_) => {
				self.entries.remove(url);
				None
			}
			None => None,
		}
	}

	pub fn put(&mut self, url: String, value: Value, ttl: Duration, now: Instant) {
		if self.capacity == 0 {
			return;
		}
		if self.entries.len() >= self.capacity && !self.entries.contains_key(&url) {
			self.entries.retain(|_, (expires, _)| *expires > now);
			if self.entries.len() >= self.capacity {
				// Still full: drop whatever expires soonest.
				if let Some(k) = self.entries.iter().min_by_key(|(_, (e, _))| *e).map(|(k, _)| k.clone()) {
					self.entries.remove(&k);
				}
			}
		}
		self.entries.insert(url, (now + ttl, value));
	}

	pub fn clear(&mut self) {
		self.entries.clear();
	}

	pub fn len(&self) -> usize {
		self.entries.len()
	}
}

/// How long a response may be reused, from its `Cache-Control` header. `None` = don't cache.
pub fn cache_ttl(cache_control: Option<&str>) -> Option<Duration> {
	let cc = cache_control?.to_ascii_lowercase();
	let directives: Vec<&str> = cc.split(',').map(str::trim).collect();
	if directives.iter().any(|d| matches!(*d, "no-store" | "no-cache" | "private")) {
		return None;
	}
	let secs = directives.iter().find_map(|d| d.strip_prefix("max-age=")?.trim().parse::<u64>().ok())?;
	(secs > 0).then(|| Duration::from_secs(secs.min(CACHE_MAX_TTL_SECS)))
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
		Api { base: base.trim_end_matches('/').to_string(), agent, region: String::new(), cache: Arc::new(Mutex::new(ResponseCache::new(CACHE_ENTRIES))) }
	}

	/// Forget cached responses (e.g. after changing region or base URL).
	pub fn clear_cache(&self) {
		self.cache.lock().unwrap_or_else(|p| p.into_inner()).clear();
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
		self.get(path, query).map_err(String::from)
	}

	/// Like [`Api::get_json`], keeping the error's code (offline, rate-limited…).
	pub fn get_typed(&self, path: &str, query: &[(&str, &str)]) -> Result<Value, CoreError> {
		self.get(path, query)
	}

	/// POSTs JSON. Never cached and never retried (a repeat could apply the change twice).
	pub fn post_json(&self, path: &str, body: &Value) -> Result<Value, String> {
		self.post_typed(path, body).map_err(String::from)
	}

	pub fn post_typed(&self, path: &str, body: &Value) -> Result<Value, CoreError> {
		let url = self.url(path, &[]);
		let started = Instant::now();
		let result = match self.agent.post(&url).timeout(timeout_for(path)).set("Content-Type", "application/json").send_string(&body.to_string()) {
			Ok(res) => read_json(res),
			Err(e) => Err(error_from(e)),
		};
		log_request("POST", &url, &result, started, false, 0);
		result
	}

	fn get(&self, path: &str, query: &[(&str, &str)]) -> Result<Value, CoreError> {
		let url = self.url(path, query);
		let started = Instant::now();
		if let Some(hit) = self.cache.lock().unwrap_or_else(|p| p.into_inner()).get(&url, Instant::now()) {
			log_request("GET", &url, &Ok(Value::Null), started, true, 0);
			return Ok(hit);
		}
		let mut attempt = 0u32;
		let result = loop {
			let result = match self.agent.get(&url).timeout(timeout_for(path)).call() {
				Ok(res) => {
					let ttl = cache_ttl(res.header("cache-control"));
					read_json(res).map(|v| (v, ttl))
				}
				Err(e) => Err(error_from(e)),
			};
			match result {
				Err(ref e) => match retry_delay(attempt, e, jitter_ms()) {
					Some(wait) => {
						attempt += 1;
						std::thread::sleep(wait);
					}
					None => break result,
				},
				Ok(_) => break result,
			}
		};
		let result = result.map(|(value, ttl)| {
			if let Some(ttl) = ttl {
				self.cache.lock().unwrap_or_else(|p| p.into_inner()).put(url.clone(), value.clone(), ttl, Instant::now());
			}
			value
		});
		log_request("GET", &url, &result, started, false, attempt);
		result
	}

	pub fn search(&self, query: &str, filter: Filter, page: Option<&str>) -> Result<Page, CoreError> {
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

	pub fn suggest(&self, query: &str) -> Result<Vec<String>, CoreError> {
		if query.trim().is_empty() {
			return Ok(Vec::new());
		}
		let v = self.get("/api/suggest", &[("q", query)])?;
		Ok(v.as_array().map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).take(10).collect()).unwrap_or_default())
	}

	pub fn video(&self, id: &str) -> Result<Track, CoreError> {
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
			plays: v.get("viewCount").and_then(|x| x.as_u64().or_else(|| x.as_str().and_then(|s| s.parse().ok()))).map(model::plays_label),
		})
	}

	pub fn playlist(&self, id: &str, page: Option<&str>) -> Result<CollectionPage, CoreError> {
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

	pub fn artist(&self, reference: &str, page: Option<&str>) -> Result<ArtistPage, CoreError> {
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

/// Largest response the core will read; the biggest real ones (long playlists) are well under 2 MB.
pub const MAX_RESPONSE_BYTES: u64 = 8 * 1024 * 1024;
/// Retries for a failed GET (so at most 3 tries), and the longest `Retry-After` worth waiting for.
pub const MAX_RETRIES: u32 = 2;
const MAX_RETRY_WAIT_SECS: u64 = 5;

/// Suggestions are typed live, so they give up quickly; everything else gets longer.
pub fn timeout_for(path: &str) -> Duration {
	if path.contains("/suggest") {
		Duration::from_secs(6)
	} else if path.contains("/search") || path.contains("/inworld") || path.contains("/rooms") {
		Duration::from_secs(15)
	} else {
		Duration::from_secs(25)
	}
}

/// How long to wait before trying a failed GET again, or None to give up. Backoff doubles from
/// 300 ms with jitter; a server's `Retry-After` is honoured when it's short, and a long one means
/// "not now", so the error goes to the user instead of a frozen screen.
pub fn retry_delay(attempt: u32, e: &CoreError, jitter_ms: u64) -> Option<Duration> {
	if attempt >= MAX_RETRIES || !e.retryable {
		return None;
	}
	if let Some(secs) = e.retry_after {
		return (secs <= MAX_RETRY_WAIT_SECS).then(|| Duration::from_millis(secs * 1000 + jitter_ms % 250));
	}
	Some(Duration::from_millis(300 * (1 << attempt) + jitter_ms % 200))
}

fn jitter_ms() -> u64 {
	std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.subsec_nanos() as u64 / 1000).unwrap_or(0)
}

fn request_id(res: &ureq::Response) -> Option<String> {
	res.header("x-request-id").or_else(|| res.header("cf-ray")).map(|s| s.chars().filter(|c| c.is_ascii_alphanumeric() || *c == '-' || *c == '_').take(64).collect())
}

/// Reads a body as JSON, refusing anything over [`MAX_RESPONSE_BYTES`].
fn read_body(res: ureq::Response) -> Result<(Value, Option<String>), CoreError> {
	use std::io::Read;
	let rid = request_id(&res);
	let mut buf = Vec::new();
	res.into_reader().take(MAX_RESPONSE_BYTES + 1).read_to_end(&mut buf).map_err(|e| CoreError::new(code::NETWORK, format!("Couldn't read the response: {e}")).with_request_id(rid.clone()))?;
	if buf.len() as u64 > MAX_RESPONSE_BYTES {
		return Err(CoreError::new(code::TOO_LARGE, "BetterYTS sent more than the app can read").with_request_id(rid));
	}
	let v = serde_json::from_slice(&buf).map_err(|_| CoreError::new(code::BAD_RESPONSE, "BetterYTS sent something unexpected. Try again in a moment.").with_request_id(rid.clone()))?;
	Ok((v, rid))
}

/// A successful response. A v1 envelope (`{ ok, data, meta }`) is unwrapped to its data, so
/// callers see the same shapes from versioned and legacy routes.
fn read_json(res: ureq::Response) -> Result<Value, CoreError> {
	let (v, rid) = read_body(res)?;
	unwrap_envelope(v).map_err(|e| e.with_request_id(rid))
}

/// `{ ok: true, data, meta }` → data; `{ ok: false, error: { code, message, retryable } }` → error.
/// Anything else (every legacy route) passes through untouched.
pub fn unwrap_envelope(v: Value) -> Result<Value, CoreError> {
	let Some(o) = v.as_object() else { return Ok(v) };
	let enveloped = o.contains_key("ok") && o.keys().all(|k| matches!(k.as_str(), "ok" | "data" | "error" | "meta"));
	if !enveloped {
		return Ok(v);
	}
	let rid = v.pointer("/meta/requestId").and_then(Value::as_str).map(String::from);
	if v.get("ok").and_then(Value::as_bool) == Some(true) {
		return Ok(v.get("data").cloned().unwrap_or(Value::Null));
	}
	let err = v.get("error");
	let message = err.and_then(|e| e.get("message").and_then(Value::as_str).or_else(|| e.as_str())).unwrap_or("BetterYTS couldn't do that").to_string();
	let mut e = CoreError::new(err.and_then(|e| e.get("code")).and_then(Value::as_str).unwrap_or(code::ERROR), message);
	if let Some(r) = err.and_then(|e| e.get("retryable")).and_then(Value::as_bool) {
		e.retryable = r;
	}
	Err(e.with_request_id(rid))
}

/// A failed request → a typed error. Error bodies are `{ error: "…" }`, `{ error: { message } }`
/// or the v1 envelope; the server's message is shown when it sent one.
fn error_from(e: ureq::Error) -> CoreError {
	match e {
		ureq::Error::Status(status, res) => {
			let retry_after = crate::error::parse_retry_after(res.header("retry-after"));
			let rid = request_id(&res);
			let body: Option<Value> = res.into_string().ok().and_then(|t| serde_json::from_str(&t).ok());
			let err = body.as_ref().and_then(|v| v.get("error"));
			let message = err
				.and_then(|e| e.as_str().map(String::from).or_else(|| e.get("message").and_then(Value::as_str).map(String::from)))
				.unwrap_or_else(|| match status {
					429 => "BetterYTS is busy. Try again in a moment.".to_string(),
					500..=599 => format!("BetterYTS is having trouble right now (HTTP {status})"),
					_ => format!("BetterYTS returned HTTP {status}"),
				});
			let server_code = err.and_then(|e| e.get("code")).and_then(Value::as_str);
			CoreError::from_status(status, message, server_code).with_retry_after(retry_after).with_request_id(rid)
		}
		ureq::Error::Transport(t) => {
			let timed_out = t.kind() == ureq::ErrorKind::Io && t.to_string().to_lowercase().contains("timed out");
			if timed_out {
				CoreError::new(code::TIMEOUT, "BetterYTS took too long to answer. Check your connection.")
			} else {
				CoreError::new(code::NETWORK, format!("Can't reach BetterYTS. Check your connection. ({})", t.kind()))
			}
		}
	}
}

fn log_request(method: &'static str, url: &str, result: &Result<Value, CoreError>, started: Instant, cached: bool, retries: u32) {
	let (status, error, request_id) = match result {
		Ok(_) => (200, None, None),
		Err(e) => (e.status.unwrap_or(0), Some(e.code.clone()), e.request_id.clone()),
	};
	crate::diag::record(crate::diag::RequestRecord {
		at: crate::library::Store::now(),
		method,
		route: crate::diag::route_template(crate::diag::path_of(url)),
		status,
		ms: started.elapsed().as_millis() as u64,
		cached,
		retries,
		error,
		request_id,
	});
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
	fn cache_ttl_follows_the_workers_cache_control() {
		assert_eq!(cache_ttl(Some("public, max-age=600, s-maxage=600")), Some(Duration::from_secs(600)));
		assert_eq!(cache_ttl(Some("public, max-age=86400")), Some(Duration::from_secs(CACHE_MAX_TTL_SECS)), "capped at an hour");
		assert_eq!(cache_ttl(Some("no-store")), None);
		assert_eq!(cache_ttl(Some("private, max-age=60")), None);
		assert_eq!(cache_ttl(Some("public, s-maxage=600")), None, "s-maxage is for shared caches only");
		assert_eq!(cache_ttl(Some("max-age=0")), None);
		assert_eq!(cache_ttl(None), None);
	}

	#[test]
	fn response_cache_expires_and_evicts_soonest_first() {
		let t0 = Instant::now();
		let mut c = ResponseCache::new(2);
		c.put("a".into(), serde_json::json!(1), Duration::from_secs(10), t0);
		c.put("b".into(), serde_json::json!(2), Duration::from_secs(100), t0);
		assert_eq!(c.get("a", t0 + Duration::from_secs(5)), Some(serde_json::json!(1)));
		assert_eq!(c.get("a", t0 + Duration::from_secs(11)), None, "expired entries miss");
		assert_eq!(c.len(), 1, "and are dropped");
		c.put("a".into(), serde_json::json!(1), Duration::from_secs(10), t0);
		c.put("c".into(), serde_json::json!(3), Duration::from_secs(50), t0); // full: evicts "a" (soonest)
		assert_eq!(c.len(), 2);
		assert_eq!(c.get("a", t0), None);
		assert_eq!(c.get("b", t0), Some(serde_json::json!(2)));
		assert_eq!(c.get("c", t0), Some(serde_json::json!(3)));
		c.clear();
		assert_eq!(c.len(), 0);
	}

	#[test]
	#[ignore = "live API"]
	fn live_repeat_requests_are_served_from_the_cache() {
		let api = Api::new(DEFAULT_BASE);
		api.search("lofi hip hop", Filter::Songs, None).unwrap();
		let before = Instant::now();
		let again = api.clone().search("lofi hip hop", Filter::Songs, None).unwrap();
		assert!(!again.items.is_empty());
		assert!(before.elapsed() < Duration::from_millis(50), "second call should not hit the network");
	}

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
	fn retries_only_what_is_worth_retrying() {
		let net = CoreError::new(code::NETWORK, "offline");
		let d0 = retry_delay(0, &net, 0).unwrap();
		let d1 = retry_delay(1, &net, 0).unwrap();
		assert_eq!((d0.as_millis(), d1.as_millis()), (300, 600), "backoff doubles");
		assert!(retry_delay(0, &net, 199).unwrap().as_millis() < 500, "jitter is bounded");
		assert_eq!(retry_delay(MAX_RETRIES, &net, 0), None, "gives up after the last retry");
		assert_eq!(retry_delay(0, &CoreError::from_status(404, "", None), 0), None, "a 404 won't fix itself");
		assert_eq!(retry_delay(0, &CoreError::from_status(400, "", None), 0), None);
		let soon = CoreError::from_status(429, "", None).with_retry_after(Some(2));
		assert_eq!(retry_delay(0, &soon, 0).unwrap().as_millis(), 2000, "Retry-After is honoured");
		let later = CoreError::from_status(429, "", None).with_retry_after(Some(120));
		assert_eq!(retry_delay(0, &later, 0), None, "a long Retry-After goes to the user instead");
		assert!(timeout_for("/api/suggest") < timeout_for("/api/search"));
		assert!(timeout_for("/api/search") < timeout_for("/api/playlist/PL1"));
	}

	#[test]
	fn v1_envelopes_unwrap_and_legacy_shapes_pass_through() {
		use serde_json::json;
		let ok = unwrap_envelope(json!({ "ok": true, "data": { "items": [1] }, "meta": { "requestId": "req_1", "apiVersion": "1" } })).unwrap();
		assert_eq!(ok, json!({ "items": [1] }));
		let err = unwrap_envelope(json!({ "ok": false, "error": { "code": "RATE_LIMITED", "message": "Please retry later.", "retryable": true }, "meta": { "requestId": "req_2" } })).unwrap_err();
		assert_eq!((err.code.as_str(), err.message.as_str(), err.retryable, err.request_id.as_deref()), ("RATE_LIMITED", "Please retry later.", true, Some("req_2")));
		// Legacy replies that happen to carry `ok` alongside other fields stay as they are.
		let legacy = json!({ "ok": true, "playlists": 2, "tracks": 30 });
		assert_eq!(unwrap_envelope(legacy.clone()).unwrap(), legacy);
		assert_eq!(unwrap_envelope(json!([1, 2])).unwrap(), json!([1, 2]));
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
