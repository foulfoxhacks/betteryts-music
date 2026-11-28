// BetterYTS Music desktop UI. Every call goes to the shared Rust core (core_call), exactly like
// the Android app's Core.call. Elements are built with textContent, never innerHTML, so titles from
// the network can't inject markup.
'use strict';

const invoke = (cmd, args) => window.__TAURI__.core.invoke(cmd, args);
const listen = (ev, fn) => window.__TAURI__.event.listen(ev, fn);

async function core(method, args = {}) {
  const reply = JSON.parse(await invoke('core_call', { method, args: JSON.stringify(args) }));
  if (!reply.ok) { const e = new Error(reply.error || 'Something went wrong'); e.code = reply.code; throw e; }
  return reply.data;
}

// ---------- tiny DOM helpers ----------
const $ = (id) => document.getElementById(id);
function el(tag, props = {}, ...kids) {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (k === 'class') n.className = v;
    else if (k === 'text') n.textContent = v;
    else if (k.startsWith('on')) n.addEventListener(k.slice(2), v);
    else if (v !== undefined && v !== null) n.setAttribute(k, v);
  }
  for (const c of kids.flat()) if (c) n.append(c);
  return n;
}
let toastTimer;
function toast(msg) { const t = $('toast'); t.textContent = msg; t.classList.add('show'); clearTimeout(toastTimer); toastTimer = setTimeout(() => t.classList.remove('show'), 2600); }
const fail = (e) => toast(e.message || String(e));
const art = (t) => t.thumbnail || `https://i.ytimg.com/vi/${encodeURIComponent(t.id)}/mqdefault.jpg`;
const fmt = (s) => { s = Math.max(0, Math.floor(s || 0)); const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), x = String(s % 60).padStart(2, '0'); return h ? `${h}:${String(m).padStart(2, '0')}:${x}` : `${m}:${x}`; };

// ---------- state ----------
let library = { favorites: [], history: [], playlists: [], disliked: [], searches: [] };
let queue = { tracks: [], position: -1, current: null, shuffle: false, repeat: 'off' };
let station = null;
let settings = null;      // the core's settings view: themes, plugins, SponsorBlock categories
let better = null;        // DeArrow's title for a song, when that plugin is on
let betterFor = null;
let segments = [];        // SponsorBlock segments for the current song
let skippedUntil = 0;
let tab = 'home';
let lan = null;           // BTLink status while hosting or linked
let lanFingerprint = null;
let lanName = localStorage.getItem('lanName') || 'Computer';
const prefs = JSON.parse(localStorage.getItem('prefs') || '{}');
const savePrefs = () => { try { localStorage.setItem('prefs', JSON.stringify(prefs)); } catch (e) { /* private mode */ } };
const isFav = (id) => library.favorites.some((t) => t.id === id);
const isDisliked = (id) => (library.disliked || []).some((t) => t.id === id);
// A plugin runs only when it and its category are both on; the core's `active` says exactly that.
const pluginOn = (id) => (settings?.plugins || []).find((p) => p.id === id)?.active === true;

// ---------- YouTube player (official IFrame API; stays visible) ----------
let yt = null, ytReady = false, pendingLoad = null, playing = false, duration = 0, position = 0, endedFor = null;
window.onYouTubeIframeAPIReady = () => {
  yt = new YT.Player('yt', {
    host: 'https://www.youtube-nocookie.com',
    width: '100%', height: '100%',
    playerVars: { playsinline: 1, rel: 0, iv_load_policy: 3, origin: location.origin },
    events: {
      onReady: () => { ytReady = true; applyVolume(); if (pendingLoad) { load(...pendingLoad); pendingLoad = null; } },
      onStateChange: (e) => {
        playing = e.data === 1 || e.data === 3;
        $('play').textContent = playing ? '⏸' : '▶';
        if (e.data === 1) { yt.setPlaybackRate(Number($('speed').value)); onStarted(); }
        if (e.data === 0) onEnded();
        pushMedia();
      },
      onError: (e) => { toast(e.data === 101 || e.data === 150 ? "The uploader doesn't allow this song in other apps. Skipping." : `Couldn't play that song (error ${e.data}). Skipping.`); setTimeout(() => next(false), 1500); },
    },
  });
  setInterval(tick, 500);
};
const tag = document.createElement('script'); tag.src = 'https://www.youtube.com/iframe_api'; document.head.append(tag);

