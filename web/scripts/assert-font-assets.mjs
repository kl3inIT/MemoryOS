import { readdir, readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";

const distDirectory = fileURLToPath(new URL("../dist/", import.meta.url));
const emittedFiles = await readdir(distDirectory, { recursive: true });
const styleSheets = emittedFiles.filter((file) => file.endsWith(".css"));
const fontFiles = emittedFiles.filter((file) => file.endsWith(".woff2"));

if (styleSheets.length === 0) {
  throw new Error("The production build emitted no CSS to inspect");
}
if (fontFiles.length === 0) {
  throw new Error("The production build emitted no same-origin WOFF2 assets");
}

for (const styleSheet of styleSheets) {
  const css = await readFile(path.join(distDirectory, styleSheet), "utf8");
  if (/url\((?:["'])?data:font/iu.test(css)) {
    throw new Error(`${styleSheet} contains a CSP-incompatible inline font`);
  }
}

console.log(`Verified ${fontFiles.length} emitted WOFF2 assets with no inline font URLs.`);
