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
      expect.arrayContaining(["main", "product", "how-it-works", "deployment", "roadmap", "faq"]),
    );
    for (const target of targets) {
      expect(page.querySelector(`[id="${target}"]`), `#${target}`).not.toBeNull();
    }
  });

  it("sends every email link to the AWS contact address", () => {
    const emailLinks = [...renderPage().querySelectorAll('a[href^="mailto:"]')];

    expect(emailLinks.length).toBeGreaterThan(0);
    expect(new Set(emailLinks.map((link) => link.getAttribute("href")))).toEqual(
      new Set(["mailto:aws@vanda.app"]),
    );
  });

  it("isolates every new-tab link from the opener", () => {
    const newTabLinks = [...renderPage().querySelectorAll('a[target="_blank"]')];

    expect(newTabLinks.length).toBeGreaterThan(0);
    for (const link of newTabLinks) {
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
