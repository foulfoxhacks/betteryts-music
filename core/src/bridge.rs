//! One entry point for the app: `dispatch(method, json_args) -> json`.
//!
//! Every reply is `{"ok":true,"data":…}` or `{"ok":false,"error":"…"}`. Network calls run
//! without holding the state lock, so a slow request never blocks the queue or library.

use std::path::Path;
use std::sync::{Mutex, MutexGuard};

use serde::de::DeserializeOwned;
use serde_json::{json, Value};

use crate::api::{Api, Filter, DEFAULT_BASE};
use crate::error::CoreError;
use crate::library::Store;
use crate::model::{Artist, Track};
use crate::queue::Queue;
use crate::plugins::SettingsStore;
use crate::stations::{Station, StationStore};
use crate::{google, links, plugins, queue, radio};

struct State {
	api: Api,
	queue: Queue,
	store: Store,
	settings: SettingsStore,
	stations: StationStore,
}

static STATE: Mutex<Option<State>> = Mutex::new(None);
/// The app's data folder (from `init`), for files outside the State such as the link identity.
static DATA_DIR: Mutex<Option<String>> = Mutex::new(None);

fn data_dir() -> std::path::PathBuf {
	std::path::PathBuf::from(DATA_DIR.lock().unwrap_or_else(|p| p.into_inner()).clone().unwrap_or_else(|| std::env::temp_dir().to_string_lossy().into_owned()))
}

fn state() -> MutexGuard<'static, Option<State>> {
	STATE.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn with_state<R>(f: impl FnOnce(&mut State) -> Result<R, CoreError>) -> Result<R, CoreError> {
	let mut guard = state();
	let s = guard.get_or_insert_with(|| State { api: Api::new(DEFAULT_BASE), queue: Queue::default(), store: Store::in_memory(), settings: SettingsStore::in_memory(), stations: StationStore::in_memory() });
	f(s)
}

fn api() -> Api {
	with_state(|s| Ok(s.api.clone())).expect("infallible")
}

fn arg<T: DeserializeOwned>(args: &Value, key: &str) -> Result<T, CoreError> {
	let v = args.get(key).cloned().unwrap_or(Value::Null);
	serde_json::from_value(v).map_err(|e| CoreError::invalid(format!("Bad argument `{key}`: {e}")))
}

fn opt_str<'a>(args: &'a Value, key: &str) -> Option<&'a str> {
	args.get(key).and_then(Value::as_str).filter(|s| !s.is_empty())
}

fn to_value<T: serde::Serialize>(v: T) -> Result<Value, CoreError> {
	serde_json::to_value(v).map_err(|e| CoreError::from(e.to_string()))
}

fn queue_op(f: impl FnOnce(&mut Queue)) -> Result<Value, CoreError> {
	with_state(|s| {
		f(&mut s.queue);
		to_value(s.queue.view())
	})
}

/// The library as the app sees it: play statistics stay in the core (they're only read through
/// `insights` and `smart.*`), so the reply after every change stays small.
fn library_view(lib: &crate::library::Library) -> Result<Value, CoreError> {
	let mut v = to_value(lib)?;
	if let Some(o) = v.as_object_mut() {
		o.remove("stats");
		o.remove("listening");
		o.remove("features");
	}
	Ok(v)
}

/// Changes the library, saves it, and returns `{ result, library }`.
fn library_op<R: serde::Serialize>(f: impl FnOnce(&mut crate::library::Library) -> R) -> Result<Value, CoreError> {
	with_state(|s| {
		let result = f(&mut s.store.lib);
		s.store.save()?;
		Ok(json!({ "result": to_value(result)?, "library": library_view(&s.store.lib)? }))
	})
}

/// The artists you already listen to (compacted names), for station tuning.
fn known_artists(lib: &crate::library::Library) -> std::collections::HashSet<String> {
	let names = lib.favorites.iter().map(|t| &t.artist).chain(lib.history.iter().map(|h| &h.track.artist)).chain(lib.followed.iter().map(|a| &a.name));
	names.map(|n| crate::plugins::compact(n)).filter(|n| !n.is_empty()).collect()
}

