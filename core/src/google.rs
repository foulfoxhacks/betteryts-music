//! Optional Google account link: read-only import from the YouTube Data API v3.
//!
//! Sign-in goes through the BetterYTS Worker (`/auth/google/*`), which holds the OAuth client
//! secret; this module talks to that broker for tokens and to Google's API with the access token.
//! Scope is `youtube.readonly`: playlists, liked videos and subscriptions are read, never changed.

use std::time::Duration;

use serde::Serialize;
use serde_json::{json, Value};

use crate::api::enc;
use crate::model::{s, Artist, Track};
use crate::music;

const YT_API: &str = "https://www.googleapis.com/youtube/v3";

/// Errors that mean "the access token is no good"; the app refreshes it and retries once.
pub const AUTH_ERROR: &str = "google_auth_expired";

pub const MAX_IMPORT_PLAYLISTS: usize = 100;
pub const MAX_ITEMS_PER_PLAYLIST: usize = 1000;
pub const MAX_LIKED: usize = 2000;
pub const MAX_SUBSCRIPTIONS: usize = 300;

fn agent() -> ureq::Agent {
	ureq::AgentBuilder::new()
		.timeout_connect(Duration::from_secs(10))
		.timeout(Duration::from_secs(30))
		.user_agent(concat!("BetterYTSMusic/", env!("CARGO_PKG_VERSION"), " (Android)"))
		.build()
}

/// Reads an error message from either `{"error":"…"}` or `{"error":{"message":"…"}}`.
pub fn error_message(v: &Value) -> Option<String> {
	match v.get("error") {
		Some(Value::String(e)) => Some(e.clone()),
		Some(e @ Value::Object(_)) => s(e, "message"),
		_ => None,
	}
}

fn read(res: Result<ureq::Response, ureq::Error>, what: &str) -> Result<Value, String> {
	match res {
		Ok(r) => r.into_string().ok().and_then(|t| serde_json::from_str(&t).ok()).ok_or_else(|| format!("{what}: unexpected response")),
		Err(ureq::Error::Status(401, _)) => Err(AUTH_ERROR.to_string()),
		Err(ureq::Error::Status(code, r)) => {
			let body: Option<Value> = r.into_string().ok().and_then(|t| serde_json::from_str(&t).ok());
			let msg = body.as_ref().and_then(error_message).unwrap_or_else(|| format!("HTTP {code}"));
			if code == 403 && msg.to_lowercase().contains("quota") {
				return Err("YouTube's daily limit for this app has been reached. Try again tomorrow.".into());
			}
			Err(format!("{what}: {msg}"))
		}
		Err(ureq::Error::Transport(t)) => Err(format!("{what}: can't connect ({})", t.kind())),
	}
}

// ---------------------------------------------------------------------------------------------
// Token broker (the BetterYTS Worker)
// ---------------------------------------------------------------------------------------------

#[derive(Serialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Tokens {
	pub access_token: String,
	pub expires_in: u64,
	pub refresh_token: Option<String>,
}

fn tokens_from(v: &Value) -> Result<Tokens, String> {
	Ok(Tokens {
		access_token: s(v, "access_token").ok_or("Sign-in failed: no access token")?,
		expires_in: v.get("expires_in").and_then(Value::as_u64).unwrap_or(3600),
		refresh_token: s(v, "refresh_token"),
	})
}

fn broker(base: &str, path: &str, body: Value) -> Result<Value, String> {
	let res = agent().post(&format!("{}{}", base.trim_end_matches('/'), path)).set("Content-Type", "application/json").send_string(&body.to_string());
	match res {
		// From the broker, 400 on refresh means the refresh token was revoked or expired.
		Err(ureq::Error::Status(400, _)) if path.ends_with("/refresh") => Err(AUTH_ERROR.to_string()),
		other => read(other, "Google sign-in"),
	}
}

pub fn exchange(base: &str, code: &str, verifier: &str) -> Result<Tokens, String> {
	tokens_from(&broker(base, "/api/auth/google/token", json!({ "code": code, "code_verifier": verifier }))?)
}

pub fn refresh(base: &str, refresh_token: &str) -> Result<Tokens, String> {
	tokens_from(&broker(base, "/api/auth/google/refresh", json!({ "refresh_token": refresh_token }))?)
}

pub fn revoke(base: &str, token: &str) -> Result<(), String> {
	broker(base, "/api/auth/google/revoke", json!({ "token": token })).map(|_| ())
}

// ---------------------------------------------------------------------------------------------
// YouTube Data API v3 (read-only)
// ---------------------------------------------------------------------------------------------

pub struct YouTube {
	agent: ureq::Agent,
	token: String,
}

