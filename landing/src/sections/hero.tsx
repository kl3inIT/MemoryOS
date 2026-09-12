import { useLayoutEffect, useRef } from "react";
import { ActionLink } from "@/components/action-link";
import { ParticleField } from "@/components/particle-field";
import { contact, hero } from "@/content";
import { allowsMotion, gsap, useMotion } from "@/motion/motion";
import { splitText } from "@/motion/text";
import { ProductPreview } from "@/sections/product-preview";

function Hero() {
  const scope = useRef<HTMLElement>(null);

  // Motion sets up after the first frame, so until then CSS hides what the sequence brings in
  // (src/styles/base.css). Writing one attribute here reads no layout while the page mounts.
  useLayoutEffect(() => {
    const root = scope.current;
    if (!root || !allowsMotion()) {
      return;
    }
    root.dataset.intro = "pending";
    return () => {
      delete root.dataset.intro;
    };
  }, []);

  // The page's one load sequence: the headline sets itself letter by letter, each rising out of its
  // word from the baseline in reading order, the description's words follow, then the actions and
  // the preview rise into place. The split is undone once the headline has landed.
  useMotion(scope, () => {
    const root = scope.current;
    const title = root?.querySelector<HTMLElement>("[data-hero-title]");
    const description = root?.querySelector<HTMLElement>("[data-hero-description]");
    if (!root || !title || !description) {
      return;
    }
    const headline = splitText(title, {
      type: "words,chars",
      mask: "words",
      wordsClass: "hero-word",
    });
    const { words } = splitText(description, { type: "words", mask: "words" });
    gsap
      .timeline({ delay: 0.15 })
      .from(headline.chars, {
        yPercent: 110,
        duration: 1,
        stagger: 0.02,
        ease: "expo.out",
        onComplete: () => headline.revert(),
      })
      .from(words, { yPercent: 110, duration: 0.8, stagger: 0.012, ease: "power4.out" }, 0.5)
      .from(
        "[data-hero-actions] > *",
        { opacity: 0, y: 16, duration: 0.8, stagger: 0.08, ease: "expo.out" },
        0.8,
      )
      .from("[data-hero-preview]", { opacity: 0, y: 48, duration: 1.4, ease: "expo.out" }, 0.6)
      .from("[data-preview-part]", { opacity: 0, y: 8, duration: 0.5, stagger: 0.14 }, 1);
    // The start states above take over from the CSS that hid these parts; both apply within this
    // task, so nothing flashes.
    delete root.dataset.intro;

    // The light drifts away as the visitor scrolls past the hero.
    gsap.to(".hero-glow", {
      opacity: 0.45,
      yPercent: -8,
      scale: 1.15,
      ease: "none",
      scrollTrigger: { trigger: scope.current, start: "top top", end: "bottom top", scrub: true },
    });
  });

  return (
    <section
      ref={scope}
      aria-labelledby="hero-heading"
      className="relative isolate flex flex-1 flex-col justify-center overflow-hidden px-[var(--page-gutter)] py-14 sm:py-20"
    >
      <div aria-hidden="true" className="hero-glow pointer-events-none absolute inset-0 -z-10" />
      <ParticleField className="[--field-x:0.5] [--field-y:0.8] lg:[--field-x:0.76] lg:[--field-y:0.55]" />
      <div className="mx-auto grid w-full max-w-[var(--page-width-wide)] items-center gap-14 lg:grid-cols-[minmax(0,1fr)_minmax(0,32rem)] lg:gap-20">
        <div className="max-w-2xl">
          <h1 id="hero-heading" data-hero-title="" className="font-display text-content-primary">
            {hero.title}
          </h1>
          <p data-hero-description="" className="mt-6 max-w-xl font-lead text-content-secondary">
            {hero.description}
          </p>
          <div data-hero-actions="" className="mt-10 flex flex-wrap gap-3">
            <ActionLink href={contact.href} size="lg">
              {contact.label}
            </ActionLink>
            <ActionLink href={hero.secondaryAction.href} prominence="secondary" size="lg">
              {hero.secondaryAction.label}
            </ActionLink>
          </div>
        </div>
        <div data-hero-preview="">
          <ProductPreview />
        </div>
      </div>
    </section>
  );
}

export { Hero };
