import { useRef, useState } from "react";
import { Section } from "@/components/section";
import { capabilities } from "@/content";
import { cn } from "@/lib/utils";
import { gsap, pinStart, ScrollTrigger, useMotion } from "@/motion/motion";
import { animateMock } from "@/sections/capabilities/animate-mock";
import { mocks } from "@/sections/capabilities/mocks";

// Scrolling distance per capability while the explorer is pinned, in viewport heights.
const viewportsPerCapability = 0.4;

/*
 * With motion and a roomy viewport the explorer is pinned: the list on the left follows the scroll
 * and the matching panel plays its mock. Everywhere else the panels form a grid, each mock playing
 * once as it scrolls into view, or resting in its final state without motion. `data-mode` on the
 * root switches the layout, so the grid is also what renders before or without the script.
 */
function Capabilities() {
  const { items } = capabilities;
  const scope = useRef<HTMLDivElement>(null);
  const pin = useRef<ScrollTrigger | null>(null);
  const [active, setActive] = useState(0);

  useMotion(scope, ({ roomy }) => {
    const root = scope.current;
    if (!root) {
      return;
    }
    if (roomy) {
      root.dataset.mode = "pinned";
    }

    // Building a mock's timeline puts each of its elements in its start state, so each mock is
    // built on first use rather than all nine while the page loads.
    const mockRoots = gsap.utils.toArray<HTMLElement>("[data-mock]", root);
    const timelines = new Map<number, gsap.core.Timeline>();
    const mockTimeline = (index: number) => {
      const mockRoot = mockRoots[index];
      if (!mockRoot) {
        return undefined;
      }
      const timeline = timelines.get(index) ?? animateMock(mockRoot);
      timelines.set(index, timeline);
      return timeline;
    };
    const revertMocks = () => timelines.forEach((timeline) => timeline.revert());

    if (!roomy) {
      // A panel's mock takes its start state while still half a viewport below the fold and plays
      // once the panel's top passes 80% of the viewport height, like ScrollTrigger's "top 80%",
      // without measuring layout for every panel.
      const panels = gsap.utils.toArray<HTMLElement>("[data-capability]", root);
      const observe = (rootMargin: string, play: boolean) => {
        const observer = new IntersectionObserver(
          (entries) => {
            for (const entry of entries.filter(({ isIntersecting }) => isIntersecting)) {
              observer.unobserve(entry.target);
              const timeline = mockTimeline(panels.indexOf(entry.target as HTMLElement));
              if (play) {
                timeline?.play();
              }
            }
          },
          { rootMargin },
        );
        panels.forEach((panel) => observer.observe(panel));
        return observer;
      };
      const observers = [observe("0px 0px 50% 0px", false), observe("0px 0px -20% 0px", true)];
      return () => {
        observers.forEach((observer) => observer.disconnect());
        revertMocks();
      };
    }

    const fills = gsap.utils.toArray<HTMLElement>("[data-progress]", root);
    let current = 0;
    setActive(0);
    gsap.set(fills, { scaleY: 0 });
    mockTimeline(0);
    ScrollTrigger.create({
      trigger: root,
      start: "top 75%",
      once: true,
      onEnter: () => mockTimeline(current)?.play(),
    });
    pin.current = ScrollTrigger.create({
      trigger: root,
      start: pinStart,
      end: () => `+=${window.innerHeight * viewportsPerCapability * items.length}`,
      pin: true,
      invalidateOnRefresh: true,
      onUpdate: ({ progress }) => {
        const position = progress * items.length;
        fills.forEach((fill, index) => {
          gsap.set(fill, { scaleY: gsap.utils.clamp(0, 1, position - index) });
        });
        const index = Math.min(items.length - 1, Math.floor(position));
        if (index !== current) {
          current = index;
          setActive(index);
          mockTimeline(index)?.restart();
        }
      },
    });

    return () => {
      delete root.dataset.mode;
      pin.current = null;
    };
  });

  // The list only shows while pinned; it scrolls to the middle of the chosen capability's stretch.
  const showCapability = (index: number) => {
    const trigger = pin.current;
    if (trigger) {
      const stretch = (trigger.end - trigger.start) / items.length;
      window.scrollTo({ top: trigger.start + stretch * (index + 0.5), behavior: "smooth" });
    }
  };

  return (
    <Section id="capabilities" title={capabilities.title} description={capabilities.description}>
      <div ref={scope} className="group/explorer">
        <div className="grid gap-10 group-data-[mode=pinned]/explorer:grid-cols-[14rem_minmax(0,1fr)] group-data-[mode=pinned]/explorer:items-center group-data-[mode=pinned]/explorer:gap-12">
          <ul className="hidden group-data-[mode=pinned]/explorer:block">
            {items.map((item, index) => (
              <li key={item.title}>
                <button
                  type="button"
                  onClick={() => showCapability(index)}
                  aria-current={index === active ? "true" : undefined}
                  className={cn(
                    "relative w-full rounded-md py-2 pr-3 pl-4 text-left font-main-ui-action transition-colors duration-200",
                    index === active
                      ? "text-content-primary"
                      : "text-content-secondary hover:text-content-primary",
                  )}
                >
                  <span
                    aria-hidden="true"
                    className="absolute inset-y-1.5 left-0 w-0.5 rounded-full bg-border-default"
                  />
                  <span
                    aria-hidden="true"
                    data-progress=""
                    className="absolute inset-y-1.5 left-0 w-0.5 origin-top rounded-full bg-accent"
                  />
                  {item.title}
                </button>
              </li>
            ))}
          </ul>
          <div className="grid gap-6 sm:grid-cols-2 xl:grid-cols-3 group-data-[mode=pinned]/explorer:grid-cols-1">
            {items.map((item, index) => {
              const Illustration = mocks[item.mock];
              return (
                <article
                  key={item.title}
                  data-capability=""
                  data-active={index === active}
                  className="frame-gradient rounded-2xl p-px group-data-[mode=pinned]/explorer:[grid-area:1/1] group-data-[mode=pinned]/explorer:transition-opacity group-data-[mode=pinned]/explorer:duration-500 group-data-[mode=pinned]/explorer:data-[active=false]:pointer-events-none group-data-[mode=pinned]/explorer:data-[active=false]:opacity-0"
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
                      className="relative mt-auto h-64 border-t border-border-subtle bg-surface-canvas group-data-[mode=pinned]/explorer:h-[22rem]"
                    >
                      <Illustration />
                    </div>
                  </div>
                </article>
              );
            })}
          </div>
        </div>
      </div>
    </Section>
  );
}

export { Capabilities };
