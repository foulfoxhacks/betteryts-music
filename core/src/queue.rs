//! The play queue: what's playing, what's next, shuffle and repeat.
//!
//! `items` keeps tracks in the order they were added; `order` is the play order (indexes into
//! `items`) and `pos` is where we are in it. Shuffle only reorders what's still to come, so the
//! history above the current track stays put, and turning shuffle off restores the added order.

use serde::Serialize;

use crate::model::Track;

#[derive(Serialize, Clone, Copy, Debug, PartialEq, Eq, Default)]
#[serde(rename_all = "lowercase")]
pub enum Repeat {
	#[default]
	Off,
	All,
	One,
}

/// Small xorshift RNG: enough for shuffling, and seedable for tests.
#[derive(Debug, Clone)]
pub struct Rng(u64);

impl Rng {
	pub fn seeded(seed: u64) -> Self {
		Rng(seed.max(1))
	}
	pub fn from_clock() -> Self {
		let nanos = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos() as u64).unwrap_or(0x9E37_79B9);
		Rng::seeded(nanos ^ 0x9E37_79B9_7F4A_7C15)
	}
	fn next(&mut self) -> u64 {
		let mut x = self.0;
		x ^= x << 13;
		x ^= x >> 7;
		x ^= x << 17;
		self.0 = x;
		x
	}
	fn shuffle<T>(&mut self, v: &mut [T]) {
		for i in (1..v.len()).rev() {
			let j = (self.next() % (i as u64 + 1)) as usize;
			v.swap(i, j);
		}
	}
}

#[derive(Debug, Clone)]
pub struct Queue {
	items: Vec<Track>,
	order: Vec<usize>,
	pos: usize,
	shuffle: bool,
	repeat: Repeat,
	rng: Rng,
}

/// What the UI sees: tracks in play order and the current position.
#[derive(Serialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct QueueView {
	pub tracks: Vec<Track>,
	/// Index of the current track in `tracks`; -1 when the queue is empty.
	pub position: i64,
	pub current: Option<Track>,
	pub shuffle: bool,
	pub repeat: Repeat,
	pub has_next: bool,
	pub has_previous: bool,
}

pub const MAX_QUEUE: usize = 1000;

impl Default for Queue {
	fn default() -> Self {
		Queue::new(Rng::from_clock())
	}
}

impl Queue {
	pub fn new(rng: Rng) -> Self {
		Queue { items: Vec::new(), order: Vec::new(), pos: 0, shuffle: false, repeat: Repeat::Off, rng }
	}

	pub fn current(&self) -> Option<&Track> {
		self.order.get(self.pos).map(|&i| &self.items[i])
	}

	/// Replaces the queue. `start` is an index into `tracks`.
	pub fn set(&mut self, tracks: Vec<Track>, start: usize, shuffle: bool) {
		self.items = tracks.into_iter().take(MAX_QUEUE).collect();
		self.order = (0..self.items.len()).collect();
		self.shuffle = shuffle;
		if self.items.is_empty() {
			self.pos = 0;
			return;
		}
		let start = start.min(self.items.len() - 1);
		if shuffle {
			self.order.retain(|&i| i != start);
			self.rng.shuffle(&mut self.order);
			self.order.insert(0, start);
			self.pos = 0;
		} else {
			self.pos = start;
		}
	}

	pub fn clear(&mut self) {
		self.items.clear();
		self.order.clear();
		self.pos = 0;
	}

	/// Advances. `user` is a skip button press; otherwise the track ended by itself, and repeat-one
	/// replays it. Returns the new current track, or None at the end of the queue.
	pub fn next(&mut self, user: bool) -> Option<Track> {
		if self.order.is_empty() {
			return None;
		}
		if !user && self.repeat == Repeat::One {
			return self.current().cloned();
		}
		if self.pos + 1 < self.order.len() {
			self.pos += 1;
		} else if self.repeat != Repeat::Off {
			if self.shuffle {
				// New lap, new order — but don't play the same song twice in a row.
				let last = self.order[self.pos];
				self.rng.shuffle(&mut self.order);
				if self.order.len() > 1 && self.order[0] == last {
					self.order.swap(0, 1);
				}
			}
			self.pos = 0;
		} else {
			return None;
		}
		self.current().cloned()
	}

