import { useLayoutEffect, useRef } from "react";
import { ActionLink } from "@/components/action-link";
import { ParticleField } from "@/components/particle-field";
import { contact, hero } from "@/content";
import { allowsMotion, gsap, useMotion } from "@/motion/motion";
import { ProductPreview } from "@/sections/product-preview";

const secondsPerCharacter = 0.028;
const caretLingerSeconds = 2.4;

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

  // The page's one load sequence. The h1 stays still so it paints as the largest contentful
  // element immediately; the statement types beneath it, then the actions and the preview arrive.
  useMotion(scope, () => {
    // Inline start states take over from the CSS that hid these parts; both apply within this
    // task, so nothing flashes.
    delete scope.current?.dataset.intro;
    const characters = gsap.utils.toArray<HTMLElement>("[data-character]");
    let caret: HTMLElement | undefined;
    const placeCaret = (next?: HTMLElement) => {
      caret?.classList.remove("typed-caret");
      next?.classList.add("typed-caret");
      caret = next;
    };

    gsap.set(characters, { opacity: 0 });
    const timeline = gsap.timeline({ delay: 0.3, defaults: { ease: "power3.out" } });
    characters.forEach((character, index) => {
      const at = index * secondsPerCharacter;
      timeline.set(character, { opacity: 1 }, at).call(placeCaret, [character], at);
    });
    timeline
      .call(placeCaret, [], `+=${caretLingerSeconds}`)
      .from("[data-hero-actions]", { opacity: 0, y: 12, duration: 0.6 }, 0.5)
      .from("[data-hero-preview]", { opacity: 0, y: 24, duration: 0.8 }, 0.7)
      .from("[data-preview-part]", { opacity: 0, y: 8, duration: 0.5, stagger: 0.14 }, 1.1);

    // The light drifts away as the visitor scrolls past the hero.
    gsap.to(".hero-glow", {
      opacity: 0.45,
      yPercent: -8,
      scale: 1.15,
      ease: "none",
      scrollTrigger: { trigger: scope.current, start: "top top", end: "bottom top", scrub: true },
    });

    return () => placeCaret();
  });

  return (
    <section
      ref={scope}
      aria-labelledby="hero-heading"
      className="relative isolate overflow-hidden px-[var(--page-gutter)] pt-14 pb-20 sm:pt-20 sm:pb-24 lg:pt-24"
    >
      <div aria-hidden="true" className="hero-glow pointer-events-none absolute inset-0 -z-10" />
      <ParticleField className="[--field-x:0.5] [--field-y:0.8] lg:[--field-x:0.76] lg:[--field-y:0.55]" />
      <div className="mx-auto grid max-w-[var(--page-width-wide)] items-center gap-14 lg:grid-cols-[minmax(0,1fr)_minmax(0,32rem)] lg:gap-20">
        <div className="max-w-2xl">
          <h1 id="hero-heading" className="font-display text-content-primary">
            {hero.title}
          </h1>
          <p className="mt-6 max-w-xl font-lead text-content-secondary">
            <span className="sr-only">{hero.statement}</span>
            {/* Characters keep their place while hidden, so typing never moves the layout. */}
            <span aria-hidden="true">
              {[...hero.statement].map((character, index) => (
                <span key={index} data-character="">
                  {character}
                </span>
              ))}
            </span>
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
