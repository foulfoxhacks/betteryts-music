//! Spotify import: read a public playlist/album through the BetterYTS Worker (/api/spotify) and
//! match each song to a YouTube video (/api/match). Kotlin runs the matches a few at a time so it
//! can show progress; each call here is one small, cached request.

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::api::Api;
use crate::model::{self, Track};

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SpotifyTrack {
	pub title: String,
	#[serde(default)]
	pub artists: Vec<String>,
	#[serde(default)]
	pub duration_ms: Option<u64>,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct SpotifyList {
	pub kind: String,
	pub id: String,
	pub name: String,
	#[serde(default)]
	pub owner: Option<String>,
	#[serde(default)]
	pub image: Option<String>,
	pub tracks: Vec<SpotifyTrack>,
	/// Spotify's page lists at most 100 songs.
	#[serde(default)]
	pub may_be_truncated: bool,
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Matched {
	/// The song, named as Spotify names it, playing the matched YouTube video.
	pub track: Option<Track>,
	/// "high", "low" or "none".
	pub confidence: String,
	/// What YouTube calls the video (to show when the match is a guess).
	pub video_title: Option<String>,
}

/// True for links the importer understands (playlist/album pages and URIs).
pub fn is_spotify_link(text: &str) -> bool {
	let t = text.trim().to_ascii_lowercase();
	(t.contains("open.spotify.com/") && (t.contains("/playlist/") || t.contains("/album/"))) || t.starts_with("spotify:playlist:") || t.starts_with("spotify:album:")
}

pub fn read(api: &Api, link: &str) -> Result<SpotifyList, String> {
	if !is_spotify_link(link) {
		return Err("Paste a Spotify playlist or album link".into());
	}
	let v = api.get_json("/api/spotify", &[("url", link.trim())])?;
	serde_json::from_value(v).map_err(|_| "Couldn't read that Spotify playlist".to_string())
}

pub fn match_song(api: &Api, song: &SpotifyTrack) -> Result<Matched, String> {
	let artist = song.artists.first().map(String::as_str).unwrap_or("");
	let secs = song.duration_ms.map(|ms| ((ms + 500) / 1000).to_string()).unwrap_or_default();
	let v = api.get_json("/api/match", &[("track", song.title.as_str()), ("artist", artist), ("duration", secs.as_str())])?;
	Ok(from_reply(song, &v))
}

/// Pure: build the library track from the Worker's match reply.
pub fn from_reply(song: &SpotifyTrack, v: &Value) -> Matched {
	let confidence = model::s(v, "confidence").unwrap_or_else(|| "none".into());
	let m = v.get("match").filter(|m| m.is_object());
	let track = match (m, confidence.as_str()) {
		(Some(m), "high" | "low") => model::s(m, "id").filter(|id| id.len() == 11).map(|id| {
			let secs = m.get("durationSeconds").and_then(Value::as_u64).map(|s| s as u32).filter(|s| *s > 0);
			Track {
				id,
				title: song.title.clone(),
				artist: if song.artists.is_empty() { model::s(m, "channel").unwrap_or_default() } else { song.artists.join(", ") },
				artist_id: None,
				thumbnail: model::s(m, "thumbnail").filter(|u| u.starts_with("https://")),
				duration: model::s(m, "duration"),
				duration_seconds: secs,
				live: false,
				plays: None,
			}
		}),
		_ => None,
	};
	Matched {
		confidence: if track.is_some() { confidence } else { "none".into() },
		video_title: m.and_then(|m| model::s(m, "title")),
		track,
	}
}

#[cfg(test)]
mod tests {
	use super::*;
	use serde_json::json;

	fn song() -> SpotifyTrack {
		SpotifyTrack { title: "Housewife".into(), artists: vec!["Tate McRae".into(), "Guest".into()], duration_ms: Some(209_400) }
	}

	#[test]
	fn links() {
		assert!(is_spotify_link("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=x"));
		assert!(is_spotify_link("https://open.spotify.com/intl-de/album/4aawyAB9vmqN3uQ7FjRGTy"));
		assert!(is_spotify_link("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"));
		assert!(!is_spotify_link("https://open.spotify.com/track/3h5T5JypYU7huFiVYhv1dr"));
		assert!(!is_spotify_link("https://www.youtube.com/playlist?list=PL123"));
	}

	#[test]
	fn match_reply_keeps_spotify_names() {
		let reply = json!({"confidence": "high", "match": {"id": "DPEodkHzH-s", "title": "Tate McRae - Housewife (Official Video)", "channel": "Tate McRae", "duration": "3:30", "durationSeconds": 210, "thumbnail": "https://i.ytimg.com/vi/x/hq2.jpg"}});
		let m = from_reply(&song(), &reply);
		let t = m.track.unwrap();
		assert_eq!((t.id.as_str(), t.title.as_str(), t.artist.as_str()), ("DPEodkHzH-s", "Housewife", "Tate McRae, Guest"));
		assert_eq!(t.duration_seconds, Some(210));
		assert_eq!(m.confidence, "high");
		assert_eq!(m.video_title.as_deref(), Some("Tate McRae - Housewife (Official Video)"));
	}

	#[test]
	fn no_match_or_bad_id_is_none() {
		assert!(from_reply(&song(), &json!({"confidence": "none", "match": null})).track.is_none());
		assert!(from_reply(&song(), &json!({"confidence": "none", "match": {"id": "DPEodkHzH-s"}})).track.is_none(), "server said none");
		assert!(from_reply(&song(), &json!({"confidence": "high", "match": {"id": "bad"}})).track.is_none(), "invalid video ID");
		assert_eq!(from_reply(&song(), &json!({})).confidence, "none");
	}

	#[test]
	#[ignore = "live API (BETTERYTS_BASE=http://localhost:5173 to test a dev Worker)"]
	fn live_read_and_match() {
		let api = Api::new(&std::env::var("BETTERYTS_BASE").unwrap_or_else(|_| crate::api::DEFAULT_BASE.to_string()));
		let list = read(&api, "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M").unwrap();
		assert!(list.tracks.len() >= 20);
		let m = match_song(&api, &list.tracks[0]).unwrap();
		assert!(m.track.is_some(), "{m:?}");
	}
}