	pub fn previous(&mut self) -> Option<Track> {
		if self.order.is_empty() {
			return None;
		}
		if self.pos > 0 {
			self.pos -= 1;
		} else if self.repeat == Repeat::All {
			self.pos = self.order.len() - 1;
		}
		self.current().cloned()
	}

	/// Jumps to a position in play order.
	pub fn jump(&mut self, position: usize) -> Option<Track> {
		if position < self.order.len() {
			self.pos = position;
		}
		self.current().cloned()
	}

	fn push_item(&mut self, track: Track) -> Option<usize> {
		if self.order.len() >= MAX_QUEUE {
			return None;
		}
		self.items.push(track);
		Some(self.items.len() - 1)
	}

	/// Adds to the end. Returns false if the queue is full.
	pub fn add(&mut self, track: Track) -> bool {
		match self.push_item(track) {
			Some(i) => {
				self.order.push(i);
				true
			}
			None => false,
		}
	}

	/// Plays right after the current track. In an empty queue it becomes the current track.
	pub fn play_next(&mut self, track: Track) -> bool {
		let was_empty = self.order.is_empty();
		match self.push_item(track) {
			Some(i) => {
				let at = if was_empty { 0 } else { self.pos + 1 };
				self.order.insert(at, i);
				true
			}
			None => false,
		}
	}

	/// Removes a position in play order. Removing the current track makes the next one current.
	pub fn remove(&mut self, position: usize) {
		if position >= self.order.len() {
			return;
		}
		self.order.remove(position);
		if position < self.pos || (self.pos >= self.order.len() && self.pos > 0) {
			self.pos -= 1;
		}
		if self.order.is_empty() {
			self.clear();
		}
	}

	/// Moves a track in play order, keeping the same track current.
	pub fn move_track(&mut self, from: usize, to: usize) {
		if from >= self.order.len() || to >= self.order.len() || from == to {
			return;
		}
		let current = self.order[self.pos];
		let item = self.order.remove(from);
		self.order.insert(to, item);
		self.pos = self.order.iter().position(|&i| i == current).unwrap_or(0);
	}

	pub fn toggle_shuffle(&mut self) -> bool {
		self.shuffle = !self.shuffle;
		if self.order.is_empty() {
			return self.shuffle;
		}
		let current = self.order[self.pos];
		if self.shuffle {
			let mut rest = self.order.split_off(self.pos + 1);
			self.rng.shuffle(&mut rest);
			self.order.extend(rest);
		} else {
			self.order.sort_unstable();
			self.pos = self.order.iter().position(|&i| i == current).unwrap_or(0);
		}
		self.shuffle
	}

	pub fn cycle_repeat(&mut self) -> Repeat {
		self.repeat = match self.repeat {
			Repeat::Off => Repeat::All,
			Repeat::All => Repeat::One,
			Repeat::One => Repeat::Off,
		};
		self.repeat
	}

	pub fn view(&self) -> QueueView {
		let tracks: Vec<Track> = self.order.iter().map(|&i| self.items[i].clone()).collect();
		let empty = tracks.is_empty();
		QueueView {
			position: if empty { -1 } else { self.pos as i64 },
			current: self.current().cloned(),
			has_next: !empty && (self.pos + 1 < tracks.len() || self.repeat != Repeat::Off),
			has_previous: !empty && (self.pos > 0 || self.repeat == Repeat::All),
			tracks,
			shuffle: self.shuffle,
			repeat: self.repeat,
		}
	}
}

#[cfg(test)]
mod tests {
	use super::*;

	fn t(id: &str) -> Track {
		Track { id: id.into(), title: id.into(), artist: "A".into(), artist_id: None, thumbnail: None, duration: None, duration_seconds: None, live: false, plays: None }
	}
	fn ids(q: &Queue) -> Vec<String> {
		q.view().tracks.into_iter().map(|t| t.id).collect()
	}
	fn cur(q: &Queue) -> &str {
		&q.current().unwrap().id
	}
	fn abc() -> Vec<Track> {
		vec![t("a"), t("b"), t("c"), t("d"), t("e")]
	}

