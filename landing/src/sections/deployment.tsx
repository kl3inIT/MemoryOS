import { useRef } from "react";
import { Section } from "@/components/section";
import { deployment, type DeploymentHost } from "@/content";
import { cn } from "@/lib/utils";
import { gsap, ScrollTrigger, useMotion } from "@/motion/motion";

// `flow` links run down the stacked diagram and across it from lg; `vertical` links always run down.
type LinkAxis = "flow" | "vertical";

type ConnectorProps = {
  label: string;
  axis: LinkAxis;
  className?: string;
};

function Connector({ label, axis, className }: ConnectorProps) {
  const across = axis === "flow";
  return (
    <div
      className={cn(
        "relative flex min-h-14 items-center justify-center",
        across && "lg:min-h-0",
        className,
      )}
    >
      <span
        aria-hidden="true"
        data-link=""
        data-axis={axis}
        className={cn(
          "absolute inset-y-0 left-1/2 w-px origin-top bg-border-strong",
          across &&
            "lg:inset-x-0 lg:inset-y-auto lg:top-1/2 lg:left-0 lg:h-px lg:w-auto lg:origin-left",
        )}
      />
      {/* A dot rides a track that moves by its own length; the wider box keeps the dot unclipped. */}
      <span
        aria-hidden="true"
        className={cn(
          "absolute inset-y-0 left-1/2 -ml-1 w-2 overflow-hidden",
          across &&
            "lg:inset-x-0 lg:inset-y-auto lg:top-1/2 lg:left-0 lg:-mt-1 lg:ml-0 lg:h-2 lg:w-auto",
        )}
      >
        <span data-packet-track="" data-axis={axis} className="absolute inset-0 opacity-0">
          <span
            className={cn(
              "absolute bottom-0 left-1/2 -ml-[3px] size-1.5 rounded-full bg-accent",
              across && "lg:top-1/2 lg:right-0 lg:bottom-auto lg:left-auto lg:-mt-[3px] lg:ml-0",
            )}
          />
        </span>
      </span>
      <span
        data-link-label=""
        className="relative rounded-full border border-border-subtle bg-surface-canvas px-2 py-0.5 font-secondary-action text-content-secondary"
      >
        {label}
      </span>
    </div>
  );
}

type HostCardProps = {
  host: DeploymentHost;
  className?: string;
};

function HostCard({ host, className }: HostCardProps) {
  return (
    <div
      data-host=""
      className={cn("rounded-xl border border-border-subtle bg-surface-raised p-4", className)}
    >
      <p className="font-main-ui-action text-content-primary">{host.title}</p>
      <p className="font-secondary-body text-content-muted">{host.platform}</p>
      <ul className="mt-3 grid gap-x-4 gap-y-1.5 font-main-ui-body text-content-secondary sm:grid-cols-[repeat(auto-fit,minmax(11rem,1fr))]">
        {host.services.map((service) => (
          <li key={service} data-service="">
            {service}
          </li>
        ))}
      </ul>
    </div>
  );
}

/*
 * Stacked below lg, the account and the VPC are real boxes. From lg the diagram is one grid: those
 * boxes become `display: contents`, two decorative frames draw the boundaries, and the links sit in
 * the columns between the boundaries, so the HTTPS link visibly crosses both and the model link
 * leaves the VPC. The diagram assembles once, then dots follow a request while it is on screen.
 */
