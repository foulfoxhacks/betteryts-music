# BetterYTS Music — Privacy

**In short:** no account, no ads, no analytics, no tracking. Your library lives on your device.
The same statement is shown in the app under Settings › About › Privacy.

## Stays on your device
Liked songs, playlists, history, followed artists, stations, smart playlists, search history,
listening statistics and settings. Backups are files you save yourself; the app never uploads one.
Listening statistics and search history are never included in backups.

## Sent to services, and why
| Where | What | When |
|---|---|---|
| BetterYTS API (yts.foulfoxhacks.dev) | Search text, song/playlist/artist IDs; skip-segment and dislike look-ups | Searching and browsing |
| YouTube (youtube-nocookie.com embedded player) | Whatever YouTube's official player sends | Playing a song |
| YouTube Music (music.youtube.com) | The song a radio/station grows from | Radio and stations |
| LRCLIB (lrclib.net) | Song title and artist | Lyrics plugin on |
| Google / Spotify | OAuth sign-in; read-only import | Only if you connect them; tokens stay on the device |
| BetterYTS Sync (`/api/sync/…`) | Only the encrypted library: a score-random ID, an auth secret and AES-256-GCM ciphertext, none of which can be read without your sync code | Settings › Sync, after you create or enter a code |
| BetterYTS rooms | Room playback messages | Listen Together |
| BetterYTS VRChat handoff | Only the playlists you pick: song IDs, titles, channel names | Send to a VRChat world |
| Scrobbling service you connect | Song, artist, time played | Only if you connect one |
| Update manifest (yts.foulfoxhacks.dev) | A version check | Sideload build, every few hours |

Every service sees your IP address, as with any website.

**Sync**: the library is encrypted on your device with a key derived from your sync code, which
never leaves your devices. BetterYTS stores ciphertext and can't read it, and sync covers liked
songs, playlists, followed artists, dislikes, smart playlists and hidden lists — not the queue,
listening statistics or search history.

## Diagnostics
Settings › Diagnostics is local. A copied report leaves out song IDs, searches, tokens and room
codes, and is only shared if you paste it somewhere.

## Permissions
Internet; notifications (playback controls); music files (only for "On this phone", when you allow
it); install packages (sideload updater only). No location, contacts, microphone or camera.

## Deleting your data
Clear the app's storage in Android settings, or uninstall. Disconnect Google in Library to revoke
its token. Settings › Sync › Erase the synced copy deletes the encrypted copy on BetterYTS; every
device keeps its own library.
