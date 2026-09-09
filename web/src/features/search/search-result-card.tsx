import { ArrowUpRight, FileText } from "lucide-react";
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
  const title = item.title || "Untitled document";
  const updatedAt = readableDate(item.updatedAt);

  function open(section: SearchSection | undefined, event: MouseEvent<HTMLButtonElement>) {
    onOpen(item, section, event.currentTarget);
  }

  return (
    <article className="group py-5 sm:px-2">
      <header className="flex items-start gap-3">
        <span className="mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-md border border-border-subtle bg-surface-raised text-content-secondary">
          <FileText className="size-3.5" aria-hidden="true" />
        </span>
        <div className="min-w-0 flex-1">
          <button
            type="button"
            className="max-w-full cursor-pointer rounded-sm text-left font-main-ui-action text-content-primary underline-offset-4 hover:underline focus-visible:outline-hidden focus-visible:ring-3 focus-visible:ring-focus-ring/30"
            onClick={(event) => open(item.sections[0], event)}
          >
            <span className="line-clamp-2 break-words">{title}</span>
          </button>
          <p className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 font-secondary-body text-content-muted">
            <span>{friendlyMediaType(item.mediaType)}</span>
            {updatedAt ? (
              <>
                <span aria-hidden="true">·</span>
                <time dateTime={item.updatedAt}>Updated {updatedAt}</time>
              </>
            ) : null}
          </p>
        </div>
      </header>

      <ol className="mt-3 ml-10 space-y-3">
        {item.sections.map((section, index) => {
          const snippet = createSearchSnippet(section.content, title, query, 240);
          const matchLabel = index === 0 ? "Best match" : `Related match ${index + 1}`;
          return (
            <li key={`${section.startOrdinal}:${section.endOrdinal}`}>
              <div className="flex items-center justify-between gap-3">
                <p className="font-secondary-action text-content-muted">{matchLabel}</p>
                <button
                  type="button"
                  className="inline-flex min-h-8 shrink-0 cursor-pointer items-center gap-1 rounded-md px-2 font-secondary-action text-content-secondary outline-none transition-colors duration-150 hover:bg-surface-subtle hover:text-content-primary focus-visible:ring-3 focus-visible:ring-focus-ring/30 motion-reduce:transition-none"
                  aria-label={`Open ${matchLabel.toLowerCase()} in ${title}`}
                  onClick={(event) => open(section, event)}
                >
                  View context
                  <ArrowUpRight className="size-3" aria-hidden="true" />
                </button>
              </div>
              <p className="mt-1 line-clamp-3 whitespace-pre-wrap break-words font-main-ui-body text-content-secondary">
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
  return date.toLocaleDateString();
}
