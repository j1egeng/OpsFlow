import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './acceptance', testMatch: '**/*.spec.ts', workers: 1, fullyParallel: false, retries: 0,
  timeout: 180_000, outputDir: 'acceptance-results/artifacts',
  reporter: [['list'], ['html', { outputFolder: 'acceptance-results/report', open: 'never' }], ['json', { outputFile: 'acceptance-results/results.json' }]],
  use: { baseURL: 'http://127.0.0.1:4174', trace: 'retain-on-failure', screenshot: 'only-on-failure', timezoneId: 'Asia/Shanghai' },
  projects: [{ name: 'postgres-chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 1050 } } }],
  webServer: [
    { command: 'node acceptance/server.mjs', url: 'http://127.0.0.1:18082/actuator/health', timeout: 120_000, reuseExistingServer: false },
    { command: 'npm run dev -- --port 4174', url: 'http://127.0.0.1:4174', timeout: 30_000, reuseExistingServer: false, env: { BACKEND_URL: 'http://127.0.0.1:18082' } },
  ],
})
