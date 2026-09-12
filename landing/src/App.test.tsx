import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { App } from "@/App";
import { capabilities, howItWorks } from "@/content";

// The page states benefits, not the internal stack (design: positioning revision).
const internalTechnology = /\b(Docling|OCR|OpenSearch|PostgreSQL|Redis|MinIO|Python)\b/;

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

  it("isolates every new-tab link from the opener", () => {
    for (const link of renderPage().querySelectorAll('a[target="_blank"]')) {
      expect(link.getAttribute("rel")?.split(" ")).toEqual(
        expect.arrayContaining(["noopener", "noreferrer"]),
      );
    }
  });

  it("links neither the source repository nor third-party notices", () => {
    const page = renderPage();

    expect(page.querySelector('a[href*="github.com"]')).toBeNull();
    expect(page.querySelector('a[href$="THIRD_PARTY_NOTICES.txt"]')).toBeNull();
  });

  it("names no internal technology", () => {
    expect(renderPage().textContent).not.toMatch(internalTechnology);
  });

  it("names every graphic or hides it from assistive technology", () => {
    const page = renderPage();

    for (const image of page.querySelectorAll("img")) {
      expect(image.hasAttribute("alt")).toBe(true);
    }
    for (const graphic of page.querySelectorAll("svg, canvas")) {
      const named =
        graphic.getAttribute("role") === "img" &&
        (graphic.hasAttribute("aria-label") || graphic.hasAttribute("aria-labelledby"));
      const hidden = graphic.closest('[aria-hidden="true"]') !== null;
      expect(named || hidden, graphic.outerHTML).toBe(true);
    }
  });

  it("gives every ingestion stage and capability a heading and its description", () => {
    renderPage();

    for (const entry of [...howItWorks.stages, ...capabilities.items]) {
      expect(
        // Anchored at the end: a stage heading starts with its number, and "Index" and "Enterprise
        // identity" also begin longer headings.
        screen.getByRole("heading", { level: 3, name: new RegExp(`${entry.title}$`) }),
      ).toBeVisible();
      expect(screen.getByText(entry.description)).toBeVisible();
    }
  });

  it("lists the passages the access check blocks and the ones it sends to the model", () => {
    renderPage();
    const { gate } = howItWorks;
    const titlesIn = (name: string) =>
      within(screen.getByRole("list", { name }))
        .getAllByRole("listitem")
        .map((item) => item.textContent);

    expect(titlesIn(gate.blockedLabel)).toEqual(
      gate.passages
        .filter((passage) => !passage.allowed)
        .map((passage) => `${passage.title}${gate.blockedNote}`),
    );
    expect(titlesIn(gate.allowedLabel)).toEqual(
      gate.passages.filter((passage) => passage.allowed).map((passage) => passage.title),
    );
  });

  it("renders every element in its final place when motion is not allowed", () => {
    const page = renderPage();
    const moved = [...page.querySelectorAll<HTMLElement>("[style]")].filter(
      ({ style }) => style.opacity !== "" || style.transform !== "" || style.visibility !== "",
    );

    expect(moved).toEqual([]);
    expect(page.querySelector("[data-mode], [data-intro], [data-live], [data-visible]")).toBeNull();
  });
});
