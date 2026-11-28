//! BTLink: low-latency, encrypted BetterYTS-to-BetterYTS links on a local network.
//!
//! One device hosts (its clock is the room's clock); others join. Design (docs/btlink.md):
//! - UDP datagrams ≤ 1200 bytes; no TCP head-of-line blocking.
//! - Noise `XXpsk3_25519_ChaChaPoly_BLAKE2s` handshake (the `snow` crate): both sides prove their
//!   long-term keys, and only devices holding the room key (32 random bytes, shared by QR) can join.
//!   The host's first encrypted message (Welcome) proves it holds the room key too.
//! - Explicit 64-bit nonces with a replay window, so packets may arrive out of order.
//! - Control commands carry the host-clock instant they take effect, so every device acts together;
//!   they're acknowledged and retransmitted. Audio frames are unreliable (drop-tolerant).
//! - The host enforces permissions (a locked room ignores members' commands) and limits peers,
//!   pending handshakes and command rate.
//!
//! This module is sans-IO: it turns incoming datagrams and the clock into outgoing datagrams and
//! events, which makes it deterministic to test. [`udp`] runs it on a real socket.

pub mod session;
pub mod udp;
pub mod wire;

use std::collections::{BTreeMap, HashMap, VecDeque};
use std::net::SocketAddr;

use snow::{HandshakeState, StatelessTransportState};

pub use wire::{Command, Message};
use wire::*;

pub const NOISE: &str = "Noise_XXpsk3_25519_ChaChaPoly_BLAKE2s";
pub const MAX_PEERS: usize = 32;
const MAX_PENDING: usize = 64;
const IDLE_US: i64 = 10_000_000;
const PING_US: i64 = 1_000_000;
const HS_RETRY_US: i64 = 250_000;
const MAX_TRIES: u32 = 8;
/// Commands a member may send per second.
const RATE_PER_SEC: u32 = 30;

/// This device's long-term key pair. Keep the private half in app storage.
#[derive(Clone)]
pub struct Identity {
	pub private: Vec<u8>,
	pub public: Vec<u8>,
}

impl Identity {
	pub fn generate() -> Identity {
		let kp = snow::Builder::new(NOISE.parse().expect("valid pattern")).generate_keypair().expect("OS randomness");
		Identity { private: kp.private, public: kp.public }
	}
	/// What people compare out loud or on screen ("4F2A-9C11-…") to confirm a device.
	pub fn fingerprint(&self) -> String {
		fingerprint(&self.public)
	}
}

pub fn fingerprint(public: &[u8]) -> String {
	let hex: String = public.iter().take(8).map(|b| format!("{b:02X}")).collect();
	hex.as_bytes().chunks(4).map(|c| String::from_utf8_lossy(c).into_owned()).collect::<Vec<_>>().join("-")
}

/// 32 random bytes that admit a device to a room. Shared as hex in a QR code / invite.
#[derive(Clone, PartialEq, Debug)]
pub struct RoomKey(pub [u8; 32]);

