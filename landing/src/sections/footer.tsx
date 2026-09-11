import { ActionLink } from "@/components/action-link";
import { ParticleField } from "@/components/particle-field";
import { contact, footer } from "@/content";

function Footer() {
  const year = new Date().getFullYear();

  return (
    <footer className="relative isolate overflow-hidden border-t border-border-subtle bg-surface-raised px-[var(--page-gutter)]">
      <div aria-hidden="true" className="cta-glow pointer-events-none absolute inset-0 -z-10" />
      {/* The particles gather on the contact action: below the copy on small screens, beside it from sm. */}
      <ParticleField className="[--field-x:0.2] [--field-y:0.62] sm:[--field-x:0.86] sm:[--field-y:0.5]" />
      <div className="mx-auto max-w-[var(--page-width-wide)]">
        <div className="flex flex-col gap-8 py-16 sm:flex-row sm:items-end sm:justify-between sm:py-20">
          <div className="max-w-xl">
            <h2 className="font-heading-section text-content-primary">{footer.title}</h2>
            <p className="mt-4 font-lead text-content-secondary">{footer.description}</p>
          </div>
          <ActionLink href={contact.href} size="lg" className="self-start sm:self-auto">
            {footer.action}
          </ActionLink>
        </div>
        <p className="border-t border-border-subtle py-8 font-main-ui-body text-content-muted">
          © {year} {footer.organization}
        </p>
      </div>
    </footer>
  );
}

export { Footer };
