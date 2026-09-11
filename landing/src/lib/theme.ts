import { useEffect, useLayoutEffect, useState } from "react";

// Same key and values as web/src/features/theme/theme-provider.tsx; public/theme-init.js reads
// it before first paint.
const storageKey = "memoryos-theme";
const darkSchemeQuery = "(prefers-color-scheme: dark)";

type Theme = "light" | "dark";

// Storage access throws when the browser blocks site data; the page then follows the system.
function readStoredTheme(): Theme | null {
  try {
    const stored = window.localStorage.getItem(storageKey);
    return stored === "light" || stored === "dark" ? stored : null;
  } catch {
    return null;
  }
}

function storeTheme(theme: Theme) {
  try {
    window.localStorage.setItem(storageKey, theme);
  } catch {
    // The choice still applies for this visit.
  }
}

function systemTheme(): Theme {
  return typeof window.matchMedia === "function" && window.matchMedia(darkSchemeQuery).matches
    ? "dark"
    : "light";
}

function useTheme() {
  const [theme, setTheme] = useState<Theme>(() => readStoredTheme() ?? systemTheme());

  useLayoutEffect(() => {
    document.documentElement.classList.toggle("dark", theme === "dark");
  }, [theme]);

  useEffect(() => {
    if (typeof window.matchMedia !== "function") {
      return;
    }

    const media = window.matchMedia(darkSchemeQuery);
    const followSystem = () => {
      if (!readStoredTheme()) {
        setTheme(media.matches ? "dark" : "light");
      }
    };
    media.addEventListener("change", followSystem);
    return () => media.removeEventListener("change", followSystem);
  }, []);

  const toggleTheme = () => {
    const next = theme === "dark" ? "light" : "dark";
    storeTheme(next);
    setTheme(next);
  };

  return { theme, toggleTheme };
}

export { useTheme, type Theme };
