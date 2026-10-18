import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: { baseURL: 'http://127.0.0.1:4173', trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: [
    { command: 'node e2e/start-backend.mjs', url: 'http://127.0.0.1:18081/actuator/health', timeout: 120_000, reuseExistingServer: false },
    { command: 'npm run dev -- --port 4173', url: 'http://127.0.0.1:4173', timeout: 30_000, reuseExistingServer: false,
      env: { BACKEND_URL: 'http://127.0.0.1:18081' } },
  ],
})
