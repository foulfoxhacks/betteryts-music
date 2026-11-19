# BetterYTS Music by FoulFoxHacks

An ad-free music app for Android, built on the [BetterYTS API](https://yts.foulfoxhacks.dev/docs).
Search songs, artists and playlists, follow artists, keep favourites and playlists — no Google
account, and everything stays on the phone.

## How it's built

```
core/       Rust: BetterYTS API client, title/artist clean-up, play queue, on-device library
android/    Kotlin + Jetpack Compose UI; plays through YouTube's official embedded player
scripts/    build-core.ps1 cross-compiles core/ into android/app/src/main/jniLibs
```

- **Rust core** (`core/`). Everything that isn't UI: HTTP (ureq + rustls, no OpenSSL),
  mapping YouTube results into songs/artists/playlists, turning
  "Artist - Song (Official Video)" into a song and an artist, the queue (shuffle that keeps
  history, repeat all/one, play next, reorder) and the library (favourites, history, playlists,
  followed artists) saved atomically as JSON in app storage. Unit-tested on the desktop.
- **One JNI call.** Kotlin calls `Core.call(method, json)` and gets `{"ok":…,"data"|"error":…}`
  back. The methods are listed in `core/src/bridge.rs`.
- **Android app** (`android/`). Compose UI in the akasammythepuppy.me look: square framed panels,
  uppercase display type with gradient fills, gold/copper/violet on ink. Home (moods, listen
  again, new from followed artists, favourites), Search with suggestions and filters, Library,
  artist and playlist pages, a mini player and a full player with the queue.
- **Playback** uses YouTube's IFrame player (youtube-nocookie) in a WebView, the same way the
  BetterYTS website plays videos. One WebView moves between the mini player, the full player and
  picture-in-picture, so songs never reload when you move it. Playback speed (0.5×–2×) is in the
  full player's top bar, and songs resume where you left off (per song, on the phone; the
  Settings → Playback switch turns it off).

### Playback rules (YouTube's embedded-player terms)

- The player stays visible. Pressing Home (or Recents) while a song plays shrinks it into a
  **picture-in-picture** window that keeps playing over other apps, with play/pause and next
  buttons. Closing that window or turning the screen off pauses playback. There is still no
  audio-only, background or lock-screen playback; that would need stream extraction, which
  YouTube's terms don't allow.
- The full player meets YouTube's 200×200 minimum player size. **The mini player's video and the
  system-sized PiP window are smaller than that on some screens** (the mini player is about
  114×64 dp); if the app goes to the Play Store, make the mini player bigger, or pause when the
  player is collapsed, and check the PiP size on small devices.
- Uploads whose owners block embedding (player errors 101/150) are skipped with a message.

## Build

Prerequisites: Rust with the Android targets and `cargo-ndk`, the Android SDK (platform 36) and
NDK, and JDK 17+.

```powershell
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
./scripts/build-core.ps1             # Rust core → android/app/src/main/jniLibs
cd android
./gradlew assembleDebug              # → app/build/outputs/apk/debug/app-debug.apk
```

For a signed production APK:

```powershell
cd android
./gradlew assembleRelease            # → app/build/outputs/apk/release/app-release.apk
```

Release signing is read from `android/keystore.properties`, next to `release-keystore.jks`
(both gitignored):

```properties
storeFile=release-keystore.jks
storePassword=…
keyAlias=betteryts
keyPassword=…
```

**Back up the keystore and its password.** Losing them means you can no longer update an
installed app; signing with a new key requires uninstalling the old version first (a debug build
installed on the same device also has to be uninstalled). If `keystore.properties` is missing,
`assembleRelease` still builds but produces an unsigned APK that Android will refuse to install.

Tests for the core: `cd core; cargo test` (plus `cargo test -- --ignored` for live-API checks).

## Optional: Connect with Google

Library → **Connect with Google** imports YouTube / YouTube Music playlists, liked songs and
subscriptions (read-only). Sign-in runs in the browser through the BetterYTS Worker, so it
doesn't need Google Play services. Setup and the Google Cloud steps: [docs/google-setup.md](docs/google-setup.md).

## Links into the app

Sharing a YouTube or YouTube Music link to the app plays it (songs), opens it (playlists,
artists) or searches it. `music.youtube.com` and `yts.foulfoxhacks.dev` links can open the app
directly.
