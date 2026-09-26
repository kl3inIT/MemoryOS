import { useAppTranslation } from "@/i18n/use-app-translation";
import { SearchX } from "lucide-react";
import { BrandLoader } from "@/components/brand-loader";
import { Button } from "@/components/ui/button";
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "@/components/ui/empty";
import { Spinner } from "@/components/ui/spinner";
import { TablePagination } from "@/components/ui/table-pagination";
import type { Result as SearchResult, Section } from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";
import { SearchResultCard } from "./search-result-card";
import { PAGE_SIZE, type DocumentSearch } from "./use-document-search";

/** Every state of the question on screen: its loader, its failure, nothing found, or a page of results. */
export function SearchResults({
  search,
  onOpen,
}: {
  search: DocumentSearch;
  onOpen: (item: SearchResult, section: Section | undefined, trigger: HTMLButtonElement) => void;
}) {
  const ui = useAppTranslation();
  const { request, result } = search;
  if (!request) return null;
  if (result.isPending)
    return (
      <div className="flex animate-in justify-center py-12 duration-200 fade-in motion-reduce:animate-none">
        <BrandLoader label={ui("Searching documents…")} />
      </div>
    );
  if (result.isError)
    return (
      <div className="animate-in duration-200 fade-in slide-in-from-bottom-1 motion-reduce:animate-none">
        <Empty role="alert" className="min-h-72">
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <SearchX aria-hidden="true" />
            </EmptyMedia>
            <EmptyTitle role="heading" aria-level={2}>
              {ui("Search is temporarily unavailable")}
            </EmptyTitle>
            <EmptyDescription>{ui("Please try again in a moment.")}</EmptyDescription>
          </EmptyHeader>
          <Button onClick={() => void result.refetch()}>{ui("Try again")}</Button>
        </Empty>
      </div>
    );
  if (!result.data.results.length)
    return (
      <div className="animate-in duration-200 fade-in slide-in-from-bottom-1 motion-reduce:animate-none">
        <Empty className="min-h-72">
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <SearchX aria-hidden="true" />
            </EmptyMedia>
            <EmptyTitle role="heading" aria-level={2}>
              {ui("No matching documents")}
            </EmptyTitle>
            <EmptyDescription>
              {ui("Try a broader phrase, remove a filter, or check the document code.")}
            </EmptyDescription>
          </EmptyHeader>
          {search.hasFilters ? (
            <Button prominence="secondary" onClick={search.clearFilters}>
              {ui("Clear filters")}
            </Button>
          ) : null}
        </Empty>
      </div>
    );

  const { data } = result;
  const totalResults = data.totalResults;
  // The total counts readable Documents among bounded candidates; at the bound there may be more.
  const totalLabel =
    totalResults >= data.candidateLimit ? `${totalResults}+` : String(totalResults);
  const totalPages = Math.min(search.maxPages, Math.max(1, Math.ceil(totalResults / PAGE_SIZE)));
  const { currentPage } = search;
  return (
    <div className="min-w-0 animate-in duration-200 fade-in slide-in-from-bottom-1 motion-reduce:animate-none">
      <section
        aria-labelledby="search-results-heading"
        className={cn("min-w-0 transition-opacity", result.isPlaceholderData && "opacity-60")}
      >
        <header className="flex flex-wrap items-baseline justify-between gap-3 border-b border-border-subtle pb-3">
          <h2 id="search-results-heading" className="font-main-ui-action text-content-primary">
            {totalLabel === "1"
              ? ui("{{count}} result for “{{query}}”", { count: totalLabel, query: request.query })
              : ui("{{count}} results for “{{query}}”", {
                  count: totalLabel,
                  query: request.query,
                })}
          </h2>
          {result.isFetching ? (
            <span className="inline-flex items-center gap-1.5 font-secondary-action text-content-muted">
              <Spinner className="size-3.5" />
              {ui("Updating")}
            </span>
          ) : null}
        </header>
        <ol className="divide-y divide-border-subtle">
          {data.results.map((item) => (
            <li key={item.documentId}>
              <SearchResultCard item={item} query={request.query ?? ""} onOpen={onOpen} />
            </li>
          ))}
        </ol>
        <TablePagination
          label={ui("Search results pages")}
          // A placeholder is still the previous page, so label the page actually shown.
          page={data.page}
          totalPages={totalPages}
          summary={ui("Showing {{first}}–{{last}} of {{total}}", {
            first: data.page * PAGE_SIZE + 1,
            last: data.page * PAGE_SIZE + data.results.length,
            total: totalLabel,
          })}
          previousDisabled={currentPage <= 0 || result.isPlaceholderData}
          nextDisabled={
            !data.hasMore || currentPage + 1 >= search.maxPages || result.isPlaceholderData
          }
          onPrevious={() => search.showPage(currentPage - 1)}
          onNext={() => search.showPage(currentPage + 1)}
        />
      </section>
    </div>
  );
}
