import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    trace: 'on-first-retry',
    screenshot: 'only-on-failure'
  },
  expect: {
    timeout: 10_000
  },

  // Each application is exercised as its own project with its own baseURL.
  projects: [
    {
      name: 'angular',
      testMatch: /angular\.spec\.ts/,
      use: { ...devices['Desktop Chrome'], baseURL: 'http://localhost:4200' }
    },
    {
      name: 'spring',
      testMatch: /spring\.spec\.ts/,
      use: { ...devices['Desktop Chrome'], baseURL: 'http://localhost:8080' }
    },
    {
      name: 'ops-floor',
      testMatch: /ops-floor\.spec\.ts/,
      use: { ...devices['Desktop Chrome'], baseURL: 'http://localhost:5174' }
    }
  ],

  webServer: [
    {
      command: 'npm run start -- --host 127.0.0.1',
      cwd: '../seller-dashboard',
      url: 'http://localhost:4200',
      reuseExistingServer: !process.env.CI,
      timeout: 120_000
    },
    {
      command: './mvnw -q spring-boot:run',
      cwd: '../cs-console',
      url: 'http://localhost:8080',
      reuseExistingServer: !process.env.CI,
      timeout: 120_000
    },
    {
      command: 'npm run dev -- --port 5174 --strictPort',
      cwd: '../ops-floor',
      url: 'http://localhost:5174',
      reuseExistingServer: !process.env.CI,
      timeout: 120_000
    }
  ]
});