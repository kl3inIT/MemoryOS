import { fileURLToPath, URL } from "node:url";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  test: {
    execArgv: Number(process.versions.node.split(".")[0]) >= 25 ? ["--no-webstorage"] : [],
    include: ["src/**/*.test.{ts,tsx}", "scripts/**/*.test.mjs", "tests/**/*.test.mjs"],
    environment: "jsdom",
    maxWorkers: 2,
    reporters: process.env.CI
      ? ["default", ["junit", { includeConsoleOutput: false }]]
      : ["default"],
    outputFile: process.env.CI ? { junit: "reports/unit.xml" } : undefined,
    setupFiles: ["./src/test/setup.ts"],
  },
});
