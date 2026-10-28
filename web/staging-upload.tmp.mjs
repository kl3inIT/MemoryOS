// Temporary: uploads files to a staging member's Chat library through the API's upload flow.
// Usage: node staging-upload.tmp.mjs <stateFile> <file>...
import { createHash, randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { basename } from "node:path";
import { request } from "@playwright/test";

const [stateFile, ...files] = process.argv.slice(2);
const app = "https://staging.vadan.app";
const ctx = await request.newContext({ baseURL: app, storageState: stateFile });
const headers = { "X-MemoryOS-CSRF": "1", Origin: app };
for (const file of files) {
  const bytes = readFileSync(file);
  const sha256 = createHash("sha256").update(bytes).digest("hex");
  const start = await ctx.post("/api/chat/files/uploads", {
    headers,
    data: { requestId: randomUUID(), filename: basename(file), mediaType: "text/csv", sizeBytes: bytes.length, sha256 },
  });
  const body = await start.json();
  if (!start.ok()) {
    console.log(basename(file), "initiate", start.status(), body.code, body.detail);
    continue;
  }
  const up = body.upload;
  const put = await ctx.fetch(new URL(up.uri, app).href, { method: up.method, headers: up.requiredHeaders ?? {}, data: bytes, timeout: 600_000 });
  const fin = await ctx.post(`/api/chat/files/${body.file.id}/finalize`, { headers });
  const finBody = await fin.text();
  console.log(basename(file), bytes.length, "put", put.status(), "finalize", fin.status(), finBody.slice(0, 160));
}
await ctx.dispose();
