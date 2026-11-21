#!/usr/bin/env node
// Drives the MemoryOS web app served by `pnpm --dir web dev:e2e` (Vite + fixture backend on 127.0.0.1:4173).
// It fakes the signed-in identity, runs one command per line, and saves screenshots under output/run-memoryos/.
//
//   node .skills/run-memoryos/driver.mjs [options] ["command" ...]      commands as arguments
//   @'
//   goto /
//   shot home
//   '@ | node .skills/run-memoryos/driver.mjs [options]                 commands on stdin (PowerShell)
//
// Options:
//   --identity owner|member|signed-out|<file.json>   default owner (every capability)
//   --lang vi|en                                     identity uiLanguage and browser locale, default vi
//   --mobile                                         390x844 touch viewport instead of 1440x900
//   --route "<path|glob>=<file.json | inline JSON>"  answer with 200 JSON; /api/x matches that path with any query,
//                                                    anything else is a Playwright URL glob; repeatable
//   --base <url>                                     default http://127.0.0.1:4173
//   --headed                                         show the browser window
//
// Commands (selectors are Playwright selectors: role=button[name="Gửi"], text=Hello, css, ...):
//   goto <path>                 navigate, wait for the network to settle, print the final URL
//   click <selector>
//   fill <selector> :: <text>
//   press <key>                 keyboard key on the focused element, e.g. Enter, Escape
//   wait <selector | ms>        wait for a visible element or a fixed delay
//   text <selector>             print innerText of the first match
//   aria [selector]             print the ARIA snapshot (default body); use it to find selectors
//   eval <js expression>        print the result of evaluating it in the page
//   shot <name> [full]          save output/run-memoryos/<name>.png (full = whole page)
//   viewport <w> <h>
import { createRequire } from "node:module";
import { mkdirSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
// @playwright/test is installed only in web/; resolve it from there.
const { chromium } = createRequire(join(repoRoot, "web/package.json"))("@playwright/test");
const outDir = join(repoRoot, "output/run-memoryos");

const ALL_CAPABILITIES = [
  "SYSTEM_ADMIN",
  "SYSTEM_BASIC",
  "SEARCH_READ",
  "CHAT_READ",
  "CHAT_WRITE",
  "IMAGE_GENERATE",
  "LLM_GATEWAY_USE",
  "USERS_MANAGE",
  "GROUPS_READ",
  "GROUPS_MANAGE",
  "SOURCES_READ",
  "SOURCES_MANAGE",
  "SOURCES_DELETE",
  "MODELS_MANAGE",
  "MCP_MANAGE",
  "AGENTS_CREATE",
  "AGENTS_MANAGE",
  "AUDIT_READ",
  "CHAT_HISTORY_READ",
];
const MEMBER_CAPABILITIES = [
  "SYSTEM_BASIC",
  "SEARCH_READ",
  "CHAT_READ",
  "CHAT_WRITE",
  "IMAGE_GENERATE",
  "LLM_GATEWAY_USE",
];

function parseOptions(argv) {
  const options = { identity: "owner", lang: "vi", mobile: false, routes: [], base: "http://127.0.0.1:4173", headed: false, commands: [] };
  for (let index = 0; index < argv.length; index++) {
    const arg = argv[index];
    if (arg === "--identity") options.identity = argv[++index];
    else if (arg === "--lang") options.lang = argv[++index];
    else if (arg === "--mobile") options.mobile = true;
    else if (arg === "--route") options.routes.push(argv[++index]);
    else if (arg === "--base") options.base = argv[++index];
    else if (arg === "--headed") options.headed = true;
    else if (arg.startsWith("--")) throw new Error(`Unknown option ${arg}`);
    else options.commands.push(arg);
  }
  return options;
}

function identityFor(name, lang) {
  if (name === "signed-out") return null;
  if (name === "owner" || name === "member") {
    const owner = name === "owner";
    return {
      actorId: owner ? "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1" : "97c41cb9-55ae-4a52-94ab-7aad59be91e5",
      displayName: owner ? "Nguyễn Minh Anh" : "Trần Quốc Bảo",
      authorizationVersion: 1,
      uiLanguage: lang,
      tenant: { displayName: "Công ty Minh Long", role: owner ? "OWNER" : "MEMBER" },
      capabilities: owner ? ALL_CAPABILITIES : MEMBER_CAPABILITIES,
      scopedCapabilities: [],
    };
  }
  return JSON.parse(readFileSync(resolve(name), "utf8"));
}

function routeBody(value) {
  const trimmed = value.trim();
  const body = (
    trimmed.startsWith("{") || trimmed.startsWith("[") ? trimmed : readFileSync(resolve(trimmed), "utf8")
  ).replace(/^﻿/, "");
  try {
    JSON.parse(body);
  } catch {
    // PowerShell strips the double quotes out of an inline JSON argument; a file is safe.
    throw new Error(`--route body is not valid JSON: ${body.slice(0, 60)}... Pass a .json file instead.`);
  }
  return body;
}

async function readStdin() {
  if (process.stdin.isTTY) return [];
  let text = "";
  for await (const chunk of process.stdin) text += chunk;
  return text.split(/\r?\n/);
}

async function run(page, line) {
  const trimmed = line.trim();
  if (!trimmed || trimmed.startsWith("#")) return;
  const space = trimmed.indexOf(" ");
  const command = space < 0 ? trimmed : trimmed.slice(0, space);
  const rest = space < 0 ? "" : trimmed.slice(space + 1).trim();
  console.log(`> ${trimmed}`);
  switch (command) {
    case "goto":
      await page.goto(rest || "/");
      await page.waitForLoadState("networkidle").catch(() => {});
      console.log(`url ${page.url()}`);
      return;
    case "click":
      await page.locator(rest).first().click();
      return;
    case "fill": {
      const [selector, text = ""] = rest.split(" :: ");
      await page.locator(selector).first().fill(text);
      return;
    }
    case "press":
      await page.keyboard.press(rest);
      return;
    case "wait":
      if (/^\d+$/.test(rest)) await page.waitForTimeout(Number(rest));
      else await page.locator(rest).first().waitFor({ state: "visible" });
      return;
    case "text":
      console.log(await page.locator(rest).first().innerText());
      return;
    case "aria":
      console.log(await page.locator(rest || "body").first().ariaSnapshot());
      return;
    case "eval":
      console.log(JSON.stringify(await page.evaluate(rest), null, 2));
      return;
    case "shot": {
      const [name, mode] = rest.split(/\s+/);
      const path = join(outDir, `${name || "shot"}.png`);
      await page.screenshot({ path, fullPage: mode === "full" });
      console.log(`saved ${path}`);
      return;
    }
    case "viewport": {
      const [width, height] = rest.split(/\s+/).map(Number);
      await page.setViewportSize({ width, height });
      return;
    }
    default:
      throw new Error(`Unknown command "${command}"`);
  }
}

const options = parseOptions(process.argv.slice(2));
const commands = options.commands.length ? options.commands : await readStdin();
const identity = identityFor(options.identity, options.lang);
const stubs = options.routes.map((spec) => {
  const separator = spec.indexOf("=");
  if (separator < 1) throw new Error(`--route needs <path|glob>=<json>, got ${spec.slice(0, 60)}`);
  const pattern = spec.slice(0, separator);
  // "/api/groups" matches that exact path with any query string; anything else is a Playwright URL glob.
  return {
    matcher: pattern.startsWith("/") ? (url) => url.pathname === pattern : pattern,
    body: routeBody(spec.slice(separator + 1)),
  };
});
mkdirSync(outDir, { recursive: true });

const browser = await chromium.launch({ headless: !options.headed });
const context = await browser.newContext({
  baseURL: options.base,
  locale: options.lang === "en" ? "en-US" : "vi-VN",
  ...(options.mobile
    ? { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 }
    : { viewport: { width: 1440, height: 900 } }),
});
await context.route("**/api/identity/me", (route) =>
  identity ? route.fulfill({ json: identity }) : route.fulfill({ status: 401 }),
);
for (const { matcher, body } of stubs)
  await context.route(matcher, (route) => route.fulfill({ status: 200, contentType: "application/json", body }));

const page = await context.newPage();
page.on("console", (message) => {
  // A failed request is already printed as an [api ...] line.
  if (message.type() === "error" && !message.text().startsWith("Failed to load resource"))
    console.log(`[console.error] ${message.text()}`);
});
page.on("pageerror", (error) => console.log(`[pageerror] ${error.message}`));
page.on("response", (response) => {
  const url = new URL(response.url());
  if (url.pathname.startsWith("/api/") && response.status() >= 400)
    console.log(`[api ${response.status()}] ${response.request().method()} ${url.pathname}${url.search}`);
});

let failed = false;
try {
  for (const line of commands) await run(page, line);
} catch (error) {
  failed = true;
  console.log(`[failed] ${error.message.split("\n")[0]}`);
  const path = join(outDir, "failure.png");
  await page.screenshot({ path }).catch(() => {});
  console.log(`saved ${path}`);
} finally {
  await browser.close();
}
process.exit(failed ? 1 : 0);
