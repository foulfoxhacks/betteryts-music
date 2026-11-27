//! BTLink wire format: datagram frames and the messages carried inside encrypted frames.
//! Everything is little-endian and length-checked; a malformed datagram is dropped, never trusted.

pub const VERSION: u8 = 1;
/// Fits one Ethernet/Wi-Fi packet without IP fragmentation.
pub const MAX_DATAGRAM: usize = 1200;
pub const MAX_TEXT: usize = 512;

pub const T_HS1: u8 = 1;
pub const T_HS2: u8 = 2;
pub const T_HS3: u8 = 3;
pub const T_DATA: u8 = 4;
pub const T_BEACON: u8 = 5;

/// `[version][type][session u32]`, then for data frames `[nonce u64]`, then the body.
#[derive(Debug, PartialEq)]
pub struct Frame<'a> {
	pub kind: u8,
	pub session: u32,
	pub nonce: u64,
	pub body: &'a [u8],
}

pub fn encode_frame(kind: u8, session: u32, nonce: u64, body: &[u8]) -> Vec<u8> {
	let mut v = Vec::with_capacity(14 + body.len());
	v.push(VERSION);
	v.push(kind);
	v.extend_from_slice(&session.to_le_bytes());
	if kind == T_DATA {
		v.extend_from_slice(&nonce.to_le_bytes());
	}
	v.extend_from_slice(body);
	v
}

pub fn decode_frame(d: &[u8]) -> Option<Frame<'_>> {
	if d.len() < 6 || d.len() > MAX_DATAGRAM || d[0] != VERSION {
		return None;
	}
	let kind = d[1];
	let session = u32::from_le_bytes(d[2..6].try_into().ok()?);
	if kind == T_DATA {
		let nonce = u64::from_le_bytes(d.get(6..14)?.try_into().ok()?);
		return Some(Frame { kind, session, nonce, body: &d[14..] });
	}
	matches!(kind, T_HS1 | T_HS2 | T_HS3 | T_BEACON).then_some(Frame { kind, session, nonce: 0, body: &d[6..] })
}

/// A playback command. The host's clock is the authority: `at` (in a Control message) is when, in
/// host time, the command takes effect, so every listener applies it at the same instant.
#[derive(Debug, Clone, PartialEq)]
pub enum Command {
	Load { id: String, position: f64 },
	Play { position: f64 },
	Pause { position: f64 },
	Seek { position: f64 },
	Rate(f32),
	Stop,
	/// Small app-defined payload (queue changes, now-playing text); JSON by convention.
	Custom(String),
}

#[derive(Debug, Clone, PartialEq)]
pub enum Message {
	/// Clock sync: member → host.
	Ping { t0: i64 },
	/// Host → member: echo t0, host receive time t1, host send time t2.
	Pong { t0: i64, t1: i64, t2: i64 },
	/// Reliable (acked and retransmitted).
	Control { seq: u32, at: i64, cmd: Command },
	Ack { seq: u32 },
	/// The host refused a member's command (room locked).
	Denied { seq: u32 },
	/// Host → member right after the handshake: proves the host holds the room key.
	Welcome { locked: bool },
	/// Unreliable media: drop-tolerant, played from a jitter buffer.
	Audio { stream: u16, seq: u32, pts: i64, codec: u8, data: Vec<u8> },
	Bye,
}

struct W(Vec<u8>);
impl W {
	fn u8(&mut self, x: u8) { self.0.push(x); }
	fn u16(&mut self, x: u16) { self.0.extend_from_slice(&x.to_le_bytes()); }
	fn u32(&mut self, x: u32) { self.0.extend_from_slice(&x.to_le_bytes()); }
	fn i64(&mut self, x: i64) { self.0.extend_from_slice(&x.to_le_bytes()); }
	fn f64(&mut self, x: f64) { self.0.extend_from_slice(&x.to_le_bytes()); }
	fn str(&mut self, s: &str) {
		let b = &s.as_bytes()[..s.len().min(MAX_TEXT)];
		self.u16(b.len() as u16);
		self.0.extend_from_slice(b);
	}
}

