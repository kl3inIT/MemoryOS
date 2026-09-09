import { useQuery } from "@tanstack/react-query";
import { CalendarDays, FileStack, LoaderCircle, Search, SlidersHorizontal, X } from "lucide-react";
import { useRef, useState } from "react";
import { AppShell } from "@/components/app-shell/app-shell";
import { Brand } from "@/components/brand";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { DocumentPreviewDialog, type DocumentSelection } from "./document-preview-dialog";
import { SearchResultCard } from "./search-result-card";
import { friendlyMediaType } from "./search-presentation";
import { sameOriginMutationHeaders } from "@/lib/api";
import { cn } from "@/lib/utils";
import { searchDocuments } from "@/lib/hey-api/sdk.gen";
import type { Result as SearchResult, SearchRequest, Section } from "@/lib/hey-api/types.gen";

export function SearchPage() {
  const [query, setQuery] = useState("");
  const [mediaType, setMediaType] = useState("");
  const [since, setSince] = useState("");
  const [request, setRequest] = useState<SearchRequest | null>(null);
  const [selected, setSelected] = useState<DocumentSelection | null>(null);
  const searchInputRef = useRef<HTMLInputElement | null>(null);
  const returnFocusRef = useRef<HTMLElement | null>(null);
  const result = useQuery({
    queryKey: ["document-search", request],
    queryFn: async ({ signal }) => {
      const response = await searchDocuments({
        body: request!,
        headers: sameOriginMutationHeaders,
        signal,
        throwOnError: true,
      });
      return response.data;
    },
    enabled: request !== null,
    retry: false,
    staleTime: 0,
  });

  function submit() {
    if (!query.trim()) return;
    setSelected(null);
    const nextRequest: SearchRequest = {
      query: query.trim(),
      mediaTypes: mediaType ? [mediaType] : [],
      updatedSince: since ? new Date(`${since}T00:00:00`).toISOString() : undefined,
      page: 0,
      pageSize: 10,
    };
    if (JSON.stringify(nextRequest) === JSON.stringify(request)) void result.refetch();
    else setRequest(nextRequest);
  }

  function clearFilters() {
    setMediaType("");
    setSince("");
    setSelected(null);
    if (request) {
      setRequest({ ...request, mediaTypes: [], updatedSince: undefined, page: 0 });
    }
  }

  function openDocument(
    item: SearchResult,
    section: Section | undefined,
    trigger: HTMLButtonElement,
  ) {
    if (!item.documentId || !item.generation) return;
    const activeMatchIndex = Math.max(
      0,
      item.sections.findIndex((candidate) => candidate === section),
    );
    returnFocusRef.current = trigger;
    setSelected({
      documentId: item.documentId,
      generation: item.generation,
      title: item.title || "Untitled document",
      matches: item.sections.map((candidate) => ({
        matchingOrdinal: candidate.matchingOrdinal,
        from: Math.max(0, candidate.matchingOrdinal - 1),
      })),
      activeMatchIndex,
    });
  }

  const statusMessage = searchStatus(request, result);
  const hasFilters = Boolean(mediaType || since);
  const typeFacets = result.data?.results.reduce<
    Array<{ mediaType: string; label: string; count: number }>
  >((facets, item) => {
    const existing = facets.find((facet) => facet.mediaType === item.mediaType);
    if (existing) existing.count += 1;
    else
      facets.push({
        mediaType: item.mediaType,
        label: friendlyMediaType(item.mediaType),
        count: 1,
      });
    return facets;
  }, []);

  return (
    <AppShell pageTitle="Search">
      <section
        className={cn(
          "mx-auto w-full max-w-[80rem] px-5 py-5 sm:px-8 sm:py-7 lg:px-10",
          !request && "flex min-h-full flex-col",
        )}
      >
        <h1 className="sr-only">Search documents</h1>
        <div className={cn(!request && "my-auto w-full max-w-3xl self-center pb-[10dvh]")}>
          {!request ? (
            <header className="mb-6">
              <Brand compact />
              <h2 className="mt-3 font-heading-h2 text-content-primary">How can I help?</h2>
            </header>
          ) : null}
          <form
            onSubmit={(event) => {
              event.preventDefault();
              submit();
            }}
            className={cn(
              "rounded-2xl border border-border-default p-2",
              request
                ? "bg-surface-raised shadow-sm"
                : "bg-surface-overlay shadow-md transition-shadow duration-200 focus-within:shadow-md motion-reduce:transition-none",
            )}
          >
            <div className="flex flex-col gap-2 sm:flex-row">
              <div className="relative min-w-0 flex-1">
                <Input
                  ref={searchInputRef}
                  size="lg"
                  aria-label="Search documents"
                  value={query}
                  maxLength={1000}
                  placeholder="Search connected sources"
                  className="border-transparent bg-surface-sunken pr-20 pl-4 hover:border-border-subtle focus-visible:border-focus-ring"
                  onChange={(event) => setQuery(event.target.value)}
                />
                {query ? (
                  <button
                    type="button"
                    aria-label="Clear search"
                    className="absolute top-1/2 right-11 flex size-8 -translate-y-1/2 cursor-pointer items-center justify-center rounded-md text-content-muted outline-none transition-colors duration-150 hover:bg-surface-subtle hover:text-content-primary focus-visible:ring-3 focus-visible:ring-focus-ring/30 motion-reduce:transition-none"
                    onClick={() => {
                      setQuery("");
                      searchInputRef.current?.focus();
                    }}
                  >
                    <X className="size-3.5" aria-hidden="true" />
                  </button>
                ) : null}
                <Button
                  type="submit"
                  size="sm"
                  aria-label="Search"
                  disabled={!query.trim()}
                  className="absolute top-1/2 right-1.5 w-8 -translate-y-1/2 px-0"
                >
                  <Search className="size-3.5" aria-hidden="true" />
                </Button>
              </div>
              {result.isFetching ? (
                <Button
                  type="button"
                  prominence="secondary"
                  size="lg"
                  onClick={() => {
                    searchInputRef.current?.focus();
                    setSelected(null);
                    setRequest(null);
                  }}
                >
                  Cancel
                </Button>
              ) : null}
            </div>

            {request ? (
              <div className="mt-2 grid gap-2 border-t border-border-subtle px-1 pt-2 sm:grid-cols-[auto_minmax(0,13rem)_minmax(0,13rem)_1fr] sm:items-center">
                <span className="hidden items-center gap-2 px-2 font-secondary-action text-content-muted sm:flex">
                  <SlidersHorizontal className="size-3.5" aria-hidden="true" />
                  Filters
                </span>
                <label className="relative">
                  <span className="sr-only">File type</span>
                  <FileStack
                    className="pointer-events-none absolute top-1/2 left-3 size-3.5 -translate-y-1/2 text-content-muted"
                    aria-hidden="true"
                  />
                  <Select
                    size="sm"
                    className="pl-8"
                    aria-label="File type"
                    value={mediaType}
                    onChange={(event) => setMediaType(event.target.value)}
                  >
                    <option value="">All types</option>
                    <option value="application/pdf">PDF</option>
                    <option value="application/vnd.openxmlformats-officedocument.wordprocessingml.document">
                      Word
                    </option>
                    <option value="application/vnd.openxmlformats-officedocument.presentationml.presentation">
                      PowerPoint
                    </option>
                    <option value="text/plain">Text</option>
                    <option value="text/markdown">Markdown</option>
                  </Select>
                </label>
                <label className="relative">
                  <span className="sr-only">Updated since</span>
                  <CalendarDays
                    className="pointer-events-none absolute top-1/2 left-3 z-10 size-3.5 -translate-y-1/2 text-content-muted"
                    aria-hidden="true"
                  />
                  <Input
                    size="sm"
                    type="date"
                    aria-label="Updated since"
                    className="pl-8"
                    value={since}
                    onChange={(event) => setSince(event.target.value)}
                  />
                </label>
                {hasFilters ? (
                  <Button
                    type="button"
                    size="sm"
                    prominence="tertiary"
                    className="justify-self-start sm:justify-self-end"
                    onClick={clearFilters}
                  >
                    Clear filters
                  </Button>
                ) : null}
              </div>
            ) : null}
          </form>
        </div>

        <p role="status" aria-live="polite" className="sr-only">
          {statusMessage}
        </p>

        <div className="mt-5" aria-busy={result.isFetching}>
          {!request ? null : result.isFetching ? (
            <div className="flex items-center justify-center gap-2 py-12 text-content-secondary">
              <LoaderCircle
                className="size-5 animate-spin motion-reduce:animate-none"
                aria-hidden="true"
              />
              Searching documents…
            </div>
          ) : result.isError ? (
            <div role="alert" className="rounded-xl border border-border-default p-6">
              <h2 className="font-heading-h3">Search is temporarily unavailable</h2>
              <p className="mt-2 text-content-secondary">Please try again in a moment.</p>
              <Button className="mt-4" onClick={() => void result.refetch()}>
                Try again
              </Button>
            </div>
          ) : !result.data?.results.length ? (
            <div className="py-12 text-center">
              <h2 className="font-heading-h3">No matching documents</h2>
              <p className="mt-2 text-content-secondary">
                Try a broader phrase, remove a filter, or check the document code.
              </p>
              {hasFilters ? (
                <Button className="mt-4" prominence="secondary" onClick={clearFilters}>
                  Clear filters
                </Button>
              ) : null}
            </div>
          ) : (
            <div className="grid min-w-0 gap-8 lg:grid-cols-[minmax(0,1fr)_14rem]">
              <section aria-labelledby="search-results-heading" className="min-w-0">
                <header className="flex flex-wrap items-end justify-between gap-2 border-b border-border-default pb-3">
                  <div>
                    <h2
                      id="search-results-heading"
                      className="font-main-ui-action text-content-primary"
                    >
                      {result.data.results.length}{" "}
                      {result.data.results.length === 1 ? "result" : "results"}
                    </h2>
                    <p className="mt-0.5 font-secondary-body text-content-muted">
                      Page {(request.page ?? 0) + 1} · “{request.query}”
                    </p>
                  </div>
                </header>
                <ol className="divide-y divide-border-subtle">
                  {result.data.results.map((item) => (
                    <li key={item.documentId}>
                      <SearchResultCard
                        item={item}
                        query={request.query ?? ""}
                        onOpen={openDocument}
                      />
                    </li>
                  ))}
                </ol>
                <nav
                  aria-label="Search results pages"
                  className="mt-5 flex items-center justify-between gap-3"
                >
                  <Button
                    size="sm"
                    prominence="secondary"
                    disabled={!request.page}
                    onClick={() => {
                      setSelected(null);
                      setRequest({ ...request, page: (request.page ?? 0) - 1 });
                    }}
                  >
                    Previous
                  </Button>
                  <span className="font-secondary-body text-content-muted">
                    Page {(request.page ?? 0) + 1}
                  </span>
                  <Button
                    size="sm"
                    prominence="secondary"
                    disabled={!result.data.hasMore}
                    onClick={() => {
                      setSelected(null);
                      setRequest({ ...request, page: (request.page ?? 0) + 1 });
                    }}
                  >
                    Next
                  </Button>
                </nav>
              </section>

              <aside aria-labelledby="file-types-heading" className="hidden lg:block">
                <div className="sticky top-6 border-t border-border-default pt-3">
                  <h2 id="file-types-heading" className="font-secondary-action text-content-muted">
                    File types on this page
                  </h2>
                  <ul className="mt-2 space-y-1">
                    {typeFacets?.map((facet) => {
                      const selectedFacet = mediaType === facet.mediaType;
                      return (
                        <li key={facet.mediaType}>
                          <button
                            type="button"
                            aria-label={`${facet.label}: ${facet.count} ${facet.count === 1 ? "result" : "results"} on this page`}
                            aria-pressed={selectedFacet}
                            className="flex min-h-9 w-full cursor-pointer items-center justify-between gap-3 rounded-lg px-2.5 text-left font-main-ui-body text-content-secondary outline-none transition-colors duration-150 hover:bg-surface-subtle hover:text-content-primary focus-visible:ring-3 focus-visible:ring-focus-ring/30 aria-pressed:bg-surface-subtle aria-pressed:text-content-primary motion-reduce:transition-none"
                            onClick={() => {
                              setMediaType(facet.mediaType);
                              setSelected(null);
                              setRequest({
                                ...request,
                                mediaTypes: [facet.mediaType],
                                page: 0,
                              });
                            }}
                          >
                            <span className="min-w-0 truncate">{facet.label}</span>
                            <span className="font-secondary-action text-content-muted">
                              {facet.count}
                            </span>
                          </button>
                        </li>
                      );
                    })}
                  </ul>
                </div>
              </aside>
            </div>
          )}
        </div>
      </section>

      {selected ? (
        <DocumentPreviewDialog
          key={`${selected.documentId}:${selected.generation}`}
          selection={selected}
          returnFocusRef={returnFocusRef}
          fallbackFocusRef={searchInputRef}
          onClose={() => setSelected(null)}
        />
      ) : null}
    </AppShell>
  );
}

function searchStatus(
  request: SearchRequest | null,
  result: {
    isFetching: boolean;
    isError: boolean;
    data?: { results: unknown[] };
  },
): string {
  if (!request) return "";
  if (result.isFetching) return "Searching documents.";
  if (result.isError) return "Search is temporarily unavailable.";
  const count = result.data?.results.length ?? 0;
  if (count === 0) return "No matching documents.";
  return `${count} ${count === 1 ? "result" : "results"} on this page.`;
}
