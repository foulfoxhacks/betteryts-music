//! The app's view of BTLink: host or join a room, send commands, poll events, all as JSON so the
//! Android and desktop apps can drive it through the bridge.
//!
//! An invite is `btlink:1:<ip>:<port>:<room key hex>`; the app shows it as a QR code and a
//! `betterytsmusic://link?i=…` link. Whoever has the invite can join, so it's shared in person.

use std::net::{SocketAddr, UdpSocket};
use std::path::Path;
use std::sync::Mutex;

use serde_json::{json, Value};

use super::udp::{Link, Request};
use super::{Command, Event, Identity, Role, RoomKey};

pub const PORT: u16 = 47830;

struct Session {
	link: Link,
	role: Role,
	invite: Option<String>,
	peers: Vec<Value>,
	clock: Option<(i64, i64)>,
	locked: bool,
}

static SESSION: Mutex<Option<Session>> = Mutex::new(None);

pub fn format_invite(addr: SocketAddr, room: &RoomKey) -> String {
	format!("btlink:1:{}:{}:{}", addr.ip(), addr.port(), room.to_hex())
}

/// Accepts the raw invite or a `betterytsmusic://link?i=…` link.
pub fn parse_invite(text: &str) -> Option<(SocketAddr, RoomKey)> {
	let t = text.trim();
	let t = t.split_once("i=").map(|(_, v)| v.split('&').next().unwrap_or(v)).unwrap_or(t);
	let t = t.replace("%3A", ":").replace("%3a", ":");
	let rest = t.strip_prefix("btlink:1:")?;
	let (addr, key) = rest.rsplit_once(':')?;
	let addr: SocketAddr = addr.parse().ok()?;
	if addr.ip().is_unspecified() || addr.port() == 0 {
		return None;
	}
	Some((addr, RoomKey::from_hex(key)?))
}

/// This device's LAN address (no packet is sent: connecting a UDP socket only picks a route).
pub fn local_ip() -> Option<std::net::IpAddr> {
	let s = UdpSocket::bind("0.0.0.0:0").ok()?;
	s.connect("192.168.0.1:9").or_else(|_| s.connect("10.0.0.1:9")).ok()?;
	let ip = s.local_addr().ok()?.ip();
	(!ip.is_unspecified() && !ip.is_loopback()).then_some(ip)
}

/// The device identity, created once and kept in the app's data folder.
pub fn identity(dir: &Path) -> Identity {
	let path = dir.join("btlink_identity");
	let hex = |b: &[u8]| b.iter().map(|x| format!("{x:02x}")).collect::<String>();
	let unhex = |s: &str| (0..s.len() / 2).map(|i| u8::from_str_radix(s.get(i * 2..i * 2 + 2)?, 16).ok()).collect::<Option<Vec<u8>>>();
	if let Ok(text) = std::fs::read_to_string(&path) {
		if let Some((a, b)) = text.trim().split_once(':') {
			if let (Some(private), Some(public)) = (unhex(a), unhex(b)) {
				if private.len() == 32 && public.len() == 32 {
					return Identity { private, public };
				}
			}
		}
	}
	let id = Identity::generate();
	let _ = std::fs::write(&path, format!("{}:{}", hex(&id.private), hex(&id.public)));
	id
}

pub fn command_from_json(v: &Value) -> Option<Command> {
	let pos = || v.get("position").and_then(Value::as_f64).filter(|p| p.is_finite() && *p >= 0.0).unwrap_or(0.0);
	Some(match v.get("type")?.as_str()? {
		"load" => Command::Load { id: v.get("id")?.as_str()?.chars().take(64).collect(), position: pos() },
		"play" => Command::Play { position: pos() },
		"pause" => Command::Pause { position: pos() },
		"seek" => Command::Seek { position: pos() },
		"rate" => Command::Rate(v.get("rate")?.as_f64()? as f32),
		"stop" => Command::Stop,
		"custom" => Command::Custom(v.get("data")?.as_str()?.chars().take(400).collect()),
		_ => return None,
	})
}

pub fn command_to_json(c: &Command) -> Value {
	match c {
		Command::Load { id, position } => json!({ "type": "load", "id": id, "position": position }),
		Command::Play { position } => json!({ "type": "play", "position": position }),
		Command::Pause { position } => json!({ "type": "pause", "position": position }),
		Command::Seek { position } => json!({ "type": "seek", "position": position }),
		Command::Rate(r) => json!({ "type": "rate", "rate": r }),
		Command::Stop => json!({ "type": "stop" }),
		Command::Custom(d) => json!({ "type": "custom", "data": d }),
	}
}

fn clean_name(name: &str) -> String {
	let n: String = name.chars().filter(|c| !c.is_control()).take(40).collect();
	if n.trim().is_empty() { "BetterYTS".into() } else { n.trim().to_string() }
}

/// Starts hosting. Returns the invite and this device's fingerprint.
pub fn host(dir: &Path, name: &str) -> Result<Value, String> {
	leave();
	let id = identity(dir);
	let fp = id.fingerprint();
	let room = RoomKey::generate();
	let link = Link::start(&format!("0.0.0.0:{PORT}"), id.clone(), room.clone(), &clean_name(name), Role::Host)
		.or_else(|_| Link::start("0.0.0.0:0", id, room.clone(), &clean_name(name), Role::Host))
		.map_err(|e| format!("Couldn't open the local link: {e}"))?;
	let ip = local_ip().ok_or("Connect to Wi-Fi (or a hotspot) to host on the local network")?;
	let invite = format_invite(SocketAddr::new(ip, link.local.port()), &room);
	*SESSION.lock().unwrap_or_else(|p| p.into_inner()) = Some(Session { link, role: Role::Host, invite: Some(invite.clone()), peers: Vec::new(), clock: None, locked: false });
	Ok(json!({ "invite": invite, "fingerprint": fp }))
}

