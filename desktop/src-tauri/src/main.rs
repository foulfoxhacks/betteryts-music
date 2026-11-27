//! BetterYTS Music desktop: the same Rust core as the Android app, a web UI, YouTube's official
//! embedded player, and the OS media controls (MPRIS on Linux, the Windows media overlay/taskbar).
//!
//! The UI is served from http://localhost:<port> rather than the webview's custom scheme: YouTube's
//! embedded player refuses pages without an http(s) origin and referrer (player error 153).

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::sync::Mutex;

use souvlaki::{MediaControlEvent, MediaControls, MediaMetadata, MediaPlayback, PlatformConfig};
use tauri::{Emitter, Manager, WebviewUrl, WebviewWindowBuilder};

const PORT: u16 = 47821;

struct Media(Mutex<Option<MediaControls>>);

/// The one bridge to the core: `{"ok":…,"data"|"error":…}`, same methods as Android's Core.call.
/// Runs off the UI thread (some methods hit the network).
#[tauri::command]
async fn core_call(method: String, args: String) -> String {
	tauri::async_runtime::spawn_blocking(move || betteryts_core::bridge::dispatch(&method, &args)).await.unwrap_or_else(|e| format!(r#"{{"ok":false,"error":"{e}","code":"ERROR"}}"#))
}

/// What the OS media panel shows.
#[tauri::command]
fn media_update(state: tauri::State<Media>, title: String, artist: String, art: Option<String>, playing: bool, position: f64, duration: f64) {
	let mut guard = state.0.lock().unwrap_or_else(|p| p.into_inner());
	let Some(c) = guard.as_mut() else { return };
	let _ = c.set_metadata(MediaMetadata {
		title: Some(&title),
		artist: Some(&artist),
		cover_url: art.as_deref(),
		duration: (duration > 0.0).then(|| std::time::Duration::from_secs_f64(duration)),
		..Default::default()
	});
	let progress = Some(souvlaki::MediaPosition(std::time::Duration::from_secs_f64(position.max(0.0))));
	let _ = c.set_playback(if playing { MediaPlayback::Playing { progress } } else { MediaPlayback::Paused { progress } });
}

fn media_controls(app: &tauri::AppHandle, window: &tauri::WebviewWindow) -> Option<MediaControls> {
	#[cfg(target_os = "windows")]
	let hwnd = window.hwnd().ok().map(|h| h.0 as *mut std::ffi::c_void);
	#[cfg(not(target_os = "windows"))]
	let hwnd = {
		let _ = window;
		None
	};
	let config = PlatformConfig { dbus_name: "betteryts_music", display_name: "BetterYTS Music", hwnd };
	let mut controls = MediaControls::new(config).ok()?;
	let app = app.clone();
	controls
		.attach(move |e: MediaControlEvent| {
			let name = match e {
				MediaControlEvent::Play => "play",
				MediaControlEvent::Pause => "pause",
				MediaControlEvent::Toggle => "toggle",
				MediaControlEvent::Next => "next",
				MediaControlEvent::Previous => "previous",
				MediaControlEvent::Stop => "pause",
				_ => return,
			};
			let _ = app.emit("media-control", name);
		})
		.ok()?;
	Some(controls)
}

fn main() {
	tauri::Builder::default()
		.plugin(tauri_plugin_localhost::Builder::new(PORT).build())
		.manage(Media(Mutex::new(None)))
		.invoke_handler(tauri::generate_handler![core_call, media_update])
		.setup(|app| {
			let dir = app.path().app_data_dir()?;
			std::fs::create_dir_all(&dir)?;
			let init = serde_json::json!({ "dataDir": dir.to_string_lossy(), "region": "" }).to_string();
			betteryts_core::bridge::dispatch("init", &init);
			let url = format!("http://localhost:{PORT}").parse().expect("valid url");
			let window = WebviewWindowBuilder::new(app, "main", WebviewUrl::External(url))
				.title("BetterYTS Music")
				.inner_size(1200.0, 780.0)
				.min_inner_size(720.0, 520.0)
				.build()?;
			let controls = media_controls(app.handle(), &window);
			*app.state::<Media>().0.lock().unwrap_or_else(|p| p.into_inner()) = controls;
			Ok(())
		})
		.run(tauri::generate_context!())
		.expect("error while running BetterYTS Music");
}
