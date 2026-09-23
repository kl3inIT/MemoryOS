import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { PageField } from "./preview-toolbar";

function field(total: number, page = 1) {
  const onPage = vi.fn();
  render(<PageField page={page} total={total} onPage={onPage} />);
  return { onPage, input: screen.getByRole("spinbutton") as HTMLInputElement };
}

describe("PageField", () => {
  it("shows the page the reader is on out of the total", () => {
    const { input } = field(12, 3);
    expect(input.value).toBe("3");
    expect(screen.getByText("/ 12")).toBeInTheDocument();
  });

  it("goes to the page the reader types", async () => {
    const user = userEvent.setup();
    const { onPage, input } = field(12, 3);
    await user.clear(input);
    await user.type(input, "7{Enter}");
    expect(onPage).toHaveBeenCalledWith(7);
  });

  it("clamps a page beyond the document to the last one", async () => {
    const user = userEvent.setup();
    const { onPage, input } = field(12, 3);
    await user.clear(input);
    await user.type(input, "99{Enter}");
    expect(onPage).toHaveBeenCalledWith(12);
  });

  it("clamps zero and negatives to the first page", async () => {
    const user = userEvent.setup();
    const { onPage, input } = field(12, 3);
    await user.clear(input);
    await user.type(input, "0{Enter}");
    expect(onPage).toHaveBeenCalledWith(1);
  });

  it("returns to the current page when what was typed is not a page", async () => {
    const user = userEvent.setup();
    const { onPage, input } = field(12, 3);
    await user.clear(input);
    await user.type(input, "{Enter}");
    expect(onPage).not.toHaveBeenCalled();
    expect(input.value).toBe("3");
  });

  it("follows the reader when they scroll to another page instead", () => {
    const onPage = vi.fn();
    const { rerender } = render(<PageField page={3} total={12} onPage={onPage} />);
    rerender(<PageField page={8} total={12} onPage={onPage} />);
    expect((screen.getByRole("spinbutton") as HTMLInputElement).value).toBe("8");
  });
});
