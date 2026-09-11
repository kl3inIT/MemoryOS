import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const siteOrigin = "https://vadan.app";
// jsdom replaces the global URL, which node:fs does not accept as a file URL.
const landingRoot = join(import.meta.dirname, "..");
const head = new DOMParser().parseFromString(
  readFileSync(join(landingRoot, "index.html"), "utf8"),
  "text/html",
).head;

function attribute(selector, name) {
  return head.querySelector(selector)?.getAttribute(name) ?? "";
}

function publicFileFor(url) {
  const { origin, pathname } = new URL(url);
  expect(origin).toBe(siteOrigin);
  return join(landingRoot, "public", pathname);
}

function structuredData() {
  const blocks = head.querySelectorAll('script[type="application/ld+json"]');
  expect(blocks).toHaveLength(1);
  const graph = JSON.parse(blocks[0].textContent)["@graph"];
  return (type) => graph.find((node) => node["@type"] === type);
}

describe("site metadata", () => {
  it("declares the canonical site URL", () => {
    expect(attribute('link[rel="canonical"]', "href")).toBe(`${siteOrigin}/`);
    expect(attribute('meta[property="og:url"]', "content")).toBe(`${siteOrigin}/`);
  });

  it.each(['meta[property="og:image"]', 'meta[name="twitter:image"]'])(
    "serves %s from the public directory",
    (selector) => {
      expect(existsSync(publicFileFor(attribute(selector, "content")))).toBe(true);
    },
  );

  it("describes Vadan, its backer and MemoryOS as structured data", () => {
    const nodeOfType = structuredData();
    const organization = nodeOfType("Organization");

    expect(organization).toMatchObject({
      name: "Vadan",
      url: `${siteOrigin}/`,
      email: "aws@vadan.app",
      funder: { name: "GenAI Fund" },
    });
    expect(existsSync(publicFileFor(organization.logo))).toBe(true);
    expect(nodeOfType("SoftwareApplication")).toMatchObject({
      name: "MemoryOS",
      publisher: { "@id": organization["@id"] },
    });
    expect(nodeOfType("WebSite")).toMatchObject({ url: `${siteOrigin}/` });
  });
});