function load(id, autoplay = true, start = 0) {
  endedFor = null;
  if (!ytReady) { pendingLoad = [id, autoplay, start]; return; }
  if (autoplay) yt.loadVideoById({ videoId: id, startSeconds: start }); else yt.cueVideoById({ videoId: id, startSeconds: start });
}
function tick() {
  if (!ytReady || !yt.getCurrentTime) return;
  position = yt.getCurrentTime() || 0; duration = yt.getDuration() || 0;
  $('pos').textContent = fmt(position); $('dur').textContent = fmt(duration);
  if (!seeking && duration > 0) $('seek').value = String(Math.round(position / duration * 1000));
  // SponsorBlock: jump over the chosen parts. The host's timeline wins, so a member never seeks
  // on its own; the host's skip goes out to the room.
  if (segments.length && !seeking && duration > 0 && lan?.role !== 'member') {
    const seg = segments.find((s) => position >= s.start && position < s.end - 0.5 && s.end > skippedUntil);
    if (seg) {
      skippedUntil = seg.end;
      const label = seg.category.replace(/_/g, ' ');
      if (seg.end >= duration - 1) { toast(`Skipped the ${label}`); next(false); }
      else {
        if (lan?.role === 'host') lanSend({ type: 'seek', position: seg.end }, 60);
        yt.seekTo(seg.end, true); toast(`Skipped ${label}`);
      }
    }
  }
}
let seeking = false;
$('seek').addEventListener('input', () => { seeking = true; });
$('seek').addEventListener('change', () => {
  const to = Number($('seek').value) / 1000 * duration;
  if (lan?.role === 'member') lanSend({ type: 'seek', position: to });
  else if (ytReady && duration) { if (lan?.role === 'host') lanSend({ type: 'seek', position: to }, 60); yt.seekTo(to, true); }
  seeking = false;
});

// Exponential volume: the slider's position cubed, for fine control at low volume.
function applyVolume() {
  const s = Number($('vol').value) / 1000, v = s * s * s * 100;
  $('volText').textContent = `${v < 10 ? v.toFixed(1) : Math.round(v)}%`;
  if (ytReady) yt.setVolume(v);
  prefs.vol = $('vol').value; savePrefs();
}
$('vol').value = prefs.vol ?? 1000;
$('vol').addEventListener('input', applyVolume);
$('speed').addEventListener('change', () => { if (ytReady) yt.setPlaybackRate(Number($('speed').value)); });

// ---------- queue ----------
async function setQueue(q) { queue = q; renderQueue(); renderNow(); topUp(); }
async function playList(tracks, start = 0, shuffle = false) {
  try {
    if (station) { await core('station.stop'); station = null; }
    await setQueue(await core('queue.set', { tracks, start, shuffle }));
    playCurrent();
  } catch (e) { fail(e); }
}
function playCurrent() {
  if (!queue.current) return;
  // The room follows the host's choice, from the start of the song.
  if (lan?.role === 'host') lanSend({ type: 'load', id: queue.current.id, position: 0 }, 0);
  load(queue.current.id, true); renderNow();
}
async function next(user = true) {
  try {
    if (user && station && queue.current && position < 30) core('station.feedback', { track: queue.current, kind: 'skip' }).catch(() => {});
    const q = await core('queue.next', { user }); await setQueue(q);
    if (!q.ended) playCurrent();
  } catch (e) { fail(e); }
}
async function previous() {
  if (position > 4 && ytReady) {
    if (lan?.role === 'host') lanSend({ type: 'seek', position: 0 }, 60);
    yt.seekTo(0, true); return;
  }
  try { await setQueue(await core('queue.previous')); playCurrent(); } catch (e) { fail(e); }
}
function togglePlay() {
  if (!ytReady || !queue.current) return;
  // A member's play/pause is a request to the host; a host's is the room's.
  if (lan?.role === 'member') { lanSend({ type: playing ? 'pause' : 'play', position }); return; }
  if (lan?.role === 'host') lanSend({ type: playing ? 'pause' : 'play', position }, 60);
  if (playing) yt.pauseVideo(); else yt.playVideo();
}
async function onStarted() {
  const t = queue.current; if (!t) return;
  if (isDisliked(t.id) && lan?.role !== 'member') { toast('Skipped a song you disliked'); next(false); return; }
  const d = new Date();
  try { library = (await core('library.played', { track: t, hour: d.getHours(), day: Math.floor((d - d.getTimezoneOffset() * 60000) / 86400000) })).library; } catch (e) { /* stats are best-effort */ }
  if (station) core('station.feedback', { track: t, kind: 'played' }).catch(() => {});
  better = null; betterFor = null; segments = []; skippedUntil = 0;
  if (pluginOn('dearrow')) {
    core('plugin.dearrow', { id: t.id }).then((b) => { if (queue.current?.id === t.id && b?.title) { better = b.title; betterFor = t.id; renderNow(); } }).catch(() => {});
  }
  if (pluginOn('sponsorblock')) {
    core('plugin.segments', { id: t.id }).then((s) => { if (queue.current?.id === t.id) segments = s || []; }).catch(() => {});
  }
}
function onEnded() {
  // The host moves the room on; a member waits for the next load instead of racing ahead.
  if (lan?.role === 'member') return;
  const id = queue.current?.id; if (!id || endedFor === id) return; endedFor = id; next(false);
}

