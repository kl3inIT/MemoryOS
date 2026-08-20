import { afterEach, describe, expect, it, vi } from "vitest";
import { setupPreloadErrorReloadHandler } from "./preload-error-reload";

afterEach(() => {
  window.sessionStorage.clear();
});

describe("setupPreloadErrorReloadHandler", () => {
  it("reloads once for a stale chunk and prevents a reload loop", () => {
    vi.spyOn(Date, "now").mockReturnValue(1_000);
    const reload = vi.fn();
    const logger = { error: vi.fn() };
    const removeHandler = setupPreloadErrorReloadHandler({ reload, logger });

    const firstError = new Event("vite:preloadError", { cancelable: true });
    Object.assign(firstError, { payload: new Error("stale chunk") });
    window.dispatchEvent(firstError);

    expect(firstError.defaultPrevented).toBe(true);
    expect(reload).toHaveBeenCalledOnce();

    const repeatedError = new Event("vite:preloadError", { cancelable: true });
    Object.assign(repeatedError, { payload: new Error("still stale") });
    window.dispatchEvent(repeatedError);

    expect(reload).toHaveBeenCalledOnce();
    expect(logger.error).toHaveBeenCalledOnce();
    removeHandler();
  });
});
