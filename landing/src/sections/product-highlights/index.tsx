import { Check } from "lucide-react";
import { type ComponentType, useRef } from "react";
import { Section } from "@/components/section";
import { product, type Highlight } from "@/content";
import { cn } from "@/lib/utils";
import { scrubDrawings } from "@/motion/drawings";
import { gsap, useMotion } from "@/motion/motion";
import { GovernanceDrawing } from "@/sections/product-highlights/governance-drawing";
import { SearchDrawing } from "@/sections/product-highlights/search-drawing";

type HighlightRowProps = {
  highlight: Highlight;
  Illustration: ComponentType;
  reversed?: boolean;
};

// The drawings are hidden from assistive technology; each row's heading and points carry the
// meaning.
function HighlightRow({ highlight, Illustration, reversed = false }: HighlightRowProps) {
  return (
    <article className="grid items-center gap-10 lg:grid-cols-2 lg:gap-16">
      <div className={cn("max-w-xl", reversed && "lg:order-last")}>
        <h3 className="font-heading-h2 text-content-primary">{highlight.title}</h3>
        <p className="mt-4 font-main-content-body text-content-secondary">
          {highlight.description}
        </p>
        <ul className="mt-6 space-y-3">
          {highlight.points.map((point) => (
            <li key={point} className="flex gap-3 font-main-content-body text-content-primary">
              <Check aria-hidden="true" className="mt-1 size-4 shrink-0 text-accent" />
              {point}
            </li>
          ))}
        </ul>
      </div>
      <div aria-hidden="true" data-drawing="" className="aspect-[8/5] w-full">
        <Illustration />
      </div>
    </article>
  );
}

function ProductHighlights() {
  const scope = useRef<HTMLDivElement>(null);

  useMotion(scope, () => {
    const rows = scope.current;
    return rows
      ? scrubDrawings(rows, gsap.utils.toArray<HTMLElement>("[data-drawing]", rows))
      : undefined;
  });

  return (
    <Section id="product" title={product.title} description={product.description}>
      <div ref={scope} className="space-y-20 sm:space-y-28">
        <HighlightRow highlight={product.search} Illustration={SearchDrawing} />
        <HighlightRow highlight={product.governance} Illustration={GovernanceDrawing} reversed />
      </div>
    </Section>
  );
}

export { ProductHighlights };
