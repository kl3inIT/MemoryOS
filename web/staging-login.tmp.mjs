// Temporary: signs a staging account in through Keycloak admin impersonation and saves the browser state.
// Usage: node staging-login.tmp.mjs <username> <tokenFile> <stateFile>
import { readFileSync } from "node:fs";
import { chromium } from "@playwright/test";

const [username, tokenFile, stateFile] = process.argv.slice(2);
const token = readFileSync(tokenFile, "utf8").trim();
const kc = "https://auth.kl3in.tech";
const app = "https://staging.vadan.app";

const browser = await chromium.launch();
const context = await browser.newContext();
const auth = { Authorization: `Bearer ${token}` };
const users = await (
  await context.request.get(
    `${kc}/admin/realms/memoryos/users?exact=true&username=${encodeURIComponent(username)}`,
    { headers: auth },
  )
).json();
if (users.length !== 1) throw new Error(`user lookup: ${JSON.stringify(users).slice(0, 200)}`);
const imp = await context.request.post(`${kc}/admin/realms/memoryos/users/${users[0].id}/impersonation`, {
  headers: auth,
});
if (!imp.ok()) throw new Error(`impersonation ${imp.status()}`);
const page = await context.newPage();
await page.goto(`${app}/oauth2/authorization/memoryos`);
await page.waitForURL((url) => url.origin === app && !url.pathname.startsWith("/oauth2"), { timeout: 30_000 });
const me = await context.request.get(`${app}/api/identity/me`, {
  headers: { "X-MemoryOS-CSRF": "1", Origin: app },
});
console.log("me", me.status());
await context.storageState({ path: stateFile });
await browser.close();
