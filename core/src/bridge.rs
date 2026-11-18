//! One entry point for the app: `dispatch(method, json_args) -> json`.
//!
//! Every reply is `{"ok":true,"data":…}` or `{"ok":false,"error":"…"}`. Network calls run
//! without holding the state lock, so a slow request never blocks the queue or library.

use std::path::Path;
use std::sync::{Mutex, MutexGuard};

use serde::de::DeserializeOwned;
use serde_json::{json, Value};

use crate::api::{Api, Filter, DEFAULT_BASE};
use crate::library::Store;
use crate::model::{Artist, Track};
use crate::queue::Queue;
use crate::plugins::SettingsStore;
use crate::{google, links, plugins, queue};

struct State {
	api: Api,
	queue: Queue,
	store: Store,
	settings: SettingsStore,
}

static STATE: Mutex<Option<State>> = Mutex::new(None);

fn state() -> MutexGuard<'static, Option<State>> {
	STATE.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn with_state<R>(f: impl FnOnce(&mut State) -> Result<R, String>) -> Result<R, String> {
	let mut guard = state();
	let s = guard.get_or_insert_with(|| State { api: Api::new(DEFAULT_BASE), queue: Queue::default(), store: Store::in_memory(), settings: SettingsStore::in_memory() });
	f(s)
}

fn api() -> Api {
	with_state(|s| Ok(s.api.clone())).expect("infallible")
}

fn arg<T: DeserializeOwned>(args: &Value, key: &str) -> Result<T, String> {
	let v = args.get(key).cloned().unwrap_or(Value::Null);
	serde_json::from_value(v).map_err(|e| format!("Bad argument `{key}`: {e}"))
}

fn opt_str<'a>(args: &'a Value, key: &str) -> Option<&'a str> {
	args.get(key).and_then(Value::as_str).filter(|s| !s.is_empty())
}

fn to_value<T: serde::Serialize>(v: T) -> Result<Value, String> {
	serde_json::to_value(v).map_err(|e| e.to_string())
}

fn queue_op(f: impl FnOnce(&mut Queue)) -> Result<Value, String> {
	with_state(|s| {
		f(&mut s.queue);
		to_value(s.queue.view())
	})
}

/// Changes the library, saves it, and returns `{ result, library }`.
fn library_op<R: serde::Serialize>(f: impl FnOnce(&mut crate::library::Library) -> R) -> Result<Value, String> {
	with_state(|s| {
		let result = f(&mut s.store.lib);
		s.store.save()?;
		Ok(json!({ "result": to_value(result)?, "library": to_value(&s.store.lib)? }))
	})
}

