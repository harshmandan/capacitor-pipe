import { registerPlugin } from '@capacitor/core';

import type { PipePlugin, SabrSessionResult } from './definitions';
import type { PipePlayerPlugin } from './player';
import { switchSabrQualityWith } from './sabr-quality';
import type { SwitchSabrQualityOptions } from './sabr-quality';

const Pipe = registerPlugin<PipePlugin>('Pipe', {
  web: () => import('./web').then((m) => new m.PipeWeb()),
});

/**
 * The optional native player, registered independently of the extractor.
 *
 * Importing this does not pull the player into your app on its own — its
 * Android dependencies are `compileOnly`. Call `getPlayerStatus()` to find out
 * whether it can actually run.
 */
const PipePlayer = registerPlugin<PipePlayerPlugin>('PipePlayer', {
  web: () => import('./player-web').then((m) => new m.PipePlayerWeb()),
});

export * from './definitions';
export * from './player';
export type { SwitchSabrQualityOptions } from './sabr-quality';
export { Pipe, PipePlayer };

/**
 * Switch the native player to another quality of the SABR video it is playing,
 * without restarting it: opens a session at the new height, loads it at the
 * current position, then closes the old session.
 *
 * Resolves the new session's result; keep its `sessionId` to close later. If
 * the new session cannot be opened, resolves that failure and the old session
 * keeps playing. If it opens but will not load, it is closed and the error is
 * rethrown.
 */
export function switchSabrQuality(options: SwitchSabrQualityOptions): Promise<SabrSessionResult> {
  return switchSabrQualityWith(Pipe, PipePlayer, options);
}

/**
 * @deprecated Renamed to `Pipe`. This library no longer wraps only
 * NewPipeExtractor — PipePipeExtractor is now the primary engine. The alias
 * will be removed in the next major version.
 */
export const NPE = Pipe;
