// Temporary: calls the staging API with a saved browser state.
// Usage: node staging-api.tmp.mjs <stateFile> <METHOD> <path> [json body] [--upload field=file]
import { readFileSync } from "node:fs";
import { basename } from "node:path";
import { request } from "@playwright/test";

const [stateFile, method, path, ...rest] = process.argv.slice(2);
const app = "https://staging.vadan.app";
const ctx = await request.newContext({ baseURL: app, storageState: stateFile });
const options = { method, headers: { "X-MemoryOS-CSRF": "1", Origin: app }, maxRedirects: 0, timeout: 120_000 };
const upload = rest.find((a) => a.startsWith("--upload"));
if (upload) {
  const [field, file] = upload.slice("--upload ".length || 9).split("=");
  options.multipart = { [field]: { name: basename(file), mimeType: "application/octet-stream", buffer: readFileSync(file) } };
} else if (rest[0]) {
  options.data = JSON.parse(rest[0]);
}
const response = await ctx.fetch(path, options);
const text = await response.text();
console.log(response.status(), text.length > 4000 ? text.slice(0, 4000) + " …" : text);
await ctx.dispose();
