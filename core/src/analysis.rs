//! Audio analysis for songs we can actually read: tempo (BPM) and musical key.
//!
//! Only songs stored on the device are analysed (YouTube audio is never extracted). Input is mono
//! PCM; callers downsample to ~11 kHz and pass 30–60 s from the middle of the song.

use serde::{Deserialize, Serialize};

const NAMES: [&str; 12] = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"];

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Key {
	/// 0 = C … 11 = B.
	pub tonic: u8,
	pub minor: bool,
}

impl Key {
	pub fn name(&self) -> String {
		format!("{} {}", NAMES[self.tonic as usize % 12], if self.minor { "minor" } else { "major" })
	}

	/// Camelot wheel position (DJ notation): number 1–12 and letter A (minor) / B (major).
	pub fn camelot(&self) -> (u8, char) {
		// Major keys: C=8B, G=9B, D=10B … (each fifth is +1). Minor: A=8A.
		let fifths = |pc: u8| (pc as u32 * 7 % 12) as u8; // circle-of-fifths index from C
		if self.minor {
			let relative_major = (self.tonic + 3) % 12;
			(((fifths(relative_major) + 7) % 12) + 1, 'A')
		} else {
			(((fifths(self.tonic) + 7) % 12) + 1, 'B')
		}
	}

	/// How well two keys mix: 1.0 same, 0.8 neighbours on the wheel or relative major/minor, else 0.
	pub fn compatibility(&self, other: &Key) -> f32 {
		let (a, x) = self.camelot();
		let (b, y) = other.camelot();
		let d = (a as i32 - b as i32).rem_euclid(12).min((b as i32 - a as i32).rem_euclid(12));
		match (d, x == y) {
			(0, true) => 1.0,
			(0, false) | (1, true) => 0.8,
			_ => 0.0,
		}
	}
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Features {
	pub bpm: Option<f32>,
	pub key: Option<Key>,
	/// Key detection confidence, 0–1.
	pub key_confidence: f32,
	/// Loudness/energy, 0–1 (RMS-based).
	pub energy: f32,
}

/// Onset-strength autocorrelation tempo estimate, folded into 70–180 BPM. None for near-silence.
pub fn bpm(samples: &[f32], rate: u32) -> Option<f32> {
	let hop = (rate / 100).max(1) as usize; // 10 ms frames
	let frames: Vec<f32> = samples.chunks(hop).map(|c| (c.iter().map(|x| x * x).sum::<f32>() / c.len() as f32).sqrt()).collect();
	if frames.len() < 400 || frames.iter().cloned().fold(0.0, f32::max) < 1e-4 {
		return None;
	}
	// Onset strength: positive change in log energy.
	let log: Vec<f32> = frames.iter().map(|e| (1.0 + 1000.0 * e).ln()).collect();
	let mut onset: Vec<f32> = log.windows(2).map(|w| (w[1] - w[0]).max(0.0)).collect();
	let mean = onset.iter().sum::<f32>() / onset.len() as f32;
	onset.iter_mut().for_each(|x| *x -= mean);
	let fps = rate as f32 / hop as f32;
	let lag_for = |bpm: f32| (60.0 * fps / bpm).round() as usize;
	let (lo, hi) = (lag_for(200.0), lag_for(50.0));
	let ac = |lag: usize| onset.iter().zip(&onset[lag..]).map(|(a, b)| a * b).sum::<f32>();
	let mut best = (0usize, f32::MIN);
	for lag in lo..=hi.min(onset.len() / 2) {
		// Prefer tempi near 120 BPM a little (a common perceptual prior), and reward the half-lag.
		let bpm = 60.0 * fps / lag as f32;
		let prior = (-((bpm / 120.0).log2().powi(2)) / 0.5).exp();
		let score = (ac(lag) + 0.5 * ac(lag * 2).max(0.0)) * (0.5 + prior);
		if score > best.1 {
			best = (lag, score);
		}
	}
	if best.0 == 0 || best.1 <= 0.0 {
		return None;
	}
	let mut bpm = 60.0 * fps / best.0 as f32;
	while bpm < 70.0 {
		bpm *= 2.0;
	}
	while bpm > 180.0 {
		bpm /= 2.0;
	}
	Some((bpm * 10.0).round() / 10.0)
}

/// Goertzel power of one frequency over a block.
fn goertzel(block: &[f32], freq: f32, rate: u32) -> f32 {
	let w = 2.0 * std::f32::consts::PI * freq / rate as f32;
	let c = 2.0 * w.cos();
	let (mut s1, mut s2) = (0.0f32, 0.0f32);
	for &x in block {
		let s = x + c * s1 - s2;
		s2 = s1;
		s1 = s;
	}
	s1 * s1 + s2 * s2 - c * s1 * s2
}

/// 12-bin chroma (energy per pitch class), C2–B5, normalised to sum 1.
pub fn chroma(samples: &[f32], rate: u32) -> [f32; 12] {
	let mut c = [0f32; 12];
	let block = (rate as usize / 5).max(256); // 200 ms
	for b in samples.chunks(block).filter(|b| b.len() == block).take(300) {
		for midi in 36..84 {
			let f = 440.0 * 2f32.powf((midi as f32 - 69.0) / 12.0);
			if f < rate as f32 / 2.2 {
				c[(midi % 12) as usize] += goertzel(b, f, rate).sqrt();
			}
		}
	}
	let sum: f32 = c.iter().sum();
	if sum > 0.0 {
		c.iter_mut().for_each(|x| *x /= sum);
	}
	c
}

// Krumhansl–Kessler key profiles.
const MAJOR: [f32; 12] = [6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88];
const MINOR: [f32; 12] = [6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17];

fn correlation(a: &[f32; 12], b: &[f32; 12]) -> f32 {
	let ma = a.iter().sum::<f32>() / 12.0;
	let mb = b.iter().sum::<f32>() / 12.0;
	let (mut num, mut da, mut db) = (0.0, 0.0, 0.0);
	for i in 0..12 {
		num += (a[i] - ma) * (b[i] - mb);
		da += (a[i] - ma).powi(2);
		db += (b[i] - mb).powi(2);
	}
	if da == 0.0 || db == 0.0 { 0.0 } else { num / (da * db).sqrt() }
}

/// The best-matching of the 24 keys, with a 0–1 confidence (margin over the runner-up).
pub fn key(chroma: &[f32; 12]) -> Option<(Key, f32)> {
	if chroma.iter().sum::<f32>() == 0.0 {
		return None;
	}
	let mut scores: Vec<(Key, f32)> = Vec::with_capacity(24);
	for tonic in 0..12u8 {
		for (profile, minor) in [(&MAJOR, false), (&MINOR, true)] {
			let mut rotated = [0f32; 12];
			for i in 0..12 {
				rotated[(i + tonic as usize) % 12] = profile[i];
			}
			scores.push((Key { tonic, minor }, correlation(chroma, &rotated)));
		}
	}
	scores.sort_by(|a, b| b.1.total_cmp(&a.1));
	let conf = ((scores[0].1 - scores[1].1) * 5.0).clamp(0.0, 1.0);
	Some((scores[0].0, conf))
}

pub fn analyze(samples: &[f32], rate: u32) -> Features {
	let rms = (samples.iter().map(|x| x * x).sum::<f32>() / samples.len().max(1) as f32).sqrt();
	let k = key(&chroma(samples, rate));
	Features { bpm: bpm(samples, rate), key: k.map(|k| k.0), key_confidence: k.map(|k| k.1).unwrap_or(0.0), energy: (rms * 3.0).min(1.0) }
}

#[cfg(test)]
mod tests {
	use super::*;
	const RATE: u32 = 11_025;

