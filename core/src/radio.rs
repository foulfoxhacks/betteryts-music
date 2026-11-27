//! Song radio, the way YouTube Music does it: the `RDAMVM<id>` watch playlist from YouTube
//! Music's own `next` endpoint. It's endless: every page carries a continuation for more.
//!
//! This one call goes straight from the phone to music.youtube.com (like NewPipe-style apps),
//! not through the BetterYTS Worker: the response is ~0.5 MB of JSON, far more than a Worker's
//! CPU budget wants to parse, and radio is personal to the phone anyway.

use std::time::Duration;

use serde::Serialize;
use serde_json::{json, Value};

use crate::model::{s, Track};

const NEXT: &str = "https://music.youtube.com/youtubei/v1/next?prettyPrint=false";
const CLIENT_VERSION: &str = "1.20251110.01.00";
const UA: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36";

#[derive(Serialize, Debug, Default)]
pub struct RadioPage {
	pub tracks: Vec<Track>,
	/// Pass back as `next` for more songs.
	#[serde(skip_serializing_if = "Option::is_none")]
	pub next: Option<String>,
}

fn text(v: Option<&Value>) -> Option<String> {
	let v = v?;
	if let Some(t) = s(v, "simpleText") {
		return Some(t);
	}
	let runs = v.get("runs")?.as_array()?;
	let t: String = runs.iter().filter_map(|r| s(r, "text")).collect();
	(!t.is_empty()).then_some(t)
}

fn seconds(d: &str) -> Option<u32> {
	d.split(':').try_fold(0u32, |acc, p| p.trim().parse::<u32>().ok().map(|n| acc * 60 + n))
}

/// One `playlistPanelVideoRenderer` → Track. The byline reads "Artist • 141M views • 1.2M likes"
/// (or "Artist • Album • 2001" for official songs); its first linked run is the artist's channel.
pub fn track_from_panel(v: &Value) -> Option<Track> {
	let v = v.get("playlistPanelVideoRenderer").or_else(|| v.pointer("/playlistPanelVideoWrapperRenderer/primaryRenderer/playlistPanelVideoRenderer"))?;
	let id = s(v, "videoId")?;
	let title = text(v.get("title"))?;
	let runs = v.pointer("/longBylineText/runs").or_else(|| v.pointer("/shortBylineText/runs")).and_then(Value::as_array).cloned().unwrap_or_default();
	let parts: Vec<String> = text(v.get("longBylineText")).or_else(|| text(v.get("shortBylineText"))).unwrap_or_default().split('•').map(|p| p.trim().to_string()).collect();
	let artist = parts.first().cloned().filter(|a| !a.is_empty()).unwrap_or_else(|| "Unknown artist".into());
	let artist_id = runs.iter().find_map(|r| r.pointer("/navigationEndpoint/browseEndpoint/browseId").and_then(Value::as_str)).filter(|b| b.starts_with("UC")).map(str::to_string);
	let plays = parts.iter().find(|p| p.ends_with(" views") || p.ends_with(" plays")).map(|p| p.replace(" views", " plays"));
	let duration = text(v.get("lengthText"));
	let thumbnail = v.pointer("/thumbnail/thumbnails").and_then(Value::as_array).and_then(|a| a.last()).and_then(|t| s(t, "url"));
	Some(Track {
		duration_seconds: duration.as_deref().and_then(seconds),
		id,
		title,
		artist,
		artist_id,
		thumbnail,
		duration,
		live: false,
		plays,
	})
}

/// Reads tracks and the continuation from a first page or a continuation page.
pub fn parse(v: &Value) -> RadioPage {
	let panel = v
		.pointer("/contents/singleColumnMusicWatchNextResultsRenderer/tabbedRenderer/watchNextTabbedResultsRenderer/tabs/0/tabRenderer/content/musicQueueRenderer/content/playlistPanelRenderer")
		.or_else(|| v.pointer("/continuationContents/playlistPanelContinuation"));
	let Some(panel) = panel else { return RadioPage::default() };
	let tracks = panel.get("contents").and_then(Value::as_array).map(|a| a.iter().filter_map(track_from_panel).collect()).unwrap_or_default();
	let next = panel.pointer("/continuations/0/nextRadioContinuationData/continuation").or_else(|| panel.pointer("/continuations/0/nextContinuationData/continuation")).and_then(Value::as_str).map(str::to_string);
	RadioPage { tracks, next }
}

fn context(region: &str) -> Value {
	let gl = if region.is_empty() { "US" } else { region };
	json!({ "client": { "clientName": "WEB_REMIX", "clientVersion": CLIENT_VERSION, "hl": "en", "gl": gl } })
}

/// A YouTube playlist's radio (YouTube Music's `RDAMPL<playlist>` watch playlist). Not every
/// playlist has one; the caller falls back to growing a radio from one of its songs.
pub fn playlist_radio(playlist_id: &str, region: &str) -> Result<RadioPage, String> {
	if playlist_id.is_empty() || !playlist_id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_') {
		return Err("That isn't a playlist ID".into());
	}
	let body = json!({ "context": context(region), "playlistId": format!("RDAMPL{playlist_id}"), "enablePersistentPlaylistPanel": true, "isAudioOnly": true });
	post(&body)
}