// Autoplay: with 3 or fewer songs left, the station (or a new one from this song) adds more.
let topping = false;
async function topUp() {
  const cur = queue.current;
  if (!cur || topping || prefs.autoplay === false || queue.repeat !== 'off' || queue.tracks.length - 1 - queue.position > 3) return;
  topping = true;
  try {
    const page = station ? await core('station.more') : await core('station.start', { kind: 'track', id: cur.id, title: cur.title, art: art(cur) });
    station = page.station;
    const have = new Set(queue.tracks.map((t) => t.id));
    const fresh = page.tracks.filter((t) => !have.has(t.id) && !t.live);
    if (fresh.length) { queue = await core('queue.addAll', { tracks: fresh }); renderQueue(); }
  } catch (e) { /* no radio this time */ } finally { topping = false; }
}
async function startStation(kind, id, title, image, seed) {
  try {
    toast('Starting radio…');
    const page = await core('station.start', { kind, id, title, art: image });
    station = page.station;
    const tracks = seed ? [seed, ...page.tracks.filter((t) => t.id !== seed.id)] : page.tracks;
    await setQueue(await core('queue.set', { tracks, start: 0, shuffle: false }));
    playCurrent(); toast(`Radio: ${station ? station.seed.title : title}`);
  } catch (e) { fail(e); }
}

// ---------- rendering ----------
function trackRow(t, onClick, extra = []) {
  const acts = el('div', { class: 'acts' },
    el('button', { text: 'Next', title: 'Play next', onclick: async (e) => { e.stopPropagation(); try { await setQueue(await core('queue.playNext', { track: t })); toast(`Playing next: ${t.title}`); } catch (x) { fail(x); } } }),
    el('button', { text: 'Queue', onclick: async (e) => { e.stopPropagation(); try { await setQueue(await core('queue.add', { track: t })); toast('Added to queue'); } catch (x) { fail(x); } } }),
    el('button', { text: 'Radio', onclick: (e) => { e.stopPropagation(); startStation('track', t.id, t.title, art(t), t); } }),
    el('button', { text: isFav(t.id) ? '♥' : '♡', title: 'Like', onclick: (e) => { e.stopPropagation(); toggleFav(t); } }),
    ...extra);
  return el('div', { class: 'track' + (queue.current?.id === t.id ? ' cur' : ''), onclick: onClick },
    el('img', { src: art(t), alt: '', loading: 'lazy' }),
    el('div', { class: 't' }, el('b', { text: t.title }), el('span', { text: [t.artist, t.plays, t.live ? 'LIVE' : t.duration].filter(Boolean).join(' · ') })),
    acts);
}
const trackList = (tracks, extra) => tracks.map((t, i) => trackRow(t, () => playList(tracks, i), extra ? extra(t, i) : []));
function card(title, sub, image, onClick) { return el('div', { class: 'card', onclick: onClick }, el('img', { src: image || 'logo.png', alt: '', loading: 'lazy' }), el('b', { text: title }), el('span', { text: sub })); }

function renderNow() {
  const t = queue.current;
  const crowd = t && better && betterFor === t.id ? better : null;
  $('nowTitle').textContent = t ? (crowd || t.title) : 'Nothing playing';
  $('nowArtist').textContent = t ? [t.artist, crowd ? 'DeArrow' : ''].filter(Boolean).join(' · ') : '';
  $('like').textContent = t && isFav(t.id) ? '♥' : '♡';
  $('dislike').classList.toggle('on', !!(t && isDisliked(t.id)));
  $('shuffle').classList.toggle('on', queue.shuffle);
  $('repeat').classList.toggle('on', queue.repeat !== 'off'); $('repeat').textContent = queue.repeat === 'one' ? '↻1' : '↻';
  $('queueHead').textContent = station ? `Up next · ${station.seed.title} station` : 'Up next';
  pushMedia();
}
function renderQueue() {
  const ol = $('queue'); ol.replaceChildren();
  queue.tracks.forEach((t, i) => ol.append(el('li', { class: i === queue.position ? 'cur' : '', text: `${t.title} · ${t.artist}`, onclick: async () => { try { await setQueue(await core('queue.jump', { position: i })); playCurrent(); } catch (e) { fail(e); } } })));
}
function pushMedia() {
  const t = queue.current; if (!t) return;
  invoke('media_update', { title: t.title, artist: t.artist, art: art(t), playing, position, duration }).catch(() => {});
}

async function toggleFav(t) { try { const r = await core('library.favorite', { track: t }); library = r.library; toast(r.result ? 'Added to favorites' : 'Removed from favorites'); renderNow(); } catch (e) { fail(e); } }

