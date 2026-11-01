// Fails the build when the JavaScript and CSS every page loads before rendering outgrow the budget.
// Counts gzip bytes of the entry script, its modulepreloads and the stylesheets index.html links;
// lazily imported chunks (viewers, charts, grammars) are outside the budget by design.
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { gzipSync } from "node:zlib";

// 309.3 KiB (316,723 bytes) measured after Sentry was removed. The image build now emits the same bundle as a
// local build (nothing injects a release any more), so the margin is only the usual ~6 KiB for ordinary growth.
// Ratchet: lower it when the initial load shrinks, never raise it to absorb an eager import.
// 317.0 KiB (324,600 bytes) with the Lark connector: a provider adds its translations, generated query options and
// setup route to the initial load by design, which used up the growth margin; the margin is restored, not widened.
// 323.3 KiB with radix-ui 1.8.1, which assistant-ui 0.15.29 requires (MEM-275): the older Radix measured 322.8 KiB, so
// the upgrade alone adds 0.5 KiB, and keeping 1.6.7 would ship a second Radix in the chat chunk instead.
const INITIAL_GZIP_BUDGET_BYTES = 332_000;

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
