import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { CsvView } from "./csv-view";

const csv = "Khu vực,Doanh thu\nHà Nội,120\nĐà Nẵng,80\nCần Thơ,45\n";

const LAID_OUT = { offsetHeight: 600, offsetWidth: 800 };

// Only the rows inside the scrolling window are rendered, and jsdom lays nothing out, so the window would be
// zero high and the sheet would come out empty. These are the two measurements the window is taken from.
beforeEach(() => {
  for (const [property, value] of Object.entries(LAID_OUT))
    Object.defineProperty(HTMLElement.prototype, property, { configurable: true, value });
});

afterEach(() => {
  for (const property of Object.keys(LAID_OUT))
    Reflect.deleteProperty(HTMLElement.prototype, property);
});

describe("CsvView", () => {
  it("marks the cited rows by their sheet row number and says which one is being read", () => {
    // Row 0 is the header line, so the extraction's rows 2 and 3 are the second and third data rows.
    render(<CsvView csv={csv} cited={[2, 3]} active={3} />);

    const rows = screen.getAllByRole("row");
    expect(rows[1]).not.toHaveAttribute("data-cited");
    expect(rows[2]).toHaveAttribute("data-cited", "true");
    expect(rows[3]).toHaveAttribute("data-cited", "true");
    expect(rows[3]).toHaveAttribute("aria-current", "true");
    expect(rows[2]).not.toHaveAttribute("aria-current");
  });

  it("marks nothing when no row was cited", () => {
    render(<CsvView csv={csv} />);
    for (const row of screen.getAllByRole("row")) expect(row).not.toHaveAttribute("data-cited");
  });
});
