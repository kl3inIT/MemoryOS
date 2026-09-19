/**
 * The resolved value of a design token for APIs that need a literal colour (canvas, generated SVG); components
 * should keep using the token classes. Returns the fallback outside a browser or before the token exists.
 */
export function cssToken(name: `--${string}`, fallback: string): string {
  if (typeof window === "undefined") return fallback;
  const value = window.getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return value || fallback;
}
