import { BadgeCheck } from "lucide-react";
import { useRef } from "react";
import { Section } from "@/components/section";
import { assets } from "@/content";
import { cn } from "@/lib/utils";
import { gsap, useMotion } from "@/motion/motion";
import { revealText } from "@/motion/text";

/*
 * Organizational AI Memory as type, like the capabilities list: the problem and the solution side by
 * side, one released asset as a record with its status on a label, the four benefits as titles in
 * brand blue over their descriptions, and the surfaces that deliver an asset as a row of labels. As
 * each block scrolls in its words rise and its labels flip up (src/motion/text.ts).
 */

const label =
  "inline-flex items-center gap-[0.3em] rounded-[0.2em] px-[0.45em] pt-[0.3em] pb-[0.22em] font-heading-item";

function Assets() {
  const { problem, solution, asset, pillars, surfaces } = assets;
  const scope = useRef<HTMLDivElement>(null);

  useMotion(scope, () => {
    const root = scope.current;
    if (!root) {
      return;
    }
    for (const block of gsap.utils.toArray<HTMLElement>("[data-reveal]", root)) {
      revealText(block, { trigger: block, start: "top 85%" });
    }
  });

  return (
    <Section id="assets" title={assets.title} description={assets.description}>
      <div ref={scope} className="grid gap-20 sm:gap-28">
        <div className="grid gap-12 lg:grid-cols-2 lg:gap-16">
          {[problem, solution].map((entry) => (
            <div key={entry.title} data-reveal="">
              <h3 data-reveal-words="" className="font-title text-content-primary">
                {entry.title}
              </h3>
              <p data-reveal-words="" className="mt-4 font-lead text-content-secondary">
                {entry.description}
              </p>
            </div>
          ))}
        </div>

        <div data-reveal="">
          <div className="flex flex-wrap items-center gap-x-5 gap-y-3">
            <p data-reveal-words="" className="font-title text-content-primary">
              {asset.name}
            </p>
            <p
              data-reveal-flip=""
              className={cn(label, "bg-approval-surface text-approval-content")}
            >
              <BadgeCheck aria-hidden="true" className="size-[0.9em]" />
              {asset.status}
            </p>
          </div>
          <dl className="mt-8 grid border-t border-border-subtle sm:grid-cols-2 lg:grid-cols-5">
            {asset.fields.map((field) => (
              <div
                key={field.term}
                className="border-b border-border-subtle py-5 lg:border-b-0 lg:pr-6"
              >
                <dt className="font-main-ui-body text-content-muted">{field.term}</dt>
                <dd data-reveal-words="" className="mt-2 font-heading-item text-content-primary">
                  {field.detail}
                </dd>
              </div>
            ))}
          </dl>
        </div>

        <ul className="grid gap-x-16 gap-y-12 lg:grid-cols-2">
          {pillars.map((pillar) => (
            <li key={pillar.title} data-reveal="">
              <h3 data-reveal-words="" className="font-title text-accent">
                {pillar.title}
              </h3>
              <p data-reveal-words="" className="mt-4 max-w-xl font-lead text-content-primary">
                {pillar.description}
              </p>
            </li>
          ))}
        </ul>

        <div data-reveal="" className="border-t border-border-subtle pt-12">
          <h3 data-reveal-words="" className="font-title text-content-primary">
            {surfaces.title}
          </h3>
          <ul className="mt-6 flex flex-wrap gap-3">
            {surfaces.items.map((item) => (
              <li
                key={item}
                data-reveal-flip=""
                className={cn(label, "bg-citation-surface text-citation-content")}
              >
                {item}
              </li>
            ))}
          </ul>
        </div>
      </div>
    </Section>
  );
}

export { Assets };
