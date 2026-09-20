import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { ClampedList } from "./clamped-list";

const perRow = 3;
const rowHeight = 30;
let offsetTop: PropertyDescriptor | undefined;

/** jsdom never lays out, so rows are simulated: three visible entries fill one row. */
beforeEach(() => {
  offsetTop = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "offsetTop");
  Object.defineProperty(HTMLElement.prototype, "offsetTop", {
    configurable: true,
    get(this: HTMLElement) {
      const parent = this.parentElement;
      if (!parent) return 0;
      const laidOut = [...parent.children].filter(
        (child) => (child as HTMLElement).style.display !== "none",
      );
      const index = laidOut.indexOf(this);
      return index < 0 ? 0 : Math.floor(index / perRow) * rowHeight;
    },
  });
});

afterEach(() => {
  if (offsetTop) Object.defineProperty(HTMLElement.prototype, "offsetTop", offsetTop);
});

function chips(count: number) {
  return Array.from({ length: count }, (_, index) => (
    <span key={index}>{`Nguồn ${index + 1}`}</span>
  ));
}

describe("ClampedList", () => {
  it("keeps every entry when they fit within the row budget", () => {
    render(<ClampedList items={chips(6)} maxRows={2} label="Nguồn" />);

    expect(within(screen.getByRole("list")).getAllByRole("listitem")).toHaveLength(6);
    expect(screen.queryByRole("button", { name: /^\+/ })).not.toBeInTheDocument();
  });

  it("moves the entries past the last row behind a hover card", async () => {
    const user = userEvent.setup();
    render(<ClampedList items={chips(8)} maxRows={2} label="Nguồn" />);

    // Five entries plus the "+3" trigger fill the two rows.
    const trigger = screen.getByRole("button", { name: "+3" });
    expect(screen.getByText("Nguồn 5")).toBeVisible();

    await user.hover(trigger);
    // The card repeats the label, so both lists are present once the overflow opens.
    await waitFor(() => expect(screen.getAllByRole("list", { name: "Nguồn" })).toHaveLength(2));
    const card = screen.getAllByRole("list", { name: "Nguồn" })[1];
    expect(within(card).getByText("Nguồn 8")).toBeVisible();
  });

  it("shows one entry per row when stacked", () => {
    render(<ClampedList items={chips(9)} maxRows={4} label="Nguồn" orientation="stack" />);

    expect(screen.getByRole("button", { name: "+5" })).toBeVisible();
  });
});
