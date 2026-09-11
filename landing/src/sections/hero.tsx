import { ActionLink } from "@/components/action-link";
import { contact, hero } from "@/content";
import { ProductPreview } from "@/sections/product-preview";

function Hero() {
  return (
    <section
      aria-labelledby="hero-heading"
      className="relative isolate overflow-hidden px-[var(--page-gutter)] pt-14 pb-20 sm:pt-20 sm:pb-24 lg:pt-24"
    >
      <div aria-hidden="true" className="hero-glow pointer-events-none absolute inset-0 -z-10" />
      <div className="mx-auto grid max-w-[var(--page-width-wide)] items-center gap-14 lg:grid-cols-[minmax(0,1fr)_minmax(0,32rem)] lg:gap-20">
        <div className="max-w-2xl">
          <h1 id="hero-heading" className="font-display text-content-primary">
            {hero.title}
          </h1>
          <p className="mt-6 max-w-xl font-lead text-content-secondary">{hero.description}</p>
          <div className="mt-10 flex flex-wrap gap-3">
            <ActionLink href={contact.href} size="lg">
              {contact.label}
            </ActionLink>
            <ActionLink href={hero.secondaryAction.href} prominence="secondary" size="lg">
              {hero.secondaryAction.label}
            </ActionLink>
          </div>
        </div>
        <ProductPreview />
      </div>
    </section>
  );
}

export { Hero };