/// "PT1H2M3S" → 3723.
pub fn iso_duration(d: &str) -> Option<u32> {
	let rest = d.strip_prefix('P')?;
	let (days, time) = match rest.split_once('T') {
		Some((d, t)) => (d, t),
		None => (rest, ""),
	};
	let mut total: u64 = 0;
	let mut num = String::new();
	for c in days.chars() {
		match c {
			'0'..='9' => num.push(c),
			'D' => total += num.parse::<u64>().ok()? * 86_400,
			_ => return None,
		}
		if !c.is_ascii_digit() {
			num.clear();
		}
	}
	for c in time.chars() {
		match c {
			'0'..='9' => num.push(c),
			'H' | 'M' | 'S' => {
				let n: u64 = num.parse().ok()?;
				total += n * match c {
					'H' => 3600,
					'M' => 60,
					_ => 1,
				};
				num.clear();
			}
			_ => return None,
		}
	}
	u32::try_from(total).ok()
}

fn thumb(snippet: &Value) -> Option<String> {
	let t = snippet.get("thumbnails")?;
	["medium", "high", "default"].iter().find_map(|k| t.get(*k).and_then(|x| s(x, "url")))
}

/// playlistItems.list → Track. Deleted and private videos have no owner and are skipped.
pub fn track_from_playlist_item(v: &Value) -> Option<Track> {
	let sn = v.get("snippet")?;
	let id = sn.get("resourceId").and_then(|r| s(r, "videoId")).or_else(|| v.get("contentDetails").and_then(|c| s(c, "videoId")))?;
	let owner = s(sn, "videoOwnerChannelTitle")?;
	let (title, artist) = music::split_title(&s(sn, "title")?, &owner);
	Some(Track { id, title, artist, artist_id: s(sn, "videoOwnerChannelId"), thumbnail: thumb(sn), duration: None, duration_seconds: None, live: false, plays: None })
}

/// videos.list (liked videos) → Track, with duration.
pub fn track_from_video(v: &Value) -> Option<Track> {
	let id = s(v, "id")?;
	let sn = v.get("snippet")?;
	let channel = s(sn, "channelTitle").unwrap_or_default();
	let (title, artist) = music::split_title(&s(sn, "title")?, &channel);
	let secs = v.get("contentDetails").and_then(|c| s(c, "duration")).and_then(|d| iso_duration(&d)).filter(|s| *s > 0);
	Some(Track {
		id,
		title,
		artist,
		artist_id: s(sn, "channelId"),
		thumbnail: thumb(sn),
		duration: secs.map(|x| crate::api::fmt_duration(x as u64)),
		duration_seconds: secs,
		live: s(sn, "liveBroadcastContent").as_deref() == Some("live"),
		plays: None,
	})
}

/// subscriptions.list → Artist.
pub fn artist_from_subscription(v: &Value) -> Option<Artist> {
	let sn = v.get("snippet")?;
	let id = sn.get("resourceId").and_then(|r| s(r, "channelId"))?;
	Some(Artist { id, name: music::clean_artist(&s(sn, "title")?), handle: None, thumbnail: thumb(sn), subtitle: None })
}

#[derive(Debug, Clone, PartialEq)]
pub struct RemotePlaylist {
	pub id: String,
	pub title: String,
	pub tracks: Vec<Track>,
}

impl YouTube {
	pub fn new(access_token: &str) -> YouTube {
		YouTube { agent: agent(), token: access_token.to_string() }
	}

	fn get(&self, resource: &str, query: &[(&str, &str)]) -> Result<Value, String> {
		let mut url = format!("{YT_API}/{resource}?");
		url.push_str(&query.iter().map(|(k, v)| format!("{k}={}", enc(v))).collect::<Vec<_>>().join("&"));
		read(self.agent.get(&url).set("Authorization", &format!("Bearer {}", self.token)).call(), "YouTube")
	}

	/// Follows nextPageToken until `max` items (50 per page, 1 quota unit each).
	fn paged(&self, resource: &str, query: &[(&str, &str)], max: usize) -> Result<Vec<Value>, String> {
		let mut out = Vec::new();
		let mut token: Option<String> = None;
		loop {
			let mut q: Vec<(&str, &str)> = query.to_vec();
			q.push(("maxResults", "50"));
			if let Some(t) = &token {
				q.push(("pageToken", t));
			}
			let page = self.get(resource, &q)?;
			out.extend(page.get("items").and_then(Value::as_array).cloned().unwrap_or_default());
			token = s(&page, "nextPageToken");
			if token.is_none() || out.len() >= max {
				break;
			}
		}
		out.truncate(max);
		Ok(out)
	}

	/// The signed-in YouTube channel's name, to show who's connected.
	pub fn channel_title(&self) -> Result<Option<String>, String> {
		let v = self.get("channels", &[("part", "snippet"), ("mine", "true")])?;
		Ok(v.get("items").and_then(Value::as_array).and_then(|a| a.first()).and_then(|c| c.get("snippet")).and_then(|sn| s(sn, "title")))
	}

