import { trustSignals } from "@/content";

function TrustStrip() {
  return (
    <section
      aria-label="Customer and backing"
      className="border-y border-border-subtle bg-surface-raised px-[var(--page-gutter)]"
    >
      <ul className="mx-auto grid max-w-[var(--page-width-wide)] divide-y divide-border-subtle sm:grid-cols-2 sm:divide-x sm:divide-y-0">
        {trustSignals.map((signal) => (
          <li key={signal.title} className="py-6 sm:py-8 sm:pr-8 sm:not-first:pl-8">
            <p className="font-heading-h3 text-content-primary">{signal.title}</p>
            <p className="mt-1 font-main-ui-body text-content-secondary">{signal.description}</p>
          </li>
        ))}
      </ul>
    </section>
  );
}

export { TrustStrip };
