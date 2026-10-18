import { defineConfig } from 'vitest/config'
import { loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const target = process.env.BACKEND_URL || env.BACKEND_URL || 'http://127.0.0.1:8080'
  const proxy = {
    '/api': { target, changeOrigin: true },
    '/actuator/health': { target, changeOrigin: true },
  }
  return {
    plugins: [react()],
    server: { host: '127.0.0.1', port: 5173, strictPort: true, proxy,
      watch: { ignored: ['**/playwright-report/**', '**/test-results/**'] } },
    preview: { proxy },
    test: { environment: 'jsdom', include: ['src/**/*.test.ts'], restoreMocks: true },
  }
})
