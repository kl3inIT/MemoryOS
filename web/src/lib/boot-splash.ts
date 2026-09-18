// Boot splash rendered by index.html and styled by splash.css: ribbons draw the wordmark once,
// then the sheen repeats until the application has rendered and the intro time has passed.

const SPLASH_ID = "memoryos-splash";
const DONE_EVENT = "memoryos:boot-splash-done";
const REMOVAL_FALLBACK_MS = 1600;

/** Starts the splash exit once its intro has played, then removes it. Call after rendering the app. */
export function dismissBootSplash() {
  const splash = document.getElementById(SPLASH_ID);
  if (!splash) return;

  const introMs = Number(splash.dataset.introMs);
  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const introRemaining = reducedMotion ? 0 : introMs - performance.now();

  window.setTimeout(
    () => {
      const remove = (event?: Event) => {
        if ((event && event.target !== splash) || !splash.isConnected) return;
        splash.remove();
        document.dispatchEvent(new Event(DONE_EVENT));
      };
      splash.addEventListener("animationend", remove);
      splash.addEventListener("transitionend", remove);
      window.setTimeout(remove, REMOVAL_FALLBACK_MS);
      splash.classList.add("is-exiting");
    },
    Math.max(0, introRemaining),
  );
}

/** Resolves once the boot splash is gone, or immediately when none is shown. */
export function whenBootSplashDone() {
  return new Promise<void>((resolve) => {
    if (!document.getElementById(SPLASH_ID)) {
      resolve();
      return;
    }
    document.addEventListener(DONE_EVENT, () => resolve(), { once: true });
  });
}
