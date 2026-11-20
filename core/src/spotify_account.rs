//! "Sign in with Spotify" for the app: your playlists and Liked Songs (public links keep using
//! spotify.rs). PKCE with Spotify directly: the BetterYTS Worker only starts the flow and routes
//! the callback (betterytsmusic://oauth/spotify). The access token is used for one import and
//! never stored.

use std::time::Duration;

use serde::Serialize;
use serde_json::Value;

use crate::api::enc;
use crate::spotify::{SpotifyList, SpotifyTrack};

const API: &str = "https://api.spotify.com/v1";
const MAX_TRACKS: usize = 500;

fn agent() -> ureq::Agent {
	ureq::AgentBuilder::new()
		.timeout_connect(Duration::from_secs(10))
		.timeout(Duration::from_secs(30))
		.user_agent(concat!("BetterYTSMusic/", env!("CARGO_PKG_VERSION"), " (Android)"))
		.build()
}

fn read(res: Result<ureq::Response, ureq::Error>) -> Result<Value, String> {
	match res {
		Ok(r) => r.into_string().ok().and_then(|t| serde_json::from_str(&t).ok()).ok_or_else(|| "Spotify: unexpected response".to_string()),
		Err(ureq::Error::Status(401, _)) => Err("Spotify sign-in expired. Sign in again.".into()),
		Err(ureq::Error::Status(code, r)) => {
			let v: Option<Value> = r.into_string().ok().and_then(|t| serde_json::from_str(&t).ok());
			let msg = v.as_ref().and_then(|v| {
				v.get("error_description")
					.and_then(Value::as_str)
					.or_else(|| v.get("error").and_then(|e| e.get("message")).and_then(Value::as_str))
					.or_else(|| v.get("error").and_then(Value::as_str))
					.map(String::from)
			});
			Err(format!("Spotify: {}", msg.unwrap_or_else(|| format!("HTTP {code}"))))
		}
		Err(ureq::Error::Transport(t)) => Err(format!("Spotify: can't connect ({})", t.kind())),
	}
}

/// The client ID this BetterYTS server uses (from /api/auth/config), or an error if it's off.
pub fn client_id(api: &crate::api::Api) -> Result<String, String> {
	let v = api.get_json("/api/auth/config", &[])?;
	v.get("spotifyClientId").and_then(Value::as_str).filter(|s| !s.is_empty()).map(String::from).ok_or_else(|| "Spotify sign-in isn't set up on the server yet".to_string())
}

/// Swaps the code for an access token (PKCE: no secret).
pub fn exchange(client_id: &str, redirect_uri: &str, code: &str, verifier: &str) -> Result<String, String> {
	let res = agent().post("https://accounts.spotify.com/api/token").send_form(&[
		("grant_type", "authorization_code"),
		("code", code),
		("redirect_uri", redirect_uri),
		("client_id", client_id),
		("code_verifier", verifier),
	]);
	read(res)?.get("access_token").and_then(Value::as_str).map(String::from).ok_or_else(|| "Spotify sign-in failed".to_string())
}

fn get(token: &str, url: &str) -> Result<Value, String> {
	let full = if url.starts_with("https://") { url.to_string() } else { format!("{API}{url}") };
	if !full.starts_with(API) {
		return Err("Spotify: unexpected page link".into());
	}
	read(agent().get(&full).set("Authorization", &format!("Bearer {token}")).call())
}

#[derive(Serialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct RemoteList {
	pub id: String,
	pub name: String,
	pub count: u64,
}

/// Liked Songs (id "liked") and your playlists, up to 100.
pub fn playlists(token: &str) -> Result<Vec<RemoteList>, String> {
	let liked = get(token, "/me/tracks?limit=1")?;
	let mut out = vec![RemoteList { id: "liked".into(), name: "Liked Songs".into(), count: liked.get("total").and_then(Value::as_u64).unwrap_or(0) }];
	let mut next = Some("/me/playlists?limit=50".to_string());
	while let Some(url) = next.take() {
		let page = get(token, &url)?;
		for p in page.get("items").and_then(Value::as_array).into_iter().flatten() {
			if let (Some(id), Some(name)) = (p.get("id").and_then(Value::as_str), p.get("name").and_then(Value::as_str)) {
				out.push(RemoteList { id: id.into(), name: name.chars().take(80).collect(), count: p.get("tracks").and_then(|t| t.get("total")).and_then(Value::as_u64).unwrap_or(0) });
			}
		}
		next = page.get("next").and_then(Value::as_str).map(String::from).filter(|_| out.len() <= 100);
	}
	Ok(out)
}

/// A playlist's (or Liked Songs') tracks, in the same shape as a public-link import.
pub fn tracks(token: &str, id: &str, name: &str) -> Result<SpotifyList, String> {
	let liked = id == "liked";
	if !liked && !id.chars().all(|c| c.is_ascii_alphanumeric()) {
		return Err("Spotify: bad playlist ID".into());
	}
	let mut next = Some(if liked { "/me/tracks?limit=50".to_string() } else { format!("/playlists/{}/tracks?limit=100&fields={}", enc(id), enc("items(track(name,duration_ms,is_local,artists(name))),next")) });
	let mut out: Vec<SpotifyTrack> = Vec::new();
	while let Some(url) = next.take() {
		let page = get(token, &url)?;
		for it in page.get("items").and_then(Value::as_array).into_iter().flatten() {
			let Some(t) = it.get("track").filter(|t| !t.is_null()) else { continue };
			if t.get("is_local").and_then(Value::as_bool) == Some(true) {
				continue;
			}
			let Some(title) = t.get("name").and_then(Value::as_str).filter(|s| !s.is_empty()) else { continue };
			let artists = t.get("artists").and_then(Value::as_array).map(|a| a.iter().filter_map(|x| x.get("name").and_then(Value::as_str).map(String::from)).collect()).unwrap_or_default();
			out.push(SpotifyTrack { title: title.into(), artists, duration_ms: t.get("duration_ms").and_then(Value::as_u64) });
		}
		next = page.get("next").and_then(Value::as_str).map(String::from).filter(|_| out.len() < MAX_TRACKS);
	}
	out.truncate(MAX_TRACKS);
	Ok(SpotifyList { kind: "playlist".into(), id: id.into(), name: name.into(), owner: None, image: None, tracks: out, may_be_truncated: false })
}
