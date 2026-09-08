import { useQuery } from "@tanstack/react-query";
import { LoaderCircle, Search } from "lucide-react";
import { useRef, useState } from "react";
import { AppShell } from "@/components/app-shell/app-shell";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { DocumentPreviewDialog, type DocumentSelection } from "./document-preview-dialog";
import { SearchResultCard } from "./search-result-card";
import { sameOriginMutationHeaders } from "@/lib/api";
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

  return (
    <AppShell pageTitle="Search">
      <section className="mx-auto w-full max-w-5xl px-5 py-6 sm:px-8 sm:py-8">
        <header className="mb-5">
          <h1 className="font-heading-h2 text-content-primary">Search your documents</h1>
          <p className="mt-1.5 max-w-2xl text-content-secondary">
            Find a phrase, document code, or describe the information you need.
          </p>
        </header>

        <form
          onSubmit={(event) => {
            event.preventDefault();
            submit();
          }}
          className="space-y-3"
        >
          <div className="grid gap-2 sm:grid-cols-[minmax(0,1fr)_auto_auto]">
            <Input
              ref={searchInputRef}
              aria-label="Search documents"
              value={query}
              maxLength={1000}
              placeholder="For example: annual leave policy"
              onChange={(event) => setQuery(event.target.value)}
            />
            <Button type="submit" disabled={!query.trim()}>
              <Search className="size-4" aria-hidden="true" />
              Search
            </Button>
            {result.isFetching ? (
              <Button
                type="button"
                prominence="secondary"
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

          <div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-end">
            <label className="space-y-1 font-secondary-action text-content-secondary sm:w-48">
              <span>File type</span>
              <Select value={mediaType} onChange={(event) => setMediaType(event.target.value)}>
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
            <label className="space-y-1 font-secondary-action text-content-secondary sm:w-48">
              <span>Updated since</span>
              <Input type="date" value={since} onChange={(event) => setSince(event.target.value)} />
            </label>
            {hasFilters ? (
              <Button type="button" prominence="tertiary" onClick={clearFilters}>
                Clear filters
              </Button>
            ) : null}
          </div>
        </form>

        <p role="status" aria-live="polite" className="sr-only">
          {statusMessage}
        </p>

        <div className="mt-7" aria-busy={result.isFetching}>
          {!request ? (
            <p className="py-12 text-center text-content-muted">
              Search to see matching documents and relevant context.
            </p>
          ) : result.isFetching ? (
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
            <>
              <div className="mb-4 flex flex-wrap items-baseline justify-between gap-2">
                <p className="font-main-ui-body text-content-secondary">
                  {result.data.results.length}{" "}
                  {result.data.results.length === 1 ? "result" : "results"}
                  {" on this page"}
                </p>
                <p className="font-secondary-body text-content-muted">For “{request.query}”</p>
              </div>
              <ol className="space-y-3">
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
                className="mt-6 flex items-center justify-between gap-3"
              >
                <Button
                  prominence="secondary"
                  disabled={!request.page}
                  onClick={() => {
                    setSelected(null);
                    setRequest({ ...request, page: (request.page ?? 0) - 1 });
                  }}
                >
                  Previous
                </Button>
                <span className="font-main-ui-body text-content-secondary">
                  Page {(request.page ?? 0) + 1}
                </span>
                <Button
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
            </>
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
