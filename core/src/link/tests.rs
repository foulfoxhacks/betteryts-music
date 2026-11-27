use super::*;
use std::net::SocketAddr;

/// A tiny simulated network: delivers datagrams after `latency` µs, drops every `drop_every`-th.
struct Net {
	host: Endpoint,
	member: Endpoint,
	host_addr: SocketAddr,
	member_addr: SocketAddr,
	in_flight: Vec<(i64, SocketAddr, SocketAddr, Vec<u8>)>,
	latency: i64,
	drop_every: usize,
	sent: usize,
	/// The member's clock runs this far behind the host's.
	skew: i64,
}

impl Net {
	fn new(member_room: RoomKey, drop_every: usize) -> Net {
		let room = RoomKey([7; 32]);
		Net {
			host: Endpoint::new(Identity::generate(), room, "Host phone", Role::Host),
			member: Endpoint::new(Identity::generate(), member_room, "Member PC", Role::Member),
			host_addr: "10.0.0.1:47830".parse().unwrap(),
			member_addr: "10.0.0.2:50000".parse().unwrap(),
			in_flight: Vec::new(),
			latency: 2_000,
			drop_every,
			sent: 0,
			skew: 5_000_000,
		}
	}

	/// Runs the network from `t` for `dur` µs in 1 ms steps (host clock).
	fn run(&mut self, t: &mut i64, dur: i64) {
		let end = *t + dur;
		while *t < end {
			for (from, ep) in [(self.host_addr, &mut self.host), (self.member_addr, &mut self.member)] {
				while let Some((to, d)) = ep.poll_transmit() {
					self.sent += 1;
					if self.drop_every > 0 && self.sent % self.drop_every == 0 {
						continue;
					}
					self.in_flight.push((*t + self.latency, from, to, d));
				}
			}
			let due: Vec<_> = self.in_flight.iter().filter(|p| p.0 <= *t).cloned().collect();
			self.in_flight.retain(|p| p.0 > *t);
			for (_, from, to, d) in due {
				if to == self.host_addr {
					self.host.receive(from, &d, *t);
				} else {
					self.member.receive(from, &d, *t - self.skew);
				}
			}
			self.host.tick(*t);
			self.member.tick(*t - self.skew);
			*t += 1_000;
		}
	}
}

fn events(ep: &mut Endpoint) -> Vec<Event> {
	std::iter::from_fn(|| ep.poll_event()).collect()
}

#[test]
fn join_sync_and_control() {
	let mut n = Net::new(RoomKey([7; 32]), 0);
	let mut t = 10_000_000;
	n.member.connect(n.host_addr, t - n.skew).unwrap();
	n.run(&mut t, 3_000_000);
	let he = events(&mut n.host);
	let me = events(&mut n.member);
	assert!(matches!(&he[0], Event::Connected { name, .. } if name == "Member PC"));
	assert!(me.iter().any(|e| matches!(e, Event::Connected { name, .. } if name == "Host phone")));
	let (offset, rtt) = n.member.clock().expect("clock synced");
	assert!((offset - 5_000_000).abs() <= 1_000, "offset {offset}");
	// 2 ms each way, delivered on 1 ms simulation ticks.
	assert!(rtt <= 2 * n.latency + 2_000, "rtt {rtt}");

	// The host schedules play 100 ms ahead; the member gets the same instant in its own clock.
	let at = n.host.send_control(Command::Play { position: 12.0 }, 100_000, t);
	n.run(&mut t, 50_000);
	let got = events(&mut n.member).into_iter().find_map(|e| if let Event::Control { at_local, cmd, .. } = e { Some((at_local, cmd)) } else { None }).expect("control arrived");
	assert_eq!(got.1, Command::Play { position: 12.0 });
	assert!((got.0 + n.skew - at).abs() <= 1_000, "member acts within 1 ms of the host");
}