	fn clicks(bpm: f32, secs: f32) -> Vec<f32> {
		let n = (RATE as f32 * secs) as usize;
		let period = (60.0 / bpm * RATE as f32) as usize;
		(0..n).map(|i| if i % period < 200 { (i as f32 * 0.9).sin() * 0.8 } else { 0.0 }).collect()
	}

	fn tones(midis: &[i32], secs: f32) -> Vec<f32> {
		let n = (RATE as f32 * secs) as usize;
		(0..n)
			.map(|i| {
				let t = i as f32 / RATE as f32;
				midis.iter().enumerate().map(|(j, m)| {
					let f = 440.0 * 2f32.powf((*m as f32 - 69.0) / 12.0);
					// The first note (the tonic) is a little louder, as in real music.
					(if j == 0 { 0.5 } else { 0.3 }) * (2.0 * std::f32::consts::PI * f * t).sin()
				}).sum::<f32>()
			})
			.collect()
	}

	#[test]
	fn tempo() {
		for target in [90.0, 120.0, 128.0, 150.0] {
			let got = bpm(&clicks(target, 30.0), RATE).unwrap();
			assert!((got - target).abs() <= 2.0, "{target} → {got}");
		}
		assert_eq!(bpm(&vec![0.0; RATE as usize * 30], RATE), None, "silence has no tempo");
	}

	#[test]
	fn keys() {
		// C major scale tones over a C major triad; A minor triad with its scale.
		let c = analyze(&tones(&[48, 52, 55, 60, 62, 65, 67, 71], 6.0), RATE);
		assert_eq!(c.key.unwrap().name(), "C major");
		let a = analyze(&tones(&[45, 48, 52, 57, 59, 60, 62, 64, 65, 67], 6.0), RATE);
		assert_eq!(a.key.unwrap().name(), "A minor");
	}

