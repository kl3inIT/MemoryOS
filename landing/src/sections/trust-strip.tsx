import { trustSignals } from "@/content";

function TrustStrip() {
  return (
    <section
      aria-label="Design partner and backing"
      className="border-y border-border-subtle bg-surface-raised px-[var(--page-gutter)]"
    >
      <ul className="mx-auto grid max-w-[var(--page-width-wide)] divide-y divide-border-subtle sm:grid-cols-2 sm:divide-x sm:divide-y-0">
        {trustSignals.map(({ title, description, logo }) => (
          <li
            key={title}
            className="reveal flex items-center gap-5 py-6 sm:gap-6 sm:py-8 sm:pr-8 sm:not-first:pl-8 sm:not-first:[animation-range:entry_15%_entry_75%]"
          >
            {/* A fixed box with `contain` balances a wide wordmark against a compact one. */}
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
