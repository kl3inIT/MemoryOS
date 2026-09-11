import { BadgeCheck } from "lucide-react";
import { Section } from "@/components/section";
import { assets } from "@/content";

function AssetCard() {
  const { asset } = assets;

  return (
    <div className="reveal rounded-2xl bg-surface-canvas p-6 sm:p-8">
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

function Assets() {
  const { problem, solution, pillars, surfaces } = assets;

  return (
    <Section id="assets" title={assets.title} description={assets.description}>
      <div className="grid items-center gap-10 lg:grid-cols-2 lg:gap-16">
        <div className="reveal max-w-xl space-y-8">
          {[problem, solution].map((entry) => (
            <div key={entry.title}>
              <h3 className="font-heading-h2 text-content-primary">{entry.title}</h3>
              <p className="mt-4 font-main-content-body text-content-secondary">
                {entry.description}
              </p>
            </div>
          ))}
        </div>
        <AssetCard />
      </div>
      <ul className="mt-16 grid gap-px overflow-hidden rounded-2xl border border-border-subtle bg-border-subtle sm:mt-20 sm:grid-cols-2 lg:grid-cols-4">
        {pillars.map((pillar) => (
          <li key={pillar.title} className="bg-surface-raised p-6">
            <div className="reveal">
              <h3 className="font-heading-h3 text-content-primary">{pillar.title}</h3>
              <p className="mt-2 font-main-content-body text-content-secondary">
                {pillar.description}
              </p>
            </div>
          </li>
        ))}
      </ul>
      <div className="reveal mt-6 flex flex-col gap-4 rounded-2xl bg-surface-canvas p-6 lg:flex-row lg:items-center lg:justify-between">
        <p className="font-main-ui-action text-content-primary">{surfaces.title}</p>
        <ul className="flex flex-wrap gap-2">
          {surfaces.items.map((item) => (
            <li
              key={item}
              className="rounded-lg border border-border-subtle bg-surface-raised px-3 py-1.5 font-main-ui-body text-content-secondary"
            >
              {item}
            </li>
          ))}
        </ul>
      </div>
    </Section>
  );
}

export { Assets };