impl RoomKey {
	pub fn generate() -> RoomKey {
		// A fresh X25519 private key is 32 bytes from the OS RNG: exactly what a room key needs.
		let mut k = [0u8; 32];
		k.copy_from_slice(&Identity::generate().private[..32]);
		RoomKey(k)
	}
	pub fn to_hex(&self) -> String {
		self.0.iter().map(|b| format!("{b:02x}")).collect()
	}
	pub fn from_hex(s: &str) -> Option<RoomKey> {
		let s = s.trim();
		if s.len() != 64 {
			return None;
		}
		let mut k = [0u8; 32];
		for (i, b) in k.iter_mut().enumerate() {
			*b = u8::from_str_radix(s.get(i * 2..i * 2 + 2)?, 16).ok()?;
		}
		Some(RoomKey(k))
	}
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Role {
	Host,
	Member,
}

#[derive(Debug, Clone, PartialEq)]
pub enum Event {
	/// A device completed the handshake (host side), or we joined the host (member side).
	Connected { peer: SocketAddr, fingerprint: String, name: String },
	Disconnected { peer: SocketAddr },
	/// Apply `cmd` at local monotonic time `at_local` (µs). Members get the host's commands; the
	/// host gets members' requests (already permission-checked) to apply and rebroadcast.
	Control { peer: SocketAddr, at_local: i64, cmd: Command },
	Denied { seq: u32 },
	Audio { peer: SocketAddr, stream: u16, seq: u32, pts: i64, codec: u8, data: Vec<u8> },
	/// Member side: the host clock estimate changed (offset and round trip, µs).
	Clock { offset: i64, rtt: i64 },
}

struct Pending {
	hs: HandshakeState,
	/// The last handshake message we sent, for retransmission.
	last: Vec<u8>,
	sent_at: i64,
	tries: u32,
	initiator: bool,
}

struct Unacked {
	msg: Message,
	sent_at: i64,
	tries: u32,
}

struct Session {
	transport: StatelessTransportState,
	send_nonce: u64,
	replay: ReplayWindow,
	fingerprint: String,
	name: String,
	/// Member side: true once the host's Welcome arrived (proof it holds the room key).
	confirmed: bool,
	/// Member side: the last handshake message, resent until Welcome arrives.
	hs3: Option<Vec<u8>>,
	last_heard: i64,
	last_ping: i64,
	next_seq: u32,
	unacked: BTreeMap<u32, Unacked>,
	seen: VecDeque<u32>,
	rate_window: (i64, u32),
	clock: Clock,
}

/// One end of BTLink. Feed it datagrams ([`Endpoint::receive`]) and time ([`Endpoint::tick`]);
/// send what [`Endpoint::poll_transmit`] returns; read [`Endpoint::poll_event`].
pub struct Endpoint {
	identity: Identity,
	room: RoomKey,
	name: String,
	role: Role,
	/// Host: members' commands are refused while locked.
	pub locked: bool,
	pending: HashMap<(SocketAddr, u32), Pending>,
	sessions: HashMap<(SocketAddr, u32), Session>,
	out: VecDeque<(SocketAddr, Vec<u8>)>,
	events: VecDeque<Event>,
	session_counter: u32,
}

impl Endpoint {
	pub fn new(identity: Identity, room: RoomKey, name: &str, role: Role) -> Endpoint {
		let seed = u32::from_le_bytes(identity.public[..4].try_into().unwrap_or([1, 2, 3, 4]));
		Endpoint { identity, room, name: name.chars().take(64).collect(), role, locked: false, pending: HashMap::new(), sessions: HashMap::new(), out: VecDeque::new(), events: VecDeque::new(), session_counter: seed | 1 }
	}

	fn builder(&self) -> snow::Builder<'_> {
		snow::Builder::new(NOISE.parse().expect("valid pattern")).local_private_key(&self.identity.private).psk(3, &self.room.0)
	}

	/// Member: start joining the host at `host`.
	pub fn connect(&mut self, host: SocketAddr, now: i64) -> Result<(), String> {
		let mut hs = self.builder().build_initiator().map_err(|e| e.to_string())?;
		let mut buf = [0u8; MAX_DATAGRAM];
		let n = hs.write_message(&[], &mut buf).map_err(|e| e.to_string())?;
		self.session_counter = self.session_counter.wrapping_mul(2_654_435_761).wrapping_add(now as u32) | 1;
		let sid = self.session_counter;
		let frame = encode_frame(T_HS1, sid, 0, &buf[..n]);
		self.out.push_back((host, frame.clone()));
		self.pending.insert((host, sid), Pending { hs, last: frame, sent_at: now, tries: 1, initiator: true });
		Ok(())
	}

	pub fn poll_transmit(&mut self) -> Option<(SocketAddr, Vec<u8>)> {
		self.out.pop_front()
	}

	pub fn poll_event(&mut self) -> Option<Event> {
		self.events.pop_front()
	}

	pub fn peers(&self) -> usize {
		self.sessions.values().filter(|s| s.confirmed).count()
	}

	/// The member's estimate of the host clock (offset, round trip), µs.
	pub fn clock(&self) -> Option<(i64, i64)> {
		self.sessions.values().find_map(|s| Some((s.clock.offset()?, s.clock.rtt()?)))
	}

