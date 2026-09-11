import { useEffect, useLayoutEffect, useState } from "react";
import { flushSync } from "react-dom";

// Same key and values as web/src/features/theme/theme-provider.tsx; public/theme-init.js reads
// it before first paint.
const storageKey = "memoryos-theme";
const darkSchemeQuery = "(prefers-color-scheme: dark)";
const reducedMotionQuery = "(prefers-reduced-motion: reduce)";
const revealDurationMs = 520;

type Theme = "light" | "dark";

type Point = {
  x: number;
  y: number;
};

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

function matchesMedia(query: string) {
  return typeof window.matchMedia === "function" && window.matchMedia(query).matches;
}

function systemTheme(): Theme {
  return matchesMedia(darkSchemeQuery) ? "dark" : "light";
}

// Grows the new theme as a circle from the control that switched it (see base.css).
function revealFrom({ x, y }: Point) {
  const radius = Math.hypot(
    Math.max(x, window.innerWidth - x),
    Math.max(y, window.innerHeight - y),
  );
  document.documentElement.animate(
    { clipPath: [`circle(0px at ${x}px ${y}px)`, `circle(${radius}px at ${x}px ${y}px)`] },
    {
      duration: revealDurationMs,
      easing: "cubic-bezier(0.22, 1, 0.36, 1)",
      pseudoElement: "::view-transition-new(root)",
    },
  );
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

  const toggleTheme = (origin?: Point) => {
    const next = theme === "dark" ? "light" : "dark";
    storeTheme(next);

    if (
      !origin ||
      typeof document.startViewTransition !== "function" ||
      matchesMedia(reducedMotionQuery)
    ) {
      setTheme(next);
      return;
    }

    // The callback must update the DOM synchronously so the transition captures the new theme.
    const transition = document.startViewTransition(() => flushSync(() => setTheme(next)));
    transition.ready.then(
      () => revealFrom(origin),
      () => {
        // A skipped transition still applies the theme.
      },
    );
  };

  return { theme, toggleTheme };
}

export { useTheme, type Point, type Theme };
