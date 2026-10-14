import { describe, expect, test } from 'bun:test';

import { PipePlayerWeb } from './player-web';
import { PipeWeb } from './web';

// Feature detection depends on which calls report and which throw, so the split is the contract.

describe('PipeWeb', () => {
  const pipe = new PipeWeb();

  test('extraction reports structured failure instead of throwing', async () => {
    const result = await pipe.extractStreamInfo({ videoUrl: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ' });
    expect(result.success).toBe(false);
    expect(result.attempts).toEqual([]);
  });

  test('opening a SABR session reports failure', async () => {
    const result = await pipe.openSabrSession({ videoUrl: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ' });
    expect(result.success).toBe(false);
  });

  test('session calls throw unavailable', async () => {
    await expect(pipe.closeSabrSession({ sessionId: 'x' })).rejects.toThrow();
  });

  test('engine status lists no engines', async () => {
    expect(await pipe.getEngineStatus()).toEqual({ available: [], media3Available: false });
  });
});

describe('PipePlayerWeb', () => {
  const player = new PipePlayerWeb();

  test('status reports unavailable', async () => {
    const status = await player.getPlayerStatus();
    expect(status.available).toBe(false);
    expect(status.attached).toBe(false);
  });

  test('position reads as nowhere', async () => {
    expect((await player.getPosition()).positionMs).toBe(0);
  });

  test('enterPip reports instead of throwing', async () => {
    expect(await player.enterPip()).toEqual({ entered: false });
  });

  test('playback calls throw unavailable', async () => {
    await expect(player.play()).rejects.toThrow();
  });
});
