import { afterEach, describe, expect, it, vi } from "vitest";
import { dismissBootSplash, whenBootSplashDone } from "./boot-splash";

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  document.getElementById("memoryos-splash")?.remove();
  delete document.documentElement.dataset.memoryosSplash;
});

function mountSplash(mode: "full" | "short") {
  document.documentElement.dataset.memoryosSplash = mode;
  const splash = document.createElement("div");
  splash.id = "memoryos-splash";
  splash.dataset.fullIntroMs = "1250";
  splash.dataset.shortIntroMs = "1500";
  document.body.append(splash);
  return splash;
}

describe("boot splash", () => {
  it("reports completion at once when no splash is shown", async () => {
    await expect(whenBootSplashDone()).resolves.toBeUndefined();
  });

  it.each([
    ["full", 1250],
    ["short", 1500],
  ] as const)(
    "exits after the %s intro time and reports completion once removed",
    async (mode, introMs) => {
      vi.useFakeTimers();
      vi.spyOn(performance, "now").mockReturnValue(0);
      vi.stubGlobal("matchMedia", () => ({ matches: false }));
      const splash = mountSplash(mode);
      let done = false;
      void whenBootSplashDone().then(() => {
        done = true;
      });

      dismissBootSplash();
      await vi.advanceTimersByTimeAsync(introMs - 1);
      expect(splash).not.toHaveClass("is-exiting");
      await vi.advanceTimersByTimeAsync(1);
      expect(splash).toHaveClass("is-exiting");
      expect(done).toBe(false);

      splash.firstChild?.dispatchEvent(new Event("animationend", { bubbles: true }));
      splash.dispatchEvent(new Event("animationend"));
      await vi.advanceTimersByTimeAsync(0);

      expect(splash.isConnected).toBe(false);
      expect(done).toBe(true);
    },
  );

  it("removes the splash after the fallback when no exit event arrives", async () => {
    vi.useFakeTimers();
    vi.spyOn(performance, "now").mockReturnValue(0);
    vi.stubGlobal("matchMedia", () => ({ matches: true }));
    const splash = mountSplash("full");

    dismissBootSplash();
    await vi.advanceTimersByTimeAsync(0);
    expect(splash).toHaveClass("is-exiting");
    await vi.advanceTimersByTimeAsync(1600);

    expect(splash.isConnected).toBe(false);
  });
});