struct R<'a>(&'a [u8]);
impl<'a> R<'a> {
	fn take(&mut self, n: usize) -> Option<&'a [u8]> {
		if self.0.len() < n {
			return None;
		}
		let (a, b) = self.0.split_at(n);
		self.0 = b;
		Some(a)
	}
	fn u8(&mut self) -> Option<u8> { Some(self.take(1)?[0]) }
	fn u16(&mut self) -> Option<u16> { Some(u16::from_le_bytes(self.take(2)?.try_into().ok()?)) }
	fn u32(&mut self) -> Option<u32> { Some(u32::from_le_bytes(self.take(4)?.try_into().ok()?)) }
	fn i64(&mut self) -> Option<i64> { Some(i64::from_le_bytes(self.take(8)?.try_into().ok()?)) }
	fn f64(&mut self) -> Option<f64> { Some(f64::from_le_bytes(self.take(8)?.try_into().ok()?)).filter(|x: &f64| x.is_finite()) }
	fn str(&mut self) -> Option<String> {
		let n = self.u16()? as usize;
		if n > MAX_TEXT {
			return None;
		}
		String::from_utf8(self.take(n)?.to_vec()).ok()
	}
}

fn encode_cmd(w: &mut W, c: &Command) {
	match c {
		Command::Load { id, position } => { w.u8(1); w.str(id); w.f64(*position); }
		Command::Play { position } => { w.u8(2); w.f64(*position); }
		Command::Pause { position } => { w.u8(3); w.f64(*position); }
		Command::Seek { position } => { w.u8(4); w.f64(*position); }
		Command::Rate(r) => { w.u8(5); w.f64(*r as f64); }
		Command::Stop => w.u8(6),
		Command::Custom(s) => { w.u8(7); w.str(s); }
	}
}

fn decode_cmd(r: &mut R) -> Option<Command> {
	Some(match r.u8()? {
		1 => Command::Load { id: r.str()?, position: r.f64()? },
		2 => Command::Play { position: r.f64()? },
		3 => Command::Pause { position: r.f64()? },
		4 => Command::Seek { position: r.f64()? },
		5 => Command::Rate((r.f64()? as f32).clamp(0.25, 4.0)),
		6 => Command::Stop,
		7 => Command::Custom(r.str()?),
		_ => return None,
	})
}

pub fn encode(m: &Message) -> Vec<u8> {
	let mut w = W(Vec::with_capacity(32));
	match m {
		Message::Ping { t0 } => { w.u8(1); w.i64(*t0); }
		Message::Pong { t0, t1, t2 } => { w.u8(2); w.i64(*t0); w.i64(*t1); w.i64(*t2); }
		Message::Control { seq, at, cmd } => { w.u8(3); w.u32(*seq); w.i64(*at); encode_cmd(&mut w, cmd); }
		Message::Ack { seq } => { w.u8(4); w.u32(*seq); }
		Message::Denied { seq } => { w.u8(5); w.u32(*seq); }
		Message::Welcome { locked } => { w.u8(6); w.u8(*locked as u8); }
		Message::Audio { stream, seq, pts, codec, data } => {
			w.u8(7); w.u16(*stream); w.u32(*seq); w.i64(*pts); w.u8(*codec);
			w.u16(data.len() as u16);
			w.0.extend_from_slice(data);
		}
		Message::Bye => w.u8(8),
	}
	w.0
}

pub fn decode(b: &[u8]) -> Option<Message> {
	let mut r = R(b);
	let m = match r.u8()? {
		1 => Message::Ping { t0: r.i64()? },
		2 => Message::Pong { t0: r.i64()?, t1: r.i64()?, t2: r.i64()? },
		3 => Message::Control { seq: r.u32()?, at: r.i64()?, cmd: decode_cmd(&mut r)? },
		4 => Message::Ack { seq: r.u32()? },
		5 => Message::Denied { seq: r.u32()? },
		6 => Message::Welcome { locked: r.u8()? != 0 },
		7 => {
			let (stream, seq, pts, codec) = (r.u16()?, r.u32()?, r.i64()?, r.u8()?);
			let n = r.u16()? as usize;
			Message::Audio { stream, seq, pts, codec, data: r.take(n)?.to_vec() }
		}
		8 => Message::Bye,
		_ => return None,
	};
	// Trailing bytes mean a corrupt or foreign message.
	r.0.is_empty().then_some(m)
}

/// Rejects replayed or very old nonces (a sliding 1024-message window, as in WireGuard).
#[derive(Debug, Default)]
pub struct ReplayWindow {
	top: u64,
	bits: [u64; 16],
	any: bool,
}

impl ReplayWindow {
	const SIZE: u64 = 1024;

	/// True if `n` is new (and records it).
	pub fn accept(&mut self, n: u64) -> bool {
		if !self.any {
			self.any = true;
			self.top = n;
			self.set(n);
			return true;
		}
		if n > self.top {
			let shift = n - self.top;
			if shift >= Self::SIZE {
				self.bits = [0; 16];
			} else {
				for k in (self.top + 1)..=n {
					self.clear(k);
				}
			}
			self.top = n;
			self.set(n);
			return true;
		}
		if self.top - n >= Self::SIZE || self.get(n) {
			return false;
		}
		self.set(n);
		true
	}

