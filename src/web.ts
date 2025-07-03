import { WebPlugin } from '@capacitor/core';

import type { NPEPlugin } from './definitions';

export class NPEWeb extends WebPlugin implements NPEPlugin {
  async echo(options: { value: string }): Promise<{ value: string }> {
    console.log('ECHO', options);
    return options;
  }
}