	/// Host time now, for scheduling (member: estimated; host: its own clock).
	pub fn host_time(&self, now: i64) -> i64 {
		match self.role {
			Role::Host => now,
			Role::Member => now + self.clock().map(|c| c.0).unwrap_or(0),
		}
	}

	/// Host: sends a command to every member, to take effect `lead_us` from now (enough for the
	/// slowest member to receive it; 80–150 ms on Wi-Fi). Returns the host-clock instant.
	/// Member: asks the host to apply it (the host decides and rebroadcasts).
	pub fn send_control(&mut self, cmd: Command, lead_us: i64, now: i64) -> i64 {
		let at = self.host_time(now) + lead_us.max(0);
		let keys: Vec<_> = self.sessions.iter().filter(|(_, s)| s.confirmed).map(|(k, _)| *k).collect();
		for k in keys {
			self.send_reliable(k, cmd.clone(), at, now);
		}
		at
	}

	/// Host: an audio frame to every member (unreliable; dropped when too large).
	pub fn send_audio(&mut self, stream: u16, seq: u32, pts: i64, codec: u8, data: &[u8]) {
		if data.len() > MAX_DATAGRAM - 64 {
			return;
		}
		let keys: Vec<_> = self.sessions.iter().filter(|(_, s)| s.confirmed).map(|(k, _)| *k).collect();
		for k in keys {
			self.send(k, &Message::Audio { stream, seq, pts, codec, data: data.to_vec() });
		}
	}

	/// Leaves (member) or closes the room (host).
	pub fn close(&mut self) {
		let keys: Vec<_> = self.sessions.keys().copied().collect();
		for k in keys {
			self.send(k, &Message::Bye);
		}
		self.sessions.clear();
		self.pending.clear();
	}

	fn send_reliable(&mut self, k: (SocketAddr, u32), cmd: Command, at: i64, now: i64) {
		let Some(s) = self.sessions.get_mut(&k) else { return };
		let seq = s.next_seq;
		s.next_seq = s.next_seq.wrapping_add(1);
		let msg = Message::Control { seq, at, cmd };
		s.unacked.insert(seq, Unacked { msg: msg.clone(), sent_at: now, tries: 1 });
		self.send(k, &msg);
	}

	fn send(&mut self, k: (SocketAddr, u32), m: &Message) {
		let Some(s) = self.sessions.get_mut(&k) else { return };
		let plain = encode(m);
		let mut buf = vec![0u8; plain.len() + 16];
		let nonce = s.send_nonce;
		s.send_nonce += 1;
		if let Ok(n) = s.transport.write_message(nonce, &plain, &mut buf) {
			self.out.push_back((k.0, encode_frame(T_DATA, k.1, nonce, &buf[..n])));
		}
	}

	/// Handles one datagram. Anything malformed, unauthenticated or replayed is dropped silently.
	pub fn receive(&mut self, from: SocketAddr, data: &[u8], now: i64) {
		let Some(f) = decode_frame(data) else { return };
		let k = (from, f.session);
		match f.kind {
			T_HS1 if self.role == Role::Host => self.on_hs1(k, f.body, now),
			T_HS2 if self.role == Role::Member => self.on_hs2(k, f.body, now),
			T_HS3 if self.role == Role::Host => self.on_hs3(k, f.body, now),
			T_DATA => self.on_data(k, f.nonce, f.body, now),
			_ => {}
		}
	}

	fn hello(&self) -> Vec<u8> {
		self.name.as_bytes().to_vec()
	}

	fn on_hs1(&mut self, k: (SocketAddr, u32), body: &[u8], now: i64) {
		if let Some(p) = self.pending.get(&k) {
			// Our reply was lost: send it again.
			self.out.push_back((k.0, p.last.clone()));
			return;
		}
		if self.sessions.contains_key(&k) || self.pending.len() >= MAX_PENDING || self.sessions.len() >= MAX_PEERS {
			return;
		}
		let Ok(mut hs) = self.builder().build_responder() else { return };
		let mut tmp = [0u8; MAX_DATAGRAM];
		if hs.read_message(body, &mut tmp).is_err() {
			return;
		}
		let mut buf = [0u8; MAX_DATAGRAM];
		let Ok(n) = hs.write_message(&self.hello(), &mut buf) else { return };
		let frame = encode_frame(T_HS2, k.1, 0, &buf[..n]);
		self.out.push_back((k.0, frame.clone()));
		self.pending.insert(k, Pending { hs, last: frame, sent_at: now, tries: 1, initiator: false });
	}

