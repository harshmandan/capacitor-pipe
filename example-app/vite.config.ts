import { defineConfig } from 'vite';
import { resolve } from 'path';

export default defineConfig({
  root: './src',
  build: {
    outDir: '../dist',
    minify: false,
    emptyOutDir: true,
    rollupOptions: {
      // Two pages: the existing extractor demo and the player composite proof.
      input: {
        index: resolve(__dirname, 'src/index.html'),
        player: resolve(__dirname, 'src/player.html'),
      },
    },
  },
});
