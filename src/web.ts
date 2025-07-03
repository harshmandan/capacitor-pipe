import { WebPlugin } from '@capacitor/core';

import type { NPEPlugin, StreamInfoResult } from './definitions';

export class NPEWeb extends WebPlugin implements NPEPlugin {
  async extractStreamInfo(options: { videoUrl: string }): Promise<StreamInfoResult> {
    console.log('NPE Web - extractStreamInfo not implemented for web platform', options);
    return {
      success: false,
      error: 'NPE plugin is only available on Android platform',
    };
  }
}