	fn idx(n: u64) -> (usize, u64) {
		let i = (n % Self::SIZE) as usize;
		(i / 64, 1u64 << (i % 64))
	}
	fn get(&self, n: u64) -> bool { let (w, b) = Self::idx(n); self.bits[w] & b != 0 }
	fn set(&mut self, n: u64) { let (w, b) = Self::idx(n); self.bits[w] |= b; }
	fn clear(&mut self, n: u64) { let (w, b) = Self::idx(n); self.bits[w] &= !b; }
}

/// Host-clock estimate from ping/pong samples (NTP-style). The sample with the lowest round trip
/// is the least distorted by queuing, so the estimate uses the best of the recent ones.
#[derive(Debug, Default)]
pub struct Clock {
	samples: std::collections::VecDeque<(i64, i64)>,
}

impl Clock {
	pub fn add(&mut self, t0: i64, t1: i64, t2: i64, t3: i64) {
		let rtt = (t3 - t0) - (t2 - t1);
		if rtt < 0 {
			return;
		}
		let offset = ((t1 - t0) + (t2 - t3)) / 2;
		self.samples.push_back((rtt, offset));
		if self.samples.len() > 16 {
			self.samples.pop_front();
		}
	}
	/// host time = local time + offset (µs).
	pub fn offset(&self) -> Option<i64> { self.samples.iter().min_by_key(|s| s.0).map(|s| s.1) }
	pub fn rtt(&self) -> Option<i64> { self.samples.iter().map(|s| s.0).min() }
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn messages_round_trip_and_junk_is_rejected() {
		let all = [
			Message::Ping { t0: -5 },
			Message::Pong { t0: 1, t1: 2, t2: 3 },
			Message::Control { seq: 9, at: 123_456, cmd: Command::Load { id: "dQw4w9WgXcQ".into(), position: 12.5 } },
			Message::Control { seq: 10, at: 0, cmd: Command::Custom("{\"q\":1}".into()) },
			Message::Ack { seq: 9 },
			Message::Denied { seq: 4 },
			Message::Welcome { locked: true },
			Message::Audio { stream: 1, seq: 2, pts: 3, codec: 1, data: vec![1, 2, 3] },
			Message::Bye,
		];
		for m in all {
			assert_eq!(decode(&encode(&m)), Some(m.clone()), "{m:?}");
		}
		assert_eq!(decode(&[3, 1, 2]), None, "truncated");
		assert_eq!(decode(&[99]), None, "unknown kind");
		let mut extra = encode(&Message::Bye);
		extra.push(0);
		assert_eq!(decode(&extra), None, "trailing bytes");
		let mut nan = vec![3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2];
		nan.extend_from_slice(&f64::NAN.to_le_bytes());
		assert_eq!(decode(&nan), None, "non-finite positions refused");
	}

	#[test]
	fn frames() {
		let f = encode_frame(T_DATA, 7, 42, b"xyz");
		assert_eq!(decode_frame(&f), Some(Frame { kind: T_DATA, session: 7, nonce: 42, body: b"xyz" }));
		assert_eq!(decode_frame(&encode_frame(T_HS1, 1, 0, b"e")).unwrap().body, b"e");
		assert!(decode_frame(&[2, T_HS1, 0, 0, 0, 0]).is_none(), "other versions dropped");
		assert!(decode_frame(&[VERSION, 77, 0, 0, 0, 0]).is_none(), "unknown frame type");
		assert!(decode_frame(&vec![VERSION; MAX_DATAGRAM + 1]).is_none(), "oversized");
	}

	#[test]
	fn replay_window() {
		let mut w = ReplayWindow::default();
		assert!(w.accept(5));
		assert!(!w.accept(5), "replay");
		assert!(w.accept(3), "late but new");
		assert!(w.accept(2000));
		assert!(!w.accept(5), "too old");
		assert!(w.accept(1999));
		assert!(!w.accept(1999));
	}

	#[test]
	fn clock_uses_the_fastest_round_trip() {
		let mut c = Clock::default();
		// Host is 1_000_000 µs ahead. A slow sample (queued 40 ms one way) and a clean one.
		c.add(0, 1_040_000, 1_040_000, 42_000);
		c.add(100_000, 1_100_500, 1_100_600, 101_100);
		assert_eq!(c.rtt(), Some(1000));
		assert!((c.offset().unwrap() - 1_000_000).abs() < 1000);
	}
}
