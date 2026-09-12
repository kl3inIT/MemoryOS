import { type ComponentType, useRef } from "react";
import { Section } from "@/components/section";
import { type Entry, product } from "@/content";
import { cn } from "@/lib/utils";
import { scrubDrawings } from "@/motion/drawings";
import { gsap, useMotion } from "@/motion/motion";
import { AccessDrawing } from "@/sections/product-highlights/access-drawing";
import { SearchDrawing } from "@/sections/product-highlights/search-drawing";

type HighlightRowProps = {
  highlight: Entry;
  Illustration: ComponentType;
  reversed?: boolean;
};

// The drawings are hidden from assistive technology; each row's heading and description carry the
// meaning.
function HighlightRow({ highlight, Illustration, reversed = false }: HighlightRowProps) {
  return (
    <article className="grid items-center gap-10 lg:grid-cols-2 lg:gap-16">
      <div className={cn("max-w-xl", reversed && "lg:order-last")}>
        <h3 className="font-title text-content-primary">{highlight.title}</h3>
        <p className="mt-4 font-lead text-content-secondary">{highlight.description}</p>
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
        <HighlightRow highlight={product.identity} Illustration={AccessDrawing} reversed />
      </div>
    </Section>
  );
}

export { ProductHighlights };
