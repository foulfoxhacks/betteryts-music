//! Understands YouTube / YouTube Music / BetterYTS links shared into the app.

use serde::Serialize;

#[derive(Serialize, Debug, PartialEq, Eq)]
#[serde(tag = "linkType", rename_all = "lowercase")]
pub enum Link {
	Video { id: String },
	Playlist { id: String, video: Option<String> },
	Artist { id: String },
	Search { query: String },
}

fn is_video_id(s: &str) -> bool {
	s.len() == 11 && s.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

fn is_list_id(s: &str) -> bool {
	(13..=64).contains(&s.len()) && s.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

fn query_param<'a>(url: &'a str, key: &str) -> Option<&'a str> {
	let q = url.split_once('?')?.1.split('#').next()?;
	q.split('&').find_map(|kv| kv.split_once('=').filter(|(k, _)| *k == key).map(|(_, v)| v))
}

fn decode(s: &str) -> String {
	let b = s.as_bytes();
	let mut out = Vec::with_capacity(b.len());
	let mut i = 0;
	while i < b.len() {
		match b[i] {
			b'+' => out.push(b' '),
			b'%' if i + 2 < b.len() => {
				match u8::from_str_radix(&s[i + 1..i + 3], 16) {
					Ok(v) => {
						out.push(v);
						i += 2;
					}
					Err(_) => out.push(b'%'),
				}
			}
			c => out.push(c),
		}
		i += 1;
	}
	String::from_utf8_lossy(&out).into_owned()
}

/// Finds the first link in shared text ("Check this out https://youtu.be/…").
pub fn resolve(text: &str) -> Option<Link> {
	let url = text.split_whitespace().find(|w| w.contains("youtu") || w.contains("foulfoxhacks.dev"))?;
	let url = url.trim_end_matches(|c: char| matches!(c, ')' | ']' | '.' | ',' | '"' | '\''));
	let path_start = url.find("://").map(|i| i + 3).unwrap_or(0);
	let rest = &url[path_start..];
	let (host, path) = rest.split_once('/').unwrap_or((rest, ""));
	let path = path.split(['?', '#']).next().unwrap_or("");
	let host = host.trim_start_matches("www.").trim_start_matches("m.").trim_start_matches("music.");

	let list = query_param(url, "list").filter(|l| is_list_id(l)).map(String::from);
	let v = query_param(url, "v").filter(|v| is_video_id(v)).map(String::from);

	let segs: Vec<&str> = path.split('/').filter(|s| !s.is_empty()).collect();
	let first_seg_video = match (host, segs.as_slice()) {
		("youtu.be", [id, ..]) if is_video_id(id) => Some(id.to_string()),
		(_, ["shorts" | "embed" | "live" | "v", id, ..]) if is_video_id(id) => Some(id.to_string()),
		(h, [id]) if h.ends_with("foulfoxhacks.dev") && is_video_id(id) => None, // ambiguous with /<query>
		(h, ["v", id]) if h.ends_with("foulfoxhacks.dev") && is_video_id(id) => Some(id.to_string()),
		_ => None,
	};
	let video = v.or(first_seg_video);

	if let Some(id) = list {
		return Some(Link::Playlist { id, video });
	}
	if let Some(id) = video {
		return Some(Link::Video { id });
	}
	match segs.as_slice() {
		["channel", id, ..] if id.starts_with("UC") => return Some(Link::Artist { id: id.to_string() }),
		[h, ..] if h.starts_with('@') && h.len() > 1 => return Some(Link::Artist { id: decode(h) }),
		_ => {}
	}
	if let Some(q) = query_param(url, "search_query").or_else(|| query_param(url, "q")) {
		let q = decode(q);
		if !q.trim().is_empty() {
			return Some(Link::Search { query: q });
		}
	}
	None
}

#[cfg(test)]
mod tests {
	use super::*;

	fn v(id: &str) -> Option<Link> {
		Some(Link::Video { id: id.into() })
	}

	#[test]
	fn video_links() {
		assert_eq!(resolve("https://youtu.be/dQw4w9WgXcQ?si=abc"), v("dQw4w9WgXcQ"));
		assert_eq!(resolve("look https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10s"), v("dQw4w9WgXcQ"));
		assert_eq!(resolve("https://music.youtube.com/watch?v=dQw4w9WgXcQ&feature=share"), v("dQw4w9WgXcQ"));
		assert_eq!(resolve("https://m.youtube.com/shorts/dQw4w9WgXcQ"), v("dQw4w9WgXcQ"));
		assert_eq!(resolve("https://yts.foulfoxhacks.dev/v/dQw4w9WgXcQ"), v("dQw4w9WgXcQ"));
		assert_eq!(resolve("https://yts.foulfoxhacks.dev/?v=dQw4w9WgXcQ"), v("dQw4w9WgXcQ"));
	}

	#[test]
	fn playlists_artists_searches() {
		assert_eq!(
			resolve("https://music.youtube.com/playlist?list=PLOHoVaTp8R7dfrJW5pumS0iD_dhlXKv17"),
			Some(Link::Playlist { id: "PLOHoVaTp8R7dfrJW5pumS0iD_dhlXKv17".into(), video: None })
		);
		assert_eq!(
			resolve("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLOHoVaTp8R7dfrJW5pumS0iD_dhlXKv17"),
			Some(Link::Playlist { id: "PLOHoVaTp8R7dfrJW5pumS0iD_dhlXKv17".into(), video: Some("dQw4w9WgXcQ".into()) })
		);
		assert_eq!(resolve("https://www.youtube.com/@LofiGirl/videos"), Some(Link::Artist { id: "@LofiGirl".into() }));
		assert_eq!(resolve("https://youtube.com/channel/UCSJ4gkVC6NrvII8umztf0Ow"), Some(Link::Artist { id: "UCSJ4gkVC6NrvII8umztf0Ow".into() }));
		assert_eq!(resolve("https://www.youtube.com/results?search_query=lofi+hip%20hop"), Some(Link::Search { query: "lofi hip hop".into() }));
		assert_eq!(resolve("no links here"), None);
		assert_eq!(resolve("https://youtube.com/watch?v=short"), None);
	}
}
