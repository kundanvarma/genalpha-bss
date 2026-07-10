import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Served behind the gateway at /shop/ (StripPrefix=1), so asset URLs carry the
// prefix while the nginx root stays /.
export default defineConfig({
  base: '/shop/',
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/tmf-api': 'http://localhost:8080',
    },
  },
});