fn handle(method: &str, a: &Value) -> Result<Value, String> {
	match method {
		"init" => {
			let dir: String = arg(a, "dataDir")?;
			let base = opt_str(a, "baseUrl").unwrap_or(DEFAULT_BASE).to_string();
			let mut guard = state();
			let queue = guard.take().map(|s| s.queue).unwrap_or_default();
			let mut api = Api::new(&base);
			api.region = opt_str(a, "region").unwrap_or("").chars().filter(|c| c.is_ascii_alphabetic()).take(2).collect::<String>().to_uppercase();
			*guard = Some(State { api, queue, store: Store::open(Path::new(&dir)), settings: SettingsStore::open(Path::new(&dir)) });
			Ok(json!({ "version": env!("CARGO_PKG_VERSION"), "baseUrl": base }))
		}

		// ---- browse ----
		"search" => to_value(api().search(&arg::<String>(a, "query")?, Filter::parse(opt_str(a, "filter").unwrap_or("all")), opt_str(a, "page"))?),
		"suggest" => to_value(api().suggest(&arg::<String>(a, "query")?)?),
		"video" => to_value(api().video(&arg::<String>(a, "id")?)?),
		"playlist" => to_value(api().playlist(&arg::<String>(a, "id")?, opt_str(a, "page"))?),
		"artist" => to_value(api().artist(&arg::<String>(a, "id")?, opt_str(a, "page"))?),
		"feed" => {
			let followed = with_state(|s| Ok(s.store.lib.followed.clone()))?;
			let (tracks, failed) = api().feed(&followed, 60);
			Ok(json!({ "tracks": to_value(tracks)?, "failed": failed }))
		}
		"resolveLink" => to_value(links::resolve(&arg::<String>(a, "text")?)),

		// ---- queue ----
		"queue.get" => queue_op(|_| {}),
		"queue.set" => {
			let tracks: Vec<Track> = arg(a, "tracks")?;
			let start: usize = arg::<Option<usize>>(a, "start")?.unwrap_or(0);
			let shuffle: bool = arg::<Option<bool>>(a, "shuffle")?.unwrap_or(false);
			queue_op(|q| q.set(tracks, start, shuffle))
		}
		"queue.next" => {
			let user = arg::<Option<bool>>(a, "user")?.unwrap_or(true);
			with_state(|s| {
				let moved = s.queue.next(user).is_some();
				let mut v = to_value(s.queue.view())?;
				v["ended"] = json!(!moved);
				Ok(v)
			})
		}
		"queue.previous" => queue_op(|q| {
			q.previous();
		}),
		"queue.jump" => {
			let pos: usize = arg(a, "position")?;
			queue_op(|q| {
				q.jump(pos);
			})
		}
		"queue.add" => {
			let t: Track = arg(a, "track")?;
			queue_op(|q| {
				q.add(t);
			})
		}
		"queue.addAll" => {
			let ts: Vec<Track> = arg(a, "tracks")?;
			queue_op(|q| {
				for t in ts {
					if !q.add(t) {
						break;
					}
				}
			})
		}
		"queue.playNext" => {
			let t: Track = arg(a, "track")?;
			queue_op(|q| {
				q.play_next(t);
			})
		}
		"queue.remove" => {
			let pos: usize = arg(a, "position")?;
			queue_op(|q| q.remove(pos))
		}
		"queue.move" => {
			let (from, to): (usize, usize) = (arg(a, "from")?, arg(a, "to")?);
			queue_op(|q| q.move_track(from, to))
		}
		"queue.shuffle" => queue_op(|q| {
			q.toggle_shuffle();
		}),
		"queue.repeat" => queue_op(|q| {
			q.cycle_repeat();
		}),
		"queue.clear" => queue_op(queue::Queue::clear),

		// ---- library ----
		"library.get" => with_state(|s| to_value(&s.store.lib)),
		"library.favorite" => {
			let t: Track = arg(a, "track")?;
			library_op(|l| l.toggle_favorite(t))
		}
		"library.played" => {
			let t: Track = arg(a, "track")?;
			library_op(|l| l.add_history(t, Store::now()))
		}
		"library.clearHistory" => library_op(|l| l.history.clear()),
		"library.createPlaylist" => {
			let name: String = arg::<Option<String>>(a, "name")?.unwrap_or_default();
			let tracks: Vec<Track> = arg::<Option<Vec<Track>>>(a, "tracks")?.unwrap_or_default();
			library_op(|l| l.create_playlist(&name, tracks))
		}
		"library.renamePlaylist" => {
			let (id, name): (String, String) = (arg(a, "id")?, arg(a, "name")?);
			library_op(|l| l.rename_playlist(&id, &name))
		}
		"library.deletePlaylist" => {
			let id: String = arg(a, "id")?;
			library_op(|l| l.delete_playlist(&id))
		}
		"library.addToPlaylist" => {
			let (id, t): (String, Track) = (arg(a, "id")?, arg(a, "track")?);
			library_op(|l| l.add_to_playlist(&id, t))
		}
		"library.removeFromPlaylist" => {
			let (id, index): (String, usize) = (arg(a, "id")?, arg(a, "index")?);
			library_op(|l| l.remove_from_playlist(&id, index))
		}
		"library.follow" => {
			let artist: Artist = arg(a, "artist")?;
			library_op(|l| l.toggle_follow(artist))
		}

		// ---- Google account (optional, read-only import) ----
		"google.exchange" => {
			let (code, verifier): (String, String) = (arg(a, "code")?, arg(a, "verifier")?);
			to_value(google::exchange(api().base(), &code, &verifier)?)
		}
		"google.refresh" => to_value(google::refresh(api().base(), &arg::<String>(a, "refreshToken")?)?),
		"google.revoke" => {
			google::revoke(api().base(), &arg::<String>(a, "token")?)?;
			Ok(Value::Null)
		}
		"google.profile" => to_value(google::YouTube::new(&arg::<String>(a, "accessToken")?).channel_title()?),
		"google.import" => {
			let yt = google::YouTube::new(&arg::<String>(a, "accessToken")?);
			let want = |k: &str| a.get(k).and_then(Value::as_bool).unwrap_or(true);
			// Fetch everything first (no lock held), then merge in one go.
			let playlists = if want("playlists") { yt.playlists()? } else { Vec::new() };
			let liked = if want("liked") { yt.liked()? } else { Vec::new() };
			let followed = if want("subscriptions") { yt.subscriptions()? } else { Vec::new() };
			let playlists = playlists.into_iter().map(|p| (p.id, p.title, p.tracks)).collect();
			library_op(|l| l.import_youtube(playlists, liked, followed))
		}

		// ---- settings and plugins ----
		"settings.get" => with_state(|s| Ok(s.settings.settings.view())),
		"settings.theme" => {
			let id: String = arg(a, "theme")?;
			with_state(|s| {
				if !s.settings.settings.set_theme(&id) {
					return Err(format!("Unknown theme `{id}`"));
				}
				s.settings.save()?;
				Ok(s.settings.settings.view())
			})
		}
		"plugins.set" => {
			let (id, on): (String, bool) = (arg(a, "id")?, arg(a, "enabled")?);
			with_state(|s| {
				if !s.settings.settings.set_plugin(&id, on) {
					return Err(format!("Unknown plugin `{id}`"));
				}
				s.settings.save()?;
				Ok(s.settings.settings.view())
			})
		}
		"plugin.segments" => to_value(plugins::segments(&api(), &arg::<String>(a, "id")?)?),
		"plugin.dislikes" => plugins::dislikes(&api(), &arg::<String>(a, "id")?),
		"plugin.lyrics" => to_value(plugins::lyrics(&arg::<Track>(a, "track")?)?),
		"plugin.protv" => {
			let name: String = arg::<Option<String>>(a, "name")?.unwrap_or_default();
			Ok(Value::String(plugins::protv_playlist(&name, &arg::<Vec<Track>>(a, "tracks")?)))
		}

		other => Err(format!("Unknown method `{other}`")),
	}
}