const views = {
  async home(m) {
    m.append(el('h1', { text: 'Welcome back' }));
    const st = await core('stations.list').catch(() => ({ saved: [], recent: [] }));
    const all = [...st.saved, ...st.recent.filter((r) => !st.saved.some((s) => s.id === r.id))];
    if (all.length) m.append(el('h2', { text: 'Your stations' }), el('div', { class: 'shelf' }, all.map((s) => card(s.seed.title, s.saved ? 'Saved station' : 'Recent station', s.seed.art, async () => { try { const p = await core('station.resume', { id: s.id }); station = p.station; await setQueue(await core('queue.set', { tracks: p.tracks, start: 0 })); playCurrent(); } catch (e) { fail(e); } }))));
    const smart = await core('smart.list').catch(() => []);
    if (smart.length) m.append(el('h2', { text: 'Smart playlists' }), el('div', { class: 'shelf' }, smart.map((s) => card(s.name, `${s.count} songs`, s.art[0], async () => { try { playList((await core('smart.tracks', { id: s.id })).tracks); } catch (e) { fail(e); } }))));
    if (library.history.length) m.append(el('h2', { text: 'Listen again' }), ...trackList(library.history.slice(0, 12)));
    if (!all.length && !smart.length && !library.history.length) m.append(el('p', { class: 'muted', text: 'Search for a song to begin. Your library, stations and smart playlists build up as you listen.' }));
  },
  async search(m) {
    const box = el('input', { type: 'search', placeholder: 'Songs, artists, playlists', value: prefs.lastQuery || '' });
    const results = el('div');
    const run = async () => {
      const q = box.value.trim(); if (!q) return;
      prefs.lastQuery = q; savePrefs(); results.replaceChildren(el('p', { class: 'muted', text: 'Searching…' }));
      try {
        const page = await core('search', { query: q, filter: 'songs' });
        core('library.addSearch', { query: q }).then((r) => { library = r.library; }).catch(() => {});
        const tracks = page.items.filter((i) => i.itemType === 'track');
        results.replaceChildren(...(tracks.length ? trackList(tracks) : [el('p', { class: 'muted', text: 'No results. Try different words.' })]));
      } catch (e) { results.replaceChildren(el('p', { text: e.message }), el('button', { text: 'Try again', onclick: run })); }
    };
    box.addEventListener('keydown', (e) => { if (e.key === 'Enter') run(); });
    m.append(el('h1', { text: 'Search' }), box);
    if (library.searches?.length) m.append(el('div', { class: 'row', style: 'margin:10px 0' }, library.searches.slice(0, 10).map((s) => el('button', { class: 'chip', text: s.query, onclick: () => { box.value = s.query; run(); } }))));
    m.append(results);
    setTimeout(() => box.focus(), 0);
    if (box.value) run();
  },
  async library(m) {
    m.append(el('h1', { text: 'Your library' }));
    m.append(el('h2', { text: `Liked songs · ${library.favorites.length}` }));
    if (library.favorites.length) m.append(el('div', { class: 'row' }, el('button', { class: 'primary', text: 'Play', onclick: () => playList(library.favorites) }), el('button', { text: 'Shuffle', onclick: () => playList(library.favorites, 0, true) })), ...trackList(library.favorites));
    for (const p of library.playlists) {
      m.append(el('h2', { text: `${p.name} · ${p.tracks.length}` }), el('div', { class: 'row' }, el('button', { text: 'Play', onclick: () => playList(p.tracks) }), el('button', { text: 'Radio', onclick: () => startStation('library', p.id, p.name, p.tracks[0] && art(p.tracks[0])) })));
    }
    m.append(el('p', { class: 'muted', text: 'Move your library between devices with Settings › Backup (same file as the Android app and the website).' }));
  },
  async stations(m) {
    m.append(el('h1', { text: 'Stations' }));
    const st = await core('stations.list');
    if (st.current) {
      m.append(el('h2', { text: `Playing: ${st.current.seed.title}` }), el('div', { class: 'row' },
        ...['familiar', 'balanced', 'discover'].map((mix) => el('button', { class: 'chip' + (st.current.mix === mix ? ' on' : ''), text: mix[0].toUpperCase() + mix.slice(1), onclick: async () => { await core('station.tune', { mix }); render(); } })),
        el('button', { text: st.current.saved ? 'Saved' : 'Save station', onclick: async () => { try { await core('station.save'); toast('Station saved'); render(); } catch (e) { fail(e); } } })));
    }
    for (const s of [...st.saved, ...st.recent]) m.append(el('div', { class: 'track', onclick: async () => { try { const p = await core('station.resume', { id: s.id }); station = p.station; await setQueue(await core('queue.set', { tracks: p.tracks, start: 0 })); playCurrent(); } catch (e) { fail(e); } } }, el('img', { src: s.seed.art || 'logo.png', alt: '' }), el('div', { class: 't' }, el('b', { text: s.seed.title }), el('span', { text: `${s.saved ? 'Saved' : 'Recent'} · ${s.mix}` }))));
    if (!st.saved.length && !st.recent.length) m.append(el('p', { class: 'muted', text: 'Start radio from any song (Radio button) and it shows up here.' }));
  },
  async insights(m) {
    const i = await core('insights', { today: Math.floor(Date.now() / 86400000) });
    m.append(el('h1', { text: 'Insights' }));
    const stat = (label, value, note) => el('div', { class: 'stat' }, el('b', { text: String(value) }), el('span', { text: label }), note ? el('i', { text: note }) : null);
    m.append(el('div', { class: 'stats' },
      stat('songs played', i.totalPlays),
      stat('minutes listened', Math.round(i.minutes)),
      stat('artists', i.distinctArtists),
      stat('day streak', i.currentStreak, i.longestStreak > i.currentStreak ? `best ${i.longestStreak}` : null)));
    const peak = Math.max(1, ...i.hours);
    const bars = el('div', { class: 'bars' });
    i.hours.forEach((n, h) => bars.append(el('div', { class: 'bar', title: `${h}:00 · ${n} songs` },
      el('i', { style: `height:${Math.round(n / peak * 100)}%` }), el('span', { text: h % 6 === 0 ? String(h) : '' }))));
    m.append(el('h2', { text: 'When you listen' }), bars,
      el('p', { class: 'muted', text: i.listener ? `You're a ${i.listener.toLowerCase()}. Listening times and stats stay on this computer.` : 'Listen a little more and your listening times show up here.' }));
    if (i.topArtists?.length) m.append(el('h2', { text: 'Top artists' }), el('div', { class: 'row' }, i.topArtists.map((a) => el('span', { class: 'chip', text: `${a.name} · ${a.plays}` }))));
    if (i.topTracks?.length) m.append(el('h2', { text: 'Top songs' }), ...trackList(i.topTracks.map((x) => x.track)));
    if (!i.totalPlays) m.append(el('p', { class: 'muted', text: 'Play something and this page fills in.' }));
  },
  async link(m) {
    m.append(el('h1', { text: 'Link nearby devices' }));
    m.append(el('p', { class: 'muted', text: 'Play in sync with the phone app on the same Wi-Fi: encrypted, no internet round trip, and only devices with your invite can join. Each device plays its own official player.' }));
    m.append(el('div', { id: 'lanStatus' }));
    lanShown = '';
    renderLinkStatus();
  },
  async settings(m) {
    if (!settings) settings = await core('settings.get').catch(() => null);
    m.append(el('h1', { text: 'Settings' }));
    const auto = el('input', { type: 'checkbox' }); auto.checked = prefs.autoplay !== false;
    auto.addEventListener('change', () => { prefs.autoplay = auto.checked; savePrefs(); });
    const compact = el('input', { type: 'checkbox' }); compact.checked = !!prefs.compact;
    compact.addEventListener('change', () => { prefs.compact = compact.checked; savePrefs(); document.body.classList.toggle('compact', compact.checked); });
    m.append(el('label', { class: 'row' }, auto, el('span', { text: 'Autoplay: a station keeps going when the queue ends' })));
    m.append(el('label', { class: 'row' }, compact, el('span', { text: 'Compact sidebar' })));
    if (settings) {
      m.append(el('h2', { text: 'Theme' }), el('div', { class: 'row' }, settings.themes.map((t) => el('button', {
        class: 'chip' + (settings.theme === t.id ? ' on' : ''),
        text: t.name.split(' · ')[0],
        onclick: async () => { try { settings = await core('settings.theme', { theme: t.id }); render(); } catch (e) { fail(e); } },
      }))));
      m.append(el('h2', { text: 'Plugins' }), el('p', { class: 'muted', text: 'Built for the BetterYTS ecosystem. A category switches all of its plugins; each one has its own switch too. Changes apply right away.' }));
      for (const c of settings.categories) {
        const on = el('input', { type: 'checkbox' }); on.checked = c.enabled;
        on.addEventListener('change', async () => { try { settings = await core('plugins.category', { id: c.id, enabled: on.checked }); render(); } catch (e) { fail(e); } });
        m.append(el('div', { class: 'row', style: 'margin-top:14px' }, on, el('b', { text: c.name }), el('span', { class: 'muted', text: c.description })));
        for (const p of settings.plugins.filter((x) => x.category === c.id)) {
          const sw = el('input', { type: 'checkbox' }); sw.checked = p.enabled;
          sw.addEventListener('change', async () => { try { settings = await core('plugins.set', { id: p.id, enabled: sw.checked }); render(); } catch (e) { fail(e); } });
          m.append(el('label', { class: 'row', style: 'margin-left:18px' }, sw, el('span', { text: p.name }), el('span', { class: 'muted', text: `— ${p.description}` })));
        }
      }
      m.append(el('h2', { text: 'SponsorBlock' }), el('p', { class: 'muted', text: 'Parts of a music video to skip, from community-submitted segments.' }));
      const chosen = new Set(settings.sponsorCategories || []);
      m.append(el('div', { class: 'row' }, settings.sponsorCatalog.map((s) => {
        const chip = el('button', { class: 'chip' + (chosen.has(s.id) ? ' on' : ''), text: s.name });
        chip.addEventListener('click', async () => {
          chosen.has(s.id) ? chosen.delete(s.id) : chosen.add(s.id);
          try { settings = await core('settings.sponsorCategories', { categories: [...chosen] }); render(); } catch (e) { fail(e); }
        });
        return chip;
      })));
    }
    m.append(el('h2', { text: 'Keyboard' }), el('p', { class: 'muted', text: 'Space play/pause · Shift+← / Shift+→ previous/next · ← / → seek 10 s · ↑ / ↓ volume · L like · / search. Media keys work too (MPRIS on Linux, the Windows media overlay).' }));
    m.append(el('h2', { text: 'Backup' }));
    const file = el('input', { type: 'file', accept: '.json,application/json' });
    file.addEventListener('change', async () => {
      const f = file.files[0]; if (!f) return;
      try {
        const text = await f.text(); const pv = await core('backup.preview', { text, mode: 'merge' });
        const s = pv.summary;
        if (confirm(`Import: ${s.favoritesAdded} liked songs, ${s.playlistsAdded} playlists, ${s.artistsAdded} artists (${s.duplicates} already here, ${s.invalid} invalid left out)?`)) { await core('backup.import', { text, mode: 'merge' }); library = await core('library.get'); toast('Backup imported'); }
      } catch (e) { fail(e); }
    });
    m.append(el('div', { class: 'row' }, el('button', { text: 'Export backup', onclick: async () => { try { const { text } = await core('backup.export', { appVersion: 'desktop-0.10.0' }); const a = el('a', { href: URL.createObjectURL(new Blob([text], { type: 'application/json' })), download: `betteryts-backup-${new Date().toISOString().slice(0, 10)}.json` }); a.click(); } catch (e) { fail(e); } } }), file));
    m.append(el('h2', { text: 'Sync' }), el('p', { class: 'muted', text: 'Keep liked songs, playlists, followed artists, smart playlists and hidden lists in step with the website and your other devices. End-to-end encrypted: the sync code is the only key, and BetterYTS only ever stores ciphertext. The queue and listening stats stay on each computer.' }));
    const syncState = await core('sync.get').catch(() => null);
    if (syncState && syncState.enabled) {
      const when = syncState.lastSync ? new Date(syncState.lastSync).toLocaleString() : 'never';
      m.append(el('div', { class: 'row' }, el('code', { class: 'invite', text: syncState.code }),
        el('button', { text: 'Copy code', onclick: () => navigator.clipboard?.writeText(syncState.code).then(() => toast('Sync code copied')).catch(() => toast('Copy the code text instead')) })));
      m.append(el('p', { class: 'muted', text: `${syncState.items} items \u00b7 last synced ${when}` }));
      m.append(el('div', { class: 'row' },
        el('button', { class: 'primary', text: 'Sync now', onclick: syncNow }),
        el('button', { text: 'New code', onclick: async () => { try { const s = await core('sync.newCode'); toast(`New sync code: ${s.code}`); render(); } catch (e) { fail(e); } } }),
        el('button', { text: 'Turn off', onclick: async () => { if (!confirm('Turn off sync on this computer? The library stays here.')) return; try { await core('sync.off'); toast('Sync is off on this computer'); render(); } catch (e) { fail(e); } } })));
      m.append(el('button', { text: 'Erase the synced copy', onclick: async () => {
        if (!confirm('Delete the encrypted copy on BetterYTS? Every device keeps its own library.')) return;
        try { await core('sync.erase'); toast('The encrypted copy was deleted'); render(); } catch (e) { fail(e); }
      } }));
    } else {
      const codeBox = el('input', { type: 'text', placeholder: 'BTS1-XXXX-XXXX-…', 'aria-label': 'Sync code' });
      const use = async () => {
        const code = codeBox.value.trim();
        if (!code) return toast('Paste a sync code first');
        try { await core('sync.setCode', { code }); await syncNow(); } catch (e) { fail(e); }
      };
      codeBox.addEventListener('keydown', (e) => { if (e.key === 'Enter') use(); });
      m.append(el('div', { class: 'row' }, codeBox, el('button', { class: 'primary', text: 'Use this code', onclick: use }),
        el('button', { text: 'Create a new code', onclick: async () => { try { const s = await core('sync.newCode'); toast(`New sync code: ${s.code} — press Sync now to upload this library`); render(); } catch (e) { fail(e); } } })));
      m.append(el('p', { class: 'muted', text: 'On the website: Settings \u203a Sync creates a code, or accepts this one.' }));
    }
    m.append(el('h2', { text: 'About' }), el('img', { src: 'logo.png', alt: 'BetterYTS', style: 'width:220px;border-radius:14px' }),
      el('p', { text: 'BetterYTS Music by FoulFoxHacks. Search and pages come from the BetterYTS API (yts.foulfoxhacks.dev); songs play through YouTube\'s official embedded player, which stays visible.' }),
      el('p', { class: 'muted', text: 'Privacy: no account, no ads, no analytics. Your library is stored on this computer. Searches go to BetterYTS, playback to YouTube, radio song lists to YouTube Music. See PRIVACY.md in the source repository.' }));
  },
};

