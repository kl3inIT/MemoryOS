import { useLayoutEffect, useRef } from "react";
import { ActionLink } from "@/components/action-link";
import { ParticleField } from "@/components/particle-field";
import { contact, hero } from "@/content";
import { allowsMotion, gsap, useMotion } from "@/motion/motion";
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

  // The page's one load sequence. The h1 stays still so it paints as the largest contentful
  // element immediately; the description, the actions and the preview arrive beneath it.
  useMotion(scope, () => {
    // Inline start states take over from the CSS that hid these parts; both apply within this
    // task, so nothing flashes.
    delete scope.current?.dataset.intro;
    gsap
      .timeline({ delay: 0.3, defaults: { ease: "power3.out" } })
      .from("[data-hero-description]", { opacity: 0, y: 12, duration: 0.7 }, 0)
      .from("[data-hero-actions]", { opacity: 0, y: 12, duration: 0.6 }, 0.25)
      .from("[data-hero-preview]", { opacity: 0, y: 24, duration: 0.8 }, 0.45)
      .from("[data-preview-part]", { opacity: 0, y: 8, duration: 0.5, stagger: 0.14 }, 0.85);

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
      className="relative isolate overflow-hidden px-[var(--page-gutter)] pt-14 pb-20 sm:pt-20 sm:pb-24 lg:pt-24"
    >
      <div aria-hidden="true" className="hero-glow pointer-events-none absolute inset-0 -z-10" />
      <ParticleField className="[--field-x:0.5] [--field-y:0.8] lg:[--field-x:0.76] lg:[--field-y:0.55]" />
      <div className="mx-auto grid max-w-[var(--page-width-wide)] items-center gap-14 lg:grid-cols-[minmax(0,1fr)_minmax(0,32rem)] lg:gap-20">
        <div className="max-w-2xl">
          <h1 id="hero-heading" className="font-display text-content-primary">
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
