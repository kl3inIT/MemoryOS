import { act, renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { useManualRefresh } from "./use-manual-refresh";

describe("useManualRefresh", () => {
  it("reports work only while the refresh the person started is in flight", async () => {
    let settle!: () => void;
    const { result } = renderHook(() =>
      useManualRefresh(() => new Promise<void>((resolve) => (settle = resolve))),
    );

    expect(result.current.pending).toBe(false);

    act(() => result.current.refresh());
    expect(result.current.pending).toBe(true);

    await act(async () => {
      settle();
    });
    expect(result.current.pending).toBe(false);
  });

  it("stops reporting work when the refresh fails", async () => {
    const { result } = renderHook(() =>
      useManualRefresh(() => Promise.reject(new Error("offline"))),
    );

    await act(async () => {
      result.current.refresh();
    });

    expect(result.current.pending).toBe(false);
  });
});
