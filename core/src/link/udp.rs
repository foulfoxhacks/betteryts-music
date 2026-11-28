//! Runs an [`Endpoint`] on a real UDP socket in a background thread.
//!
//! Commands go in through a channel; events come out through another. The thread wakes every
//! 5 ms (or on a datagram) to send, retransmit and ping, which keeps added latency in the low
//! milliseconds on a LAN.

use std::net::{SocketAddr, UdpSocket};
use std::sync::mpsc::{channel, Receiver, Sender, TryRecvError};
use std::time::{Duration, Instant};

use super::{Command, Endpoint, Event, Identity, Role, RoomKey};

pub enum Request {
	Connect(SocketAddr),
	Control { cmd: Command, lead_us: i64 },
	Audio { stream: u16, seq: u32, pts: i64, codec: u8, data: Vec<u8> },
	Lock(bool),
	Close,
}

pub struct Link {
	pub local: SocketAddr,
	pub requests: Sender<Request>,
	pub events: Receiver<Event>,
	/// Monotonic origin shared with the endpoint, so callers can turn `at_local` into an Instant.
	pub epoch: Instant,
}

impl Link {
	/// Binds `bind` (e.g. "0.0.0.0:47830" for a host, "0.0.0.0:0" for a member) and starts the thread.
	pub fn start(bind: &str, identity: Identity, room: RoomKey, name: &str, role: Role) -> std::io::Result<Link> {
		let socket = UdpSocket::bind(bind)?;
		socket.set_read_timeout(Some(Duration::from_millis(5)))?;
		let local = socket.local_addr()?;
		let (req_tx, req_rx) = channel();
		let (ev_tx, ev_rx) = channel();
		let epoch = Instant::now();
		let mut ep = Endpoint::new(identity, room, name, role);
		std::thread::Builder::new().name("btlink".into()).spawn(move || {
			let now = || epoch.elapsed().as_micros() as i64;
			let mut buf = [0u8; 2048];
			loop {
				loop {
					match req_rx.try_recv() {
						Ok(Request::Connect(a)) => { let _ = ep.connect(a, now()); }
						Ok(Request::Control { cmd, lead_us }) => { ep.send_control(cmd, lead_us, now()); }
						Ok(Request::Audio { stream, seq, pts, codec, data }) => ep.send_audio(stream, seq, pts, codec, &data),
						Ok(Request::Lock(on)) => ep.locked = on,
						Ok(Request::Close) | Err(TryRecvError::Disconnected) => {
							ep.close();
							while let Some((to, d)) = ep.poll_transmit() { let _ = socket.send_to(&d, to); }
							return;
						}
						Err(TryRecvError::Empty) => break,
					}
				}
				if let Ok((n, from)) = socket.recv_from(&mut buf) {
					ep.receive(from, &buf[..n], now());
				}
				ep.tick(now());
				while let Some((to, d)) = ep.poll_transmit() {
					let _ = socket.send_to(&d, to);
				}
				while let Some(e) = ep.poll_event() {
					if ev_tx.send(e).is_err() {
						return;
					}
				}
			}
		})?;
		Ok(Link { local, requests: req_tx, events: ev_rx, epoch })
	}
}
