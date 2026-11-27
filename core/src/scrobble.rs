//! Scrobbling to ListenBrainz (listenbrainz.org), opt-in.
//!
//! The user pastes their ListenBrainz user token in Settings; it's checked once, then kept in the
//! settings file on the device. Only the song, artist and when it was played are sent, and only
//! for songs listened to for at least half their length or four minutes (the standard rule).

use std::time::Duration;

use serde_json::{json, Value};

use crate::model::Track;

const API: &str = "https://api.listenbrainz.org/1";

/// The scrobbling rule: the song is longer than 30 s, and you listened to half of it or 4 minutes.
pub fn should_scrobble(duration_secs: Option<u32>, listened_secs: u64) -> bool {
	match duration_secs {
		Some(d) if d <= 30 => false,
		Some(d) => listened_secs >= (d as u64 / 2).min(240),
		// Unknown length (live, some device files): four minutes counts.
		None => listened_secs >= 240,
	}
}

fn metadata(t: &Track) -> Value {
	let mut info = json!({ "media_player": "BetterYTS Music", "submission_client": "BetterYTS Music", "submission_client_version": env!("CARGO_PKG_VERSION") });
	if !t.id.starts_with("local:") {
		info["origin_url"] = json!(format!("https://music.youtube.com/watch?v={}", t.id));
		info["music_service"] = json!("youtube.com");
	}
	if let Some(d) = t.duration_seconds {
		info["duration_ms"] = json!(d as u64 * 1000);
	}
	json!({ "artist_name": t.artist, "track_name": t.title, "additional_info": info })
}

/// The JSON body for "playing_now" (no timestamp) or "single" (a finished listen at `listened_at`, Unix s).
pub fn body(t: &Track, listened_at: Option<u64>) -> Value {
	let mut listen = json!({ "track_metadata": metadata(t) });
	match listened_at {
		Some(at) => {
			listen["listened_at"] = json!(at);
			json!({ "listen_type": "single", "payload": [listen] })
		}
		None => json!({ "listen_type": "playing_now", "payload": [listen] }),
	}
}

fn agent() -> ureq::Agent {
	ureq::AgentBuilder::new().timeout_connect(Duration::from_secs(8)).timeout(Duration::from_secs(15)).user_agent(concat!("BetterYTSMusic/", env!("CARGO_PKG_VERSION"))).build()
}

/// Checks a token; returns the ListenBrainz user name.
pub fn validate(token: &str) -> Result<String, String> {
	let token = token.trim();
	if token.is_empty() || token.len() > 100 || !token.chars().all(|c| c.is_ascii_alphanumeric() || c == '-') {
		return Err("That doesn't look like a ListenBrainz token (Settings › API keys on listenbrainz.org)".into());
	}
	let res = agent().get(&format!("{API}/validate-token")).set("Authorization", &format!("Token {token}")).call().map_err(|e| format!("Can't reach ListenBrainz ({e})"))?;
	let v: Value = res.into_json().map_err(|_| "ListenBrainz sent something unexpected".to_string())?;
	if v.get("valid").and_then(Value::as_bool) == Some(true) {
		Ok(v.get("user_name").and_then(Value::as_str).unwrap_or("").to_string())
	} else {
		Err("ListenBrainz didn't accept that token".into())
	}
}

pub fn submit(token: &str, t: &Track, listened_at: Option<u64>) -> Result<(), String> {
	agent()
		.post(&format!("{API}/submit-listens"))
		.set("Authorization", &format!("Token {}", token.trim()))
		.send_json(body(t, listened_at))
		.map(|_| ())
		.map_err(|e| match e {
			ureq::Error::Status(401, _) => "ListenBrainz: the token was revoked; connect again".to_string(),
			ureq::Error::Status(429, _) => "ListenBrainz is rate-limiting; will try with the next song".to_string(),
			other => format!("ListenBrainz: {other}"),
		})
}

#[cfg(test)]
mod tests {
	use super::*;

	fn t(id: &str, secs: Option<u32>) -> Track {
		serde_json::from_value(json!({ "id": id, "title": "Song", "artist": "Artist", "durationSeconds": secs })).unwrap()
	}

	#[test]
	fn the_scrobble_rule() {
		assert!(!should_scrobble(Some(25), 25), "too short to count");
		assert!(should_scrobble(Some(200), 100));
		assert!(!should_scrobble(Some(200), 99));
		assert!(should_scrobble(Some(3600), 240), "four minutes of a long mix counts");
		assert!(!should_scrobble(None, 200));
		assert!(should_scrobble(None, 240));
	}

	#[test]
	fn bodies() {
		let now = body(&t("dQw4w9WgXcQ", Some(213)), None);
		assert_eq!(now["listen_type"], "playing_now");
		assert!(now["payload"][0].get("listened_at").is_none());
		assert_eq!(now["payload"][0]["track_metadata"]["additional_info"]["duration_ms"], 213_000);
		let done = body(&t("local:5", None), Some(1_700_000_000));
		assert_eq!((done["listen_type"].as_str(), done["payload"][0]["listened_at"].as_u64()), (Some("single"), Some(1_700_000_000)));
		assert!(done["payload"][0]["track_metadata"]["additional_info"].get("origin_url").is_none(), "device files have no link");
		assert!(validate("bad token!").is_err(), "checked before any request");
	}
}