function Deployment() {
  const {
    caption,
    people,
    entry,
    account,
    network,
    application,
    internalLink,
    data,
    modelLink,
    model,
    sharedServices,
  } = deployment;
  const scope = useRef<HTMLElement>(null);

  useMotion(scope, ({ wide }) => {
    const figure = scope.current;
    if (!figure) {
      return;
    }
    const acrossPage = (element: HTMLElement) => wide && element.dataset.axis === "flow";

    const flow = gsap.timeline({ paused: true, repeat: -1, repeatDelay: 0.8 });
    gsap.utils
      .toArray<HTMLElement>("[data-packet-track]", figure)
      .forEach((track, index, tracks) => {
        const property = acrossPage(track) ? "xPercent" : "yPercent";
        // The last link, to the model, carries less and more slowly: only permitted context.
        const duration = index === tracks.length - 1 ? 1.4 : 0.9;
        flow
          .set(track, { opacity: 1 }, index * 0.9)
          .fromTo(
            track,
            { [property]: -100 },
            { [property]: 0, duration, ease: "power1.inOut" },
            index * 0.9,
          )
          .set(track, { opacity: 0 });
      });

    let onScreen = false;
    const assemble = gsap.timeline({
      defaults: { duration: 0.5, ease: "power2.out" },
      scrollTrigger: { trigger: figure, start: "top 70%", once: true },
      onComplete: () => {
        if (onScreen) {
          flow.play();
        }
      },
    });
    assemble
      .from("[data-boundary]", { opacity: 0, scale: 0.98, stagger: 0.12 })
      .from("[data-host]", { opacity: 0, y: 12, stagger: 0.1 }, 0.25)
      .from("[data-service]", { opacity: 0, x: -6, stagger: 0.03, duration: 0.3 }, 0.45);
    gsap.utils.toArray<HTMLElement>("[data-link]", figure).forEach((link, index) => {
      assemble.from(
        link,
        { [acrossPage(link) ? "scaleX" : "scaleY"]: 0, duration: 0.4 },
        0.6 + index * 0.15,
      );
    });
    assemble
      .from("[data-link-label]", { opacity: 0, stagger: 0.15, duration: 0.3 }, 0.8)
      .from("[data-shared]", { opacity: 0, y: 8, stagger: 0.06 }, 0.9);

    ScrollTrigger.create({
      trigger: figure,
      start: "top bottom",
      end: "bottom top",
      onToggle: ({ isActive }) => {
        onScreen = isActive;
        if (isActive && assemble.progress() === 1) {
          flow.play();
        } else {
          flow.pause();
        }
      },
    });
  });

  return (
    <Section id="deployment" title={deployment.title} description={deployment.description}>
      <figure ref={scope} className="rounded-2xl bg-surface-canvas p-4 sm:p-6 lg:p-8">
        <figcaption className="font-main-ui-action text-content-secondary">{caption}</figcaption>
        <div className="mt-6 flex flex-col lg:grid lg:grid-cols-[10rem_2rem_1.25rem_1rem_minmax(0,1fr)_1rem_5.5rem_12rem_1.25rem] lg:grid-rows-[2.75rem_2.25rem_auto_3.5rem_auto_1rem_1.25rem_auto_1.25rem]">
          <span
            aria-hidden="true"
            data-boundary=""
            className="hidden rounded-2xl border border-border-default bg-surface-base lg:block lg:[grid-area:1/3/10/10]"
          />
          <span
            aria-hidden="true"
            data-boundary=""
            className="hidden rounded-xl border border-dashed border-border-strong lg:block lg:[grid-area:2/4/7/7]"
          />
          <div
            data-host=""
            className="rounded-xl border border-border-subtle bg-surface-raised p-4 lg:self-center lg:[grid-area:3/1]"
          >
            <p className="font-main-ui-action text-content-primary">{people.title}</p>
            <p className="mt-1 font-secondary-body text-content-muted">{people.description}</p>
          </div>
          <Connector label={entry} axis="flow" className="lg:[grid-area:3/2/4/5]" />
          <div
            data-boundary=""
            className="rounded-2xl border border-border-default bg-surface-base p-4 lg:contents"
          >
            <p className="font-main-ui-action text-content-primary lg:self-center lg:[grid-area:1/4/2/9]">
              {account}
            </p>
            <div
              data-boundary=""
              className="mt-4 rounded-xl border border-dashed border-border-strong p-3 lg:contents"
            >
              <p className="font-secondary-action text-content-muted lg:self-center lg:[grid-area:2/5]">
                {network}
              </p>
              <HostCard host={application} className="mt-3 lg:mt-0 lg:[grid-area:3/5]" />
              <Connector label={internalLink} axis="vertical" className="lg:[grid-area:4/5]" />
              <HostCard host={data} className="lg:[grid-area:5/5]" />
            </div>
            <Connector label={modelLink} axis="flow" className="lg:[grid-area:3/6/4/8]" />
            <HostCard host={model} className="lg:self-center lg:[grid-area:3/8]" />
            <ul className="mt-4 grid gap-2 sm:grid-cols-2 lg:mt-0 lg:grid-cols-5 lg:[grid-area:8/4/9/9]">
              {sharedServices.map((service) => (
                <li
                  key={service.title}
                  data-shared=""
                  className="rounded-lg border border-border-subtle bg-surface-raised px-3 py-2.5"
                >
                  <p className="font-main-ui-action text-content-primary">{service.title}</p>
                  <p className="font-secondary-body text-content-muted">{service.description}</p>
                </li>
              ))}
            </ul>
          </div>
        </div>
      </figure>
    </Section>
  );
}

export { Deployment };
