import { useRef } from "react";
import { Section } from "@/components/section";
import { roadmap } from "@/content";
import { gsap, useMotion } from "@/motion/motion";

const dashedAcross =
  "lg:bg-[repeating-linear-gradient(90deg,var(--border-strong)_0_6px,transparent_6px_12px)] lg:[mask-image:linear-gradient(to_right,black_35%,transparent)]";
const dashedDown =
  "bg-[repeating-linear-gradient(180deg,var(--border-strong)_0_6px,transparent_6px_12px)] [mask-image:linear-gradient(to_bottom,black_35%,transparent)]";

/*
 * The timeline draws itself with the scroll: a solid line through the four PoC months, each node
 * lighting with its deliverable as the line arrives, then a dashed line that fades into the
 * direction after the PoC. It runs down the page below lg and across it from lg.
 */
function Roadmap() {
  const scope = useRef<HTMLDivElement>(null);

  useMotion(scope, ({ wide }) => {
    const root = scope.current;
    if (!root) {
      return;
    }
    const grow = wide ? "scaleX" : "scaleY";
    const timeline = gsap.timeline({
      defaults: { ease: "none" },
      scrollTrigger: {
        trigger: root,
        start: "top 75%",
        end: wide ? "top 20%" : "bottom 55%",
        scrub: 0.6,
      },
    });
    const milestones = gsap.utils.toArray<HTMLElement>("[data-milestone]", root);
    milestones.forEach((milestone, index) => {
      const select = gsap.utils.selector(milestone);
      timeline
        .from(select("[data-node]"), { scale: 0, duration: 0.2, ease: "back.out(3)" }, index)
        .from(
          select("[data-milestone-copy]"),
          { opacity: 0, y: 8, duration: 0.3, ease: "power2.out" },
          index,
        )
        .from(
          select("[data-deliverable]"),
          { opacity: 0, scale: 0.8, duration: 0.25, ease: "back.out(2)" },
          index + 0.2,
        )
        .from(select("[data-fill]"), { [grow]: 0, duration: 1 }, index);
    });
    timeline
      .from("[data-next-line]", { [grow]: 0, duration: 1 }, milestones.length)
      .from(
        "[data-next]",
        { opacity: 0, [wide ? "x" : "y"]: -8, stagger: 0.2, duration: 0.3, ease: "power2.out" },
        milestones.length + 0.1,
      );
  });

  return (
    <Section id="roadmap" title={roadmap.title} description={roadmap.description}>
      <div ref={scope} className="grid gap-10 lg:grid-cols-5 lg:gap-0">
        <ol className="grid gap-10 lg:col-span-4 lg:grid-cols-4 lg:gap-0">
          {roadmap.milestones.map((milestone) => (
            <li
              key={milestone.dateTime}
              data-milestone=""
              className="relative pl-7 lg:pt-8 lg:pr-6 lg:pl-0"
            >
              {/* Below lg each segment also spans the gap, so it meets the next node. */}
              <span
                aria-hidden="true"
                className="absolute top-0 left-0 h-[calc(100%+2.5rem)] w-px bg-border-default lg:h-px lg:w-full"
              />
              <span
                aria-hidden="true"
                data-fill=""
                className="absolute top-0 left-0 h-[calc(100%+2.5rem)] w-px origin-top bg-accent lg:h-px lg:w-full lg:origin-left"
              />
              <span
                aria-hidden="true"
                data-node=""
                className="absolute top-1.5 -left-[5px] size-[11px] rounded-full border-2 border-surface-base bg-accent lg:-top-[5px] lg:left-0"
              />
              <div data-milestone-copy="">
                <time
                  dateTime={milestone.dateTime}
                  className="font-main-ui-action text-content-muted"
                >
                  {milestone.period}
                </time>
                <h3 className="mt-2 font-heading-h3 text-content-primary">{milestone.title}</h3>
                <p className="mt-2 font-main-content-body text-content-secondary">
                  {milestone.description}
                </p>
              </div>
              <p
                data-deliverable=""
                className="mt-4 inline-flex rounded-full bg-citation-surface px-2.5 py-1 font-secondary-action text-citation-content"
              >
                <span className="sr-only">{roadmap.deliverableLabel} </span>
                {milestone.deliverable}
              </p>
            </li>
          ))}
        </ol>
        <div className="relative pl-7 lg:pt-8 lg:pl-0">
          <span
            aria-hidden="true"
            data-next-line=""
            className={`absolute top-0 left-0 h-full w-px origin-top lg:h-px lg:w-full lg:origin-left ${dashedDown} ${dashedAcross}`}
          />
          <h3 data-next="" className="font-heading-h3 text-content-primary">
            {roadmap.next.title}
          </h3>
          <ul className="mt-3 space-y-2">
            {roadmap.next.items.map((item) => (
              <li key={item} data-next="" className="font-main-content-body text-content-secondary">
                {item}
              </li>
            ))}
          </ul>
        </div>
      </div>
    </Section>
  );
}

export { Roadmap };
