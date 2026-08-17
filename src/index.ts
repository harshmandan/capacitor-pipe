import { registerPlugin } from '@capacitor/core';

import type { PipePlugin } from './definitions';
import type { PipePlayerPlugin } from './player';

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
export { Pipe, PipePlayer };

/**
 * @deprecated Renamed to `Pipe`. This library no longer wraps only
 * NewPipeExtractor — PipePipeExtractor is now the primary engine. The alias
 * will be removed in the next major version.
 */
export const NPE = Pipe;
