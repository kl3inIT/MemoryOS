import { useId, useRef } from "react";
import { howItWorks } from "@/content";
import { gsap, offsetTo, useMotion } from "@/motion/motion";

function GateLink() {
  return (
    <div aria-hidden="true" className="relative mx-auto h-8 w-px lg:h-px lg:w-full">
      <span data-gate-link="" className="absolute inset-0 origin-top bg-accent/60 lg:origin-left" />
    </div>
  );
}

/*
 * Every request passes the access check: the passages leave the person, the two they may not read
 * stop at the check, and only the permitted two reach the model. The markup holds that end state.
 */
function AccessGate() {
  const { gate } = howItWorks;
  const scope = useRef<HTMLElement>(null);
  const blockedId = useId();
  const allowedId = useId();
  const blocked = gate.passages.filter((passage) => !passage.allowed);
  const allowed = gate.passages.filter((passage) => passage.allowed);

  useMotion(scope, ({ wide }) => {
    const diagram = scope.current?.querySelector<HTMLElement>("[data-gate-diagram]");
    const person = diagram?.querySelector<HTMLElement>("[data-person]");
    if (!diagram || !person) {
      return;
    }

    const timeline = gsap.timeline({
      defaults: { ease: "power2.inOut" },
      scrollTrigger: {
        trigger: diagram,
        start: "top 80%",
        end: "bottom 45%",
        scrub: 0.6,
        invalidateOnRefresh: true,
      },
    });
    timeline.from(
      "[data-gate-link]",
      { [wide ? "scaleX" : "scaleY"]: 0, stagger: 0.3, duration: 0.5 },
      0,
    );
    gsap.utils.toArray<HTMLElement>("[data-gate-passage]", diagram).forEach((passage, index) => {
      timeline.from(
        passage,
        {
          x: () => offsetTo(passage, person, diagram).x,
          y: () => offsetTo(passage, person, diagram).y,
          opacity: 0,
          duration: 1,
        },
        0.2 + index * 0.15,
      );
    });
    timeline
      .from("[data-blocked-mark]", { scaleX: 0, stagger: 0.1, duration: 0.3 }, ">-0.1")
      .from("[data-blocked-note]", { opacity: 0, stagger: 0.1, duration: 0.3 }, "<");
  });

  return (
    <figure
      ref={scope}
      className="mt-16 rounded-2xl border border-border-subtle bg-surface-canvas p-5 sm:p-8"
    >
      <figcaption className="max-w-xl">
        <h3 className="font-heading-h3 text-content-primary">{gate.title}</h3>
        <p className="mt-1 font-main-content-body text-content-secondary">{gate.summary}</p>
      </figcaption>
      <div
        data-gate-diagram=""
        className="relative mt-8 grid items-center gap-3 lg:grid-cols-[minmax(0,0.9fr)_3.5rem_minmax(0,1.1fr)_3.5rem_minmax(0,1fr)]"
      >
        <div
          data-person=""
          className="rounded-xl border border-border-subtle bg-surface-raised p-4"
        >
          <p className="font-main-ui-action text-content-primary">{gate.person.title}</p>
          <p className="mt-1 font-main-ui-body text-content-secondary">{gate.person.description}</p>
        </div>
        <GateLink />
        <div className="rounded-xl border-2 border-accent/60 bg-surface-raised p-4">
          <p className="font-main-ui-action text-accent">{gate.check}</p>
          <p id={blockedId} className="mt-3 font-secondary-action text-content-muted">
            {gate.blockedLabel}
          </p>
          <ul aria-labelledby={blockedId} className="mt-2 space-y-2">
            {blocked.map((passage) => (
              <li
                key={passage.title}
                data-gate-passage=""
                className="flex items-center justify-between gap-2 rounded-lg border border-border-subtle bg-surface-base px-3 py-2 font-main-ui-body"
              >
                <span className="relative text-content-secondary">
                  {passage.title}
                  <span
                    aria-hidden="true"
                    data-blocked-mark=""
                    className="absolute inset-x-0 top-1/2 h-px origin-left bg-content-secondary"
                  />
                </span>
                <span
                  data-blocked-note=""
                  className="shrink-0 rounded bg-surface-canvas px-1.5 py-0.5 font-secondary-action text-content-muted"
                >
                  {gate.blockedNote}
                </span>
              </li>
            ))}
          </ul>
        </div>
        <GateLink />
        <div className="rounded-xl border border-border-subtle bg-surface-raised p-4">
          <p className="font-main-ui-action text-content-primary">{gate.model.title}</p>
          <p className="mt-1 font-main-ui-body text-content-secondary">{gate.model.description}</p>
          <p id={allowedId} className="mt-3 font-secondary-action text-content-muted">
            {gate.allowedLabel}
          </p>
          <ul aria-labelledby={allowedId} className="mt-2 space-y-2">
            {allowed.map((passage) => (
              <li
                key={passage.title}
                data-gate-passage=""
                className="rounded-lg bg-citation-surface px-3 py-2 font-main-ui-body text-citation-content"
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
