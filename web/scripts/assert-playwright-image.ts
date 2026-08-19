import { readFile } from "node:fs/promises";

const packageJson = JSON.parse(await readFile(new URL("../package.json", import.meta.url), "utf8"));
const workflow = await readFile(new URL("../../.github/workflows/ci.yml", import.meta.url), "utf8");

const playwrightVersion = packageJson.devDependencies?.["@playwright/test"];
if (typeof playwrightVersion !== "string") {
  throw new Error("web/package.json must pin @playwright/test to an exact version.");
}

const expectedImage = `mcr.microsoft.com/playwright:v${playwrightVersion}-noble`;
if (!workflow.includes(`image: ${expectedImage}`)) {
  throw new Error(
    `The frontend CI container must match @playwright/test ${playwrightVersion}: ${expectedImage}`,
  );
}
