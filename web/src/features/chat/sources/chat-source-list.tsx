import { ArrowUpRight } from "lucide-react";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { SourceIcon } from "@/components/assistant-ui/elements/source-icon";
import { DocumentSourceIcon } from "@/features/documents/document-source-icon";
import { DocumentMeta } from "@/features/documents/provider-link";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { ChatSource } from "./chat-evidence";
import { SourceExcerpt } from "./chat-source-excerpt";
import { sourceLocationLabels, sourceMeta, webDisplayUrl } from "./chat-source-meta";

/** File-kind tile for documents and files; site favicon (globe when unavailable) for Web pages. */
function SourceTile({ source }: { source: ChatSource }) {
  if (!source.web)
    return (
      <DocumentSourceIcon
        mediaType={source.mediaType}
        filename={source.title}
        sourceTypes={source.sourceTypes}
      />
    );
  return (
    <span className="flex size-8 shrink-0 items-center justify-center rounded-lg border border-border-subtle bg-surface-subtle">
      <SourceIcon domain={new URL(source.web.url).hostname} fallback="globe" className="size-4" />
    </span>
  );
}

/**
 * One cited source as a flat, fully clickable row: tile and title, type
 * or site, then a two-line excerpt. The number matches the inline citation chip.
 */
export function ChatSourceRow({
  source,
  onSelect,
}: {
  source: ChatSource;
  onSelect: (citationId: number) => void;
}) {
  const ui = useAppTranslation();
  const { t } = useTranslation("reader");
  // The row is a button, so the meta stays plain text; the opened source links to its provider.
  const meta = source.web
    ? new URL(source.web.url).hostname.replace(/^www\./, "")
    : sourceMeta(source, ui).join(" · ");
  return (
    <button
      type="button"
      data-slot="source-row"
      aria-label={t("readSource", { number: source.citationId, title: source.title })}
      onClick={() => onSelect(source.citationId)}
      className="flex w-full cursor-pointer items-start gap-3 rounded-xl px-3 py-2.5 text-start outline-none transition-colors duration-150 hover:bg-surface-subtle focus-visible:ring-3 focus-visible:ring-focus-ring/40 motion-reduce:transition-none"
    >
      <SourceTile source={source} />
      <span className="min-w-0 flex-1">
        <span className="flex items-start gap-2">
          <span className="line-clamp-2 min-w-0 flex-1 break-words text-sm font-medium leading-5 text-content-primary">
            {source.title}
          </span>
          <span
            aria-hidden="true"
            className="shrink-0 rounded-md bg-surface-sunken px-1.5 text-xs font-medium leading-5 text-content-secondary tabular-nums"
          >
            {source.citationId}
          </span>
        </span>
        {meta ? (
          <span className="block truncate text-xs leading-5 text-content-muted">{meta}</span>
        ) : null}
        <SourceExcerpt source={source} className="mt-1 line-clamp-2" />
      </span>
    </button>
  );
}

/** Identity of the opened source above its evidence: tile, title and meta linking to the source of record. */
export function ChatSourceHeader({
  source,
  children,
}: {
  source: ChatSource;
  children?: ReactNode;
}) {
  const ui = useAppTranslation();
  const { t } = useTranslation("reader");
  return (
    <div className="shrink-0 border-b border-border-subtle px-5 py-4">
      <div className="flex items-start gap-3">
        <SourceTile source={source} />
        <div className="min-w-0 flex-1">
          <h3
            className="line-clamp-3 break-words font-main-ui-action text-content-primary"
            title={source.title}
          >
            {source.title}
          </h3>
          <p className="mt-0.5 break-words text-xs leading-5 text-content-muted">
            {source.web ? (
              <>
                {t("sourceNumber", { number: source.citationId })}
                <span aria-hidden="true"> · </span>
                <a
                  href={source.web.url}
                  target="_blank"
                  rel="noopener noreferrer"
                  title={source.web.url}
                  aria-label={ui("Mở trang gốc")}
                  className="inline-flex max-w-full items-center gap-0.5 rounded-sm text-content-secondary underline decoration-border-default underline-offset-4 hover:text-content-primary hover:decoration-current focus-visible:outline-hidden focus-visible:ring-3 focus-visible:ring-focus-ring/30"
                >
                  <span className="truncate">{webDisplayUrl(source.web.url)}</span>
                  <ArrowUpRight className="size-3 shrink-0" aria-hidden="true" />
                </a>
              </>
            ) : (
              <DocumentMeta
                lead={[t("sourceNumber", { number: source.citationId })]}
                trail={sourceLocationLabels(source, ui)}
                mediaType={source.mediaType}
                sourceTypes={source.sourceTypes}
                providerUrl={source.providerUrl}
                title={source.title}
              />
            )}
          </p>
        </div>
      </div>
      {children}
    </div>
  );
}
