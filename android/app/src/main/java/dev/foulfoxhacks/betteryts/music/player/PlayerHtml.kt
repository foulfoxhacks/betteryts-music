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
  var player = null, ready = false, pending = null, rate = 1;

  function load(id, start, autoplay) {
    if (!ready) { pending = [id, start, autoplay]; return; }
    var o = { videoId: id, startSeconds: start || 0 };
    if (autoplay) player.loadVideoById(o); else player.cueVideoById(o);
  }
  function play() { if (ready) player.playVideo(); }
  function pause() { if (ready) player.pauseVideo(); }
  function seek(t) { if (ready) player.seekTo(t, true); }
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
          if (e.data == 1) player.setPlaybackRate(rate);
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
