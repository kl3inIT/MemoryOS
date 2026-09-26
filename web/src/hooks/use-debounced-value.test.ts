import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useDebouncedValue } from "./use-debounced-value";

describe("useDebouncedValue", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("returns the initial value at once", () => {
    const { result } = renderHook(() => useDebouncedValue("draft", 250));

    expect(result.current).toBe("draft");
  });

  it("settles on the last value once it has not changed for the delay", () => {
    const { result, rerender } = renderHook(({ value }) => useDebouncedValue(value, 250), {
      initialProps: { value: "" },
    });

    rerender({ value: "f" });
    act(() => vi.advanceTimersByTime(200));
    rerender({ value: "fi" });
    act(() => vi.advanceTimersByTime(200));
    rerender({ value: "fin" });
    act(() => vi.advanceTimersByTime(249));
    expect(result.current).toBe("");

    act(() => vi.advanceTimersByTime(1));
    expect(result.current).toBe("fin");
  });
});
