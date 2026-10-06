import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    strictPort: true,
    // With VITE_CS_URL set to '' the live feed is fetched from this origin and forwarded here, so
    // a browser that is not on the same machine as the stack (a Codespace) still reaches it.
    proxy: { '/api': 'http://localhost:8080' },
  },
});
