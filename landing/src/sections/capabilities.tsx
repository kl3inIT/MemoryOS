import { Section } from "@/components/section";
import { capabilities, type CapabilitySize } from "@/content";
import { cn } from "@/lib/utils";

// Two columns from sm and six from lg: two wide cells, six standard cells, one full-width cell.
const spanBySize: Record<CapabilitySize, string> = {
  wide: "sm:col-span-2 lg:col-span-3",
  standard: "lg:col-span-2",
  full: "sm:col-span-2 lg:col-span-6",
};

function Capabilities() {
  return (
    <Section id="capabilities" title={capabilities.title} description={capabilities.description}>
      <ul className="grid gap-px overflow-hidden rounded-2xl border border-border-subtle bg-border-subtle sm:grid-cols-2 lg:grid-cols-6">
        {capabilities.items.map(({ title, description, icon: Icon, size }) => (
          <li key={title} className={cn("bg-surface-raised p-6 sm:p-8", spanBySize[size])}>
            {/* The content moves, not the cell, so the hairline grid never shows through. */}
            <div className="reveal">
              <Icon aria-hidden="true" className="size-5 text-accent" />
              <h3 className="mt-5 font-heading-h3 text-content-primary">{title}</h3>
              <p className="mt-2 max-w-prose font-main-content-body text-content-secondary">
                {description}
              </p>
            </div>
          </li>
        ))}
      </ul>
    </Section>
  );
}

export { Capabilities };
