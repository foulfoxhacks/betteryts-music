//! BetterYTS Music core.
//!
//! Everything that isn't UI lives here so it can be unit-tested on a desktop:
//! - [`api`]: client for the BetterYTS API (yts.foulfoxhacks.dev), mapped to music-shaped models
//! - [`music`]: title/artist clean-up ("Artist - Song (Official Video)" → song + artist)
//! - [`queue`]: the play queue (shuffle, repeat, play next, reorder)
//! - [`library`]: favourites, history, playlists and followed artists, saved as JSON on the device
//! - [`bridge`]: one JSON-in/JSON-out entry point, exposed to Kotlin through [`jni_entry`]
//!
//! Playback itself is not here: the app plays through YouTube's official embedded player.

pub mod api;
pub mod bridge;
pub mod google;
pub mod library;
pub mod links;
pub mod model;
pub mod music;
pub mod plugins;
pub mod queue;
pub mod radio;
pub mod spotify;
pub mod qr;
pub mod backup;
pub mod inworld;
#[cfg(test)]
mod qr_fixtures;

mod jni_entry;
