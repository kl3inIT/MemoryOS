import { useId, useRef } from "react";
import { howItWorks } from "@/content";
import { cn } from "@/lib/utils";
import { useMotion } from "@/motion/motion";
import { revealText } from "@/motion/text";

const label = "inline-block rounded-[0.2em] px-[0.45em] pt-[0.3em] pb-[0.22em] font-heading-item";

/*
 * Every request passes the access check, told as type: the signed-in person, the check that strikes
 * out the passages they may not read, and the model that receives only the rest. From lg the three
 * stand in a row under one line. As the figure scrolls in, its words rise, the line draws from the
 * person to the model, the three labels flip up (src/motion/text.ts), the passages arrive and the
 * blocked ones are struck through. The markup holds that end state.
 */
function AccessGate() {
  const { gate } = howItWorks;
  const scope = useRef<HTMLElement>(null);
  const blockedId = useId();
  const allowedId = useId();
  const blocked = gate.passages.filter((passage) => !passage.allowed);
  const allowed = gate.passages.filter((passage) => passage.allowed);

  useMotion(scope, () => {
    const figure = scope.current;
    if (!figure) {
      return;
    }
    revealText(figure, { trigger: figure, start: "top 75%" })
      .from("[data-gate-line]", { scaleX: 0, duration: 1.2, ease: "power2.inOut" }, 0.2)
      .from(
        "[data-gate-passage]",
        { opacity: 0, y: 24, duration: 0.6, stagger: 0.12, ease: "power3.out" },
        0.7,
      )
      .from(
        "[data-blocked-mark]",
        { scaleX: 0, duration: 0.5, stagger: 0.2, ease: "power2.inOut" },
        1.4,
      );
  });

  return (
    <figure ref={scope} className="mt-24 border-t border-border-subtle pt-16 sm:mt-32">
      <figcaption className="max-w-3xl">
        <h3 data-reveal-words="" className="font-title text-content-primary">
          {gate.title}
        </h3>
        <p data-reveal-words="" className="mt-4 font-lead text-content-secondary">
          {gate.summary}
        </p>
      </figcaption>
      <div className="relative mt-14 grid gap-12 lg:grid-cols-3 lg:gap-10 lg:pt-10">
        <span
          aria-hidden="true"
          data-gate-line=""
          className="absolute inset-x-0 top-0 hidden h-px origin-left bg-accent lg:block"
        />
        <div>
          <p data-reveal-flip="" className={cn(label, "bg-citation-surface text-citation-content")}>
            {gate.person.title}
          </p>
          <p className="mt-5 font-lead text-content-secondary">{gate.person.description}</p>
        </div>
        <div>
          <p data-reveal-flip="" className={cn(label, "bg-accent-surface text-accent-content")}>
            {gate.check}
          </p>
          <p id={blockedId} className="mt-5 font-main-ui-body text-content-muted">
            {gate.blockedLabel}
          </p>
          <ul aria-labelledby={blockedId} className="mt-3 space-y-2">
            {blocked.map((passage) => (
              <li
                key={passage.title}
                data-gate-passage=""
                className="font-heading-item text-content-muted"
              >
                <span className="relative">
                  {passage.title}
                  <span
                    aria-hidden="true"
                    data-blocked-mark=""
                    className="absolute inset-x-0 top-[55%] h-[0.08em] origin-left bg-current"
                  />
                </span>
                <span className="ml-3 align-middle font-secondary-action">{gate.blockedNote}</span>
              </li>
            ))}
          </ul>
        </div>
        <div>
          <p data-reveal-flip="" className={cn(label, "bg-citation-surface text-citation-content")}>
            {gate.model.title}
          </p>
          <p className="mt-5 font-lead text-content-secondary">{gate.model.description}</p>
          <p id={allowedId} className="mt-5 font-main-ui-body text-content-muted">
            {gate.allowedLabel}
          </p>
          <ul aria-labelledby={allowedId} className="mt-3 space-y-2">
            {allowed.map((passage) => (
              <li
                key={passage.title}
                data-gate-passage=""
                className="font-heading-item text-accent"
              >
                {passage.title}
              </li>
            ))}
          </ul>
        </div>
      </div>
    </figure>
  );
}

export { AccessGate };
