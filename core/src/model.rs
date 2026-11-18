//! Music-shaped models, and mapping from the BetterYTS API's YouTube-shaped JSON.

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::music;

/// A playable song (a YouTube video).
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Track {
	pub id: String,
	pub title: String,
	pub artist: String,
	/// Channel ID ("UC…") or @handle, for opening the artist page.
	#[serde(default)]
	pub artist_id: Option<String>,
	#[serde(default)]
	pub thumbnail: Option<String>,
	#[serde(default)]
	pub duration: Option<String>,
	#[serde(default)]
	pub duration_seconds: Option<u32>,
	#[serde(default)]
	pub live: bool,
}

/// A YouTube playlist or mix.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Collection {
	pub id: String,
	/// "playlist" or "mix"
	pub collection_type: String,
	pub title: String,
	pub subtitle: String,
	#[serde(default)]
	pub thumbnail: Option<String>,
	#[serde(default)]
	pub first_video_id: Option<String>,
}

/// A YouTube channel, shown as an artist.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Artist {
	/// Channel ID ("UC…") or @handle.
	pub id: String,
	pub name: String,
	#[serde(default)]
	pub handle: Option<String>,
	#[serde(default)]
	pub thumbnail: Option<String>,
	#[serde(default)]
	pub subtitle: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(tag = "itemType", rename_all = "lowercase")]
pub enum Item {
	Track(Track),
	Collection(Collection),
	Artist(Artist),
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct Page {
	pub items: Vec<Item>,
	pub next: Option<String>,
}

pub(crate) fn s(v: &Value, k: &str) -> Option<String> {
	v.get(k).and_then(Value::as_str).map(str::trim).filter(|x| !x.is_empty()).map(String::from)
}

fn https(url: Option<String>) -> Option<String> {
	url.map(|u| if u.starts_with("//") { format!("https:{u}") } else { u }).filter(|u| u.starts_with("https://"))
}

/// Maps a search/playlist/channel result to a track. Shorts and upcoming premieres are skipped:
/// they don't make sense in a music queue. `fallback_artist` fills in channel pages, whose items
/// don't repeat the channel.
pub fn track_from(v: &Value, fallback_artist: Option<(&str, &str)>) -> Option<Track> {
	if v.get("type").and_then(Value::as_str) != Some("video") || v.get("upcoming").and_then(Value::as_bool) == Some(true) {
		return None;
	}
	let id = s(v, "id")?;
	let ch = v.get("channel").filter(|c| c.is_object());
	let (channel_name, channel_id) = match ch {
		Some(c) => (s(c, "name").unwrap_or_default(), s(c, "id").or_else(|| s(c, "handle"))),
		None => (String::new(), None),
	};
	let (channel_name, channel_id) = match fallback_artist {
		Some((name, id)) if channel_name.is_empty() => (name.to_string(), Some(id.to_string())),
		_ => (channel_name, channel_id),
	};
	let raw_title = s(v, "title").unwrap_or_else(|| id.clone());
	let (title, artist) = music::split_title(&raw_title, &channel_name);
	Some(Track {
		id,
		title,
		artist,
		artist_id: channel_id,
		thumbnail: https(s(v, "thumbnail")),
		duration: s(v, "duration"),
		duration_seconds: v.get("durationSeconds").and_then(Value::as_u64).map(|x| x as u32),
		live: v.get("live").and_then(Value::as_bool).unwrap_or(false),
	})
}

pub fn collection_from(v: &Value) -> Option<Collection> {
	let kind = v.get("type").and_then(Value::as_str)?;
	if kind != "playlist" && kind != "mix" {
		return None;
	}
	let owner = v.get("channel").and_then(|c| s(c, "name")).map(|n| music::clean_artist(&n));
	let count = s(v, "videoCount").filter(|c| c != "Mix");
	let subtitle = match (kind, owner, count) {
		("mix", Some(o), _) => format!("Mix · {o}"),
		("mix", None, _) => "Mix".to_string(),
		(_, Some(o), Some(c)) => format!("{o} · {c}"),
		(_, Some(o), None) => o,
		(_, None, Some(c)) => c,
		_ => "Playlist".to_string(),
	};
	Some(Collection {
		id: s(v, "id")?,
		collection_type: kind.to_string(),
		title: s(v, "title").unwrap_or_else(|| "Playlist".into()),
		subtitle,
		thumbnail: https(s(v, "thumbnail")),
		first_video_id: s(v, "firstVideoId"),
	})
}

pub fn artist_from(v: &Value) -> Option<Artist> {
	if v.get("type").and_then(Value::as_str) != Some("channel") {
		return None;
	}
	let handle = s(v, "handle");
	Some(Artist {
		id: s(v, "id").or_else(|| handle.clone())?,
		name: music::clean_artist(&s(v, "title")?),
		handle,
		thumbnail: https(s(v, "thumbnail")),
		subtitle: s(v, "subscribers"),
	})
}

pub fn item_from(v: &Value) -> Option<Item> {
	track_from(v, None)
		.map(Item::Track)
		.or_else(|| collection_from(v).map(Item::Collection))
		.or_else(|| artist_from(v).map(Item::Artist))
}

#[cfg(test)]
mod tests {
	use super::*;
	use serde_json::json;

