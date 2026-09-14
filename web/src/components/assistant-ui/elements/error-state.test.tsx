import { fireEvent, render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { ErrorState } from "./error-state";

it("keeps the recovery action mounted, blocks pending activation and never retries by itself", () => {
  const onClick = vi.fn();
  const view = render(
    <ErrorState
      title="Check answer"
      detail="Saved conversation is authoritative"
      action={{ label: "Check conversation", pending: false, onClick }}
    />,
  );
  const button = screen.getByRole("button");
  expect(onClick).not.toHaveBeenCalled();
  fireEvent.click(button);
  expect(onClick).toHaveBeenCalledTimes(1);
  view.rerender(
    <ErrorState
      title="Kiểm tra câu trả lời"
      detail="Đang kiểm tra"
      action={{ label: "Kiểm tra hội thoại", pending: true, onClick }}
    />,
  );
  expect(screen.getByRole("button")).toBe(button);
  expect(button).toBeDisabled();
  fireEvent.click(button);
  expect(onClick).toHaveBeenCalledTimes(1);
});

it("does not invent a retry action for inaccessible files", () => {
  render(<ErrorState title="File unavailable" detail="Access may have changed" />);
  expect(screen.getByRole("alert")).toHaveTextContent("File unavailable");
  expect(screen.queryByRole("button")).not.toBeInTheDocument();
});