/// Runs a station step without holding the lock (it goes to the network), then stores the result.
fn station_step(station: Station, step: impl FnOnce(&mut Station, &crate::stations::Live, &std::collections::HashSet<String>, &dyn Fn(&Track) -> bool) -> Result<crate::stations::Outcome, String>) -> Result<Value, CoreError> {
	let (api, known, settings) = with_state(|s| Ok((s.api.clone(), known_artists(&s.store.lib), s.settings.settings.clone())))?;
	let mut station = station;
	let disliked: std::collections::HashSet<String> = with_state(|s| Ok(s.store.lib.disliked.iter().map(|t| t.id.clone()).collect()))?;
	let hidden = |t: &Track| settings.hides_track(t) || disliked.contains(&t.id);
	let mut outcome = step(&mut station, &crate::stations::Live { api: &api }, &known, &hidden)?;
	// Smart mix: reorder the page by genre, tempo, key and taste, and say why.
	let mut why = serde_json::Map::new();
	if station.mix == crate::stations::Mix::Smart && !outcome.tracks.is_empty() {
		let seed = Track { id: station.radio_from.clone().unwrap_or_default(), title: station.seed.title.clone(), artist: String::new(), artist_id: None, thumbnail: None, duration: None, duration_seconds: None, live: false, plays: None };
		let (features, taste) = with_state(|s| Ok((s.store.lib.features.clone(), crate::recommend::taste(&s.store.lib))))?;
		let ranked = crate::recommend::rerank(&seed, std::mem::take(&mut outcome.tracks), &features, &taste);
		for (t, w) in ranked {
			if !w.reasons.is_empty() {
				why.insert(t.id.clone(), json!(w.reasons));
			}
			outcome.tracks.push(t);
		}
	}
	with_state(|s| {
		s.stations.stations.set_current(station);
		s.stations.stations.sync_saved();
		s.stations.save()?;
		let all = &s.stations.stations;
		let view = all.current.as_ref().map(|c| crate::stations::view(c, all));
		Ok(json!({ "station": to_value(view)?, "tracks": to_value(&outcome.tracks)?, "recovered": outcome.recovered, "filtered": outcome.filtered, "why": why }))
	})
}

fn stations_view(s: &State) -> Result<Value, CoreError> {
	let all = &s.stations.stations;
	let v = |list: &[Station]| list.iter().map(|x| crate::stations::view(x, all)).collect::<Vec<_>>();
	Ok(json!({
		"current": to_value(all.current.as_ref().map(|c| crate::stations::view(c, all)))?,
		"saved": to_value(v(&all.saved))?,
		"recent": to_value(v(&all.recent))?,
	}))
}

