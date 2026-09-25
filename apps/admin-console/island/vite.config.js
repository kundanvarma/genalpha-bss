import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/* One bundle for every React island in the back office.
 *
 * The console is a vanilla shell: it owns sign-in, the navigation, the command
 * palette and the page frame, and a desk may already render its own panel
 * instead of the generic table (core/list-dispatch.js). An island mounts a
 * React root INSIDE that panel — nothing else changes. The bundle is a classic
 * script that hangs one function on `window`, so it loads in the same list as
 * the desks and needs no module loader in the page.
 */
export default defineConfig({
  // lib mode does not set NODE_ENV for the bundled dependencies, and React
  // ships its development build unless told otherwise — 648 kB against 140 kB,
  // with the slow paths and the warnings a console does not want in production
  define: { 'process.env.NODE_ENV': '"production"' },
  build: {
    lib: { entry: 'src/mount.jsx', name: 'ConsoleIslands', formats: ['iife'], fileName: () => 'islands.js' },
    outDir: process.env.ISLAND_OUT || '../site/island',
    emptyOutDir: true,
    rollupOptions: { output: { assetFileNames: 'islands.[ext]' } },
  },
});
