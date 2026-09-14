// Picks the boot splash before first paint (see src/splash.css): the full intro on a tab's first load,
// the short form afterwards (reload, return from sign-in). It is a same-origin file because the CSP
// forbids inline scripts.
(() => {
  let mode = "short";
  try {
    if (!window.sessionStorage.getItem("memoryos.introPlayed")) {
      window.sessionStorage.setItem("memoryos.introPlayed", "1");
      mode = "full";
    }
  } catch {
    // Storage unavailable: prefer the short splash over replaying the intro on every load.
  }
  document.documentElement.setAttribute("data-memoryos-splash", mode);
})();
