import type { CSSProperties } from "react";

/*
 * The ramp idiom of the line drawings (src/components/line-drawing.tsx). Each part eases in from
 * its drawing's progress `--p` (0–1) through the `ramp` utilities in src/styles/base.css; without
 * motion `--p` is unset and counts as 1, so every drawing shows its final state.
 */
type RampVars = Record<`--${string}`, string | number>;

// Custom properties as a style object.
function cssVars(vars: RampVars): CSSProperties & RampVars {
  return vars;
}

// A part's ramp: it eases in while its drawing's progress runs from `from` to `to`.
function ramp(from: number, to: number, vars: RampVars = {}): CSSProperties & RampVars {
  return cssVars({ "--from": from, "--to": to, ...vars });
}

type Point = readonly [x: number, y: number];

export { cssVars, ramp, type Point };