pub fn dispatch(method: &str, args: &str) -> String {
	let args: Value = if args.trim().is_empty() { Value::Null } else { serde_json::from_str(args).unwrap_or(Value::Null) };
	let reply = match handle(method, &args) {
		Ok(data) => json!({ "ok": true, "data": data }),
		Err(error) => json!({ "ok": false, "error": error }),
	};
	reply.to_string()
}

#[cfg(test)]
mod tests {
	use super::*;

	fn call(method: &str, args: Value) -> Value {
		serde_json::from_str(&dispatch(method, &args.to_string())).unwrap()
	}
	fn track(id: &str) -> Value {
		json!({ "id": id, "title": id, "artist": "A" })
	}

	// The bridge has global state, so its scenarios run in one test.
	#[test]
	fn queue_and_library_through_the_bridge() {
		let dir = std::env::temp_dir().join(format!("btm-bridge-{}", Store::now()));
		std::fs::create_dir_all(&dir).unwrap();
		let init = call("init", json!({ "dataDir": dir.to_string_lossy(), "region": "gb!" }));
		assert_eq!(init["ok"], true, "{init}");

		let q = call("queue.set", json!({ "tracks": [track("aaaaaaaaaaa"), track("bbbbbbbbbbb")], "start": 1 }));
		assert_eq!(q["data"]["current"]["id"], "bbbbbbbbbbb");
		let q = call("queue.next", json!({ "user": false }));
		assert_eq!(q["data"]["ended"], true, "end of queue reported");
		let q = call("queue.playNext", json!({ "track": track("ccccccccccc") }));
		assert_eq!(q["data"]["tracks"][2]["id"], "ccccccccccc");
		assert_eq!(call("queue.next", json!({}))["data"]["current"]["id"], "ccccccccccc");
		assert_eq!(call("queue.repeat", json!({}))["data"]["repeat"], "all");

		let fav = call("library.favorite", json!({ "track": track("aaaaaaaaaaa") }));
		assert_eq!(fav["data"]["result"], true);
		let pl = call("library.createPlaylist", json!({ "name": "Mine", "tracks": [track("aaaaaaaaaaa")] }));
		let id = pl["data"]["result"]["id"].as_str().unwrap().to_string();
		assert_eq!(call("library.addToPlaylist", json!({ "id": id, "track": track("bbbbbbbbbbb") }))["data"]["result"], true);
		call("library.played", json!({ "track": track("bbbbbbbbbbb") }));

		// Re-init (as after an app restart): the library comes back from disk.
		call("init", json!({ "dataDir": dir.to_string_lossy() }));
		let lib = call("library.get", json!({}));
		assert_eq!(lib["data"]["favorites"][0]["id"], "aaaaaaaaaaa");
		assert_eq!(lib["data"]["playlists"][0]["tracks"].as_array().unwrap().len(), 2);
		assert_eq!(lib["data"]["history"][0]["id"], "bbbbbbbbbbb", "history entries are flattened tracks");

		let bad = call("queue.jump", json!({}));
		assert_eq!(bad["ok"], false);
		assert!(bad["error"].as_str().unwrap().contains("position"));
		assert_eq!(call("nope", json!({}))["ok"], false);
		assert_eq!(call("resolveLink", json!({ "text": "https://youtu.be/dQw4w9WgXcQ" }))["data"]["linkType"], "video");

		// Settings persist across a restart too.
		assert_eq!(call("settings.theme", json!({ "theme": "mello" }))["data"]["theme"], "mello");
		assert_eq!(call("plugins.set", json!({ "id": "lyrics", "enabled": false }))["ok"], true);
		assert_eq!(call("plugins.set", json!({ "id": "nope", "enabled": true }))["ok"], false);
		call("init", json!({ "dataDir": dir.to_string_lossy() }));
		let st = call("settings.get", json!({}));
		assert_eq!(st["data"]["theme"], "mello");
		assert_eq!(st["data"]["plugins"].as_array().unwrap().iter().find(|p| p["id"] == "lyrics").unwrap()["enabled"], false);
		assert!(call("plugin.protv", json!({ "name": "Q", "tracks": [track("aaaaaaaaaaa")] }))["data"].as_str().unwrap().contains("@https://www.youtube.com/watch?v=aaaaaaaaaaa"));
		let _ = std::fs::remove_dir_all(&dir);
	}
}
