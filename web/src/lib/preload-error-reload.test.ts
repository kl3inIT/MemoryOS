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

  it("does not reload the page when pdf.js cannot import its worker module", () => {
    const reload = vi.fn();
    const logger = { error: vi.fn() };
    const removeHandler = setupPreloadErrorReloadHandler({ reload, logger });

    const workerError = new Event("vite:preloadError", { cancelable: true });
    Object.assign(workerError, {
      payload: new TypeError(
        "Failed to fetch dynamically imported module: https://memoryos.example/assets/pdf.worker.min-Dswkl-cV.mjs",
      ),
    });
    window.dispatchEvent(workerError);

    expect(reload).not.toHaveBeenCalled();
    expect(workerError.defaultPrevented).toBe(false);
    expect(logger.error).toHaveBeenCalledWith(
      "PDF worker could not be loaded",
      expect.any(TypeError),
    );

    // An application chunk failure still reloads once.
    const chunkError = new Event("vite:preloadError", { cancelable: true });
    Object.assign(chunkError, {
      payload: new TypeError(
        "Failed to fetch dynamically imported module: https://memoryos.example/assets/search-page.js",
      ),
    });
    window.dispatchEvent(chunkError);
    expect(reload).toHaveBeenCalledOnce();
    removeHandler();
  });
});
