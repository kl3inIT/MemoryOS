import { useRef } from "react";
import { Section } from "@/components/section";
import { capabilities } from "@/content";
import { gsap, ScrollTrigger, useMotion } from "@/motion/motion";
import { revealText } from "@/motion/text";

/*
 * The capabilities as type, after the tools list on gsap.com: each is a title in brand blue over
 * its description, both set large, down the page. From lg a sticky index of the titles runs beside
 * them; each entry links to its capability, and motion marks the one crossing the middle of the
 * viewport. As a capability scrolls in, its words rise into place (src/motion/text.ts).
 */

const anchorOf = (title: string) => `capability-${title.toLowerCase().replace(/[^a-z0-9]+/g, "-")}`;

function Capabilities() {
  const { items } = capabilities;
  const scope = useRef<HTMLDivElement>(null);

  useMotion(scope, () => {
    const root = scope.current;
    if (!root) {
      return undefined;
    }
    const entries = gsap.utils.toArray<HTMLElement>("[data-capability]", root);
    const links = gsap.utils.toArray<HTMLElement>("[data-capability-link]", root);
    entries.forEach((entry, index) => {
      revealText(entry, { trigger: entry, start: "top 85%" });
      ScrollTrigger.create({
        trigger: entry,
        start: "top 50%",
        end: "bottom 50%",
        onToggle: (self) => links[index]?.toggleAttribute("data-active", self.isActive),
      });
    });
    return () => links.forEach((link) => link.removeAttribute("data-active"));
  });

  return (
    <Section id="capabilities" title={capabilities.title} description={capabilities.description}>
      <div ref={scope} className="grid gap-x-16 lg:grid-cols-[minmax(0,1fr)_minmax(0,2.4fr)]">
        <nav aria-labelledby="capabilities-heading" className="hidden lg:block">
          <ol className="sticky top-28 grid border-l border-border-subtle">
            {items.map((item) => (
              <li key={item.title}>
                <a
                  href={`#${anchorOf(item.title)}`}
                  data-capability-link=""
                  className="-ml-px block border-l-2 border-transparent py-2 pl-4 font-main-ui-body text-content-muted transition-colors hover:text-content-primary data-active:border-accent data-active:text-content-primary"
                >
                  {item.title}
                </a>
              </li>
            ))}
          </ol>
        </nav>
        <div>
          {items.map((item) => (
            <article
              key={item.title}
              id={anchorOf(item.title)}
              data-capability=""
              className="scroll-mt-24 border-t border-border-subtle py-12 first:border-t-0 first:pt-0 lg:py-16"
            >
              <h3 data-reveal-words="" className="font-title text-accent">
                {item.title}
              </h3>
              <p data-reveal-words="" className="mt-4 max-w-2xl font-lead text-content-primary">
                {item.description}
              </p>
            </article>
          ))}
        </div>
      </div>
    </Section>
  );
}

export { Capabilities };