#[test]
fn wrong_room_key_never_joins() {
	let mut n = Net::new(RoomKey([8; 32]), 0);
	let mut t = 0;
	n.member.connect(n.host_addr, t).unwrap();
	n.run(&mut t, 3_000_000);
	assert!(events(&mut n.host).is_empty());
	assert!(!events(&mut n.member).iter().any(|e| matches!(e, Event::Connected { .. })), "no Welcome without the room key");
	assert_eq!(n.host.peers(), 0);
}

#[test]
fn survives_packet_loss() {
	let mut n = Net::new(RoomKey([7; 32]), 3);
	let mut t = 0;
	n.member.connect(n.host_addr, t - n.skew).unwrap();
	n.run(&mut t, 4_000_000);
	assert_eq!(n.host.peers(), 1, "handshake retransmits through loss");
	events(&mut n.member);
	for i in 0..10 {
		n.host.send_control(Command::Seek { position: i as f64 }, 100_000, t);
	}
	n.run(&mut t, 2_000_000);
	let got: Vec<_> = events(&mut n.member).into_iter().filter_map(|e| if let Event::Control { cmd: Command::Seek { position }, .. } = e { Some(position as i32) } else { None }).collect();
	let mut sorted = got.clone();
	sorted.sort();
	sorted.dedup();
	assert_eq!(sorted, (0..10).collect::<Vec<_>>(), "every command arrives exactly once");
	assert_eq!(got.len(), 10, "no duplicates");
}

#[test]
fn replayed_datagrams_are_ignored_and_locked_rooms_deny() {
	let mut n = Net::new(RoomKey([7; 32]), 0);
	let mut t = 0;
	n.member.connect(n.host_addr, t - n.skew).unwrap();
	n.run(&mut t, 2_000_000);
	events(&mut n.member);
	events(&mut n.host);
	n.host.send_control(Command::Stop, 0, t);
	let (to, d) = n.host.poll_transmit().unwrap();
	n.member.receive(n.host_addr, &d, t);
	n.member.receive(n.host_addr, &d, t + 10);
	let stops = events(&mut n.member).iter().filter(|e| matches!(e, Event::Control { cmd: Command::Stop, .. })).count();
	assert_eq!((to, stops), (n.member_addr, 1), "a replayed datagram is dropped");

	n.host.locked = true;
	n.member.send_control(Command::Pause { position: 1.0 }, 0, t);
	n.run(&mut t, 100_000);
	assert!(events(&mut n.host).iter().all(|e| !matches!(e, Event::Control { .. })), "locked: the host ignores members");
	assert!(events(&mut n.member).iter().any(|e| matches!(e, Event::Denied { .. })));
}

#[test]
fn room_keys_and_fingerprints() {
	let k = RoomKey::generate();
	assert_ne!(k, RoomKey::generate());
	assert_eq!(RoomKey::from_hex(&k.to_hex()), Some(k));
	assert_eq!(RoomKey::from_hex("zz"), None);
	assert_eq!(Identity::generate().fingerprint().len(), 19);
}

#[test]
fn real_udp_on_loopback() {
	let room = RoomKey::generate();
	let host = udp::Link::start("127.0.0.1:0", Identity::generate(), room.clone(), "host", Role::Host).unwrap();
	let member = udp::Link::start("127.0.0.1:0", Identity::generate(), room, "member", Role::Member).unwrap();
	member.requests.send(udp::Request::Connect(host.local)).unwrap();
	let joined = member.events.recv_timeout(std::time::Duration::from_secs(5)).unwrap();
	assert!(matches!(joined, Event::Connected { .. }));
	std::thread::sleep(std::time::Duration::from_millis(50));
	host.requests.send(udp::Request::Control { cmd: Command::Load { id: "dQw4w9WgXcQ".into(), position: 0.0 }, lead_us: 50_000 }).unwrap();
	let got = std::iter::from_fn(|| member.events.recv_timeout(std::time::Duration::from_secs(3)).ok()).find(|e| matches!(e, Event::Control { .. }));
	assert!(matches!(got, Some(Event::Control { cmd: Command::Load { .. }, .. })));
	host.requests.send(udp::Request::Close).unwrap();
	member.requests.send(udp::Request::Close).unwrap();
}
