import { ArrowUpRight } from "lucide-react";
import { Fragment, type ReactNode } from "react";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import { documentSourceLabels, type DocumentSourceType } from "./document-source-presentation";

/**
 * The provider name doubles as the deep link, so a source never shows "Google Drive" next to
 * "Open in Google Drive". Provider URLs are validated Drive links; uploaded files have none.
 */
export function ProviderLink({
  href,
  title,
  children,
  className,
}: {
  href: string;
  title: string;
  children: ReactNode;
  className?: string;
}) {
  const ui = useAppTranslation();
  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      referrerPolicy="no-referrer"
      data-slot="provider-link"
      aria-label={ui("Mở {{title}} trong Google Drive", { title })}
      title={ui("Mở trong Google Drive")}
      className={cn(
        "inline-flex items-center gap-0.5 rounded-sm text-content-secondary underline decoration-border-default underline-offset-4 transition-colors hover:text-content-primary hover:decoration-current focus-visible:outline-hidden focus-visible:ring-3 focus-visible:ring-focus-ring/30 motion-reduce:transition-none",
        className,
      )}
    >
      {children}
      <ArrowUpRight className="size-3 shrink-0" aria-hidden="true" />
    </a>
  );
}

/** "Nguồn 1 · PDF · Google Drive↗ · Trang 4": type and provider between caller-supplied labels. */
export function DocumentMeta({
  lead = [],
  trail = [],
  mediaType,
  sourceTypes,
  providerUrl,
  title,
  className,
}: {
  lead?: readonly string[];
  trail?: readonly string[];
  mediaType?: string | null;
  sourceTypes?: readonly DocumentSourceType[];
  providerUrl?: string | null;
  title: string;
  className?: string;
}) {
  const ui = useAppTranslation();
  const labels = documentSourceLabels(mediaType, sourceTypes);
  const items: Array<{ key: string; node: ReactNode }> = [
    ...lead.map((text, index) => ({ key: `lead:${index}`, node: text })),
    ...(labels.type ? [{ key: "type", node: ui(labels.type) }] : []),
    ...labels.providers.map((provider) => ({
      key: `provider:${provider}`,
      node:
        providerUrl && provider !== "Tệp tải lên" ? (
          <ProviderLink href={providerUrl} title={title}>
            {ui(provider)}
          </ProviderLink>
        ) : (
          ui(provider)
        ),
    })),
    ...trail.map((text, index) => ({ key: `trail:${index}`, node: text })),
  ];
  return (
    <span data-slot="document-meta" className={cn("min-w-0", className)}>
      {items.map((item, index) => (
        <Fragment key={item.key}>
          {index ? <span aria-hidden="true"> · </span> : null}
          {item.node}
        </Fragment>
      ))}
    </span>
  );
}
