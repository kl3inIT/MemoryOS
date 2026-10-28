// Temporary: screenshots staging pages with a saved browser state.
// Usage: node staging-shots.tmp.mjs <stateFile> <outDir>
import { chromium } from "@playwright/test";

const [stateFile, out] = process.argv.slice(2);
const app = "https://staging.vadan.app";
const browser = await chromium.launch();
const context = await browser.newContext({ storageState: stateFile, viewport: { width: 1440, height: 900 }, locale: "vi-VN" });
const page = await context.newPage();

await page.goto(`${app}/`);
await page.waitForLoadState("networkidle");
await page.screenshot({ path: `${out}/1-home.png` });

const projects = page.getByRole("button", { name: /Dự án/ }).first();
if (await projects.count()) {
  await projects.click();
  await page.waitForTimeout(800);
}
await page.screenshot({ path: `${out}/2-sidebar-projects.png` });

await page.goto(`${app}/projects`);
await page.waitForLoadState("networkidle");
await page.screenshot({ path: `${out}/3-projects-page.png` });

await page.goto(`${app}/`);
await page.waitForLoadState("networkidle");
const row = page.getByText("Linear issue list").first().locator("xpath=ancestor::*[.//button][1]");
await row.hover();
console.log(await row.getByRole("button").evaluateAll((b) => b.map((x) => x.getAttribute("aria-label") || x.textContent)));
await row.getByRole("button").last().click();
await page.waitForTimeout(500);
await page.screenshot({ path: `${out}/4-conversation-menu.png` });
const move = page.getByRole("menuitem", { name: /dự án/i }).first();
if (await move.count()) {
  await move.hover();
  await page.waitForTimeout(500);
}
await page.screenshot({ path: `${out}/5-move-submenu.png` });
await browser.close();
