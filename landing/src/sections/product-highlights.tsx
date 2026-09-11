import { ArrowRight, Check } from "lucide-react";
import type { ReactNode } from "react";
import { Section } from "@/components/section";
import { product, type Flow, type Highlight } from "@/content";
import { cn } from "@/lib/utils";

type HighlightRowProps = {
  highlight: Highlight;
  visual: ReactNode;
  reversed?: boolean;
};

function HighlightRow({ highlight, visual, reversed = false }: HighlightRowProps) {
  return (
    <article className="grid items-center gap-10 lg:grid-cols-2 lg:gap-16">
      <div className={cn("reveal max-w-xl", reversed && "lg:order-last")}>
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
      {visual}
    </article>
  );
}

type FlowVisualProps = {
  flow: Flow;
};

function FlowVisual({ flow }: FlowVisualProps) {
  return (
    <div className="reveal grid items-center gap-4 rounded-2xl bg-surface-canvas p-6 sm:grid-cols-[minmax(0,1fr)_auto_minmax(0,1fr)] sm:p-8">
      <ul className="space-y-2">
        {flow.inputs.map((input) => (
          <li
            key={input}
            className="rounded-lg border border-border-subtle bg-surface-raised px-3 py-2 font-main-ui-body text-content-primary"
          >
            {input}
          </li>
        ))}
      </ul>
      <ArrowRight
        aria-hidden="true"
        className="mx-auto size-5 rotate-90 text-content-muted sm:rotate-0"
      />
      <div className="rounded-xl bg-accent-surface p-5 text-accent-content shadow-[0_16px_48px_-12px_var(--glow-core)]">
        <p className="font-main-ui-action">{flow.label}</p>
        <ul className="mt-3 space-y-2 font-main-ui-body text-accent-content/80">
          {flow.outputs.map((output) => (
            <li key={output}>{output}</li>
          ))}
        </ul>
      </div>
    </div>
  );
}

function ProductHighlights() {
  return (
    <Section id="product" title={product.title} description={product.description}>
      <div className="space-y-20 sm:space-y-28">
        <HighlightRow
          highlight={product.search}
          visual={<FlowVisual flow={product.search.flow} />}
        />
        <HighlightRow
          highlight={product.identity}
          visual={<FlowVisual flow={product.identity.flow} />}
          reversed
        />
      </div>
    </Section>
  );
}

export { ProductHighlights };
