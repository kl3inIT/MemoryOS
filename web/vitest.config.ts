import { fileURLToPath, URL } from "node:url";
import react from "@vitejs/plugin-react";
import { configDefaults, defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  test: {
    restoreMocks: true,
    // A public repository's GitHub runner has four cores; a developer machine keeps two free for everything else.
    maxWorkers: process.env.CI ? 4 : 2,
    reporters: process.env.CI
      ? [...configDefaults.reporters, ["junit", { includeConsoleOutput: false }]]
      : ["default"],
    outputFile: process.env.CI ? { junit: "reports/unit.xml" } : undefined,
    setupFiles: ["./src/test/setup.ts"],
    // Building a jsdom window is a third of a file's cost, so only component tests and the logic tests that touch a
    // browser API (marked with an @vitest-environment jsdom docblock) get one.
    projects: [
      { extends: true, test: { name: "dom", include: ["src/**/*.test.tsx"], environment: "jsdom" } },
      {
        extends: true,
        test: { name: "logic", include: ["src/**/*.test.ts", "scripts/**/*.test.mjs"], environment: "node" },
      },
    ],
  },
});
