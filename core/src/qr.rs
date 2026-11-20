//! QR code encoder (byte mode, error correction level M, versions 1-40) for room invites.
//! A line-for-line port of the website's web/qr.js, which was checked against an outside
//! decoder; `matches_the_website_encoder` pins the two to the same output.

const ECC_PER_BLOCK: [i32; 41] = [-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28];
const NUM_BLOCKS: [i32; 41] = [-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49];

fn raw_modules(ver: i32) -> i32 {
	let mut n = (16 * ver + 128) * ver + 64;
	if ver >= 2 {
		let align = ver / 7 + 2;
		n -= (25 * align - 10) * align - 55;
		if ver >= 7 {
			n -= 36;
		}
	}
	n
}

fn data_codewords(ver: i32) -> i32 {
	raw_modules(ver) / 8 - ECC_PER_BLOCK[ver as usize] * NUM_BLOCKS[ver as usize]
}

fn gf_mul(x: u8, y: u8) -> u8 {
	let mut z: u32 = 0;
	for i in (0..8).rev() {
		z = (z << 1) ^ ((z >> 7) * 0x11d);
		z ^= ((y as u32 >> i) & 1) * x as u32;
	}
	z as u8
}

fn rs_divisor(degree: usize) -> Vec<u8> {
	let mut result = vec![0u8; degree];
	result[degree - 1] = 1;
	let mut root: u8 = 1;
	for _ in 0..degree {
		for j in 0..degree {
			result[j] = gf_mul(result[j], root);
			if j + 1 < degree {
				result[j] ^= result[j + 1];
			}
		}
		root = gf_mul(root, 0x02);
	}
	result
}

fn rs_remainder(data: &[u8], divisor: &[u8]) -> Vec<u8> {
	let mut result = vec![0u8; divisor.len()];
	for &b in data {
		let factor = b ^ result.remove(0);
		result.push(0);
		for (i, &coef) in divisor.iter().enumerate() {
			result[i] ^= gf_mul(coef, factor);
		}
	}
	result
}

fn bit(x: u32, i: u32) -> bool {
	(x >> i) & 1 != 0
}

pub struct Qr {
	pub size: usize,
	pub version: i32,
	pub mask: u8,
	modules: Vec<Vec<bool>>,
}

impl Qr {
	pub fn dark(&self, x: usize, y: usize) -> bool {
		self.modules[y][x]
	}

	/// One string per row, "1" for dark: easy to hand over the JNI bridge.
	pub fn rows(&self) -> Vec<String> {
		self.modules.iter().map(|r| r.iter().map(|&d| if d { '1' } else { '0' }).collect()).collect()
	}
}

