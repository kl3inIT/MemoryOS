import { act, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { useTheme } from "@/lib/theme";

const storageKey = "memoryos-theme";

afterEach(() => {
  window.localStorage.clear();
  document.documentElement.classList.remove("dark");
});

describe("useTheme", () => {
  it("applies the stored theme", () => {
    window.localStorage.setItem(storageKey, "dark");

    const { result } = renderHook(() => useTheme());

    expect(result.current.theme).toBe("dark");
    expect(document.documentElement).toHaveClass("dark");
  });

  it("applies and remembers the visitor's choice", () => {
    const { result } = renderHook(() => useTheme());

    act(() => result.current.toggleTheme());
    expect(document.documentElement).toHaveClass("dark");
    expect(window.localStorage.getItem(storageKey)).toBe("dark");

    act(() => result.current.toggleTheme());
    expect(document.documentElement).not.toHaveClass("dark");
    expect(window.localStorage.getItem(storageKey)).toBe("light");
  });
});
