import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Served behind the gateway at /csr/ (StripPrefix=1), so asset URLs carry the
// prefix while the nginx root stays /. In dev, every API the desk uses goes to
// the gateway: TMF doors, the AI plane, desk telemetry and the ontology.
export default defineConfig({
  base: '/csr/',
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/tmf-api': 'http://localhost:8080',
      '/ai': 'http://localhost:8080',
      '/insight': 'http://localhost:8080',
      '/ontology': 'http://localhost:8080',
    },
  },
});