/// Radio seeded from `video_id`, or the next page of one when `continuation` is given.
pub fn radio(video_id: &str, continuation: Option<&str>, region: &str) -> Result<RadioPage, String> {
	let context = context(region);
	let body = match continuation {
		Some(c) => json!({ "context": context, "continuation": c, "enablePersistentPlaylistPanel": true, "isAudioOnly": true }),
		None => json!({
			"context": context,
			"videoId": video_id,
			"playlistId": format!("RDAMVM{video_id}"),
			"enablePersistentPlaylistPanel": true,
			"isAudioOnly": true,
			"tunerSettingValue": "AUTOMIX_SETTING_NORMAL",
		}),
	};
	let page = post(&body)?;
	if page.tracks.is_empty() && continuation.is_none() {
		return Err("No radio for this song".into());
	}
	Ok(page)
}

fn post(body: &Value) -> Result<RadioPage, String> {
	let started = std::time::Instant::now();
	let result = post_inner(body);
	crate::diag::record(crate::diag::RequestRecord {
		at: crate::library::Store::now(),
		method: "POST",
		route: "music.youtube.com/youtubei/v1/next".into(),
		status: if result.is_ok() { 200 } else { 0 },
		ms: started.elapsed().as_millis() as u64,
		cached: false,
		retries: 0,
		error: result.as_ref().err().map(|_| "RADIO".to_string()),
		request_id: None,
	});
	result
}

fn post_inner(body: &Value) -> Result<RadioPage, String> {
	let agent = ureq::AgentBuilder::new().timeout_connect(Duration::from_secs(10)).timeout(Duration::from_secs(20)).user_agent(UA).build();
	let res = agent
		.post(NEXT)
		.set("Content-Type", "application/json")
		.set("Origin", "https://music.youtube.com")
		.set("Cookie", "SOCS=CAI")
		.send_string(&body.to_string())
		.map_err(|e| match e {
			ureq::Error::Status(code, _) => format!("Radio: YouTube Music returned HTTP {code}"),
			ureq::Error::Transport(t) => format!("Radio: can't connect ({})", t.kind()),
		})?;
	let v: Value = res.into_string().ok().and_then(|t| serde_json::from_str(&t).ok()).ok_or("Radio: unexpected response")?;
	Ok(parse(&v))
}

#[cfg(test)]
mod tests {
	use super::*;

	fn panel_item(id: &str, byline: Value) -> Value {
		json!({ "playlistPanelVideoRenderer": {
			"videoId": id,
			"title": { "runs": [{ "text": "Around the World" }] },
			"longBylineText": byline,
			"lengthText": { "runs": [{ "text": "4:02" }] },
			"thumbnail": { "thumbnails": [{ "url": "small" }, { "url": "big" }] }
		}})
	}

	#[test]
	fn parses_first_page_and_continuation() {
		let byline = json!({ "runs": [
			{ "text": "Daft Punk", "navigationEndpoint": { "browseEndpoint": { "browseId": "UCRr1xG_2WIDs18a6cIiCxeA" } } },
			{ "text": " • " }, { "text": "141M views" }, { "text": " • " }, { "text": "1.2M likes" }
		]});
		let v = json!({ "contents": { "singleColumnMusicWatchNextResultsRenderer": { "tabbedRenderer": { "watchNextTabbedResultsRenderer": { "tabs": [
			{ "tabRenderer": { "content": { "musicQueueRenderer": { "content": { "playlistPanelRenderer": {
				"contents": [panel_item("K0HSD_i2DvA", byline), { "automixPreviewVideoRenderer": {} }],
				"continuations": [{ "nextRadioContinuationData": { "continuation": "CONT" } }]
			}}}}}}
		]}}}}});
		let page = parse(&v);
		assert_eq!(page.next.as_deref(), Some("CONT"));
		assert_eq!(page.tracks.len(), 1);
		let t = &page.tracks[0];
		assert_eq!((t.artist.as_str(), t.artist_id.as_deref()), ("Daft Punk", Some("UCRr1xG_2WIDs18a6cIiCxeA")));
		assert_eq!((t.duration_seconds, t.plays.as_deref(), t.thumbnail.as_deref()), (Some(242), Some("141M plays"), Some("big")));

		let cont = json!({ "continuationContents": { "playlistPanelContinuation": {
			"contents": [panel_item("FGBhQbmPwH8", json!({ "runs": [{ "text": "Daft Punk" }, { "text": " • " }, { "text": "Discovery" }, { "text": " • " }, { "text": "2001" }] }))]
		}}});
		let page = parse(&cont);
		assert_eq!(page.next, None);
		assert_eq!((page.tracks[0].artist.as_str(), page.tracks[0].plays.as_deref(), page.tracks[0].artist_id.as_deref()), ("Daft Punk", None, None));
	}

	#[test]
	#[ignore = "network"]
	fn live_radio() {
		let p = radio("K0HSD_i2DvA", None, "US").unwrap();
		assert!(p.tracks.len() > 10 && p.next.is_some());
		let more = radio("K0HSD_i2DvA", p.next.as_deref(), "US").unwrap();
		assert!(!more.tracks.is_empty());
	}
}