// ---------- BTLink: playback in sync with other BetterYTS apps on this network ----------
let lanShown = '';
const lanLink = (invite) => 'betterytsmusic://link?i=' + encodeURIComponent(invite);

async function hostLan() {
  try {
    const r = await core('lan.host', { name: lanName });
    lanFingerprint = r.fingerprint || null;
    lan = { active: true, role: 'host', invite: r.invite, locked: false, peers: [], events: [] };
    lanShown = ''; render();
  } catch (e) { fail(e); }
}
async function joinLan(invite) {
  try {
    const r = await core('lan.join', { invite, name: lanName });
    lanFingerprint = r.fingerprint || null;
    lan = { active: true, role: 'member', invite: null, locked: false, peers: [], events: [] };
    lanShown = ''; render();
  } catch (e) { fail(e); }
}
async function leaveLan() {
  try { await core('lan.leave'); } catch (e) { /* already gone */ }
  lan = null; lanShown = ''; render();
}
async function lanSend(command, leadMs = 120) {
  try { await core('lan.send', { command, leadMs }); } catch (e) { toast(e.message || 'The link stopped'); }
}

// Poll while linked (and on the Link tab so joining shows up), applying what the host sends.
async function pollLan() {
  if (!lan && tab !== 'link') return;
  try {
    const s = await core('lan.poll');
    lan = s.active ? s : null;
    for (const e of s.events || []) onLanEvent(e);
    if (tab === 'link') renderLinkStatus();
  } catch (e) { /* the link isn't running */ }
}
setInterval(pollLan, 700);

