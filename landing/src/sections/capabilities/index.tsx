import { useRef } from "react";
import { Section } from "@/components/section";
import { capabilities } from "@/content";
import { cn } from "@/lib/utils";
import { gsap, useMotion } from "@/motion/motion";
import { animateMock } from "@/sections/capabilities/animate-mock";
import { mocks } from "@/sections/capabilities/mocks";

// How far a card starts beside its place, as a share of its own width, and how far it is tilted.
const sideOffset = { wide: 40, narrow: 16 };
const sideTilt = 3;

/*
 * The capabilities as cards that converge while they scroll in. From lg the left column slides in
 * from the left and the right column from the right; on one column the cards alternate sides. The
 * last capability, the principle the others share, spans both columns and rises from the middle. A
 * card's mock plays once the card has arrived. Without motion every card rests in place with its
 * mock in its final state; the section clips the cards while they are still beside the page.
 */
function Capabilities() {
  const { items } = capabilities;
  const scope = useRef<HTMLDivElement>(null);

  useMotion(scope, ({ wide }) => {
    const root = scope.current;
    if (!root) {
      return;
    }
    const cards = gsap.utils.toArray<HTMLElement>("[data-capability]", root);

    // Building a mock's timeline puts its elements in their start state, so it waits until its card
    // starts to arrive.
    const timelines = new Map<number, gsap.core.Timeline>();
    const mockTimeline = (index: number) => {
      const mockRoot = cards[index]?.querySelector<HTMLElement>("[data-mock]");
      if (!mockRoot) {
        return undefined;
      }
      const timeline = timelines.get(index) ?? animateMock(mockRoot);
      timelines.set(index, timeline);
      return timeline;
    };

    cards.forEach((card, index) => {
      const side = index % 2 === 0 ? -1 : 1;
      const start =
        index === cards.length - 1
          ? { opacity: 0, scale: 0.88, y: 48 }
          : {
              opacity: 0,
              xPercent: side * (wide ? sideOffset.wide : sideOffset.narrow),
              rotation: side * sideTilt,
            };
      gsap
        .timeline({
          scrollTrigger: { trigger: card, start: "top 95%", end: "top 55%", scrub: 0.6 },
          onStart: () => mockTimeline(index),
          onComplete: () => mockTimeline(index)?.play(),
        })
        .from(card, { ...start, ease: "power2.out" });
    });

    return () => timelines.forEach((timeline) => timeline.revert());
  });

  return (
    <Section
      id="capabilities"
      title={capabilities.title}
      description={capabilities.description}
      className="overflow-x-clip"
    >
      <div ref={scope} className="grid gap-6 lg:grid-cols-2 lg:gap-8">
        {items.map((item, index) => {
          const Illustration = mocks[item.mock];
          return (
            <article
              key={item.title}
              data-capability=""
              className={cn(
                "frame-gradient rounded-2xl p-px",
                index === items.length - 1 && "lg:col-span-2",
              )}
            >
              <div className="flex h-full flex-col overflow-hidden rounded-[calc(1rem-1px)] bg-surface-raised">
                <div className="p-5 sm:p-6">
                  <h3 className="font-heading-h3 text-content-primary">{item.title}</h3>
                  <p className="mt-1 font-main-content-body text-content-secondary">
                    {item.description}
                  </p>
                </div>
                <div
                  aria-hidden="true"
                  data-mock=""
                  className="relative mt-auto h-72 border-t border-border-subtle bg-surface-canvas sm:h-80"
                >
                  <Illustration />
                </div>
              </div>
            </article>
          );
        })}
      </div>
    </Section>
  );
}

export { Capabilities };
