import { gsap, SplitText } from "@/motion/motion";

type RevealRange = Pick<ScrollTrigger.Vars, "trigger" | "start" | "end" | "containerAnimation">;

/*
 * Type that arrives with the scroll, after gsap.com: the letters of `[data-reveal-title]` rise out
 * of their words, then `[data-reveal-words]` lights up word by word from dim to full. Both follow
 * the scroll through `range`. The markup holds the final text; SplitText labels each split element
 * with its whole text for assistive technology, and the `useMotion` context reverts the split.
 */
function revealText(root: HTMLElement, range: RevealRange) {
  const title = root.querySelector<HTMLElement>("[data-reveal-title]");
  const sentence = root.querySelector<HTMLElement>("[data-reveal-words]");
  const reveal = gsap.timeline({ scrollTrigger: { ...range, scrub: 0.5 } });
  if (title) {
    const { chars } = SplitText.create(title, { type: "words,chars", mask: "words" });
    reveal.from(chars, { yPercent: 110, duration: 0.5, stagger: 0.04, ease: "power3.out" });
  }
  if (sentence) {
    const { words } = SplitText.create(sentence, { type: "words" });
    reveal.from(words, { opacity: 0.15, duration: 0.3, stagger: 0.05, ease: "none" }, 0.2);
  }
}

export { revealText, type RevealRange };