pub fn encode(text: &str) -> Result<Qr, String> {
	let bytes = text.as_bytes();
	let mut ver = 1;
	while ver <= 40 {
		let count_bits = if ver <= 9 { 8 } else { 16 };
		if 4 + count_bits + bytes.len() as i32 * 8 <= data_codewords(ver) * 8 {
			break;
		}
		ver += 1;
	}
	if ver > 40 {
		return Err("Too long for a QR code".into());
	}

	let mut bits: Vec<u8> = Vec::new();
	let push = |bits: &mut Vec<u8>, val: u32, len: u32| {
		for i in (0..len).rev() {
			bits.push(((val >> i) & 1) as u8);
		}
	};
	push(&mut bits, 0b0100, 4);
	push(&mut bits, bytes.len() as u32, if ver <= 9 { 8 } else { 16 });
	for &b in bytes {
		push(&mut bits, b as u32, 8);
	}
	let capacity = (data_codewords(ver) * 8) as usize;
	let room = (capacity - bits.len()) as u32;
	push(&mut bits, 0, 4.min(room));
	let pad = ((8 - bits.len() % 8) % 8) as u32;
	push(&mut bits, 0, pad);
	let mut pad_byte: u32 = 0xec;
	while bits.len() < capacity {
		push(&mut bits, pad_byte, 8);
		pad_byte ^= 0xec ^ 0x11;
	}
	let data: Vec<u8> = bits.chunks(8).map(|c| c.iter().fold(0u8, |a, &b| (a << 1) | b)).collect();

	let num_blocks = NUM_BLOCKS[ver as usize] as usize;
	let ecc_len = ECC_PER_BLOCK[ver as usize] as usize;
	let raw = (raw_modules(ver) / 8) as usize;
	let num_short = num_blocks - raw % num_blocks;
	let short_len = raw / num_blocks;
	let divisor = rs_divisor(ecc_len);
	let mut blocks: Vec<Vec<u8>> = Vec::new();
	let mut k = 0;
	for i in 0..num_blocks {
		let len = short_len - ecc_len + if i < num_short { 0 } else { 1 };
		let mut dat = data[k..k + len].to_vec();
		k += len;
		let ecc = rs_remainder(&dat, &divisor);
		if i < num_short {
			dat.push(0);
		}
		dat.extend(ecc);
		blocks.push(dat);
	}
	let mut codewords: Vec<u8> = Vec::new();
	for i in 0..blocks[0].len() {
		for (j, block) in blocks.iter().enumerate() {
			if i != short_len - ecc_len || j >= num_short {
				codewords.push(block[i]);
			}
		}
	}

	let size = (ver * 4 + 17) as usize;
	let mut m = vec![vec![false; size]; size];
	let mut f = vec![vec![false; size]; size];
	fn set(m: &mut [Vec<bool>], f: &mut [Vec<bool>], x: usize, y: usize, dark: bool) {
		m[y][x] = dark;
		f[y][x] = true;
	}

	for i in 0..size {
		set(&mut m, &mut f, 6, i, i % 2 == 0);
		set(&mut m, &mut f, i, 6, i % 2 == 0);
	}
	for (cx, cy) in [(3i32, 3i32), (size as i32 - 4, 3), (3, size as i32 - 4)] {
		for dy in -4i32..=4 {
			for dx in -4i32..=4 {
				let (x, y) = (cx + dx, cy + dy);
				if x < 0 || y < 0 || x >= size as i32 || y >= size as i32 {
					continue;
				}
				let d = dx.abs().max(dy.abs());
				set(&mut m, &mut f, x as usize, y as usize, d != 2 && d != 4);
			}
		}
	}

	let mut align: Vec<i32> = Vec::new();
	if ver > 1 {
		let n = ver / 7 + 2;
		let step = (ver * 8 + n * 3 + 5) / (n * 4 - 4) * 2;
		align.push(6);
		let mut pos = size as i32 - 7;
		while (align.len() as i32) < n {
			align.insert(1, pos);
			pos -= step;
		}
	}
	let last = align.len().saturating_sub(1);
	for i in 0..align.len() {
		for j in 0..align.len() {
			if (i == 0 && j == 0) || (i == 0 && j == last) || (i == last && j == 0) {
				continue;
			}
			for dy in -2i32..=2 {
				for dx in -2i32..=2 {
					set(&mut m, &mut f, (align[i] + dx) as usize, (align[j] + dy) as usize, dx.abs().max(dy.abs()) != 1);
				}
			}
		}
	}

	let draw_format = |m: &mut [Vec<bool>], f: &mut [Vec<bool>], mask: u32| {
		let d = mask; // level M = 0b00
		let mut rem = d;
		for _ in 0..10 {
			rem = (rem << 1) ^ ((rem >> 9) * 0x537);
		}
		let b = ((d << 10) | rem) ^ 0x5412;
		for i in 0..=5 {
			set(m, f, 8, i as usize, bit(b, i));
		}
		set(m, f, 8, 7, bit(b, 6));
		set(m, f, 8, 8, bit(b, 7));
		set(m, f, 7, 8, bit(b, 8));
		for i in 9..15 {
			set(m, f, (14 - i) as usize, 8, bit(b, i));
		}
		for i in 0..8 {
			set(m, f, size - 1 - i as usize, 8, bit(b, i));
		}
		for i in 8..15 {
			set(m, f, 8, size - 15 + i as usize, bit(b, i));
		}
		set(m, f, 8, size - 8, true);
	};
	draw_format(&mut m, &mut f, 0);

	if ver >= 7 {
		let mut rem = ver as u32;
		for _ in 0..12 {
			rem = (rem << 1) ^ ((rem >> 11) * 0x1f25);
		}
		let b = ((ver as u32) << 12) | rem;
		for i in 0..18 {
			let a = size - 11 + (i % 3) as usize;
			let c = (i / 3) as usize;
			set(&mut m, &mut f, a, c, bit(b, i));
			set(&mut m, &mut f, c, a, bit(b, i));
		}
	}

	let mut i = 0usize;
	let mut right = size as i32 - 1;
	while right >= 1 {
		if right == 6 {
			right = 5;
		}
		for vert in 0..size {
			for j in 0..2 {
				let x = (right - j) as usize;
				let upward = ((right + 1) & 2) == 0;
				let y = if upward { size - 1 - vert } else { vert };
				if !f[y][x] && i < codewords.len() * 8 {
					m[y][x] = bit(codewords[i >> 3] as u32, 7 - (i & 7) as u32);
					i += 1;
				}
			}
		}
		right -= 2;
	}

	fn masked(mask: u8, x: usize, y: usize) -> bool {
		match mask {
			0 => (x + y) % 2 == 0,
			1 => y % 2 == 0,
			2 => x % 3 == 0,
			3 => (x + y) % 3 == 0,
			4 => (x / 3 + y / 2) % 2 == 0,
			5 => (x * y) % 2 + (x * y) % 3 == 0,
			6 => ((x * y) % 2 + (x * y) % 3) % 2 == 0,
			_ => ((x + y) % 2 + (x * y) % 3) % 2 == 0,
		}
	}
	fn apply(m: &mut [Vec<bool>], f: &[Vec<bool>], mask: u8) {
		let size = m.len();
		for y in 0..size {
			for x in 0..size {
				if !f[y][x] && masked(mask, x, y) {
					m[y][x] = !m[y][x];
				}
			}
		}
	}

	let penalty = |m: &[Vec<bool>]| -> i64 {
		let mut p: i64 = 0;
		let line = |get: &dyn Fn(usize) -> bool| -> i64 {
			let mut s = 0i64;
			let mut run = 1;
			for k in 1..=size {
				if k < size && get(k) == get(k - 1) {
					run += 1;
				} else {
					if run >= 5 {
						s += 3 + run as i64 - 5;
					}
					run = 1;
				}
			}
			let pat = [true, false, true, true, true, false, true];
			for k in 0..=size.saturating_sub(7) {
				if k + 7 > size || !pat.iter().enumerate().all(|(n, &v)| get(k + n) == v) {
					continue;
				}
				let before = k >= 4 && (1..=4).all(|n| !get(k - n));
				let after = k + 11 <= size && (7..=10).all(|n| !get(k + n));
				if before || after {
					s += 40;
				}
			}
			s
		};
		for y in 0..size {
			p += line(&|x| m[y][x]);
		}
		for x in 0..size {
			p += line(&|y| m[y][x]);
		}
		for y in 0..size - 1 {
			for x in 0..size - 1 {
				let c = m[y][x];
				if c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1] {
					p += 3;
				}
			}
		}
		let dark = m.iter().flatten().filter(|&&d| d).count() as i64;
		let total = (size * size) as i64;
		p += ((dark * 20 - total * 10).abs() / total) * 10;
		p
	};

	let mut best = 0u8;
	let mut best_score = i64::MAX;
	for mask in 0..8u8 {
		apply(&mut m, &f, mask);
		draw_format(&mut m, &mut f, mask as u32);
		let score = penalty(&m);
		if score < best_score {
			best = mask;
			best_score = score;
		}
		apply(&mut m, &f, mask);
	}
	apply(&mut m, &f, best);
	draw_format(&mut m, &mut f, best as u32);
	Ok(Qr { size, version: ver, mask: best, modules: m })
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn sizes_and_limits() {
		let q = encode("hi").unwrap();
		assert_eq!((q.size, q.version), (21, 1));
		assert_eq!(encode("https://yts.foulfoxhacks.dev/room/ABC234").unwrap().version, 3);
		assert!(encode(&"x".repeat(3000)).is_err());
		// Finder pattern corners are dark, separators light.
		assert!(q.dark(0, 0) && q.dark(6, 6) && !q.dark(7, 7) && q.dark(8, q.size - 8));
	}

	/// Same text, same matrix as web/qr.js (fingerprints produced by that encoder).
	#[test]
	fn matches_the_website_encoder() {
		for (text, want) in crate::qr_fixtures::FIXTURES {
			let q = encode(text).unwrap();
			assert_eq!(q.rows().concat(), *want, "matrix differs from web/qr.js for {text:?}");
		}
	}
}
