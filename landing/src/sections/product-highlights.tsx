import { ArrowRight, BadgeCheck, Check } from "lucide-react";
import type { ReactNode } from "react";
import { Section } from "@/components/section";
import { product, type Highlight } from "@/content";
import { cn } from "@/lib/utils";

type HighlightRowProps = {
  highlight: Highlight;
  visual: ReactNode;
  reversed?: boolean;
};

function HighlightRow({ highlight, visual, reversed = false }: HighlightRowProps) {
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
              <Check aria-hidden="true" className="mt-1 size-4 shrink-0" />
              {point}
            </li>
          ))}
        </ul>
      </div>
      {visual}
    </article>
  );
}

function SourcesVisual() {
  const { sources, indexLabel, outputs } = product.search;

  return (
    <div className="grid items-center gap-4 rounded-2xl bg-surface-canvas p-6 sm:grid-cols-[minmax(0,1fr)_auto_minmax(0,1fr)] sm:p-8">
      <ul className="space-y-2">
        {sources.map((source) => (
          <li
            key={source}
            className="rounded-lg border border-border-subtle bg-surface-raised px-3 py-2 font-main-ui-body text-content-primary"
          >
            {source}
          </li>
        ))}
      </ul>
      <ArrowRight
        aria-hidden="true"
        className="mx-auto size-5 rotate-90 text-content-muted sm:rotate-0"
      />
      <div className="rounded-xl bg-[var(--action-default-primary-surface)] p-5 text-content-inverse">
        <p className="font-main-ui-action">{indexLabel}</p>
        <ul className="mt-3 space-y-2 font-main-ui-body text-content-inverse/75">
          {outputs.map((output) => (
            <li key={output}>{output}</li>
          ))}
        </ul>
      </div>
    </div>
  );
}

function GovernanceVisual() {
  const { asset } = product.governance;

  return (
    <div className="rounded-2xl bg-surface-canvas p-6 sm:p-8">
      <div className="rounded-xl border border-border-subtle bg-surface-raised shadow-sm">
        <div className="flex items-center justify-between gap-3 border-b border-border-subtle px-5 py-4">
          <p className="font-main-ui-action text-content-primary">{asset.name}</p>
          <span className="inline-flex items-center gap-1.5 rounded-full bg-approval-surface px-2.5 py-1 font-secondary-action text-approval-content">
            <BadgeCheck aria-hidden="true" className="size-3.5" />
            {asset.status}
          </span>
        </div>
        <dl className="divide-y divide-border-subtle px-5">
          {asset.fields.map((field) => (
            <div key={field.term} className="flex justify-between gap-4 py-3 font-main-ui-body">
              <dt className="text-content-muted">{field.term}</dt>
              <dd className="text-right text-content-primary">{field.detail}</dd>
            </div>
          ))}
        </dl>
      </div>
    </div>
  );
}

function ProductHighlights() {
  return (
    <Section id="product" title={product.title} description={product.description}>
      <div className="space-y-20 sm:space-y-28">
        <HighlightRow highlight={product.search} visual={<SourcesVisual />} />
        <HighlightRow highlight={product.governance} visual={<GovernanceVisual />} reversed />
      </div>
    </Section>
  );
}

export { ProductHighlights };
