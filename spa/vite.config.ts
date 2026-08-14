import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // Dev-only convenience: the BFF serves the built SPA from its own origin in
    // production, so no proxy (and no CORS config) is needed there. Locally the
    // SPA dev server and the BFF run on different ports, so forward /api calls.
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
