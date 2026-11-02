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
    Array.from(lines.querySelectorAll("polygon"), (band) => band.getAttribute("points")),
  ).toEqual(["0,0.1 1,0.1 1,0.3 0,0.3", "0,0.6 0.7,0.6 0.7,0.8 0,0.8"]);
});

it("keeps the rounded box for a region with neither outline nor lines", () => {
  render(<CitationRegion slot="pdf-citation-box" label="Vùng" style={{}} />);
  expect(screen.getByRole("img", { name: "Vùng" }).tagName.toLowerCase()).toBe("span");
});
