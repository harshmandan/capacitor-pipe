import { WebPlugin } from '@capacitor/core';

import type { DockRect, PipePlayerPlugin, PlayerConfig, PlayerStatus } from './player';

const UNSUPPORTED =
  "capacitor-pipe's player is Android-only. On web, play the extracted stream URL " +
  'or SABR manifest with an ordinary <video> element instead.';

/**
 * Web stub.
 *
 * The player exists for native motion behaviour that a WebView cannot produce —
 * a drag-tracked, velocity-preserving transform over the host page — so there is
 * nothing meaningful to implement here. `getPlayerStatus()` reports honestly
 * rather than throwing, so feature detection works the same on every platform.
 */
export class PipePlayerWeb extends WebPlugin implements PipePlayerPlugin {
  async getPlayerStatus(): Promise<PlayerStatus> {
    return {
      available: false,
      media3Available: false,
      composeAvailable: false,
      attached: false,
      hlsAvailable: false,
      dashAvailable: false,
      media3UiComposeAvailable: false,
      corePipAvailable: false,
      pipSupported: false,
    };
  }

  async dock(_options: DockRect): Promise<void> {
    throw this.unavailable(UNSUPPORTED);
  }

  async undock(): Promise<void> {
    throw this.unavailable(UNSUPPORTED);
  }

  async load(_options: { url: string }): Promise<void> {
    throw this.unavailable(UNSUPPORTED);
  }

  async play(): Promise<void> {
    throw this.unavailable(UNSUPPORTED);
  }

  async pause(): Promise<void> {
    throw this.unavailable(UNSUPPORTED);
  }

  async release(): Promise<void> {
    throw this.unavailable(UNSUPPORTED);
  }

  async configure(_options: PlayerConfig): Promise<void> {
    throw this.unavailable(UNSUPPORTED);
  }

  async setFullscreen(_options: { fullscreen: boolean }): Promise<void> {
    throw this.unavailable(UNSUPPORTED);
  }

  async setMini(_options: { mini: boolean }): Promise<void> {
    throw this.unavailable(UNSUPPORTED);
  }

  async enterPip(): Promise<{ entered: boolean }> {
    // Reports rather than throws: PiP is an enhancement, so a host that asks
    // for it on web should get a plain "no" instead of an exception to catch.
    return { entered: false };
  }
}