	#[test]
	fn maps_video_to_track_with_music_metadata() {
		let v = json!({
			"type": "video", "id": "dQw4w9WgXcQ", "title": "Rick Astley - Never Gonna Give You Up (Official Music Video)",
			"thumbnail": "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg", "duration": "3:34", "durationSeconds": 214,
			"channel": { "name": "Rick Astley", "id": "UCuAXFkgsw1L7xaCfnd5JJOw" }, "live": false
		});
		let t = track_from(&v, None).unwrap();
		assert_eq!(t.title, "Never Gonna Give You Up");
		assert_eq!(t.artist, "Rick Astley");
		assert_eq!(t.artist_id.as_deref(), Some("UCuAXFkgsw1L7xaCfnd5JJOw"));
		assert_eq!(t.duration_seconds, Some(214));
	}

	#[test]
	fn skips_shorts_and_premieres_and_fills_channel_pages() {
		assert!(track_from(&json!({"type": "short", "id": "aaaaaaaaaaa"}), None).is_none());
		assert!(track_from(&json!({"type": "video", "id": "aaaaaaaaaaa", "upcoming": true}), None).is_none());
		let t = track_from(&json!({"type": "video", "id": "aaaaaaaaaaa", "title": "Snowfall", "channel": null}), Some(("Øneheart", "UC123"))).unwrap();
		assert_eq!(t.artist, "Øneheart");
		assert_eq!(t.artist_id.as_deref(), Some("UC123"));
	}

	#[test]
	fn maps_collections_and_artists() {
		let mix = item_from(&json!({"type": "mix", "id": "RDxyz", "title": "Mix • Lofi", "videoCount": "Mix", "firstVideoId": "aaaaaaaaaaa"})).unwrap();
		assert!(matches!(mix, Item::Collection(ref c) if c.subtitle == "Mix" && c.first_video_id.as_deref() == Some("aaaaaaaaaaa")));
		let pl = item_from(&json!({"type": "playlist", "id": "PL1", "title": "Chill", "videoCount": "42 videos", "channel": {"name": "Lofi Girl - Topic"}})).unwrap();
		assert!(matches!(pl, Item::Collection(ref c) if c.subtitle == "Lofi Girl · 42 videos"));
		let a = item_from(&json!({"type": "channel", "id": "UCabc", "title": "LofiGirlVEVO", "handle": "@LofiGirl", "subscribers": "15M subscribers"})).unwrap();
		assert!(matches!(a, Item::Artist(ref x) if x.name == "LofiGirl" && x.subtitle.as_deref() == Some("15M subscribers")));
		assert!(item_from(&json!({"type": "unknown"})).is_none());
	}

	#[test]
	fn serializes_items_with_a_type_tag() {
		let t = Item::Artist(Artist { id: "UC1".into(), name: "A".into(), handle: None, thumbnail: None, subtitle: None });
		let j = serde_json::to_value(&t).unwrap();
		assert_eq!(j["itemType"], "artist");
		assert_eq!(j["name"], "A");
	}
}