function onLanEvent(e) {
  if (e.type === 'denied') { toast('The host controls playback'); return; }
  if (e.type !== 'control') { lanShown = ''; return; }
  const cmd = e.command || {};
  const at = Math.max(0, Number(e.inMs) || 0);
  setTimeout(() => {
    // A locked room refuses members' commands, so the host ignores them here too.
    if (lan?.role === 'host' && lan.locked) return;
    applyLanCommand(cmd);
    // A command the host accepted goes out to everyone else, so every device stays together.
    if (lan?.role === 'host') lanSend(cmd, 0);
  }, at);
}

function applyLanCommand(cmd) {
  if (cmd.type === 'load') lanLoad(cmd.id, cmd.position || 0);
  else if (cmd.type === 'play' && ytReady) yt.playVideo();
  else if (cmd.type === 'pause' && ytReady) yt.pauseVideo();
  else if (cmd.type === 'seek' && ytReady) yt.seekTo(cmd.position || 0, true);
  else if (cmd.type === 'rate' && ytReady) yt.setPlaybackRate(cmd.rate || 1);
  else if (cmd.type === 'stop' && ytReady) yt.pauseVideo();
}

/** Puts a song the host chose into the queue (a stub if this device doesn't have it) and plays it. */
async function lanLoad(id, at) {
  if (!id) return;
  try {
    let i = queue.tracks.findIndex((t) => t.id === id);
    if (i < 0) {
      const stub = { id, title: 'Linked song', artist: 'BTLink' };
      await setQueue(await core('queue.set', { tracks: [...queue.tracks, stub], start: queue.tracks.length }));
    } else {
      await setQueue(await core('queue.jump', { position: i }));
    }
    load(id, true, at || 0);
  } catch (e) { fail(e); }
}

