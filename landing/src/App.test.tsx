import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { App } from "@/App";

function renderPage() {
  return render(<App />).container;
}

describe("landing page", () => {
  it("has exactly one level-one heading", () => {
    expect(renderPage().querySelectorAll("h1")).toHaveLength(1);
  });

  it("points every in-page link at a rendered element", () => {
    const page = renderPage();
    const targets = [...page.querySelectorAll('a[href^="#"]')].map(
      (link) => link.getAttribute("href")?.slice(1) ?? "",
    );

    expect(targets).toEqual(
      expect.arrayContaining(["main", "product", "assets", "how-it-works", "deployment", "faq"]),
    );
    for (const target of targets) {
      expect(page.querySelector(`[id="${target}"]`), `#${target}`).not.toBeNull();
    }
  });

  it("sends every email link to the company contact address", () => {
    const emailLinks = [...renderPage().querySelectorAll('a[href^="mailto:"]')];

    expect(emailLinks.length).toBeGreaterThan(0);
    expect(new Set(emailLinks.map((link) => link.getAttribute("href")))).toEqual(
      new Set(["mailto:info@vadan.app"]),
    );
  });

  it("does not link to the private source repository", () => {
    const hrefs = [...renderPage().querySelectorAll("a[href]")].map(
      (link) => link.getAttribute("href") ?? "",
    );

    expect(hrefs.filter((href) => href.includes("github.com"))).toEqual([]);
  });

  it("isolates every new-tab link from the opener", () => {
    for (const link of renderPage().querySelectorAll('a[target="_blank"]')) {
      expect(link.getAttribute("rel")?.split(" ")).toEqual(
        expect.arrayContaining(["noopener", "noreferrer"]),
      );
    }
  });

  it("names every graphic or hides it from assistive technology", () => {
    const page = renderPage();

    for (const image of page.querySelectorAll("img")) {
      expect(image.hasAttribute("alt")).toBe(true);
    }
    for (const graphic of page.querySelectorAll("svg")) {
      const named =
        graphic.getAttribute("role") === "img" &&
        (graphic.hasAttribute("aria-label") || graphic.hasAttribute("aria-labelledby"));
      expect(named || graphic.getAttribute("aria-hidden") === "true", graphic.outerHTML).toBe(true);
    }
  });
});