	pub fn playlists(&self) -> Result<Vec<RemotePlaylist>, String> {
		let lists = self.paged("playlists", &[("part", "snippet"), ("mine", "true")], MAX_IMPORT_PLAYLISTS)?;
		let mut out = Vec::new();
		for p in lists {
			let (Some(id), Some(title)) = (s(&p, "id"), p.get("snippet").and_then(|sn| s(sn, "title"))) else { continue };
			let items = self.paged("playlistItems", &[("part", "snippet,contentDetails"), ("playlistId", &id)], MAX_ITEMS_PER_PLAYLIST)?;
			out.push(RemotePlaylist { id, title, tracks: items.iter().filter_map(track_from_playlist_item).collect() });
		}
		Ok(out)
	}

	pub fn liked(&self) -> Result<Vec<Track>, String> {
		let items = self.paged("videos", &[("part", "snippet,contentDetails"), ("myRating", "like")], MAX_LIKED)?;
		Ok(items.iter().filter_map(track_from_video).collect())
	}

	pub fn subscriptions(&self) -> Result<Vec<Artist>, String> {
		let items = self.paged("subscriptions", &[("part", "snippet"), ("mine", "true"), ("order", "alphabetical")], MAX_SUBSCRIPTIONS)?;
		Ok(items.iter().filter_map(artist_from_subscription).collect())
	}
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn durations() {
		assert_eq!(iso_duration("PT3M34S"), Some(214));
		assert_eq!(iso_duration("PT1H2M3S"), Some(3723));
		assert_eq!(iso_duration("P1DT1S"), Some(86_401));
		assert_eq!(iso_duration("PT0S"), Some(0));
		assert_eq!(iso_duration("3:34"), None);
		assert_eq!(iso_duration("PT5X"), None);
	}

	#[test]
	fn maps_playlist_items_liked_videos_and_subscriptions() {
		let item = json!({
			"snippet": {
				"title": "Daft Punk - One More Time (Official Video)",
				"videoOwnerChannelTitle": "Daft Punk",
				"videoOwnerChannelId": "UC_kRDKYrUlrbtrSiyu5Tflg",
				"resourceId": { "kind": "youtube#video", "videoId": "FGBhQbmPwH8" },
				"thumbnails": { "default": { "url": "https://i.ytimg.com/vi/FGBhQbmPwH8/default.jpg" }, "medium": { "url": "https://i.ytimg.com/vi/FGBhQbmPwH8/mqdefault.jpg" } }
			}
		});
		let t = track_from_playlist_item(&item).unwrap();
		assert_eq!((t.title.as_str(), t.artist.as_str()), ("One More Time", "Daft Punk"));
		assert_eq!(t.thumbnail.as_deref(), Some("https://i.ytimg.com/vi/FGBhQbmPwH8/mqdefault.jpg"));
		let deleted = json!({ "snippet": { "title": "Deleted video", "resourceId": { "videoId": "aaaaaaaaaaa" } } });
		assert!(track_from_playlist_item(&deleted).is_none(), "deleted/private videos have no owner");

		let video = json!({
			"id": "dQw4w9WgXcQ",
			"snippet": { "title": "Never Gonna Give You Up", "channelTitle": "Rick Astley", "channelId": "UCuAXFkgsw1L7xaCfnd5JJOw", "liveBroadcastContent": "none" },
			"contentDetails": { "duration": "PT3M33S" }
		});
		let v = track_from_video(&video).unwrap();
		assert_eq!(v.duration.as_deref(), Some("3:33"));
		assert_eq!(v.artist_id.as_deref(), Some("UCuAXFkgsw1L7xaCfnd5JJOw"));

		let sub = json!({ "snippet": { "title": "Kavinsky - Topic", "resourceId": { "channelId": "UCabc" }, "thumbnails": { "high": { "url": "https://yt3.ggpht.com/x" } } } });
		let a = artist_from_subscription(&sub).unwrap();
		assert_eq!((a.id.as_str(), a.name.as_str()), ("UCabc", "Kavinsky"));
	}

	#[test]
	fn error_shapes() {
		assert_eq!(error_message(&json!({ "error": "x" })).as_deref(), Some("x"));
		assert_eq!(error_message(&json!({ "error": { "status": 503, "message": "not set up" } })).as_deref(), Some("not set up"));
		assert_eq!(error_message(&json!({})), None);
		let t = tokens_from(&json!({ "access_token": "ya29.a", "expires_in": 3599, "refresh_token": "1//r" })).unwrap();
		assert_eq!(t, Tokens { access_token: "ya29.a".into(), expires_in: 3599, refresh_token: Some("1//r".into()) });
		assert!(tokens_from(&json!({})).is_err());
	}
}
