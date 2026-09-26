// Fails the build when the JavaScript and CSS every page loads before rendering outgrow the budget.
// Counts gzip bytes of the entry script, its modulepreloads and the stylesheets index.html links;
// lazily imported chunks (viewers, charts, grammars) are outside the budget by design.
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { gzipSync } from "node:zlib";

// Measured on the image build, which injects the Sentry release (about 7 KiB more than a local build).
// Ratchet: lower it when the initial load shrinks, never raise it to absorb an eager import.
const INITIAL_GZIP_BUDGET_BYTES = 390_000;

const dist = join(import.meta.dirname, "..", "dist");
const html = readFileSync(join(dist, "index.html"), "utf8");
const assets = new Set(
  [...html.matchAll(/(?:src|href)="\/(assets\/[^"]+\.(?:js|css))"/g)].map((match) => match[1]),
);
let total = 0;
const sizes = [];
for (const asset of assets) {
  const bytes = gzipSync(readFileSync(join(dist, asset))).length;
  total += bytes;
  sizes.push([bytes, asset]);
}
sizes.sort((a, b) => b[0] - a[0]);
const kib = (bytes) => `${(bytes / 1024).toFixed(1)} KiB`;
if (total > INITIAL_GZIP_BUDGET_BYTES) {
  console.error(
    `Initial load is ${kib(total)} gzip, over the ${kib(INITIAL_GZIP_BUDGET_BYTES)} budget. Largest:`,
  );
  for (const [bytes, asset] of sizes.slice(0, 8)) console.error(`  ${kib(bytes)}  ${asset}`);
  process.exit(1);
}
console.log(`Initial load ${kib(total)} gzip of ${kib(INITIAL_GZIP_BUDGET_BYTES)} budget.`);
