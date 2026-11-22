/// <reference types="node" />
// It reads the installed packages, so it runs with Node's types; the app program has none of its own.
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const require = createRequire(import.meta.url);
const manifest = (path: string) =>
  JSON.parse(readFileSync(path, "utf8")) as { dependencies?: Record<string, string> };

describe("the PDF worker", () => {
  it("is the pdfjs-dist react-pdf runs, since pdf.js refuses a worker of another version", () => {
    const app = manifest(fileURLToPath(new URL("../../../package.json", import.meta.url)));
    const reactPdf = manifest(require.resolve("react-pdf/package.json"));

    expect(app.dependencies?.["pdfjs-dist"]).toBe(reactPdf.dependencies?.["pdfjs-dist"]);
  });
});
