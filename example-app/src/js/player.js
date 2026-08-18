import { PipePlayer } from 'capacitor-pipe';

// A plain progressive MP4. Phase 1 deliberately avoids the extractor: the risk
// being tested is native-over-WebView compositing, and mixing extraction in
// would confuse a failure there with a failure in SABR.
//
// Not the commondatastorage.googleapis.com Big Buck Bunny that most samples
// use — that bucket now answers 403, which surfaces as a silent black surface
// rather than an obvious error.
const SAMPLE =
  'https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_1MB.mp4';

const log = (...parts) => {
  const line = parts
    .map((p) => (typeof p === 'string' ? p : JSON.stringify(p, null, 2)))
    .join(' ');
  document.getElementById('log').textContent = line;
  console.log('[player]', line);
};

const run = (label, fn) => async () => {
  try {
    const result = await fn();
    log(label, result === undefined ? 'ok' : result);
  } catch (error) {
    log(label, 'FAILED:', error?.message ?? String(error));
  }
};

/**
 * Measure the reserved element and hand its rect over.
 *
 * The host never says "go fullscreen" or "animate to here" — it reports where
 * its space is, and the player owns the transition. Re-measured on resize
 * because that rect is the only thing the player knows about the layout.
 */
const dock = async () => {
  const r = document.getElementById('stage').getBoundingClientRect();
  // Send the WebView's own pixel ratio: assuming it equals Android's
  // resources density leaves a gap inside the reserved rect when they differ.
  const rect = {
    x: r.left,
    y: r.top,
    w: r.width,
    h: r.height,
    dpr: window.devicePixelRatio,
  };
  await PipePlayer.dock(rect);
  return rect;
};

document.getElementById('status').onclick = run('status', () => PipePlayer.getPlayerStatus());
document.getElementById('dock').onclick = run('dock', dock);
document.getElementById('undock').onclick = run('undock', () => PipePlayer.undock());
document.getElementById('load').onclick = run('load', async () => {
  // Accent is the only styling hook. Extra buttons get our chrome and a glyph.
  await PipePlayer.configure({
    // Any colour. Drives the seekbar fill, the scrub thumb, the LIVE dot
    // and the PiP/mini progress line. Deliberately not the default red here,
    // so it is obvious the prop is doing the work.
    accentColor: '#2979FF',
    showPreviousNext: true,
    title: 'Big Buck Bunny — sample clip',
    subtitle: 'Blender Foundation · 10s test clip',
    speedLabel: '1x',
    qualityLabel: '720p',
    // The player presents these; it does not invent them.
    speeds: ['0.5x', '0.75x', '1x', '1.25x', '1.5x', '2x'],
    qualities: ['Auto', '1080p', '720p', '480p', '360p', '144p'],
    // One custom slot, after the fixed speed and quality buttons.
    button: {
      id: 'like',
      icon: 'M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z',
    },
  });
  return PipePlayer.load({ url: SAMPLE });
});

PipePlayer.addListener('playerAction', (event) => {
  log('action', event);

  /*
   * The YouTube pattern. Expand was pressed on a page that never claimed a
   * rect — typically because the user minimised here and then navigated away —
   * so the player stayed in the corner and told us. A real app routes back to
   * the watch page; when that page mounts and calls dock(), the player is
   * already there and still playing.
   */
  if (event.action === 'expandUnavailable') {
    log('expandUnavailable', 'route back to the watch page here, then dock()');
  }
});
document.getElementById('play').onclick = run('play', () => PipePlayer.play());
document.getElementById('pause').onclick = run('pause', () => PipePlayer.pause());
document.getElementById('release').onclick = run('release', () => PipePlayer.release());
// Deterministic path to the same transform the gesture drives — isolates
// "is the interpolation right" from "did the gesture fire".
document.getElementById('fs').onclick = run('fullscreen', () =>
  PipePlayer.setFullscreen({ fullscreen: true }));
document.getElementById('unfs').onclick = run('exit', () =>
  PipePlayer.setFullscreen({ fullscreen: false }));

// Mini is the SAME player travelling to the corner, so playback never stops.
document.getElementById('mini').onclick = run('mini', () =>
  PipePlayer.setMini({ mini: true }));
document.getElementById('unmini').onclick = run('restore', () =>
  PipePlayer.setMini({ mini: false }));

// PiP needs BOTH the opt-in below and android:supportsPictureInPicture on the
// host activity — see AndroidManifest.xml. On Android 12+ the system enters PiP
// by itself on swipe-home; this button is the explicit route.
document.getElementById('pip').onclick = run('pip', async () => {
  await PipePlayer.configure({ pip: true });
  return PipePlayer.enterPip();
});

// FLAG_SECURE is a WINDOW flag: it blacks out the host page too, not just the
// video. Take a screenshot after enabling to see it work.
document.getElementById('secure').onclick = run('secure', () =>
  PipePlayer.configure({ secure: true }));
document.getElementById('unsecure').onclick = run('secure off', () =>
  PipePlayer.configure({ secure: false }));

// HLS live. Fails without media3-exoplayer-hls on the app's classpath, which is
// exactly the failure getPlayerStatus().hlsAvailable exists to predict.
document.getElementById('live').onclick = run('live', async () => {
  await PipePlayer.configure({ title: 'Live test stream', subtitle: 'HLS' });
  return PipePlayer.load({
    url: 'https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8',
  });
});

// Keep the claimed rect honest across rotation and keyboard insets.
window.addEventListener('resize', () => {
  dock().catch(() => {
    /* not docked yet; nothing to correct */
  });
});

// Report availability on load, so a missing Media3/Compose is obvious
// immediately rather than as a rejection three taps later.
PipePlayer.getPlayerStatus()
  .then((status) => log('status', status))
  .catch((error) => log('status', 'FAILED:', error?.message ?? String(error)));
