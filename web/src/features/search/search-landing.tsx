import { useAppTranslation } from "@/i18n/use-app-translation";
import { History } from "lucide-react";
import type { RefObject } from "react";
import { Button } from "@/components/ui/button";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { DocumentSourceIcon } from "@/features/documents/document-source-icon";
import { FILE_TYPE_OPTIONS } from "./search-options";
import type { DocumentSearch } from "./use-document-search";

/**
 * What the page offers before a question: a file type to search in, and this person's recent questions to ask
 * again.
 */
export function SearchLanding({
  search,
  inputRef,
  onAsk,
}: {
  search: DocumentSearch;
  inputRef: RefObject<HTMLInputElement | null>;
  onAsk: (text: string) => void;
}) {
  const ui = useAppTranslation();
  return (
    <>
      {/* Preselects the filter for the first search; a query is still required. */}
      <ToggleGroup
        type="single"
        variant="outline"
        size="sm"
        aria-label={ui("File type")}
        className="mt-4 flex-wrap"
        value={search.mediaType ?? ""}
        onValueChange={(value) => {
          search.setFilter({ type: value || undefined });
          inputRef.current?.focus();
        }}
      >
        {FILE_TYPE_OPTIONS.filter((option) => option.value !== "all").map((option) => (
          <ToggleGroupItem key={option.value} value={option.value}>
            <DocumentSourceIcon mediaType={option.value} size="xs" />
            {ui(option.label)}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>

      {search.recentSearches.length ? (
        <section aria-labelledby="recent-searches-heading" className="mt-8">
          <div className="flex items-center justify-between gap-3 pl-2">
            <h2 id="recent-searches-heading" className="font-secondary-action text-content-muted">
              {ui("Recent searches")}
            </h2>
            <Button
              type="button"
              size="sm"
              prominence="internal"
              onClick={() => {
                search.clearRecentSearches();
                inputRef.current?.focus();
              }}
            >
              {ui("Clear all")}
            </Button>
          </div>
          <ul className="mt-1">
            {search.recentSearches.map((item) => (
              <li key={item}>
                <button
                  type="button"
                  className="flex min-h-10 w-full cursor-pointer items-center gap-3 rounded-lg px-2 text-left font-main-ui-body text-content-secondary outline-none transition-colors duration-150 hover:bg-surface-subtle hover:text-content-primary focus-visible:ring-3 focus-visible:ring-focus-ring/30 motion-reduce:transition-none"
                  onClick={() => onAsk(item)}
                >
                  <History className="size-4 shrink-0 text-content-muted" aria-hidden="true" />
                  <span className="min-w-0 truncate">{item}</span>
                </button>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </>
  );
}
