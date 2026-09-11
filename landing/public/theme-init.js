// Applies the stored or system theme before first paint, so dark-mode visitors never see a light
// flash. A classic same-origin script satisfies `script-src 'self'`; src/lib/theme.ts owns the
// storage key and the toggle.
(() => {
  let stored = null;
  try {
    stored = window.localStorage.getItem("memoryos-theme");
  } catch {
    // Blocked site data: follow the system preference.
  }
  const dark =
    stored === "dark" ||
    (stored !== "light" && window.matchMedia("(prefers-color-scheme: dark)").matches);
  document.documentElement.classList.toggle("dark", dark);
})();
