import type { PipePlugin, SabrSessionResult } from './definitions';
import type { PipePlayerPlugin } from './player';

export interface SwitchSabrQualityOptions {
  /** The session the player is playing now. Closed only once the new one is loaded. */
  sessionId: string;
  /**
   * The URL that session was opened with, exactly as passed then. The native
   * side reuses the earlier extraction only for an identical URL, and that
   * reuse is most of what makes the switch fast.
   */
  videoUrl: string;
  /** The new quality, in pixels — 720 for 720p. See `OpenSabrSessionOptions.maxHeight`. */
  maxHeight: number;
}

/**
 * Switch the native player to another quality of the SABR video it is playing.
 *
 * A session serves one quality, so this opens a second session at the new
 * height, loads it at the current position, and closes the old one. The order
 * is the point: the player keeps reading the old session until the new one is
 * loaded, so closing it first would cut playback.
 *
 * Resolves the new session's result — keep its `sessionId`, because that is the
 * session to close when playback ends. If the new session cannot be opened,
 * resolves that failure and leaves the old session playing untouched. If it
 * opens but will not load, the new session is closed and the error rethrown.
 */
export async function switchSabrQualityWith(
  pipe: Pick<PipePlugin, 'openSabrSession' | 'closeSabrSession'>,
  player: Pick<PipePlayerPlugin, 'getPosition' | 'load' | 'play'>,
  options: SwitchSabrQualityOptions,
): Promise<SabrSessionResult> {
  const before = await player.getPosition();
  const opened = await pipe.openSabrSession({
    videoUrl: options.videoUrl,
    maxHeight: options.maxHeight,
    startPositionMs: before.positionMs,
  });
  if (!opened.success || !opened.sessionId) {
    return opened;
  }

  // Playback kept going while the session opened; resume from where it is now.
  const now = await player.getPosition();
  try {
    await player.load({ sessionId: opened.sessionId, startPositionMs: now.positionMs });
    if (now.playing) {
      await player.play();
    }
  } catch (error) {
    await pipe.closeSabrSession({ sessionId: opened.sessionId }).catch(() => undefined);
    throw error;
  }

  // The new session is playing; a failure to close the old one (already closed,
  // say) must not report the switch itself as failed.
  await pipe.closeSabrSession({ sessionId: options.sessionId }).catch(() => undefined);
  return opened;
}
