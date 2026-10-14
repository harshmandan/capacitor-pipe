import { describe, expect, test } from 'bun:test';

import type { PlayerPosition } from './player';
import { switchSabrQualityWith } from './sabr-quality';

const URL = 'https://www.youtube.com/watch?v=iUtnZpzkbG8';

function position(positionMs: number, playing = true): PlayerPosition {
  return { positionMs, durationMs: 185_000, bufferedMs: 0, playing, ended: false, live: false };
}

/** Records every call in order, so a test can assert the sequence, not just the outcome. */
function fakes(opts: { openFails?: boolean; loadFails?: boolean; playing?: boolean } = {}) {
  const calls: string[] = [];
  const positions = [position(30_000, opts.playing ?? true), position(31_500, opts.playing ?? true)];
  const pipe = {
    async openSabrSession(o: { videoUrl: string; maxHeight?: number; startPositionMs?: number }) {
      calls.push(`open ${o.maxHeight} @${o.startPositionMs}`);
      return opts.openFails
        ? { success: false, error: 'nope' }
        : { success: true, sessionId: 'new', manifestUrl: 'http://127.0.0.1/new/manifest.mpd' };
    },
    async closeSabrSession(o: { sessionId: string }) {
      calls.push(`close ${o.sessionId}`);
    },
  };
  const player = {
    async getPosition() {
      return positions.shift() ?? position(0);
    },
    async load(o: { sessionId?: string; startPositionMs?: number }) {
      calls.push(`load ${o.sessionId} @${o.startPositionMs}`);
      if (opts.loadFails) throw new Error('load failed');
    },
    async play() {
      calls.push('play');
    },
  };
  return { calls, pipe, player };
}

describe('switchSabrQualityWith', () => {
  test('opens the new session before closing the old, resuming from the latest position', async () => {
    const { calls, pipe, player } = fakes();
    const result = await switchSabrQualityWith(pipe, player, { sessionId: 'old', videoUrl: URL, maxHeight: 720 });

    expect(result.sessionId).toBe('new');
    expect(calls).toEqual(['open 720 @30000', 'load new @31500', 'play', 'close old']);
  });

  test('a paused player stays paused', async () => {
    const { calls, pipe, player } = fakes({ playing: false });
    await switchSabrQualityWith(pipe, player, { sessionId: 'old', videoUrl: URL, maxHeight: 480 });
    expect(calls).not.toContain('play');
  });

  test('if the new session cannot open, the old one is left playing', async () => {
    const { calls, pipe, player } = fakes({ openFails: true });
    const result = await switchSabrQualityWith(pipe, player, { sessionId: 'old', videoUrl: URL, maxHeight: 1080 });

    expect(result.success).toBe(false);
    expect(calls).toEqual(['open 1080 @30000']);
  });

  test('if the new session will not load, it is closed and the old one kept', async () => {
    const { calls, pipe, player } = fakes({ loadFails: true });
    await expect(
      switchSabrQualityWith(pipe, player, { sessionId: 'old', videoUrl: URL, maxHeight: 720 }),
    ).rejects.toThrow('load failed');
    expect(calls).toEqual(['open 720 @30000', 'load new @31500', 'close new']);
  });
});
