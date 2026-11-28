# BTLink — encrypted, low-latency links between BetterYTS clients on a LAN

Code: `core/src/link/` (sans-IO engine in `mod.rs`, wire format in `wire.rs`, UDP driver in `udp.rs`).

## Goals
Synchronised playback and control between BetterYTS clients in one place (a party, a classroom, a
VR meetup), with full host control, no server, and latency in the low milliseconds.

## What it carries
- **Control** (reliable): Load / Play / Pause / Seek / Rate / Stop / Custom, each with the
  host-clock instant it takes effect. Every device schedules it for the same moment.
- **Audio** (unreliable, drop-tolerant): frames for songs the host owns (device files). YouTube
  audio is never relayed: each device plays its own official embedded player, kept in sync by
  Control. That keeps BTLink within YouTube's terms.
- **Clock sync**: NTP-style ping/pong once a second; the estimate uses the lowest-RTT sample.
  Simulated 2 ms links stay within 1 ms of the host.

## Security
- Noise `XXpsk3_25519_ChaChaPoly_BLAKE2s` (the `snow` crate). Both sides prove a long-term
  X25519 key; the **room key** (32 random bytes, shared in the invite QR) is mixed in as a PSK.
  A device without it cannot join, and the host's first encrypted message (Welcome) proves the
  host holds it too.
- Each device has a fingerprint (`4F2A-9C11-…`) to compare when pairing.
- Explicit 64-bit nonces + 1024-message replay window; malformed, unauthenticated or replayed
  datagrams are dropped silently.
- Host-side limits: 32 peers, 64 pending handshakes, 30 commands/s per member; a **locked** room
  answers members' commands with Denied.

## Frames (UDP, ≤ 1200 bytes)
`[version=1][type][session u32]` then, for data, `[nonce u64][ciphertext]`.
Types: 1–3 handshake, 4 data, 5 discovery beacon (reserved).

## Not done yet
- Discovery beacons (the type is reserved); joining is by invite (address + room key) for now.
- The app screens (host/join, QR invite, member list) and the audio codec/jitter buffer.
