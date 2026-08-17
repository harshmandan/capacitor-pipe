import { WebPlugin } from '@capacitor/core';

import type {
  EngineStatus,
  ExtractStreamInfoOptions,
  OpenSabrSessionOptions,
  PipePlugin,
  PoTokenOptions,
  SabrSessionResult,
  StreamInfoResult,
} from './definitions';

const UNSUPPORTED = 'capacitor-pipe requires the Android runtime; there is no web implementation.';

/**
 * Web stub.
 *
 * Extraction cannot run in a browser: it needs the native HTTP stack to reach
 * YouTube's InnerTube endpoints without CORS, and SABR additionally needs a
 * WebView to mint the BotGuard-attested PO token.
 */
export class PipeWeb extends WebPlugin implements PipePlugin {
  async extractStreamInfo(_options: ExtractStreamInfoOptions): Promise<StreamInfoResult> {
    return { success: false, error: UNSUPPORTED, attempts: [] };
  }

  async openSabrSession(_options: OpenSabrSessionOptions): Promise<SabrSessionResult> {
    return { success: false, error: UNSUPPORTED };
  }

  async closeSabrSession(_options: { sessionId: string }): Promise<void> {
    throw this.unavailable(UNSUPPORTED);
  }

  async providePoToken(_options: PoTokenOptions): Promise<void> {
    throw this.unavailable(UNSUPPORTED);
  }

  async getEngineStatus(): Promise<EngineStatus> {
    return { available: [], media3Available: false };
  }
}