	#[test]
	fn plays_in_order_and_stops_at_the_end() {
		let mut q = Queue::new(Rng::seeded(7));
		q.set(abc(), 3, false);
		assert_eq!(cur(&q), "d");
		assert_eq!(q.next(false).unwrap().id, "e");
		assert_eq!(q.next(false), None, "end of queue");
		assert_eq!(cur(&q), "e", "stays on the last track");
		assert_eq!(q.previous().unwrap().id, "d");
		assert!(!Queue::new(Rng::seeded(1)).view().has_next);
	}

	#[test]
	fn repeat_modes() {
		let mut q = Queue::new(Rng::seeded(7));
		q.set(abc(), 4, false);
		assert_eq!(q.cycle_repeat(), Repeat::All);
		assert_eq!(q.next(false).unwrap().id, "a", "repeat all wraps");
		assert_eq!(q.previous().unwrap().id, "e", "and wraps backwards");
		assert_eq!(q.cycle_repeat(), Repeat::One);
		assert_eq!(q.next(false).unwrap().id, "e", "repeat one replays when a track ends");
		assert_eq!(q.next(true).unwrap().id, "a", "but the skip button still skips");
		assert_eq!(q.cycle_repeat(), Repeat::Off);
	}

	#[test]
	fn shuffle_keeps_current_first_and_unshuffle_restores_order() {
		let mut q = Queue::new(Rng::seeded(42));
		q.set(abc(), 2, true);
		assert_eq!(cur(&q), "c");
		assert_eq!(q.view().position, 0);
		let mut sorted = ids(&q);
		sorted.sort();
		assert_eq!(sorted, ["a", "b", "c", "d", "e"], "every track exactly once");
		q.next(true);
		let playing = cur(&q).to_string();
		assert!(!q.toggle_shuffle());
		assert_eq!(ids(&q), ["a", "b", "c", "d", "e"]);
		assert_eq!(cur(&q), playing, "same track keeps playing");
		assert!(q.toggle_shuffle());
		assert_eq!(cur(&q), playing);
		let pos = q.view().position as usize;
		assert_eq!(&ids(&q)[..=pos], &["a", "b", "c", "d", "e"][..=pos], "history above the current track is untouched");
	}

	#[test]
	fn play_next_add_remove_and_move() {
		let mut q = Queue::new(Rng::seeded(3));
		assert!(q.play_next(t("x")), "play next on an empty queue");
		assert_eq!(cur(&q), "x");
		q.set(abc(), 0, false);
		q.play_next(t("x"));
		q.add(t("z"));
		assert_eq!(ids(&q), ["a", "x", "b", "c", "d", "e", "z"]);
		q.move_track(6, 0);
		assert_eq!(ids(&q), ["z", "a", "x", "b", "c", "d", "e"]);
		assert_eq!(cur(&q), "a", "moving keeps the current track");
		q.remove(1);
		assert_eq!(cur(&q), "x", "removing the current track plays the next");
		q.remove(0);
		assert_eq!(cur(&q), "x");
		q.jump(4);
		assert_eq!(cur(&q), "e");
		q.remove(4);
		assert_eq!(cur(&q), "d", "removing the last current track steps back");
		for _ in 0..10 {
			q.remove(0);
		}
		assert!(q.current().is_none());
		assert_eq!(q.view().position, -1);
	}

	#[test]
	fn shuffled_repeat_all_doesnt_repeat_the_last_song_immediately() {
		for seed in 1..50 {
			let mut q = Queue::new(Rng::seeded(seed));
			q.set(abc(), 0, true);
			q.cycle_repeat();
			for _ in 0..4 {
				q.next(true);
			}
			let last = cur(&q).to_string();
			let first_of_new_lap = q.next(false).unwrap().id;
			assert_ne!(first_of_new_lap, last, "seed {seed}");
		}
	}
}
