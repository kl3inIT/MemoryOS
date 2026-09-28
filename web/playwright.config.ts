import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./tests/e2e",
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: 0,
  workers: process.env.CI ? 1 : undefined,
  // A failing shard annotates the run and prints its own assertion, which is what anybody reads. Nothing keeps a
  // blob report: merging them into an HTML nobody opened was most of what filled the artifact store.
  reporter: process.env.CI ? [["github"], ["line"]] : "list",
  use: {
    baseURL: "http://127.0.0.1:4173",
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
    video: "retain-on-failure",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  webServer: {
    command: "pnpm dev:e2e",
    reuseExistingServer: false,
    // Preview mode (MEMORYOS_E2E_PREVIEW=1) runs a production build before it serves.
    timeout: process.env.MEMORYOS_E2E_PREVIEW === "1" ? 240_000 : 120_000,
    url: "http://127.0.0.1:4173",
  },
});
