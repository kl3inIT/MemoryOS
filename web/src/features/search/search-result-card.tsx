import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { CornerDownRight } from "lucide-react";
import type { MouseEvent, ReactNode } from "react";
import type { Result as SearchResult, Section as SearchSection } from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";
import { DocumentSourceIcon } from "@/features/documents/document-source-icon";
import { documentSourceLabels } from "@/features/documents/document-source-presentation";
import { ProviderLink } from "@/features/documents/provider-link";
import { friendlyMediaType } from "@/features/documents/document-source-presentation";
import { createSearchSnippet } from "./search-presentation";

type SearchResultCardProps = {
  item: SearchResult;
  query: string;
  onOpen: (
    item: SearchResult,
    section: SearchSection | undefined,
    trigger: HTMLButtonElement,
  ) => void;
};

const matchButton =
  "-mx-2 w-[calc(100%+1rem)] cursor-pointer rounded-lg px-2 text-left transition-colors duration-150 hover:bg-surface-subtle focus-visible:outline-hidden focus-visible:ring-3 focus-visible:ring-focus-ring/30 motion-reduce:transition-none";

/**
 * One document result as enterprise search lists it (Confluence, Databricks, Glean): icon, title and a
 * single metadata line, then the best match as the clickable context and related matches as compact
 * lines. Every match still opens its own anchor.
 */
export function SearchResultCard({ item, query, onOpen }: SearchResultCardProps) {
  const ui = useAppTranslation();

  const title = item.title || ui("Untitled document");
  const updatedAt = readableDate(item.updatedAt);
  const labels = documentSourceLabels(item.mediaType, item.sourceTypes);
  const [best, ...related] = item.sections;

  function open(section: SearchSection | undefined, event: MouseEvent<HTMLButtonElement>) {
    onOpen(item, section, event.currentTarget);
  }

  function matchName(index: number) {
    const label =
      index === 0 ? ui("Best match") : ui("Related match {{number}}", { number: index + 1 });
    return ui("Open {{v1}} in {{v2}}", { v1: label.toLowerCase(), v2: title });
  }

  const meta: Array<{ key: string; node: ReactNode }> = [
    { key: "type", node: ui(labels.type ?? friendlyMediaType(item.mediaType)) },
    ...labels.providers.map((provider) => ({
      key: `provider:${provider}`,
      node:
        item.providerUrl && provider !== "Tệp tải lên" ? (
          <ProviderLink href={item.providerUrl} title={title} provider={provider}>
            {ui(provider)}
          </ProviderLink>
        ) : (
          ui(provider)
        ),
    })),
    ...(item.authors.length
      ? [
          {
            key: "authors",
            node: (
              <span className="truncate" title={item.authors.join(", ")}>
                {item.authors.slice(0, 2).join(", ")}
                {item.authors.length > 2 ? ui(" +{{v1}}", { v1: item.authors.length - 2 }) : ""}
              </span>
            ),
          },
        ]
      : []),
    ...(updatedAt
      ? [
          {
            key: "updated",
            node: (
              <time dateTime={item.updatedAt}>
                {ui("Updated")} {updatedAt}
              </time>
            ),
          },
        ]
      : []),
  ];

  return (
    <article className="flex items-start gap-3.5 py-5">
      <DocumentSourceIcon
        mediaType={item.mediaType}
        filename={item.title}
        sourceTypes={item.sourceTypes}
        size="lg"
      />
      <div className="min-w-0 flex-1">
        <h3 className="font-heading-h3 text-content-primary">
          <button
            type="button"
            className="max-w-full cursor-pointer rounded-sm text-left underline-offset-4 hover:underline focus-visible:outline-hidden focus-visible:ring-3 focus-visible:ring-focus-ring/30"
            onClick={(event) => open(best, event)}
          >
            <span className="line-clamp-2 break-words">{title}</span>
          </button>
        </h3>
        {/* Each separator sits in its item's leading padding, so the first item of a wrapped line hides it. */}
        <p className="mt-0.5 overflow-hidden py-0.5 font-secondary-body text-content-muted">
          <span className="-ml-4 flex flex-wrap items-center gap-y-0.5">
            {meta.map((entry) => (
              <span
                key={entry.key}
                className="relative inline-flex min-w-0 items-center pl-4 before:absolute before:left-1.5 before:content-['·']"
              >
                {entry.node}
              </span>
            ))}
          </span>
        </p>
        {best ? (
          <button
            type="button"
            aria-label={matchName(0)}
            className={cn(matchButton, "mt-1.5 block py-1.5")}
            onClick={(event) => open(best, event)}
          >
            <Snippet
              content={best.content}
              title={title}
              query={query}
              className="line-clamp-3 font-main-ui-body text-content-secondary"
            />
          </button>
        ) : null}
        {related.length ? (
          <ul className="space-y-0.5">
            {related.map((section, index) => (
              <li key={`${section.startOrdinal}:${section.endOrdinal}`}>
                <button
                  type="button"
                  aria-label={matchName(index + 1)}
                  className={cn(matchButton, "flex items-start gap-1.5 py-1")}
                  onClick={(event) => open(section, event)}
                >
                  <CornerDownRight
                    className="mt-1 size-3.5 shrink-0 text-content-muted"
                    aria-hidden="true"
                  />
                  <Snippet
                    content={section.content}
                    title={title}
                    query={query}
                    length={160}
                    className="line-clamp-1 font-secondary-body text-content-muted"
                  />
                </button>
              </li>
            ))}
          </ul>
        ) : null}
      </div>
    </article>
  );
}

function Snippet({
  content,
  title,
  query,
  length = 240,
  className,
}: {
  content: string;
  title: string;
  query: string;
  length?: number;
  className?: string;
}) {
  const ui = useAppTranslation();
  const snippet = createSearchSnippet(content, title, query, length);
  return (
    <span className={cn("block min-w-0 whitespace-pre-wrap break-words", className)}>
      {snippet.parts.length === 0 ? ui("No preview text available.") : null}
      {snippet.parts.map((part, partIndex) =>
        part.highlighted ? (
          <mark
            key={`${partIndex}:${part.text}`}
            className="rounded-[3px] bg-evidence-highlight-surface font-medium text-content-primary [box-decoration-break:clone]"
          >
            {part.text}
          </mark>
        ) : (
          <span key={`${partIndex}:${part.text}`}>{part.text}</span>
        ),
      )}
    </span>
  );
}

function readableDate(value: string): string | null {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  return date.toLocaleDateString(uiLocale());
}
