import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, expect, it } from "vitest";
import { CitationRegion } from "./citation-region";

afterEach(cleanup);

const outline: [number, number][] = [
  [0, 0.8],
  [0.9, 0],
  [1, 0.2],
  [0.1, 1],
];

it("draws the OCR's outline until the lines are read, then one polygon per line in its place", () => {
  const { rerender } = render(
    <CitationRegion slot="image-citation-box" label="Vùng" style={{}} outline={outline} />,
  );
  const region = screen.getByRole("img", { name: "Vùng" });
  expect(region).toHaveAttribute("data-shape", "outline");
  expect(region.querySelectorAll("polygon")).toHaveLength(1);

  rerender(
    <CitationRegion
      slot="image-citation-box"
      label="Vùng"
      style={{}}
      outline={outline}
      lines={[
        [
          [0, 0.1],
          [1, 0.1],
          [1, 0.3],
          [0, 0.3],
        ],
        [
          [0, 0.6],
          [0.7, 0.6],
          [0.7, 0.8],
          [0, 0.8],
        ],
      ]}
    />,
  );
  const lines = screen.getByRole("img", { name: "Vùng" });
  expect(lines).toHaveAttribute("data-shape", "lines");
  expect(lines).toHaveAttribute("data-slot", "image-citation-box");
  expect(
    Array.from(lines.querySelectorAll("polygon[data-band]"), (band) => band.getAttribute("points")),
  ).toEqual(["0,0.1 1,0.1 1,0.3 0,0.3", "0,0.6 0.7,0.6 0.7,0.8 0,0.8"]);
  // The strokes are cut to the OCR's outline, which stays the boundary, and have no edge of their own.
  const clip = lines.querySelector("clipPath");
  expect(clip?.querySelector("polygon")?.getAttribute("points")).toBe("0,0.8 0.9,0 1,0.2 0.1,1");
  expect(lines.querySelector("g")?.getAttribute("clip-path")).toBe(`url(#${clip?.id})`);
  expect(lines.querySelector("polygon[data-band]")?.getAttribute("class")).not.toContain("stroke");
});

it("keeps the rounded box for a region with neither outline nor lines", () => {
  render(<CitationRegion slot="pdf-citation-box" label="Vùng" style={{}} />);
  expect(screen.getByRole("img", { name: "Vùng" }).tagName.toLowerCase()).toBe("span");
});

it("draws the question over its line's stroke, the line lighter and the citation's other strokes faint", () => {
  const line: [number, number][] = [
    [0, 0.1],
    [1, 0.1],
    [1, 0.3],
    [0, 0.3],
  ];
  const other: [number, number][] = [
    [0, 0.6],
    [0.7, 0.6],
    [0.7, 0.8],
    [0, 0.8],
  ];
  const slab: [number, number][] = [
    [0.2, 0.08],
    [0.5, 0.08],
    [0.5, 0.32],
    [0.2, 0.32],
  ];
  render(
    <CitationRegion
      slot="image-citation-box"
      label="Vùng"
      style={{}}
      outline={outline}
      lines={[line, other]}
      tones={["focus", "faint"]}
      marks={[
        { slab, band: line, whole: true },
        { slab: other, whole: false },
      ]}
    />,
  );
  const region = screen.getByRole("img", { name: "Vùng" });
  const [focus, faint] = region.querySelectorAll("polygon[data-band]");
  expect(focus).toHaveAttribute("data-tone", "focus");
  expect(focus?.getAttribute("class")).toContain("opacity-50");
  expect(faint).toHaveAttribute("data-tone", "faint");
  expect(faint?.getAttribute("class")).toContain("opacity-30");
  // The whole question follows its line's stroke, cut to its slab, in the highlighter's darker step, at full strength.
  const [whole, word] = region.querySelectorAll("[data-mark]");
  expect(whole).toHaveAttribute("data-mark", "whole");
  const filled = whole!.querySelector("polygon")!;
  expect(filled.getAttribute("points")).toBe("0,0.1 1,0.1 1,0.3 0,0.3");
  expect(filled.getAttribute("class")).toBe("fill-pdf-highlight-border");
  const cut = filled.getAttribute("clip-path")!.slice(5, -1);
  expect(region.querySelector(`clipPath[id="${cut}"] polygon`)?.getAttribute("points")).toBe(
    "0.2,0.08 0.5,0.08 0.5,0.32 0.2,0.32",
  );
  expect(whole!.querySelector("polyline")).toBeNull();
  // A lone word without a stroke is its slab in the highlighter, underlined along the slab's straight foot.
  expect(word).toHaveAttribute("data-mark", "word");
  expect(word!.querySelector("polygon")?.getAttribute("class")).toBe("fill-pdf-highlight");
  expect(word!.querySelector("polyline")?.getAttribute("points")).toBe("0,0.8 0.7,0.8");
});

it("draws a region that holds none of the question faint", () => {
  render(<CitationRegion slot="pdf-citation-box" label="Vùng" style={{}} tone="faint" />);
  const region = screen.getByRole("img", { name: "Vùng" });
  expect(region).toHaveAttribute("data-tone", "faint");
  expect(region.getAttribute("class")).toContain("opacity-30");
});