function renderLinkStatus() {
  const box = $('lanStatus'); if (!box) return;
  const sig = JSON.stringify([!!lan, lan?.role, lan?.locked, lan?.peers, lan?.invite, lanFingerprint]);
  if (sig === lanShown) return;
  lanShown = sig;
  box.replaceChildren();
  if (!lan) {
    const name = el('input', { type: 'text', value: lanName, placeholder: 'This device’s name', 'aria-label': 'Device name' });
    name.addEventListener('change', () => { lanName = name.value.trim() || 'Computer'; localStorage.setItem('lanName', lanName); });
    const invite = el('input', { type: 'text', placeholder: 'btlink:1:…', 'aria-label': 'Invite' });
    box.append(el('div', { class: 'row', style: 'margin:14px 0' }, el('button', { class: 'primary', text: 'Host on this network', onclick: hostLan }), name));
    box.append(el('h2', { text: 'Join with an invite' }), el('div', { class: 'row' }, invite, el('button', {
      text: 'Join',
      onclick: () => (/^btlink:/.test(invite.value.trim()) || invite.value.includes('link?i=')) ? joinLan(invite.value.trim()) : toast('Paste a btlink: invite first'),
    })));
    box.append(el('p', { class: 'muted', text: 'Opening a shared invite link joins automatically on the phone app.' }));
    return;
  }
  box.append(el('div', { class: 'row' }, el('b', { text: lan.role === 'host' ? `Hosting · ${lan.peers.length} linked` : 'Linked' }),
    el('button', { text: lan.role === 'host' ? 'Stop hosting' : 'Leave', onclick: leaveLan })));
  if (lan.role === 'host' && lan.invite) {
    box.append(el('div', { class: 'invite', text: lan.invite }));
    box.append(el('div', { class: 'row', style: 'margin:8px 0' },
      el('button', { text: 'Copy invite', onclick: () => navigator.clipboard?.writeText(lanLink(lan.invite)).then(() => toast('Invite copied')).catch(() => toast('Copy the invite text instead')) }),
      el('span', { class: 'muted', text: 'Open it on the other device, or paste the btlink: text there. Share it in person.' })));
    const lock = el('input', { type: 'checkbox' }); lock.checked = !!lan.locked;
    lock.addEventListener('change', () => core('lan.lock', { locked: lock.checked }).catch(fail));
    box.append(el('label', { class: 'row' }, lock, el('span', { text: 'Only I control playback' })));
  }
  if (lan.peers?.length) box.append(el('h2', { text: 'Linked devices' }), ...lan.peers.map((p) => el('div', { class: 'track' },
    el('div', { class: 't' }, el('b', { text: p.name || 'Device' }), el('span', { text: p.fingerprint || p.address })))));
  if (lan.rttMs != null) box.append(el('p', { class: 'muted', text: `Round trip ${Number(lan.rttMs).toFixed(1)} ms` }));
  if (lanFingerprint) box.append(el('p', { class: 'muted', text: `This device: ${lanFingerprint}` }));
}

