import { ActionLink } from "@/components/action-link";
import { contact, footer } from "@/content";

function Footer() {
  const year = new Date().getFullYear();

  return (
    <footer className="relative isolate overflow-hidden border-t border-border-subtle bg-surface-raised px-[var(--page-gutter)]">
      <div aria-hidden="true" className="cta-glow pointer-events-none absolute inset-0 -z-10" />
      <div className="mx-auto max-w-[var(--page-width-wide)]">
        <div className="flex flex-col gap-8 py-16 sm:flex-row sm:items-end sm:justify-between sm:py-20">
          <div className="reveal max-w-xl">
            <h2 className="font-heading-section text-content-primary">{footer.title}</h2>
            <p className="mt-4 font-lead text-content-secondary">{footer.description}</p>
          </div>
          <ActionLink href={contact.href} size="lg" className="self-start sm:self-auto">
            {footer.action}
          </ActionLink>
        </div>
        <div className="flex flex-col gap-4 border-t border-border-subtle py-8 font-main-ui-body text-content-muted sm:flex-row sm:items-center sm:justify-between">
          <p>
            © {year} {footer.organization}
          </p>
          <ul className="flex flex-wrap gap-x-6 gap-y-2">
            {footer.links.map((link) => (
              <li key={link.href}>
                <a
                  href={link.href}
                  className="rounded-sm hover:text-content-primary"
                  {...(link.external ? { target: "_blank", rel: "noopener noreferrer" } : {})}
                >
                  {link.label}
                </a>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </footer>
  );
}

export { Footer };
