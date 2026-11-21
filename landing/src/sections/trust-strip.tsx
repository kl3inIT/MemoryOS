import { trustSignals } from "@/content";

function TrustStrip() {
  return (
    <section
      aria-label="Funding and backing"
      className="border-y border-border-subtle bg-surface-raised px-[var(--page-gutter)]"
    >
      <ul className="mx-auto flex max-w-[var(--page-width-wide)] justify-center">
        {trustSignals.map(({ title, description, logo }) => (
          <li key={title} className="flex items-center gap-5 py-6 sm:gap-6 sm:py-8">
            <span
              role="img"
              aria-label={logo.label}
              className="logo-mask h-7 w-32 shrink-0 bg-content-primary sm:w-40"
              style={{ maskImage: `url(${logo.src})` }}
            />
            <div className="min-w-0 border-l border-border-subtle pl-5 sm:pl-6">
              <p className="font-heading-h3 text-content-primary">{title}</p>
              <p className="mt-1 font-main-ui-body text-content-secondary">{description}</p>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}

export { TrustStrip };
