import { gsap } from "@/motion/motion";

/*
 * Motion for line drawings (src/components/line-drawing.tsx). Each drawing's `--p` follows the
 * scroll while the drawing crosses the viewport. A finished drawing is marked `data-live`, and
 * `root` is marked `data-visible` while it is on screen, so the ambient loops in
 * src/styles/base.css run only for finished drawings on screen. Call it inside a `useMotion` setup,
 * whose context reverts the tweens; the returned cleanup removes the observer and the attributes.
 */
function scrubDrawings(root: HTMLElement, drawings: readonly HTMLElement[]) {
  const setLive = (drawing: HTMLElement, live: boolean) => {
    if (drawing.hasAttribute("data-live") !== live) {
      drawing.toggleAttribute("data-live", live);
    }
  };
  const visibility = new IntersectionObserver(([entry]) => {
    root.toggleAttribute("data-visible", Boolean(entry?.isIntersecting));
  });
  visibility.observe(root);

  for (const drawing of drawings) {
    const build = gsap.timeline({
      scrollTrigger: { trigger: drawing, start: "top 85%", end: "bottom 55%", scrub: 0.5 },
      onUpdate: () => setLive(drawing, build.progress() === 1),
    });
    build.fromTo(drawing, { "--p": 0 }, { "--p": 1, ease: "none" });
  }

  return () => {
    visibility.disconnect();
    root.removeAttribute("data-visible");
    drawings.forEach((drawing) => drawing.removeAttribute("data-live"));
  };
}

export { scrubDrawings };
