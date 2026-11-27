package dev.foulfoxhacks.betteryts.music.player

/** The page inside the player WebView: YouTube's IFrame API, with a small command surface. */
object PlayerHtml {
    const val ORIGIN = "https://yts.foulfoxhacks.dev"

    val PAGE = """
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
  html, body { margin: 0; height: 100%; background: #000; overflow: hidden; }
  #p { position: absolute; inset: 0; width: 100%; height: 100%; }
</style>
</head>
<body>
<div id="p"></div>
<script>
  var player = null, ready = false, pending = null, rate = 1, vol = 100, cc = null;

  function load(id, start, autoplay) {
    if (!ready) { pending = [id, start, autoplay]; return; }
    var o = { videoId: id, startSeconds: start || 0 };
    if (autoplay) player.loadVideoById(o); else player.cueVideoById(o);
  }
  function play() { if (ready) player.playVideo(); }
  function pause() { if (ready) player.pauseVideo(); }
  function seek(t) { if (ready) player.seekTo(t, true); }
  function volume(v) { vol = v; if (ready) player.setVolume(v); }
  // Captions: list the video's tracks (reported through Bridge.onCaptions), or show one / none.
  function captionTracks() {
    if (!ready) return;
    try { player.loadModule('captions'); } catch (e) {}
    setTimeout(function () {
      var list = [];
      try { list = player.getOption('captions', 'tracklist') || []; } catch (e) {}
      Bridge.onCaptions(JSON.stringify(list.map(function (t) { return { code: t.languageCode, name: t.displayName || t.languageName || t.languageCode }; })));
      if (!cc) { try { player.unloadModule('captions'); } catch (e) {} }
    }, 700);
  }
  function captions(code) {
    cc = code || null;
    if (!ready) return;
    try {
      if (!cc) { player.unloadModule('captions'); return; }
      player.loadModule('captions');
      player.setOption('captions', 'track', { languageCode: cc });
    } catch (e) { console.log('captions ' + e); }
  }
  function speed(r) { rate = r; if (ready) { player.setPlaybackRate(r); console.log('speed ' + r + ' -> ' + player.getPlaybackRate()); } }

  window.onYouTubeIframeAPIReady = function () {
    player = new YT.Player('p', {
      host: 'https://www.youtube-nocookie.com',
      width: '100%', height: '100%',
      playerVars: { playsinline: 1, controls: 0, rel: 0, iv_load_policy: 3, fs: 0, disablekb: 1, origin: '$ORIGIN' },
      events: {
        onReady: function () {
          ready = true;
          Bridge.onReady();
          if (pending) { load(pending[0], pending[1], pending[2]); pending = null; }
        },
        onStateChange: function (e) {
          // Each new video starts at 1x; put the chosen speed back.
          if (e.data == 1) { player.setPlaybackRate(rate); player.setVolume(vol); if (cc) captions(cc); }
          Bridge.onState(e.data);
        },
        onError: function (e) { Bridge.onError(e.data); }
      }
    });
    setInterval(function () {
      if (ready && player.getCurrentTime) Bridge.onTime(player.getCurrentTime() || 0, player.getDuration() || 0);
    }, 500);
  };

  var tag = document.createElement('script');
  tag.src = 'https://www.youtube.com/iframe_api';
  document.head.appendChild(tag);
</script>
</body>
</html>
""".trimIndent()
}
