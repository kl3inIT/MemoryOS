import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { ArrowUpRight, FileText, Quote } from "lucide-react";
import type { MouseEvent } from "react";
import type { Result as SearchResult, Section as SearchSection } from "@/lib/hey-api/types.gen";
import { createSearchSnippet, friendlyMediaType } from "./search-presentation";

type SearchResultCardProps = {
  item: SearchResult;
  query: string;
  onOpen: (
    item: SearchResult,
    section: SearchSection | undefined,
    trigger: HTMLButtonElement,
  ) => void;
};

export function SearchResultCard({ item, query, onOpen }: SearchResultCardProps) {
  const ui = useAppTranslation();

  const title = item.title || ui("Untitled document");
  const updatedAt = readableDate(item.updatedAt);

  function open(section: SearchSection | undefined, event: MouseEvent<HTMLButtonElement>) {
    onOpen(item, section, event.currentTarget);
  }

  return (
    <article className="group relative py-5 sm:px-2">
      <header className="flex items-start gap-3.5">
        <span className="mt-0.5 flex size-9 shrink-0 items-center justify-center rounded-lg border border-border-default bg-surface-raised text-content-secondary shadow-xs">
          <FileText className="size-4" aria-hidden="true" />
        </span>
        <div className="min-w-0 flex-1">
          <button
            type="button"
            className="max-w-full cursor-pointer rounded-sm text-left font-heading-h3 text-content-primary underline-offset-4 transition-colors duration-150 hover:text-content-secondary hover:underline focus-visible:outline-hidden focus-visible:ring-3 focus-visible:ring-focus-ring/30 motion-reduce:transition-none"
            onClick={(event) => open(item.sections[0], event)}
          >
            <span className="line-clamp-2 break-words">{title}</span>
          </button>
          <p className="mt-1.5 flex flex-wrap items-center gap-x-2 gap-y-1 font-secondary-body text-content-muted">
            <span className="rounded-md border border-border-subtle bg-surface-subtle px-1.5 py-0.5 font-secondary-action text-content-secondary">
              {ui(friendlyMediaType(item.mediaType))}
            </span>
            {updatedAt ? (
              <>
                <span aria-hidden="true">·</span>
                <time dateTime={item.updatedAt}>
                  {ui("Updated")} {updatedAt}
                </time>
              </>
            ) : null}
          </p>
        </div>
      </header>

      <ol className="mt-4 ml-12 space-y-2.5">
        {item.sections.map((section, index) => {
          const snippet = createSearchSnippet(section.content, title, query, 240);
          const matchLabel =
            index === 0 ? ui("Best match") : ui("Related match {{number}}", { number: index + 1 });
          return (
            <li key={`${section.startOrdinal}:${section.endOrdinal}`}>
              <div className="rounded-xl border border-transparent px-3 py-2.5 transition-[background-color,border-color] duration-150 hover:border-border-subtle hover:bg-surface-subtle focus-within:border-border-default focus-within:bg-surface-subtle motion-reduce:transition-none">
                <div className="flex items-center justify-between gap-3">
                  <p className="inline-flex items-center gap-1.5 font-secondary-action text-content-muted">
                    <Quote className="size-3" aria-hidden="true" />
                    {matchLabel}
                  </p>
                  <button
                    type="button"
                    className="inline-flex min-h-8 shrink-0 cursor-pointer items-center gap-1 rounded-md px-2 font-secondary-action text-content-secondary outline-none transition-colors duration-150 hover:bg-surface-raised hover:text-content-primary focus-visible:ring-3 focus-visible:ring-focus-ring/30 motion-reduce:transition-none"
                    aria-label={ui("Open {{v1}} in {{v2}}", {
                      v1: matchLabel.toLowerCase(),
                      v2: title,
                    })}
                    onClick={(event) => open(section, event)}
                  >
                    {ui("View context")}
                    <ArrowUpRight className="size-3" aria-hidden="true" />
                  </button>
                </div>
                <p className="mt-1.5 line-clamp-3 whitespace-pre-wrap break-words font-main-ui-body text-content-secondary">
                  {snippet.parts.length === 0 ? ui("No preview text available.") : null}
                  {snippet.parts.map((part, partIndex) =>
                    part.highlighted ? (
                      <mark
                        key={`${partIndex}:${part.text}`}
                        className="rounded-sm bg-status-warning-surface px-0.5 text-status-warning-content"
                      >
                        {part.text}
                      </mark>
                    ) : (
                      <span key={`${partIndex}:${part.text}`}>{part.text}</span>
                    ),
                  )}
                </p>
              </div>
            </li>
          );
        })}
      </ol>
    </article>
  );
}

function readableDate(value: string): string | null {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  return date.toLocaleDateString(uiLocale());
}
