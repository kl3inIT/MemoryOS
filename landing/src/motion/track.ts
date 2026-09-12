import { gsap, ScrollTrigger } from "@/motion/motion";
import type { RevealRange } from "@/motion/text";

/*
 * A pinned horizontal track, after the one on gsap.com. Motion sets `data-track` on the stage, whose
 * `track:` styles (src/styles/theme.css) lay the list out in a row; the stage then pins while the
 * vertical scroll slides the row across, one to one, and `[data-track-current]` and
 * `[data-track-progress]` follow along. `animateItem` receives each item with the scroll range in
 * which it arrives: the first while the stage comes up the viewport, the rest as they slide in from
 * the right. The returned cleanup restores the list's own layout; the `useMotion` context reverts
 * the tweens and the pin.
 */
function runTrack(
  stage: HTMLElement,
  track: HTMLElement,
  items: readonly HTMLElement[],
  animateItem: (item: HTMLElement, range: RevealRange) => void,
) {
  stage.toggleAttribute("data-track", true);
  const overflow = () => Math.max(0, track.scrollWidth - track.clientWidth);
  const slide = gsap.timeline({
    scrollTrigger: {
      trigger: stage,
      start: "top top",
      end: () => `+=${overflow()}`,
      pin: true,
      scrub: 0.5,
      invalidateOnRefresh: true,
    },
  });
  // Linear, so the row stays in step with the scroll and the ranges below can follow it.
  slide.to(track, { x: () => -overflow(), ease: "none" });

  const progress = stage.querySelector("[data-track-progress]");
  if (progress) {
    slide.fromTo(progress, { scaleX: 1 / items.length }, { scaleX: 1, ease: "none" }, 0);
  }
  const current = stage.querySelector("[data-track-current]");
  items.forEach((item, index) => {
    ScrollTrigger.create({
      trigger: item,
      containerAnimation: slide,
      start: "left 50%",
      end: "right 50%",
      onToggle: (self) => {
        if (self.isActive && current) {
          current.textContent = String(index + 1).padStart(2, "0");
        }
      },
    });
  });

  // Where the row stops, the last item's left edge is still left of the middle at every lg width,
  // so every item arrives in full.
  items.forEach((item, index) =>
    animateItem(
      item,
      index === 0
        ? { trigger: stage, start: "top 75%", end: "top 15%" }
        : { trigger: item, containerAnimation: slide, start: "left 90%", end: "left 50%" },
    ),
  );

  return () => stage.removeAttribute("data-track");
}

export { runTrack };
