//! Turns YouTube video titles into song titles and artists.

/// Removes YouTube-only channel suffixes: "Artist - Topic", "ArtistVEVO", "Artist Official".
pub fn clean_artist(name: &str) -> String {
	let mut n = name.trim().to_string();
	for suffix in [" - Topic", "VEVO", " Official", " official", "Official"] {
		if n.len() > suffix.len() && n.ends_with(suffix) {
			n.truncate(n.len() - suffix.len());
			n = n.trim_end().to_string();
		}
	}
	n
}

/// Words that mark a bracketed part of a title as video packaging rather than the song name.
const NOISE: &[&str] = &[
	"official", "video", "audio", "lyric", "lyrics", "visualizer", "visualiser", "mv", "m/v", "hd", "hq", "4k", "8k",
	"music video", "clip officiel", "videoclip", "live performance video",
];

fn is_noise(inner: &str) -> bool {
	let low = inner.to_lowercase();
	NOISE.iter().any(|w| low.split(|c: char| !c.is_alphanumeric() && c != '/').any(|tok| tok == *w) || low == *w)
}

/// Drops "(Official Video)", "[Lyrics]", "【MV】" and similar, keeping "(Remix)", "(feat. X)", etc.
pub fn clean_title(title: &str) -> String {
	let mut out = String::with_capacity(title.len());
	let mut chars = title.char_indices().peekable();
	while let Some((i, c)) = chars.next() {
		let close = match c {
			'(' => ')',
			'[' => ']',
			'【' => '】',
			_ => {
				out.push(c);
				continue;
			}
		};
		match title[i + c.len_utf8()..].find(close) {
			Some(rel) => {
				let inner = &title[i + c.len_utf8()..i + c.len_utf8() + rel];
				let end = i + c.len_utf8() + rel + close.len_utf8();
				if !is_noise(inner) {
					out.push_str(&title[i..end]);
				}
				while chars.peek().is_some_and(|(j, _)| *j < end) {
					chars.next();
				}
			}
			None => out.push(c),
		}
	}
	let collapsed = out.split_whitespace().collect::<Vec<_>>().join(" ");
	collapsed.trim_matches(|c: char| c == '-' || c == '|' || c.is_whitespace()).to_string()
}

fn squash(s: &str) -> String {
	s.chars().filter(|c| c.is_alphanumeric()).flat_map(char::to_lowercase).collect()
}

/// Splits "Artist - Song" titles when the left side matches the channel (or the channel is an
/// auto-generated "- Topic"/VEVO one). Returns (song title, artist).
pub fn split_title(raw_title: &str, channel: &str) -> (String, String) {
	let artist = clean_artist(channel);
	let title = clean_title(raw_title);
	let auto_channel = channel.ends_with(" - Topic") || channel.ends_with("VEVO");
	for sep in [" - ", " – ", " — ", " | "] {
		if let Some((left, right)) = title.split_once(sep) {
			let (l, a) = (squash(left), squash(&artist));
			let matches = !l.is_empty() && !a.is_empty() && (l.contains(&a) || a.contains(&l));
			if (matches || auto_channel) && !right.trim().is_empty() {
				// Same name, different case/spacing ("lofi girl" on "Lofi Girl"): use the channel's
				// spelling, except for VEVO channels, whose names have their spaces squashed out.
				let same = l == a;
				let artist_out = if same && !channel.ends_with("VEVO") { artist.clone() } else { left.trim().to_string() };
				return (right.trim().to_string(), artist_out);
			}
			break;
		}
	}
	let artist = if artist.is_empty() { "Unknown artist".to_string() } else { artist };
	(if title.is_empty() { raw_title.trim().to_string() } else { title }, artist)
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn cleans_channel_suffixes() {
		assert_eq!(clean_artist("Daft Punk - Topic"), "Daft Punk");
		assert_eq!(clean_artist("TaylorSwiftVEVO"), "TaylorSwift");
		assert_eq!(clean_artist("Lofi Girl"), "Lofi Girl");
		assert_eq!(clean_artist("VEVO"), "VEVO");
	}

	#[test]
	fn strips_packaging_but_keeps_song_details() {
		assert_eq!(clean_title("Get Lucky (Official Video) [HD]"), "Get Lucky");
		assert_eq!(clean_title("Snowfall (Lyrics)"), "Snowfall");
		assert_eq!(clean_title("Song 【MV】"), "Song");
		assert_eq!(clean_title("Midnight City (Eric Prydz Remix)"), "Midnight City (Eric Prydz Remix)");
		assert_eq!(clean_title("Stay (feat. Justin Bieber) (Official Audio)"), "Stay (feat. Justin Bieber)");
		assert_eq!(clean_title("Unclosed (paren"), "Unclosed (paren");
	}

	#[test]
	fn splits_artist_song_titles_only_when_it_is_safe() {
		assert_eq!(split_title("Daft Punk - Get Lucky (Official Video)", "Daft Punk"), ("Get Lucky".into(), "Daft Punk".into()));
		assert_eq!(split_title("The Weeknd - Blinding Lights", "TheWeekndVEVO"), ("Blinding Lights".into(), "The Weeknd".into()));
		assert_eq!(split_title("Kavinsky – Nightcall", "Kavinsky - Topic"), ("Nightcall".into(), "Kavinsky".into()));
		// A dash that isn't "Artist - Song" stays in the title.
		assert_eq!(
			split_title("lofi hip hop radio - beats to relax/study to", "Lofi Girl"),
			("lofi hip hop radio - beats to relax/study to".into(), "Lofi Girl".into())
		);
		assert_eq!(split_title("Untitled", ""), ("Untitled".into(), "Unknown artist".into()));
		// Channel spelling wins over a lower-case title prefix; collab credits are kept.
		assert_eq!(split_title("lofi girl - snowy night", "Lofi Girl"), ("snowy night".into(), "Lofi Girl".into()));
		assert_eq!(split_title("Daft Punk ft. Pharrell - Get Lucky", "Daft Punk"), ("Get Lucky".into(), "Daft Punk ft. Pharrell".into()));
	}
}
