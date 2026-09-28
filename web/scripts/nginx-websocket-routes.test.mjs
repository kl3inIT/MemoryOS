import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * Every WebSocket the browser opens has to reach the API as an upgrade. The general `/api/` location deliberately
 * sends `Connection ""`, so a path without its own location arrives as a plain GET and Spring answers
 * `400 Can "Upgrade" only to "WebSocket".` — which is exactly how meeting recording broke on staging while every
 * test passed, because the end-to-end run mocks the socket and the integration test never goes through Nginx.
 */
const WEBSOCKET_PATHS = [
  "/api/chat/voice/transcribe/stream",
  "/api/chat/voice/synthesize/stream",
  "/api/meeting-stream",
];

// Vitest runs with the web package as its root, so the config is one directory up from scripts/.
const config = readFileSync(join(process.cwd(), "nginx.conf"), "utf8");

/** The location blocks, in file order, as Nginx reads them. */
function locations(text) {
  const blocks = [];
  const header = /location\s+([^{]+?)\s*\{/g;
  let match;
  while ((match = header.exec(text))) {
    let depth = 1;
    let index = header.lastIndex;
    while (index < text.length && depth > 0) {
      if (text[index] === "{") depth += 1;
      else if (text[index] === "}") depth -= 1;
      index += 1;
    }
    blocks.push({ selector: match[1].trim(), body: text.slice(header.lastIndex, index - 1) });
  }
  return blocks;
}

/** Nginx picks an exact `=` match first, then the longest `^~` prefix, then the first matching regex. */
function matching(selector, path) {
  if (selector.startsWith("= ")) return selector.slice(2).trim() === path;
  if (selector.startsWith("~ ") || selector.startsWith("~* ")) {
    const source = selector.replace(/^~\*?\s+/, "");
    return new RegExp(source, selector.startsWith("~*") ? "i" : "").test(path);
  }
  if (selector.startsWith("^~ ")) return path.startsWith(selector.slice(3).trim());
  return path.startsWith(selector);
}

function chosen(path) {
  const blocks = locations(config);
  return (
    blocks.find((block) => matching(block.selector, path) && block.selector.startsWith("= ")) ??
    blocks.find((block) => matching(block.selector, path))
  );
}

describe("nginx WebSocket routes", () => {
  it.each(WEBSOCKET_PATHS)("upgrades %s", (path) => {
    const block = chosen(path);
    expect(block, `no location in nginx.conf matches ${path}`).toBeDefined();
    expect(block.body, `${block.selector} does not forward the upgrade`).toMatch(
      /proxy_set_header\s+Upgrade\s+\$http_upgrade\s*;/,
    );
    expect(block.body).toMatch(/proxy_set_header\s+Connection\s+"upgrade"\s*;/);
    expect(block.body, `${block.selector} needs HTTP/1.1 to upgrade`).toMatch(
      /proxy_http_version\s+1\.1\s*;/,
    );
  });

  it("keeps the general API location free of the upgrade header", () => {
    const general = locations(config).find((block) => block.selector.includes("oauth2|login"));
    expect(general).toBeDefined();
    expect(general.body).toMatch(/proxy_set_header\s+Connection\s+""\s*;/);
  });
});
