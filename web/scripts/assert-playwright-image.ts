import { readFile } from "node:fs/promises";

const packageJson = JSON.parse(await readFile(new URL("../package.json", import.meta.url), "utf8"));
const workflow = await readFile(new URL("../../.github/workflows/ci.yml", import.meta.url), "utf8");

const playwrightVersion = packageJson.devDependencies?.["@playwright/test"];
if (typeof playwrightVersion !== "string") {
  throw new Error("web/package.json must pin @playwright/test to an exact version.");
}

const expectedImage = `mcr.microsoft.com/playwright:v${playwrightVersion}-noble`;
// Both the shards and the deployment-policy job run in this container; every use has to match.
const images = [...workflow.matchAll(/image: (mcr\.microsoft\.com\/playwright:[^@\s]+)/g)].map(
  (match) => match[1],
);
if (images.length === 0 || images.some((image) => image !== expectedImage)) {
  throw new Error(
    `The frontend CI containers must match @playwright/test ${playwrightVersion}: ${expectedImage}`,
  );
}