fn handle(method: &str, a: &Value) -> Result<Value, CoreError> {
	match method {
		"init" => {
			let dir: String = arg(a, "dataDir")?;
			*DATA_DIR.lock().unwrap_or_else(|p| p.into_inner()) = Some(dir.clone());
			let base = opt_str(a, "baseUrl").unwrap_or(DEFAULT_BASE).to_string();
			let mut guard = state();
			let queue = guard.take().map(|s| s.queue).unwrap_or_default();
			let mut api = Api::new(&base);
			api.region = opt_str(a, "region").unwrap_or("").chars().filter(|c| c.is_ascii_alphabetic()).take(2).collect::<String>().to_uppercase();
			*guard = Some(State { api, queue, store: Store::open(Path::new(&dir)), settings: SettingsStore::open(Path::new(&dir)), stations: StationStore::open(Path::new(&dir)) });
			Ok(json!({ "version": env!("CARGO_PKG_VERSION"), "baseUrl": base }))
		}

		// ---- browse ----
		"search" => {
			let mut page = api().search(&arg::<String>(a, "query")?, Filter::parse(opt_str(a, "filter").unwrap_or("all")), opt_str(a, "page"))?;
			with_state(|s| {
				let st = &s.settings.settings;
				page.items.retain(|i| match i {
					crate::model::Item::Track(t) => !st.hides_track(t),
					crate::model::Item::Artist(ar) => !st.hides_artist(ar),
					_ => true,
				});
				Ok(())
			})?;
			to_value(page)
		}
		"suggest" => to_value(api().suggest(&arg::<String>(a, "query")?)?),
		"video" => to_value(api().video(&arg::<String>(a, "id")?)?),
		"radio" => {
			let mut page = radio::radio(&arg::<String>(a, "id")?, opt_str(a, "next"), &api().region)?;
			with_state(|s| {
				page.tracks.retain(|t| !s.settings.settings.hides_track(t));
				Ok(())
			})?;
			to_value(page)
		}
		// ---- stations (radio that owns its continuation, repeats, feedback and recovery) ----
		"station.start" => {
			let seed = crate::stations::Seed { kind: arg(a, "kind")?, id: arg(a, "id")?, title: arg::<Option<String>>(a, "title")?.unwrap_or_default(), art: arg(a, "art")? };
			let mix: crate::stations::Mix = arg::<Option<crate::stations::Mix>>(a, "mix")?.unwrap_or_default();
			let own: Option<Vec<Track>> = arg(a, "tracks")?;
			// Starting a station you've had before keeps its tuning and blocked songs.
			let known = with_state(|s| Ok(s.stations.stations.find(&crate::stations::station_id(seed.kind, &seed.id))))?;
			let station = match known {
				Some(mut st) => {
					st.seen.clear();
					st.next = None;
					st.radio_from = (seed.kind == crate::stations::SeedKind::Track).then(|| seed.id.clone());
					if a.get("mix").is_some() {
						st.mix = mix;
					}
					st
				}
				None => Station::new(seed, mix, Store::now()),
			};
			station_step(station, |st, src, known, hidden| crate::stations::start(st, src, own, known, hidden))
		}
		"station.resume" => {
			let id: String = arg(a, "id")?;
			let mut st = with_state(|s| s.stations.stations.find(&id).ok_or_else(|| CoreError::invalid("That station isn't saved anymore")))?;
			st.seen.clear();
			st.next = None;
			let own: Option<Vec<Track>> = arg(a, "tracks")?;
			station_step(st, |st, src, known, hidden| crate::stations::start(st, src, own, known, hidden))
		}
		"station.more" => {
			let st = with_state(|s| s.stations.stations.current.clone().ok_or_else(|| CoreError::invalid("No station is playing")))?;
			station_step(st, |st, src, known, hidden| crate::stations::more(st, src, known, hidden))
		}
		"station.feedback" => {
			let (t, kind): (Track, crate::stations::Feedback) = (arg(a, "track")?, arg(a, "kind")?);
			with_state(|s| {
				let all = &mut s.stations.stations;
				let Some(cur) = all.current.as_mut() else { return Ok(json!({ "result": Value::Null })) };
				let result = crate::stations::feedback(cur, &t, kind, Store::now());
				all.sync_saved();
				s.stations.save()?;
				Ok(json!({ "result": result, "stations": stations_view(s)? }))
			})
		}
		"station.tune" => {
			let mix: crate::stations::Mix = arg(a, "mix")?;
			with_state(|s| {
				if let Some(cur) = s.stations.stations.current.as_mut() {
					cur.mix = mix;
				}
				s.stations.stations.sync_saved();
				s.stations.save()?;
				stations_view(s)
			})
		}
		"station.save" => with_state(|s| {
			if !s.stations.stations.save_current() {
				return Err(CoreError::from("Couldn't save this station (nothing playing, or 50 saved already)"));
			}
			s.stations.save()?;
			stations_view(s)
		}),
		"station.delete" => {
			let id: String = arg(a, "id")?;
			with_state(|s| {
				s.stations.stations.delete(&id);
				s.store.lib.pinned.retain(|k| k != &format!("station:{id}"));
				s.store.save()?;
				s.stations.save()?;
				stations_view(s)
			})
		}
		"station.stop" => with_state(|s| {
			s.stations.stations.stop();
			s.stations.save()?;
			stations_view(s)
		}),
		"stations.list" => with_state(|s| stations_view(s)),

		"playlist" => to_value(api().playlist(&arg::<String>(a, "id")?, opt_str(a, "page"))?),
		"artist" => to_value(api().artist(&arg::<String>(a, "id")?, opt_str(a, "page"))?),
		"feed" => {
			let followed = with_state(|s| Ok(s.store.lib.followed.clone()))?;
			let (mut tracks, failed) = api().feed(&followed, 60);
			with_state(|s| {
				tracks.retain(|t| !s.settings.settings.hides_track(t));
				Ok(())
			})?;
			Ok(json!({ "tracks": to_value(tracks)?, "failed": failed }))
		}
		"resolveLink" => to_value(links::resolve(&arg::<String>(a, "text")?)),
		"spotify.read" => to_value(crate::spotify::read(&api(), &arg::<String>(a, "link")?)?),
		"spotify.match" => to_value(crate::spotify::match_song(&api(), &arg::<crate::spotify::SpotifyTrack>(a, "track")?)?),

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
		"library.get" => with_state(|s| library_view(&s.store.lib)),
		"library.favorite" => {
			let t: Track = arg(a, "track")?;
			library_op(|l| l.toggle_favorite(t))
		}
		"library.played" => {
			let t: Track = arg(a, "track")?;
			// The phone's local hour and day, for insights; the core doesn't know the time zone.
			let hour: Option<u32> = arg(a, "hour")?;
			let day: Option<u32> = arg(a, "day")?;
			let now = Store::now();
			library_op(|l| {
				l.record_play(&t, now, hour, day);
				l.add_history(t, now)
			})
		}
		"library.listened" => {
			let (id, seconds): (String, u64) = (arg(a, "id")?, arg(a, "seconds")?);
			with_state(|s| {
				s.store.lib.add_listened(&id, seconds);
				s.store.save()?;
				Ok(Value::Null)
			})
		}
		"library.clearHistory" => library_op(|l| l.history.clear()),
		"library.removeHistory" => {
			let id: String = arg(a, "id")?;
			library_op(|l| l.remove_history(&id))
		}
		"library.clearStats" => library_op(|l| l.clear_stats()),
		"library.addSearch" => {
			let q: String = arg(a, "query")?;
			library_op(|l| l.add_search(&q, Store::now()))
		}
		"library.removeSearch" => {
			let q: String = arg(a, "query")?;
			library_op(|l| l.remove_search(&q))
		}
		"library.clearSearches" => library_op(|l| l.searches.clear()),
		"library.pin" => {
			let key: String = arg(a, "key")?;
			library_op(|l| l.toggle_pin(&key))
		}
		"library.movePlaylistTrack" => {
			let (id, from, to): (String, usize, usize) = (arg(a, "id")?, arg(a, "from")?, arg(a, "to")?);
			library_op(|l| l.move_in_playlist(&id, from, to))
		}
		"library.removeManyFromPlaylist" => {
			let (id, indexes): (String, Vec<usize>) = (arg(a, "id")?, arg(a, "indexes")?);
			library_op(|l| l.remove_many_from_playlist(&id, &indexes))
		}
		"library.addManyToPlaylist" => {
			let (id, tracks): (String, Vec<Track>) = (arg(a, "id")?, arg(a, "tracks")?);
			library_op(|l| l.add_many_to_playlist(&id, tracks))
		}
		"library.dedupePlaylist" => {
			let id: String = arg(a, "id")?;
			library_op(|l| l.dedupe_playlist(&id))
		}
		"library.mergePlaylists" => {
			let (target, source): (String, String) = (arg(a, "target")?, arg(a, "source")?);
			let delete: bool = arg::<Option<bool>>(a, "deleteSource")?.unwrap_or(false);
			library_op(|l| l.merge_playlists(&target, &source, delete))
		}
		"library.duplicatePlaylist" => {
			let id: String = arg(a, "id")?;
			library_op(|l| l.duplicate_playlist(&id))
		}

		// ---- smart playlists and insights (worked out on the phone) ----
		"smart.list" => with_state(|s| to_value(crate::smart::list(&s.store.lib, Store::now()))),
		"smart.tracks" => {
			let rule: Option<crate::smart::Rule> = arg(a, "rule")?;
			let id = opt_str(a, "id").map(String::from);
			with_state(|s| {
				let rule = match (rule, id) {
					(Some(r), _) => r,
					(None, Some(id)) => crate::smart::rule_for(&s.store.lib, &id).ok_or_else(|| CoreError::invalid("That smart playlist doesn't exist anymore"))?,
					(None, None) => return Err(CoreError::invalid("Bad argument `rule`")),
				};
				let tracks = crate::smart::evaluate(&s.store.lib, &rule, Store::now());
				Ok(json!({ "tracks": to_value(&tracks)?, "description": crate::smart::describe(&rule) }))
			})
		}
		"smart.preview" => {
			let rule: crate::smart::Rule = arg(a, "rule")?;
			with_state(|s| {
				let tracks = crate::smart::evaluate(&s.store.lib, &rule, Store::now());
				Ok(json!({ "count": tracks.len(), "tracks": to_value(&tracks[..tracks.len().min(25)])?, "description": crate::smart::describe(&rule.clone().clamped()) }))
			})
		}
		"smart.save" => {
			let (name, rule): (String, crate::smart::Rule) = (arg(a, "name")?, arg(a, "rule")?);
			library_op(|l| l.save_smart(&name, rule))
		}
		"smart.delete" => {
			let id: String = arg(a, "id")?;
			library_op(|l| l.delete_smart(&id))
		}
		"insights" => {
			let today: u32 = arg(a, "today")?;
			with_state(|s| to_value(crate::smart::insights(&s.store.lib, today)))
		}
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
					return Err(CoreError::invalid(format!("Unknown theme `{id}`")));
				}
				s.settings.save()?;
				Ok(s.settings.settings.view())
			})
		}
		"plugins.set" => {
			let (id, on): (String, bool) = (arg(a, "id")?, arg(a, "enabled")?);
			with_state(|s| {
				if !s.settings.settings.set_plugin(&id, on) {
					return Err(CoreError::invalid(format!("Unknown plugin `{id}`")));
				}
				s.settings.save()?;
				Ok(s.settings.settings.view())
			})
		}
		"settings.hidden" => {
			let (channels, words): (Vec<String>, Vec<String>) = (arg(a, "channels")?, arg(a, "words")?);
			with_state(|s| {
				s.settings.settings.set_hidden(&channels, &words);
				s.settings.save()?;
				Ok(s.settings.settings.view())
			})
		}
		"settings.hideArtist" => {
			let name: String = arg(a, "name")?;
			let id = opt_str(a, "id").map(String::from);
			with_state(|s| {
				s.settings.settings.toggle_hidden_artist(&name, id.as_deref());
				s.settings.save()?;
				Ok(s.settings.settings.view())
			})
		}
		"backup.export" => {
			let version: String = arg::<Option<String>>(a, "appVersion")?.unwrap_or_default();
			with_state(|s| Ok(json!({ "text": crate::backup::export(&s.store.lib, &s.settings.settings, &version, Store::now()).to_string() })))
		}
		"backup.preview" => {
			let text: String = arg(a, "text")?;
			let mode = crate::backup::Mode::parse(opt_str(a, "mode"));
			with_state(|s| {
				let (info, summary) = crate::backup::preview(&text, &s.store, &s.settings.settings, mode)?;
				Ok(json!({ "info": to_value(info)?, "summary": to_value(summary)? }))
			})
		}
		"backup.import" => {
			let text: String = arg(a, "text")?;
			let mode = crate::backup::Mode::parse(opt_str(a, "mode"));
			with_state(|s| {
				let sum = crate::backup::import_with(&text, &mut s.store, &mut s.settings.settings, mode)?;
				s.store.save()?;
				s.settings.save()?;
				Ok(json!({ "summary": sum, "settings": s.settings.settings.view() }))
			})
		}
		"spotify.clientId" => Ok(json!({ "clientId": crate::spotify_account::client_id(&api())? })),
		"spotify.exchange" => {
			let (client, redirect, code, verifier): (String, String, String, String) = (arg(a, "clientId")?, arg(a, "redirectUri")?, arg(a, "code")?, arg(a, "verifier")?);
			Ok(json!({ "token": crate::spotify_account::exchange(&client, &redirect, &code, &verifier)? }))
		}
		"spotify.playlists" => to_value(crate::spotify_account::playlists(&arg::<String>(a, "token")?)?),
		"spotify.tracks" => to_value(crate::spotify_account::tracks(&arg::<String>(a, "token")?, &arg::<String>(a, "id")?, &arg::<String>(a, "name")?)?),
		"inworld.check" => to_value(crate::inworld::check(&api(), &arg::<String>(a, "code")?)?),
		"inworld.preview" | "inworld.send" => {
			let ids: Vec<String> = arg::<Option<Vec<String>>>(a, "playlistIds")?.unwrap_or_default();
			let include_liked: bool = arg::<Option<bool>>(a, "liked")?.unwrap_or(false);
			let include_queue: bool = arg::<Option<bool>>(a, "queue")?.unwrap_or(false);
			let save: bool = arg::<Option<bool>>(a, "save")?.unwrap_or(false);
			let lists = with_state(|s| {
				let mut out: Vec<(String, Vec<Track>)> = Vec::new();
				if include_queue {
					let q = s.queue.view();
					if !q.tracks.is_empty() {
						out.push(("Now playing queue".to_string(), q.tracks));
					}
				}
				if include_liked && !s.store.lib.favorites.is_empty() {
					out.push(("Liked songs".to_string(), s.store.lib.favorites.clone()));
				}
				for p in s.store.lib.playlists.iter().filter(|p| ids.contains(&p.id)) {
					out.push((p.name.clone(), p.tracks.clone()));
				}
				Ok(out)
			})?;
			if method == "inworld.preview" {
				let (_, pv) = crate::inworld::payload("XXXX-XXXX", "youtube", save, &lists)?;
				return to_value(pv);
			}
			let (code, service): (String, String) = (arg(a, "code")?, arg(a, "service")?);
			let (playlists, tracks) = crate::inworld::send(&api(), &code, &service, save, &lists)?;
			let names: Vec<String> = lists.iter().map(|(n, _)| n.clone()).collect();
			let lib = library_op(|l| l.record_world_send(crate::library::WorldSend { at: Store::now(), service: service.clone(), playlists, tracks, saved: save, expires_at: None, names }))?;
			Ok(json!({ "playlists": playlists, "tracks": tracks, "library": lib["library"] }))
		}
		"inworld.forget" => library_op(|l| l.world_sends.clear()),
		"qr" => {
			let q = crate::qr::encode(&arg::<String>(a, "text")?)?;
			Ok(json!({ "size": q.size, "rows": q.rows() }))
		}
		"plugins.category" => {
			let (id, on): (String, bool) = (arg(a, "id")?, arg(a, "enabled")?);
			with_state(|s| {
				if !s.settings.settings.set_category(&id, on) {
					return Err(CoreError::invalid(format!("Unknown category `{id}`")));
				}
				s.settings.save()?;
				Ok(s.settings.settings.view())
			})
		}
		"plugins.install" => {
			let manifest: String = arg(a, "manifest")?;
			with_state(|s| {
				s.settings.settings.install(&manifest)?;
				s.settings.save()?;
				Ok(s.settings.settings.view())
			})
		}
		"plugins.remove" => {
			let id: String = arg(a, "id")?;
			with_state(|s| {
				if !s.settings.settings.remove(&id) {
					return Err(CoreError::invalid(format!("No community plugin `{id}`")));
				}
				s.settings.save()?;
				Ok(s.settings.settings.view())
			})
		}
		"rooms.new" => {
			let v = api().get_json("/api/rooms/new", &[])?;
			Ok(json!({ "code": v.get("code"), "hostKey": v.get("hostKey") }))
		}
		"plugin.segments" => {
			let cats = with_state(|s| Ok(s.settings.settings.sponsor_categories.clone()))?;
			to_value(plugins::segments(&api(), &arg::<String>(a, "id")?, &cats)?)
		}
		"settings.sponsorCategories" => {
			let cats: Vec<String> = arg(a, "categories")?;
			with_state(|s| {
				s.settings.settings.sponsor_categories = plugins::clean_categories(&cats);
				s.settings.save()?;
				Ok(s.settings.settings.view())
			})
		}

		// ---- dislikes, album actions, sharing ----
		"library.dislike" => {
			let t: Track = arg(a, "track")?;
			library_op(|l| l.toggle_dislike(t))
		}
		"library.likeMany" => {
			let ts: Vec<Track> = arg(a, "tracks")?;
			library_op(|l| l.like_many(ts))
		}
		"share.clean" => Ok(json!(links::clean_share(&arg::<String>(a, "text")?))),

		// ---- scrobbling (ListenBrainz, opt-in; the token stays in the settings file) ----
		"scrobble.connect" => {
			let token: String = arg(a, "token")?;
			let user = crate::scrobble::validate(&token)?;
			with_state(|s| {
				s.settings.settings.listenbrainz_token = Some(token.trim().to_string());
				s.settings.save()?;
				Ok(json!({ "user": user, "settings": s.settings.settings.view() }))
			})
		}
		"scrobble.disconnect" => with_state(|s| {
			s.settings.settings.listenbrainz_token = None;
			s.settings.save()?;
			Ok(s.settings.settings.view())
		}),
		"scrobble.nowPlaying" | "scrobble.listen" => {
			let t: Track = arg(a, "track")?;
			let Some(token) = with_state(|s| Ok(s.settings.settings.listenbrainz_token.clone()))? else { return Ok(json!({ "sent": false })) };
			let at = if method == "scrobble.listen" {
				let listened: u64 = arg(a, "listened")?;
				if !crate::scrobble::should_scrobble(t.duration_seconds, listened) {
					return Ok(json!({ "sent": false }));
				}
				Some(arg::<Option<u64>>(a, "startedAt")?.unwrap_or_else(|| Store::now()) / 1000)
			} else {
				None
			};
			crate::scrobble::submit(&token, &t, at)?;
			Ok(json!({ "sent": true }))
		}
		"plugin.dislikes" => Ok(plugins::dislikes(&api(), &arg::<String>(a, "id")?)?),
		"plugin.lyrics" => to_value(plugins::lyrics(&arg::<Track>(a, "track")?)?),
		"plugin.protv" => {
			let name: String = arg::<Option<String>>(a, "name")?.unwrap_or_default();
			Ok(Value::String(plugins::protv_playlist(&name, &arg::<Vec<Track>>(a, "tracks")?)))
		}

		// ---- BTLink: encrypted local-network sync (link/) ----
		"lan.host" => Ok(crate::link::session::host(&data_dir(), &arg::<Option<String>>(a, "name")?.unwrap_or_default())?),
		"lan.join" => Ok(crate::link::session::join(&data_dir(), &arg::<String>(a, "invite")?, &arg::<Option<String>>(a, "name")?.unwrap_or_default())?),
		"lan.send" => {
			let cmd: Value = arg(a, "command")?;
			crate::link::session::send(&cmd, arg::<Option<i64>>(a, "leadMs")?.unwrap_or(120))?;
			Ok(Value::Null)
		}
		"lan.lock" => {
			crate::link::session::lock(arg(a, "locked")?);
			Ok(Value::Null)
		}
		"lan.poll" => Ok(crate::link::session::poll()),
		"lan.leave" => {
			crate::link::session::leave();
			Ok(Value::Null)
		}

		// ---- audio analysis (songs on the device only) and recommendations ----
		"analysis.file" => {
			let (id, path): (String, String) = (arg(a, "id")?, arg(a, "path")?);
			let (pcm, rate) = crate::analysis::decode_file(&path, 45.0)?;
			let f = crate::analysis::analyze(&pcm, rate);
			let out = json!({ "bpm": f.bpm, "key": f.key.map(|k| k.name()), "camelot": f.key.map(|k| { let (n, l) = k.camelot(); format!("{n}{l}") }), "keyConfidence": f.key_confidence, "energy": f.energy });
			with_state(|s| {
				let lib = &mut s.store.lib;
				if lib.features.len() >= crate::library::MAX_FEATURES && !lib.features.contains_key(&id) {
					return Ok(out.clone());
				}
				lib.features.insert(id.clone(), f);
				s.store.save()?;
				Ok(out.clone())
			})
		}
		"analysis.get" => {
			let id: String = arg(a, "id")?;
			with_state(|s| Ok(s.store.lib.features.get(&id).map(|f| json!({ "bpm": f.bpm, "key": f.key.map(|k| k.name()), "camelot": f.key.map(|k| { let (n, l) = k.camelot(); format!("{n}{l}") }), "energy": f.energy })).unwrap_or(Value::Null)))
		}
		"analysis.count" => with_state(|s| Ok(json!(s.store.lib.features.len()))),
		"recommend.genres" => {
			let t: Track = arg(a, "track")?;
			Ok(json!(crate::recommend::genres(&t.title, &t.artist, &arg::<Option<Vec<String>>>(a, "tags")?.unwrap_or_default())))
		}

		// ---- diagnostics (no queries, tokens or IDs; see diag.rs) ----
		"diagnostics.get" => {
			let records = crate::diag::recent();
			Ok(json!({ "summary": crate::diag::summarize(&records), "requests": to_value(records)?, "coreVersion": env!("CARGO_PKG_VERSION") }))
		}
		"diagnostics.clear" => {
			crate::diag::clear();
			Ok(Value::Null)
		}

		other => Err(CoreError::invalid(format!("Unknown method `{other}`"))),
	}
}