	fn on_hs2(&mut self, k: (SocketAddr, u32), body: &[u8], now: i64) {
		if let Some(s) = self.sessions.get(&k) {
			if let Some(hs3) = s.hs3.clone() {
				self.out.push_back((k.0, hs3));
			}
			return;
		}
		let Some(mut p) = self.pending.remove(&k) else { return };
		let mut payload = [0u8; MAX_DATAGRAM];
		let Ok(pn) = p.hs.read_message(body, &mut payload) else { return };
		let name = String::from_utf8_lossy(&payload[..pn]).chars().take(64).collect();
		let mut buf = [0u8; MAX_DATAGRAM];
		let Ok(n) = p.hs.write_message(&self.hello(), &mut buf) else { return };
		let frame = encode_frame(T_HS3, k.1, 0, &buf[..n]);
		self.out.push_back((k.0, frame.clone()));
		let fp = p.hs.get_remote_static().map(fingerprint).unwrap_or_default();
		let Ok(transport) = p.hs.into_stateless_transport_mode() else { return };
		self.sessions.insert(k, Session::new(transport, fp, name, false, Some(frame), now));
	}

	fn on_hs3(&mut self, k: (SocketAddr, u32), body: &[u8], now: i64) {
		if self.sessions.contains_key(&k) {
			// Our Welcome was lost and the member resent message 3: welcome it again.
			let locked = self.locked;
			self.send(k, &Message::Welcome { locked });
			return;
		}
		let Some(mut p) = self.pending.remove(&k) else { return };
		let mut payload = [0u8; MAX_DATAGRAM];
		// Fails here when the member doesn't hold the room key (psk3).
		let Ok(pn) = p.hs.read_message(body, &mut payload) else { return };
		let name: String = String::from_utf8_lossy(&payload[..pn]).chars().take(64).collect();
		let fp = p.hs.get_remote_static().map(fingerprint).unwrap_or_default();
		let Ok(transport) = p.hs.into_stateless_transport_mode() else { return };
		self.sessions.insert(k, Session::new(transport, fp.clone(), name.clone(), true, None, now));
		let locked = self.locked;
		self.send(k, &Message::Welcome { locked });
		self.events.push_back(Event::Connected { peer: k.0, fingerprint: fp, name });
	}

	fn on_data(&mut self, k: (SocketAddr, u32), nonce: u64, body: &[u8], now: i64) {
		let Some(s) = self.sessions.get_mut(&k) else { return };
		let mut buf = vec![0u8; body.len()];
		let Ok(n) = s.transport.read_message(nonce, body, &mut buf) else { return };
		if !s.replay.accept(nonce) {
			return;
		}
		s.last_heard = now;
		let Some(msg) = decode(&buf[..n]) else { return };
		match msg {
			Message::Welcome { locked: _ } => {
				if !s.confirmed {
					s.confirmed = true;
					s.hs3 = None;
					let (fp, name) = (s.fingerprint.clone(), s.name.clone());
					self.events.push_back(Event::Connected { peer: k.0, fingerprint: fp, name });
					self.send(k, &Message::Ping { t0: now });
				}
			}
			_ if !s.confirmed => {}
			Message::Ping { t0 } => {
				let t1 = now;
				self.send(k, &Message::Pong { t0, t1, t2: now });
			}
			Message::Pong { t0, t1, t2 } => {
				s.clock.add(t0, t1, t2, now);
				if let (Some(offset), Some(rtt)) = (s.clock.offset(), s.clock.rtt()) {
					self.events.push_back(Event::Clock { offset, rtt });
				}
			}
			Message::Ack { seq } => {
				s.unacked.remove(&seq);
			}
			Message::Denied { seq } => self.events.push_back(Event::Denied { seq }),
			Message::Control { seq, at, cmd } => {
				let dup = s.seen.contains(&seq);
				if !dup {
					s.seen.push_back(seq);
					if s.seen.len() > 256 {
						s.seen.pop_front();
					}
				}
				// Over the rate limit: no ack, so an honest sender retries later.
				let sec = now / 1_000_000;
				if s.rate_window.0 != sec {
					s.rate_window = (sec, 0);
				}
				s.rate_window.1 += 1;
				if s.rate_window.1 > RATE_PER_SEC {
					return;
				}
				let offset = s.clock.offset().unwrap_or(0);
				self.send(k, &Message::Ack { seq });
				if dup {
					return;
				}
				match self.role {
					Role::Member => self.events.push_back(Event::Control { peer: k.0, at_local: at - offset, cmd }),
					Role::Host if self.locked => self.send(k, &Message::Denied { seq }),
					// A member's request: the host applies it at the requested host time.
					Role::Host => self.events.push_back(Event::Control { peer: k.0, at_local: at, cmd }),
				}
			}
			Message::Audio { stream, seq, pts, codec, data } => self.events.push_back(Event::Audio { peer: k.0, stream, seq, pts, codec, data }),
			Message::Bye => {
				self.sessions.remove(&k);
				self.events.push_back(Event::Disconnected { peer: k.0 });
			}
		}
	}