	#[test]
	fn camelot_and_mixing() {
		let k = |t: u8, m: bool| Key { tonic: t, minor: m };
		assert_eq!(k(0, false).camelot(), (8, 'B'), "C major");
		assert_eq!(k(9, true).camelot(), (8, 'A'), "A minor");
		assert_eq!(k(7, false).camelot(), (9, 'B'), "G major");
		assert_eq!(k(4, true).camelot(), (9, 'A'), "E minor");
		assert_eq!(k(0, false).compatibility(&k(9, true)), 0.8, "relative minor");
		assert_eq!(k(0, false).compatibility(&k(7, false)), 0.8, "a fifth apart");
		assert_eq!(k(0, false).compatibility(&k(6, false)), 0.0, "tritone clashes");
	}
}

/// Decodes up to `secs` seconds from the middle of an audio file (MP3, FLAC, AAC/M4A, Ogg Vorbis,
/// WAV) into mono at ~11 kHz. `path` may be `/proc/self/fd/N` for an Android content URI.
pub fn decode_file(path: &str, secs: f32) -> Result<(Vec<f32>, u32), String> {
	use symphonia::core::audio::SampleBuffer;
	use symphonia::core::codecs::DecoderOptions;
	use symphonia::core::formats::{FormatOptions, SeekMode, SeekTo};
	use symphonia::core::io::MediaSourceStream;
	use symphonia::core::meta::MetadataOptions;
	use symphonia::core::probe::Hint;
	use symphonia::core::units::Time;

	let file = std::fs::File::open(path).map_err(|e| format!("Can't open the file: {e}"))?;
	let mss = MediaSourceStream::new(Box::new(file), Default::default());
	let probed = symphonia::default::get_probe().format(&Hint::new(), mss, &FormatOptions::default(), &MetadataOptions::default()).map_err(|_| "Unsupported audio format".to_string())?;
	let mut format = probed.format;
	let track = format.default_track().ok_or("No audio track")?.clone();
	let rate = track.codec_params.sample_rate.ok_or("Unknown sample rate")?;
	let mut decoder = symphonia::default::get_codecs().make(&track.codec_params, &DecoderOptions::default()).map_err(|_| "Unsupported codec".to_string())?;
	// Start in the middle: intros and fade-outs say little about tempo or key.
	if let (Some(frames), Some(tb)) = (track.codec_params.n_frames, track.codec_params.time_base) {
		let total = tb.calc_time(frames);
		let mid = (total.seconds as f64 + total.frac) / 2.0 - secs as f64 / 2.0;
		if mid > 0.0 {
			let _ = format.seek(SeekMode::Coarse, SeekTo::Time { time: Time::from(mid), track_id: Some(track.id) });
		}
	}
	let step = (rate / 11_025).max(1) as usize;
	let want = (secs * (rate as f32 / step as f32)) as usize;
	let mut out = Vec::with_capacity(want);
	let mut acc = (0f32, 0usize);
	while out.len() < want {
		let Ok(packet) = format.next_packet() else { break };
		if packet.track_id() != track.id {
			continue;
		}
		let Ok(decoded) = decoder.decode(&packet) else { continue };
		let spec = *decoded.spec();
		let ch = spec.channels.count().max(1);
		let mut buf = SampleBuffer::<f32>::new(decoded.capacity() as u64, spec);
		buf.copy_interleaved_ref(decoded);
		for frame in buf.samples().chunks(ch) {
			acc.0 += frame.iter().sum::<f32>() / ch as f32;
			acc.1 += 1;
			if acc.1 == step {
				out.push(acc.0 / step as f32);
				acc = (0.0, 0);
			}
		}
	}
	if out.len() < (rate / step as u32) as usize * 5 {
		return Err("Too little audio to analyse".into());
	}
	Ok((out, rate / step as u32))
}

#[cfg(test)]
mod file_tests {
	use super::*;

	#[test]
	fn decodes_and_analyses_a_wav() {
		// A 20 s, 44.1 kHz stereo WAV of clicks at 120 BPM.
		let rate = 44_100u32;
		let period = rate as usize / 2;
		let n = rate as usize * 20;
		let mut data = Vec::with_capacity(n * 4);
		for i in 0..n {
			let s = if i % period < 800 { ((i as f32 * 0.3).sin() * 20_000.0) as i16 } else { 0 };
			data.extend_from_slice(&s.to_le_bytes());
			data.extend_from_slice(&s.to_le_bytes());
		}
		let mut wav = Vec::new();
		wav.extend_from_slice(b"RIFF");
		wav.extend_from_slice(&(36 + data.len() as u32).to_le_bytes());
		wav.extend_from_slice(b"WAVEfmt ");
		wav.extend_from_slice(&16u32.to_le_bytes());
		wav.extend_from_slice(&1u16.to_le_bytes());
		wav.extend_from_slice(&2u16.to_le_bytes());
		wav.extend_from_slice(&rate.to_le_bytes());
		wav.extend_from_slice(&(rate * 4).to_le_bytes());
		wav.extend_from_slice(&4u16.to_le_bytes());
		wav.extend_from_slice(&16u16.to_le_bytes());
		wav.extend_from_slice(b"data");
		wav.extend_from_slice(&(data.len() as u32).to_le_bytes());
		wav.extend_from_slice(&data);
		let path = std::env::temp_dir().join(format!("btm-{}.wav", crate::library::Store::now()));
		std::fs::write(&path, wav).unwrap();
		let (pcm, r) = decode_file(path.to_str().unwrap(), 15.0).unwrap();
		assert_eq!(r, 11_025);
		let f = analyze(&pcm, r);
		assert!((f.bpm.unwrap() - 120.0).abs() <= 2.0, "{:?}", f.bpm);
		assert!(decode_file("/nonexistent.mp3", 10.0).is_err());
		let _ = std::fs::remove_file(path);
	}
}