pub fn join(dir: &Path, invite: &str, name: &str) -> Result<Value, String> {
	let (addr, room) = parse_invite(invite).ok_or("That isn't a BetterYTS link invite")?;
	leave();
	let id = identity(dir);
	let fp = id.fingerprint();
	let link = Link::start("0.0.0.0:0", id, room, &clean_name(name), Role::Member).map_err(|e| format!("Couldn't open the local link: {e}"))?;
	link.requests.send(Request::Connect(addr)).map_err(|_| "The link stopped".to_string())?;
	*SESSION.lock().unwrap_or_else(|p| p.into_inner()) = Some(Session { link, role: Role::Member, invite: None, peers: Vec::new(), clock: None, locked: false });
	Ok(json!({ "fingerprint": fp, "host": addr.to_string() }))
}

/// Host: commands for everyone; member: a request the host may apply.
pub fn send(cmd: &Value, lead_ms: i64) -> Result<(), String> {
	let c = command_from_json(cmd).ok_or("Unknown command")?;
	let guard = SESSION.lock().unwrap_or_else(|p| p.into_inner());
	let s = guard.as_ref().ok_or("Not linked")?;
	s.link.requests.send(Request::Control { cmd: c, lead_us: lead_ms.clamp(0, 2000) * 1000 }).map_err(|_| "The link stopped".into())
}

pub fn lock(on: bool) {
	if let Some(s) = SESSION.lock().unwrap_or_else(|p| p.into_inner()).as_mut() {
		s.locked = on;
		let _ = s.link.requests.send(Request::Lock(on));
	}
}

pub fn leave() {
	if let Some(s) = SESSION.lock().unwrap_or_else(|p| p.into_inner()).take() {
		let _ = s.link.requests.send(Request::Close);
	}
}

/// New events plus the current status. Control events carry `inMs`: how long from now to apply.
pub fn poll() -> Value {
	let mut guard = SESSION.lock().unwrap_or_else(|p| p.into_inner());
	let Some(s) = guard.as_mut() else { return json!({ "active": false, "events": [] }) };
	let now = s.link.epoch.elapsed().as_micros() as i64;
	let mut events = Vec::new();
	while let Ok(e) = s.link.events.try_recv() {
		match e {
			Event::Connected { peer, fingerprint, name } => {
				s.peers.retain(|p| p["address"] != json!(peer.to_string()));
				s.peers.push(json!({ "address": peer.to_string(), "name": name, "fingerprint": fingerprint }));
				events.push(json!({ "type": "connected", "name": name, "fingerprint": fingerprint }));
			}
			Event::Disconnected { peer } => {
				let name = s.peers.iter().find(|p| p["address"] == json!(peer.to_string())).and_then(|p| p["name"].as_str().map(String::from)).unwrap_or_default();
				s.peers.retain(|p| p["address"] != json!(peer.to_string()));
				events.push(json!({ "type": "disconnected", "name": name }));
			}
			Event::Control { at_local, cmd, .. } => events.push(json!({ "type": "control", "inMs": (at_local - now) / 1000, "command": command_to_json(&cmd) })),
			Event::Denied { .. } => events.push(json!({ "type": "denied" })),
			Event::Clock { offset, rtt } => s.clock = Some((offset, rtt)),
			Event::Audio { .. } => {}
		}
	}
	json!({
		"active": true,
		"role": if s.role == Role::Host { "host" } else { "member" },
		"invite": s.invite,
		"locked": s.locked,
		"peers": s.peers,
		"rttMs": s.clock.map(|c| c.1 as f64 / 1000.0),
		"events": events,
	})
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn invites() {
		let room = RoomKey([0xab; 32]);
		let inv = format_invite("192.168.1.20:47830".parse().unwrap(), &room);
		assert_eq!(parse_invite(&inv), Some(("192.168.1.20:47830".parse().unwrap(), room.clone())));
		let link = format!("betterytsmusic://link?i={}&x=1", inv.replace(':', "%3A"));
		assert_eq!(parse_invite(&link).unwrap().1, room);
		assert!(parse_invite("btlink:1:0.0.0.0:47830:00").is_none());
		assert!(parse_invite("https://example.org").is_none());
	}

	#[test]
	fn commands_and_identity() {
		for c in [json!({ "type": "load", "id": "dQw4w9WgXcQ", "position": 3.5 }), json!({ "type": "pause", "position": 1.0 }), json!({ "type": "stop" })] {
			assert_eq!(command_to_json(&command_from_json(&c).unwrap()), c);
		}
		assert!(command_from_json(&json!({ "type": "format_disk" })).is_none());
		assert_eq!(command_from_json(&json!({ "type": "seek", "position": -4 })), Some(Command::Seek { position: 0.0 }), "negative positions clamp");
		let dir = std::env::temp_dir().join(format!("btm-link-{}", crate::library::Store::now()));
		std::fs::create_dir_all(&dir).unwrap();
		let a = identity(&dir);
		assert_eq!(identity(&dir).public, a.public, "the identity is kept");
		let _ = std::fs::remove_dir_all(dir);
	}
}
