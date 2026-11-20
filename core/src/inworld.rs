//! Send music to a VRChat world from the app: the same activation-code flow as the website's
//! "Send music to a VRChat world" page (server: src/vrc/links.ts in the betteryts repo).

use serde::Serialize;
use serde_json::{json, Value};

use crate::api::Api;
use crate::model::Track;

#[derive(Serialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct CodeInfo {
	pub code: String,
	pub service: String,
}

/// "k7qx m2pa" → "K7QX-M2PA", or None if it can't be a code (saves a request and a rate-limit hit).
pub fn normalize(input: &str) -> Option<String> {
	const ALPHABET: &str = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
	let c: String = input.chars().filter(char::is_ascii_alphanumeric).map(|c| c.to_ascii_uppercase()).collect();
	(c.len() == 8 && c.chars().all(|ch| ALPHABET.contains(ch))).then(|| format!("{}-{}", &c[..4], &c[4..]))
}

pub fn check(api: &Api, input: &str) -> Result<CodeInfo, String> {
	let code = normalize(input).ok_or("Codes look like ABCD-1234 (letters and numbers)")?;
	let v = api.get_json("/api/inworld/check", &[("code", &code)])?;
	Ok(CodeInfo { code: v.get("code").and_then(Value::as_str).unwrap_or(&code).to_string(), service: v.get("service").and_then(Value::as_str).unwrap_or("youtube").to_string() })
}

/// Sends playlists (name, tracks) to the world waiting on `code`. Returns (playlists, tracks) sent.
/// `save`: keep it for the player's next visit (30 days); otherwise it's for this visit only.
/// Only song IDs, titles and channels are sent, never account details.
pub fn send(api: &Api, code: &str, service: &str, save: bool, playlists: &[(String, Vec<Track>)]) -> Result<(u64, u64), String> {
	let code = normalize(code).ok_or("Codes look like ABCD-1234 (letters and numbers)")?;
	let body = json!({
		"code": code,
		"service": service,
		"save": save,
		"library": {
			"playlists": playlists.iter().take(20).map(|(name, tracks)| json!({
				"name": name,
				"tracks": tracks.iter().take(200).map(|t| json!({ "id": t.id, "title": t.title, "channel": t.artist })).collect::<Vec<_>>(),
			})).collect::<Vec<_>>(),
		},
	});
	let v = api.post_json("/api/inworld/complete", &body)?;
	Ok((v.get("playlists").and_then(Value::as_u64).unwrap_or(0), v.get("tracks").and_then(Value::as_u64).unwrap_or(0)))
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn codes() {
		assert_eq!(normalize(" k7qx m2pa ").as_deref(), Some("K7QX-M2PA"));
		assert_eq!(normalize("K7QX-M2PA").as_deref(), Some("K7QX-M2PA"));
		assert_eq!(normalize("K7QX-M2P0"), None, "0 isn't used");
		assert_eq!(normalize("K7QX"), None);
	}
}
