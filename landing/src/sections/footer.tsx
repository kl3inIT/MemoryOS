import { ActionLink } from "@/components/action-link";
import { ParticleField } from "@/components/particle-field";
import { VadanLogo } from "@/components/vadan-logo";
import { contact, footer } from "@/content";

function Footer() {
  const year = new Date().getFullYear();

  return (
    <footer className="relative isolate overflow-hidden border-t border-border-subtle bg-surface-raised px-[var(--page-gutter)]">
      <div aria-hidden="true" className="cta-glow pointer-events-none absolute inset-0 -z-10" />
      {/* The particles gather on the contact action: below the copy on small screens, beside it from sm. */}
      <ParticleField className="[--field-x:0.2] [--field-y:0.62] sm:[--field-x:0.86] sm:[--field-y:0.5]" />
      <div className="mx-auto max-w-[var(--page-width-wide)]">
        <div className="flex flex-col gap-8 py-12 sm:flex-row sm:items-center sm:justify-between sm:py-14">
          <div className="max-w-2xl">
            <h2 className="font-title text-content-primary">{footer.title}</h2>
            <p className="mt-3 font-lead text-content-secondary">{footer.description}</p>
          </div>
          <ActionLink href={contact.href} size="lg" className="self-start sm:self-auto">
            {footer.action}
          </ActionLink>
        </div>
        <p className="flex items-center gap-2 border-t border-border-subtle py-6 font-main-ui-body text-content-muted">
          © {year}
          <VadanLogo className="h-3.5" />
        </p>
      </div>
    </footer>
  );
}

export { Footer };
