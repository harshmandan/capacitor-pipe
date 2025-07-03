import { registerPlugin } from '@capacitor/core';

import type { NPEPlugin } from './definitions';

const NPE = registerPlugin<NPEPlugin>('NPE', {
  web: () => import('./web').then((m) => new m.NPEWeb()),
});

export * from './definitions';
export { NPE };
