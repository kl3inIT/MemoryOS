import { useCallback, useRef, useState } from "react";

/** How close to the end still counts as reading the end, so new lines keep the page following them. */
const FOLLOW_SLACK = 48;

/** The nearest element that scrolls `element` vertically: the page it is read in. */
export function scrollerOf(element: HTMLElement): HTMLElement {
  for (let parent = element.parentElement; parent; parent = parent.parentElement) {
    const { overflowY } = getComputedStyle(parent);
    if (overflowY === "auto" || overflowY === "scroll") return parent;
  }
  return document.documentElement;
}

/**
 * Keeps the end of a growing list on screen by scrolling the page it is read in. Scrolling back stops the
 * following, so the reader is never pulled away from what they went back to read, and returning to the end
 * resumes it.
 */
export function useFollowEnd(enabled: boolean) {
  // The scroll and growth handlers read the ref; the state only tells the page to offer the way back.
  const following = useRef(true);
  const [away, setAway] = useState(false);
  const grown = useRef<HTMLElement>(null);
  const follow = useCallback((next: boolean) => {
    following.current = next;
    setAway(!next);
  }, []);

  /** Attached to what grows: it is followed for as long as it is mounted. */
  const grows = useCallback(
    (content: HTMLElement | null) => {
      if (!content || !enabled) return;
      grown.current = content;
      const scroller = scrollerOf(content);
      /** How far the end of the list lies below the bottom of the page's window. */
      const below = () =>
        content.getBoundingClientRect().bottom - scroller.getBoundingClientRect().bottom;
      const measure = () => ({ below: below(), height: content.offsetHeight });
      // How the page was last left or seen.
      let seen = measure();
      // Opening the page leaves it where it is; only what is said afterwards moves it.
      let opened = false;
      const observer = new ResizeObserver(() => {
        if (opened && following.current && below() > 0) {
          content.scrollIntoView({ block: "end" });
        }
        opened = true;
        seen = measure();
      });
      const onScroll = () => {
        const now = measure();
        if (now.below < FOLLOW_SLACK) follow(true);
        // Further from the end than the list's own growth explains: the reader moved back. Lines measured
        // above the window move the page and the list together, which leaves the end where it was.
        else if (now.below - seen.below > Math.max(now.height - seen.height, 0) + 1) follow(false);
        seen = now;
      };
      observer.observe(content);
      // The document reports its own scrolling on the window, an element on itself.
      const source = scroller === document.documentElement ? window : scroller;
      source.addEventListener("scroll", onScroll, { passive: true });
      return () => {
        grown.current = null;
        observer.disconnect();
        source.removeEventListener("scroll", onScroll);
      };
    },
    [enabled, follow],
  );

  /** The reader was taken somewhere else in the list, and stays there. */
  const stop = useCallback(() => follow(false), [follow]);

  /** Back to the end, and following again. */
  const resume = useCallback(() => {
    follow(true);
    grown.current?.scrollIntoView({ block: "end" });
  }, [follow]);

  return { grows, stop, resume, away: enabled && away };
}
