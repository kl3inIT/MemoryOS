import { useGSAP } from "@gsap/react";
import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import type { RefObject } from "react";

gsap.registerPlugin(ScrollTrigger, useGSAP);

// Mobile address bars resize the viewport while scrolling; re-measuring then would make pins jump.
// ScrollTrigger measures every trigger again on the window load event, after the web fonts.
ScrollTrigger.config({ ignoreMobileResize: true });

/*
 * gsap.matchMedia() conditions. Effects run only while `motion` holds, so visitors who prefer
 * reduced motion, and browsers where the script fails, get the static page with every element in
 * its final place. Pinned scenes also need a `roomy` viewport; elsewhere they play in normal flow.
 */
const motionConditions = {
  motion: "(prefers-reduced-motion: no-preference)",
  wide: "(min-width: 64rem)",
  roomy: "(min-width: 64rem) and (min-height: 44rem)",
};

type MotionConditions = Record<keyof typeof motionConditions, boolean>;

type MotionSetup = (conditions: MotionConditions) => void | (() => void);

// Pinned scenes start below the sticky header (h-16), leaving a small gap.
const pinStart = "top top+=80";

type Point = {
  x: number;
  y: number;
};

// Centre of an element in `ancestor`'s coordinates from layout offsets, so transforms that motion
// has already applied never skew a measurement. `ancestor` must be a positioned element.
function layoutCentre(element: HTMLElement, ancestor: HTMLElement): Point {
  let x = element.offsetWidth / 2;
  let y = element.offsetHeight / 2;
  let node: HTMLElement | null = element;
  while (node && node !== ancestor) {
    x += node.offsetLeft;
    y += node.offsetTop;
    node = node.offsetParent as HTMLElement | null;
  }
  return { x, y };
}

// The translation that moves `element` onto `target`.
function offsetTo(element: HTMLElement, target: HTMLElement, ancestor: HTMLElement): Point {
  const from = layoutCentre(element, ancestor);
  const to = layoutCentre(target, ancestor);
  return { x: to.x - from.x, y: to.y - from.y };
}

// Whether the visitor allows motion right now, for the rare start state that must be in place
// before the first frame (see the hero).
function allowsMotion() {
  return window.matchMedia(motionConditions.motion).matches;
}

/**
 * Runs `setup` with selectors scoped to `scope` whenever motion is allowed, again when a condition
 * changes, and reverts every tween, trigger and pin it created on unmount.
 *
 * The setup runs after the first frame, in a task of its own. Setting up reads styles and layout,
 * so doing it while the page mounts would make the browser lay out the whole page in the middle of
 * that task and hold up the first interaction. Scopes mount in page order and their tasks run in
 * that order, so triggers are still created top to bottom.
 */
function useMotion(scope: RefObject<HTMLElement | null>, setup: MotionSetup) {
  useGSAP(
    () => {
      const media = gsap.matchMedia();
      const start = () => {
        media.add(
          motionConditions,
          (context) => {
            const conditions = context.conditions as MotionConditions;
            return conditions.motion ? setup(conditions) : undefined;
          },
          scope,
        );
      };
      let timeout = 0;
      const frame = requestAnimationFrame(() => {
        timeout = window.setTimeout(start);
      });
      return () => {
        cancelAnimationFrame(frame);
        clearTimeout(timeout);
        media.revert();
      };
    },
    { scope },
  );
}

export { allowsMotion, gsap, offsetTo, pinStart, ScrollTrigger, useMotion, type MotionConditions };