// ---------- BetterYTS Sync: end-to-end encrypted library sync ----------
async function syncNow() {
  try {
    const r = await core('sync.now');
    library = await core('library.get');
    toast(r.changed ? 'Synced: this library took on what the other devices changed' : 'Synced: everything was already up to date');
    if (tab === 'settings') render(); else renderNow();
  } catch (e) { fail(e); }
}

/** On launch: sync quietly when the last sync is a few hours old (Settings has the button). */
async function syncQuietly() {
  try {
    const s = await core('sync.get');
    if (!s.enabled || Date.now() - (s.lastSync || 0) < 3 * 60 * 60 * 1000) return;
    const r = await core('sync.now');
    if (!r.changed) return;
    library = await core('library.get');
    if (tab === 'settings') render(); else renderNow();
  } catch (e) { /* offline: Settings › Sync now retries */ }
}

async function render() {
  document.querySelectorAll('#side nav button').forEach((b) => b.classList.toggle('on', b.dataset.tab === tab));
  const m = $('main'); m.replaceChildren();
  try { await views[tab](m); } catch (e) { m.append(el('p', { text: e.message })); }
}

// ---------- wiring ----------
document.querySelectorAll('#side nav button').forEach((b) => b.addEventListener('click', () => { tab = b.dataset.tab; render(); }));
$('play').onclick = togglePlay; $('next').onclick = () => next(true); $('prev').onclick = previous;
$('shuffle').onclick = async () => { try { await setQueue(await core('queue.shuffle')); } catch (e) { fail(e); } };
$('repeat').onclick = async () => { try { await setQueue(await core('queue.repeat')); } catch (e) { fail(e); } };
$('like').onclick = () => queue.current && toggleFav(queue.current);
$('dislike').onclick = async () => { const t = queue.current; if (!t) return; try { const r = await core('library.dislike', { track: t }); library = r.library; renderNow(); if (r.result) { toast('Disliked: stations won\'t play it'); next(true); } } catch (e) { fail(e); } };
$('radio').onclick = () => { const t = queue.current; if (t) startStation('track', t.id, t.title, art(t), t); };
document.addEventListener('keydown', (e) => {
  if (e.target.matches('input, select, textarea')) return;
  if (e.code === 'Space') { e.preventDefault(); togglePlay(); }
  else if (e.key === 'ArrowRight' && e.shiftKey) next(true);
  else if (e.key === 'ArrowLeft' && e.shiftKey) previous();
  else if (e.key === 'ArrowRight' && ytReady) yt.seekTo(position + 10, true);
  else if (e.key === 'ArrowLeft' && ytReady) yt.seekTo(Math.max(0, position - 10), true);
  else if (e.key === 'ArrowUp') { $('vol').value = String(Math.min(1000, Number($('vol').value) + 50)); applyVolume(); }
  else if (e.key === 'ArrowDown') { $('vol').value = String(Math.max(0, Number($('vol').value) - 50)); applyVolume(); }
  else if (e.key === 'l' || e.key === 'L') $('like').click();
  else if (e.key === '/') { e.preventDefault(); tab = 'search'; render(); }
});
listen('media-control', (e) => {
  const a = e.payload;
  if (a === 'toggle') togglePlay(); else if (a === 'play' && ytReady) yt.playVideo(); else if (a === 'pause' && ytReady) yt.pauseVideo();
  else if (a === 'next') next(true); else if (a === 'previous') previous();
});

(async function start() {
  document.body.classList.toggle('compact', !!prefs.compact);
  try {
    library = await core('library.get');
    settings = await core('settings.get').catch(() => null);
    await setQueue(await core('queue.get'));
    const st = await core('stations.list'); if (queue.current) station = st.current;
  } catch (e) { fail(e); }
  render();
  syncQuietly();
  setTimeout(() => $('splash').classList.add('gone'), matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : 1300);
})();
