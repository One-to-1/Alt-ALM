/// <reference types="vitest/config" />
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
  test: {
    // The sanitiser is the reason this project has a test runner at all, and it needs a real HTML
    // parser to test against: its whole job is agreeing with the one that will render the memo.
    environment: 'jsdom',
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
  },
})
