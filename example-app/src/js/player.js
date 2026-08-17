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
  const rect = { x: r.left, y: r.top, w: r.width, h: r.height };
  await PipePlayer.dock(rect);
  return rect;
};

document.getElementById('status').onclick = run('status', () => PipePlayer.getPlayerStatus());
document.getElementById('dock').onclick = run('dock', dock);
document.getElementById('undock').onclick = run('undock', () => PipePlayer.undock());
document.getElementById('load').onclick = run('load', () => PipePlayer.load({ url: SAMPLE }));
document.getElementById('play').onclick = run('play', () => PipePlayer.play());
document.getElementById('pause').onclick = run('pause', () => PipePlayer.pause());
document.getElementById('release').onclick = run('release', () => PipePlayer.release());

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
