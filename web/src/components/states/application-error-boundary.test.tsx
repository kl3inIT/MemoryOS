import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ApplicationErrorBoundary } from "./application-error-boundary";

function BrokenView(): never {
  throw new Error("intentional root boundary smoke");
}

describe("ApplicationErrorBoundary", () => {
  it("reports a render exception once and preserves the application fallback", () => {
    const onError = vi.fn();
    const onReset = vi.fn();
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => undefined);

    try {
      render(
        <ApplicationErrorBoundary onError={onError} onReset={onReset}>
          <BrokenView />
        </ApplicationErrorBoundary>,
      );

      expect(screen.getByText("MemoryOS stopped unexpectedly.")).toBeVisible();
      expect(onError).toHaveBeenCalledOnce();
      expect(onError.mock.calls[0]?.[0]).toMatchObject({
        message: "intentional root boundary smoke",
      });
      expect(onError.mock.calls[0]?.[1]).toContain("BrokenView");
    } finally {
      consoleError.mockRestore();
    }
  });
});