pub fn dispatch(method: &str, args: &str) -> String {
	let args: Value = if args.trim().is_empty() { Value::Null } else { serde_json::from_str(args).unwrap_or(Value::Null) };
	let reply = match handle(method, &args) {
		Ok(data) => json!({ "ok": true, "data": data }),
		Err(e) => {
			let mut r = json!({ "ok": false, "error": e.message, "code": e.code, "retryable": e.retryable });
			if let Some(s) = e.retry_after {
				r["retryAfter"] = json!(s);
			}
			if let Some(id) = e.request_id {
				r["requestId"] = json!(id);
			}
			r
		}
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
		call("library.played", json!({ "track": track("bbbbbbbbbbb"), "hour": 21, "day": 20_000 }));
		call("library.listened", json!({ "id": "bbbbbbbbbbb", "seconds": 125 }));
		let ins = call("insights", json!({ "today": 20_000 }));
		assert_eq!((ins["data"]["totalPlays"].as_u64(), ins["data"]["minutes"].as_u64(), ins["data"]["currentStreak"].as_u64()), (Some(2), Some(2), Some(1)));
		let smart = call("smart.list", json!({}));
		assert!(smart["data"].as_array().unwrap().iter().any(|s| s["id"] == "most-played"));
		let prev = call("smart.preview", json!({ "rule": { "source": "favorites" } }));
		assert_eq!(prev["data"]["count"], 1);
		assert!(call("library.get", json!({}))["data"].get("stats").is_none(), "stats stay in the core");
		assert_eq!(call("library.addSearch", json!({ "query": "lofi" }))["data"]["library"]["searches"][0]["query"], "lofi");

		// Re-init (as after an app restart): the library comes back from disk.
		call("init", json!({ "dataDir": dir.to_string_lossy() }));
		let lib = call("library.get", json!({}));
		assert_eq!(lib["data"]["favorites"][0]["id"], "aaaaaaaaaaa");
		assert_eq!(lib["data"]["playlists"][0]["tracks"].as_array().unwrap().len(), 2);
		assert_eq!(lib["data"]["history"][0]["id"], "bbbbbbbbbbb", "history entries are flattened tracks");

		let bad = call("queue.jump", json!({}));
		assert_eq!(bad["ok"], false);
		assert!(bad["error"].as_str().unwrap().contains("position"));
		assert_eq!((bad["code"].as_str(), bad["retryable"].as_bool()), (Some("INVALID_ARGUMENT"), Some(false)), "errors carry a stable code");
		assert_eq!(call("nope", json!({}))["ok"], false);
		assert!(call("diagnostics.get", json!({}))["data"]["summary"].is_object());
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
