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
  Settings → Playback switch turns it off). Closing the app keeps the queue: the next launch
  brings back the same songs, cued paused at the right spot.

### Two builds

| | `sideload` (from yts.foulfoxhacks.dev) | `play` (Google Play) |
|---|---|---|
| Updates | Updates itself (below) | Through the Play Store |
| Background play | Plugin, on by default (lock screen, headset, car controls) | Left out |
| `REQUEST_INSTALL_PACKAGES` | Yes (updater only) | No |

**In-app updater** (`update/Updater.kt`, sideload only): every few hours it reads
`https://yts.foulfoxhacks.dev/app/update.json`, downloads a newer APK and only offers it if the
SHA-256 and size match the manifest, it's this package with a higher `versionCode`, and it's
signed with the installed app's certificate. It installs through `PackageInstaller`: Android asks
the first time; once the app is its own installer, Android 12+ updates without a prompt. Debug
builds read a local test server instead (`http://10.0.2.2:5175/update.json`).

### Playback rules (YouTube's embedded-player terms)

- The player stays visible. Pressing Home (or Recents) while a song plays shrinks it into a
  **picture-in-picture** window that keeps playing over other apps, with play/pause and next
  buttons. In the `play` build, closing that window or turning the screen off pauses playback;
  background play is only in the `sideload` build.
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
./gradlew assembleSideloadDebug      # → app/build/outputs/apk/sideload/debug/app-sideload-debug.apk
```

For a release, run `./scripts/publish-update.ps1`. It builds the core, the signed sideload APK and
the Play bundle (`bundlePlayRelease`), writes the updater manifest into the site
(`../betteryts/public/app/update.json`, with `$env:BTM_NOTES` as release notes), and copies the
APK and AAB to `../releases/`. Then deploy the site. Upload the `.aab` to Google Play yourself.

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

## Also in the app

- **Spotify**: import public playlists by link, or Sign in with Spotify for private playlists and
  Liked Songs (PKCE; the token is used for one import and not saved).
- **Backup**: Settings › Backup exports/imports the same file as the website, so libraries move both ways.
- **Send to a VRChat world**: Library card; just this visit by default, or saved for 30 days.
- **Hidden artists and words**, plugin categories and sandboxed community plugins, Listen
  Together rooms with QR invites.
- An animated fox intro on cold start (skipped when system animations are off).

## Links into the app

Sharing a YouTube or YouTube Music link to the app plays it (songs), opens it (playlists,
artists) or searches it. `music.youtube.com` and `yts.foulfoxhacks.dev` links can open the app
directly.
