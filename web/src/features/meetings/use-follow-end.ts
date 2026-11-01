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

/** Keys that move the page up, and so take the reader away from the end. */
const BACK_KEYS = new Set(["ArrowUp", "PageUp", "Home"]);

/**
 * Keeps the end of a growing list on screen by scrolling the page it is read in. Scrolling back stops the
 * following, so the reader is never pulled away from what they went back to read, and returning to the end
 * resumes it.
 *
 * Going back is read from what the reader does (the wheel or a finger moving up, a key that moves up, dragging the
 * scrollbar), never from how the page moved: the virtualized list moves the page by itself whenever it measures a
 * line above the window, and a new line arriving in the same frame made that look like the reader going back.
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
      // Opening the page leaves it where it is; only what is said afterwards moves it.
      let opened = false;
      const observer = new ResizeObserver(() => {
        if (opened && following.current && below() > 0) {
          content.scrollIntoView({ block: "end" });
        }
        opened = true;
      });
      // A pointer held down while the page scrolls is the scrollbar being dragged; where a finger last was tells
      // which way a touch moves the page.
      let held = false;
      let touchY: number | undefined;
      let last = below();
      const back = () => {
        if (below() >= FOLLOW_SLACK) follow(false);
      };
      // Only what moves this page counts: a panel that scrolls on its own takes the wheel first, and a page that
      // fits its window has nowhere to go back to. The position is not asked: a passive listener runs after the
      // browser has already scrolled.
      const movesPageUp = (target: EventTarget | null) => {
        if (scroller.scrollHeight <= scroller.clientHeight + 1) return false;
        for (
          let at = target instanceof Element ? target : null;
          at && at !== scroller;
          at = at.parentElement
        ) {
          const { overflowY } = getComputedStyle(at);
          if (
            (overflowY === "auto" || overflowY === "scroll") &&
            at.scrollHeight > at.clientHeight + 1
          )
            return false;
        }
        return true;
      };
      const onWheel = (event: WheelEvent) => {
        if (event.deltaY < 0 && movesPageUp(event.target)) follow(false);
      };
      const onKey = (event: KeyboardEvent) => {
        // In a field the keys move the caret, not the page.
        const field =
          event.target instanceof Element &&
          event.target.closest("input, textarea, select, [contenteditable]");
        if (field) return;
        const up = BACK_KEYS.has(event.key) || (event.key === " " && event.shiftKey);
        if (up && movesPageUp(event.target)) follow(false);
      };
      const onTouchMove = (event: TouchEvent) => {
        const y = event.touches[0]?.clientY;
        // A finger moving down drags the page down, back towards what was said earlier.
        const down = y !== undefined && touchY !== undefined && y > touchY;
        if (down && movesPageUp(event.target)) follow(false);
        touchY = y;
      };
      const onTouchEnd = () => {
        touchY = undefined;
      };
      const onPointerDown = () => {
        held = true;
      };
      const onPointerUp = () => {
        held = false;
      };
      const onScroll = () => {
        const now = below();
        if (now < FOLLOW_SLACK) follow(true);
        else if (held && now > last) back();
        last = now;
      };
      observer.observe(content);
      // The document reports its own scrolling on the window, an element on itself.
      const source = scroller === document.documentElement ? window : scroller;
      source.addEventListener("scroll", onScroll, { passive: true });
      window.addEventListener("wheel", onWheel, { passive: true });
      window.addEventListener("keydown", onKey);
      window.addEventListener("touchmove", onTouchMove, { passive: true });
      window.addEventListener("touchend", onTouchEnd, { passive: true });
      window.addEventListener("pointerdown", onPointerDown, { passive: true });
      window.addEventListener("pointerup", onPointerUp, { passive: true });
      return () => {
        grown.current = null;
        observer.disconnect();
        source.removeEventListener("scroll", onScroll);
        window.removeEventListener("wheel", onWheel);
        window.removeEventListener("keydown", onKey);
        window.removeEventListener("touchmove", onTouchMove);
        window.removeEventListener("touchend", onTouchEnd);
        window.removeEventListener("pointerdown", onPointerDown);
        window.removeEventListener("pointerup", onPointerUp);
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
