import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./tests/staging",
  workers: 1,
  retries: 0,
  forbidOnly: true,
  timeout: 540_000,
  expect: { timeout: 30_000 },
  reporter: "list",
  use: {
    ...devices["Desktop Chrome"],
    actionTimeout: 30_000,
    navigationTimeout: 45_000,
    trace: "off",
    screenshot: "off",
    video: "off",
  },
});
