import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ApplicationErrorBoundary } from "./application-error-boundary";

function BrokenView(): never {
  throw new Error("intentional root boundary smoke");
}

describe("ApplicationErrorBoundary", () => {
  it("replaces a failed render with the application fallback and retries through onReset", async () => {
    const onReset = vi.fn();
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => undefined);

    try {
      render(
        <ApplicationErrorBoundary onReset={onReset}>
          <BrokenView />
        </ApplicationErrorBoundary>,
      );

      expect(screen.getByText("MemoryOS stopped unexpectedly.")).toBeVisible();
      await userEvent.click(screen.getByRole("button", { name: "Try again" }));
      expect(onReset).toHaveBeenCalledOnce();
    } finally {
      consoleError.mockRestore();
    }
  });
});