	/// Retransmits, pings and timeouts. Call every ~10–20 ms.
	pub fn tick(&mut self, now: i64) {
		// Handshake retransmission.
		let mut dead = Vec::new();
		for (k, p) in self.pending.iter_mut() {
			if now - p.sent_at >= HS_RETRY_US {
				if p.tries >= MAX_TRIES {
					dead.push(*k);
				} else if p.initiator {
					p.tries += 1;
					p.sent_at = now;
					self.out.push_back((k.0, p.last.clone()));
				} else if now - p.sent_at >= HS_RETRY_US * MAX_TRIES as i64 {
					dead.push(*k);
				}
			}
		}
		for k in dead {
			self.pending.remove(&k);
		}
		let keys: Vec<_> = self.sessions.keys().copied().collect();
		for k in keys {
			let (gone, resend, hs3, ping) = {
				let s = self.sessions.get_mut(&k).expect("key exists");
				let gone = now - s.last_heard > IDLE_US;
				let rto = (2 * s.clock.rtt().unwrap_or(50_000)).clamp(30_000, 500_000);
				let mut resend = Vec::new();
				let mut drop = Vec::new();
				for (seq, u) in s.unacked.iter_mut() {
					if now - u.sent_at >= rto {
						if u.tries >= MAX_TRIES {
							drop.push(*seq);
						} else {
							u.tries += 1;
							u.sent_at = now;
							resend.push(u.msg.clone());
						}
					}
				}
				for seq in drop {
					s.unacked.remove(&seq);
				}
				let hs3 = if !s.confirmed && now - s.last_ping >= HS_RETRY_US { s.last_ping = now; s.hs3.clone() } else { None };
				let ping = self.role == Role::Member && s.confirmed && now - s.last_ping >= PING_US;
				if ping {
					s.last_ping = now;
				}
				(gone, resend, hs3, ping)
			};
			if gone {
				self.sessions.remove(&k);
				self.events.push_back(Event::Disconnected { peer: k.0 });
				continue;
			}
			for m in resend {
				self.send(k, &m);
			}
			if let Some(f) = hs3 {
				self.out.push_back((k.0, f));
			}
			if ping {
				self.send(k, &Message::Ping { t0: now });
			}
		}
	}
}

impl Session {
	fn new(transport: StatelessTransportState, fingerprint: String, name: String, confirmed: bool, hs3: Option<Vec<u8>>, now: i64) -> Session {
		Session { transport, send_nonce: 0, replay: ReplayWindow::default(), fingerprint, name, confirmed, hs3, last_heard: now, last_ping: now, next_seq: 0, unacked: BTreeMap::new(), seen: VecDeque::new(), rate_window: (0, 0), clock: Clock::default() }
	}
}

#[cfg(test)]
mod tests;
