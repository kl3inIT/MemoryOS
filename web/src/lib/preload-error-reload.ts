const PRELOAD_ERROR_RELOAD_KEY = "memoryos:vite-preload-error-reloaded";
const PRELOAD_ERROR_RELOAD_WINDOW_MS = 10_000;

type PreloadErrorEvent = Event & {
  payload: unknown;
};

type PreloadErrorReloadOptions = {
  storage?: Storage;
  reload?: () => void;
  logger?: Pick<Console, "error">;
};

export function hasRecentlyReloadedAfterPreloadError(
  storage: Storage = window.sessionStorage,
  windowMs = PRELOAD_ERROR_RELOAD_WINDOW_MS,
) {
  try {
    const lastReload = Number(storage.getItem(PRELOAD_ERROR_RELOAD_KEY) ?? 0);
    return lastReload > 0 && Date.now() - lastReload < windowMs;
  } catch {
    return false;
  }
}

export function markPreloadErrorReloaded(storage: Storage = window.sessionStorage) {
  try {
    storage.setItem(PRELOAD_ERROR_RELOAD_KEY, String(Date.now()));
    return true;
  } catch {
    return false;
  }
}

export function setupPreloadErrorReloadHandler({
  storage = window.sessionStorage,
  reload = () => window.location.reload(),
  logger = console,
}: PreloadErrorReloadOptions = {}) {
  const handler = (event: Event) => {
    const preloadError = event as PreloadErrorEvent;

    if (hasRecentlyReloadedAfterPreloadError(storage)) {
      logger.error("Stale application chunk detected after a recent reload", preloadError.payload);
      return;
    }

    if (!markPreloadErrorReloaded(storage)) {
      logger.error("Stale application chunk detected, but reload guard could not be saved");
      return;
    }

    preloadError.preventDefault();
    reload();
  };

  window.addEventListener("vite:preloadError", handler);
  return () => window.removeEventListener("vite:preloadError", handler);
}
