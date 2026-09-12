import { gsap, SplitText } from "@/motion/motion";

type RevealRange = Pick<ScrollTrigger.Vars, "trigger" | "start" | "containerAnimation">;

// The longest stagger across a block's words, so a long description lands as quickly as a short one.
const wordsSpread = 1;
// How long the first of the labels and the words waits for the other.
const secondStart = 0.3;

/*
 * SplitText, with the transforms of the pieces it made read in one pass. GSAP reads an element's
 * transform from its computed style the first time it animates it, and a `from` tween writes each
 * target's start before reading the next, so on fresh pieces every read forced a layout: 608 of them
 * as the page set up (Lighthouse trace, 2026-09-12). Read together, before any tween writes, they
 * cost one; GSAP keeps the values for the tweens.
 */
function splitText(targets: HTMLElement | readonly HTMLElement[], vars: SplitText.Vars) {
  const split = SplitText.create(targets, vars);
  for (const piece of split.chars.length > 0 ? split.chars : split.words) {
    gsap.getProperty(piece, "y");
  }
  return split;
}

/*
 * Type that jumps in when it arrives, after the labels on gsap.com's horizontal track. Each
 * `[data-reveal-flip]` label flips up on a hinge along its top edge and settles with a spring, one
 * after another; the words of every `[data-reveal-words]` element rise out of their own masks, in
 * reading order. Whichever comes first in the markup starts first. The entrance plays once `range`
 * starts and reverses when the visitor scrolls back above it; callers may add to the returned
 * timeline. The markup holds the final text; SplitText labels each split element with its whole
 * text for assistive technology, and the `useMotion` context reverts the split.
 */
function revealText(root: HTMLElement, range: RevealRange) {
  const labels = root.querySelectorAll<HTMLElement>("[data-reveal-flip]");
  const blocks = root.querySelectorAll<HTMLElement>("[data-reveal-words]");
  const reveal = gsap.timeline({
    scrollTrigger: { ...range, toggleActions: "play none none reverse" },
  });
  const [firstLabel] = labels;
  const [firstBlock] = blocks;
  const labelsFirst =
    !firstBlock ||
    Boolean(
      firstLabel &&
      firstLabel.compareDocumentPosition(firstBlock) & Node.DOCUMENT_POSITION_FOLLOWING,
    );

  if (labels.length > 0) {
    gsap.set(labels, { transformPerspective: 800, transformOrigin: "50% 0%" });
    const at = labelsFirst ? 0 : secondStart;
    reveal
      .from(
        labels,
        { rotationX: -95, duration: 2.2, stagger: 0.15, ease: "elastic.out(1.2, 0.6)" },
        at,
      )
      // A folded label would show as a sliver above its place, so it stays hidden until it flips.
      .from(labels, { autoAlpha: 0, duration: 0.15, stagger: 0.15, ease: "none" }, at);
  }
  if (blocks.length > 0) {
    const { words } = splitText([...blocks], { type: "words", mask: "words" });
    reveal.from(
      words,
      {
        yPercent: 110,
        duration: 0.8,
        stagger: Math.min(0.03, wordsSpread / words.length),
        ease: "power4.out",
      },
      labelsFirst && labels.length > 0 ? secondStart : 0,
    );
  }
  return reveal;
}

export { revealText, splitText, type RevealRange };
