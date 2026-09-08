import { useQuery } from "@tanstack/react-query";
import { FileText, LoaderCircle, Search, X } from "lucide-react";
import { useState } from "react";
import { AppShell } from "@/components/app-shell/app-shell";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { sameOriginMutationHeaders } from "@/lib/api";
import { searchDocuments } from "@/lib/hey-api/sdk.gen";
import { getSearchDocumentOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SearchRequest } from "@/lib/hey-api/types.gen";

type Selection = { documentId: string; generation: string; from: number };

export function SearchPage() {
  const [query, setQuery] = useState("");
  const [mediaType, setMediaType] = useState("");
  const [since, setSince] = useState("");
  const [request, setRequest] = useState<SearchRequest | null>(null);
  const [selected, setSelected] = useState<Selection | null>(null);
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
    const nextRequest = {
      query: query.trim(),
      mediaTypes: mediaType ? [mediaType] : [],
      updatedSince: since ? new Date(`${since}T00:00:00`).toISOString() : undefined,
      page: 0,
      pageSize: 10,
    };
    if (JSON.stringify(nextRequest) === JSON.stringify(request)) void result.refetch();
    else setRequest(nextRequest);
  }

  return (
    <AppShell pageTitle="Search">
      <section className="mx-auto w-full max-w-5xl px-5 py-8 sm:px-8">
        <header className="mb-6">
          <h1 className="font-heading-h2 text-content-primary">Search your documents</h1>
          <p className="mt-2 text-content-secondary">
            Find a phrase, a document code, or describe what you need.
          </p>
        </header>
        <form
          onSubmit={(event) => {
            event.preventDefault();
            submit();
          }}
          className="space-y-3"
        >
          <div className="flex gap-2">
            <Input
              aria-label="Search documents"
              value={query}
              maxLength={1000}
              placeholder="For example: annual leave policy"
              onChange={(event) => setQuery(event.target.value)}
            />
            <Button type="submit" disabled={!query.trim()}>
              <Search className="size-4" />
              Search
            </Button>
            {result.isFetching && (
              <Button type="button" prominence="secondary" onClick={() => setRequest(null)}>
                Cancel
              </Button>
            )}
          </div>
          <div className="flex flex-wrap items-end gap-3">
            <label className="w-44 space-y-1 text-sm text-content-secondary">
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
            <label className="w-44 space-y-1 text-sm text-content-secondary">
              <span>Updated since</span>
              <Input type="date" value={since} onChange={(event) => setSince(event.target.value)} />
            </label>
          </div>
        </form>

        <div className="mt-8" aria-live="polite" aria-busy={result.isFetching}>
          {!request ? (
            <p className="py-12 text-center text-content-muted">
              Search to see matching documents and relevant passages.
            </p>
          ) : result.isFetching ? (
            <div role="status" className="flex items-center justify-center gap-2 py-12">
              <LoaderCircle className="size-5 animate-spin motion-reduce:animate-none" />
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
          ) : !result.data?.results?.length ? (
            <div className="py-12 text-center">
              <h2 className="font-heading-h3">No matching documents</h2>
              <p className="mt-2 text-content-secondary">
                Try another phrase or broaden your filters.
              </p>
            </div>
          ) : (
            <>
              <p className="mb-4 text-sm text-content-muted">Results for “{request.query}”</p>
              <ol className="space-y-4">
                {result.data.results.map((item) => (
                  <li
                    key={item.documentId}
                    className="rounded-xl border border-border-default bg-surface-raised p-5"
                  >
                    <div className="flex items-start gap-3">
                      <FileText className="mt-1 size-5 shrink-0 text-content-muted" />
                      <div className="min-w-0 flex-1">
                        <button
                          className="text-left font-heading-h3 text-content-primary underline-offset-4 hover:underline focus-visible:underline"
                          onClick={() =>
                            item.documentId &&
                            item.generation &&
                            setSelected({
                              documentId: item.documentId,
                              generation: item.generation,
                              from: Math.max(0, (item.sections?.[0]?.matchingOrdinal ?? 0) - 1),
                            })
                          }
                        >
                          {item.title || "Untitled document"}
                        </button>
                        <p className="mt-1 text-xs text-content-muted">
                          {item.mediaType}
                          {item.updatedAt
                            ? ` · Updated ${new Date(item.updatedAt).toLocaleDateString()}`
                            : ""}
                        </p>
                        {item.sections?.map((section) => {
                          const start = (section.startOrdinal ?? 0) + 1;
                          const end = (section.endOrdinal ?? section.startOrdinal ?? 0) + 1;
                          const label =
                            start === end ? `Passage ${start}` : `Passages ${start}–${end}`;
                          return (
                            <article
                              key={section.startOrdinal}
                              className="mt-4 border-t border-border-subtle pt-3"
                            >
                              <p className="text-xs text-content-muted">{label}</p>
                              <p className="mt-2 whitespace-pre-wrap break-words text-sm text-content-secondary">
                                {section.content}
                              </p>
                              <button
                                className="mt-2 text-sm text-content-primary underline underline-offset-4"
                                aria-label={`Read ${label.toLowerCase()} in document`}
                                onClick={() =>
                                  item.documentId &&
                                  item.generation &&
                                  setSelected({
                                    documentId: item.documentId,
                                    generation: item.generation,
                                    from: Math.max(
                                      0,
                                      (section.matchingOrdinal ?? section.startOrdinal ?? 0) - 1,
                                    ),
                                  })
                                }
                              >
                                Read in document
                              </button>
                            </article>
                          );
                        })}
                      </div>
                    </div>
                  </li>
                ))}
              </ol>
              <nav
                aria-label="Search results pages"
                className="mt-6 flex items-center justify-between"
              >
                <Button
                  prominence="secondary"
                  disabled={!request.page}
                  onClick={() => setRequest({ ...request, page: (request.page ?? 0) - 1 })}
                >
                  Previous
                </Button>
                <span className="text-sm text-content-secondary">
                  Page {(request.page ?? 0) + 1}
                </span>
                <Button
                  prominence="secondary"
                  disabled={!result.data.hasMore}
                  onClick={() => setRequest({ ...request, page: (request.page ?? 0) + 1 })}
                >
                  Next
                </Button>
              </nav>
            </>
          )}
        </div>
        {selected && (
          <DocumentPreview
            key={`${selected.documentId}:${selected.generation}:${selected.from}`}
            selection={selected}
            onClose={() => setSelected(null)}
          />
        )}
      </section>
    </AppShell>
  );
}

function DocumentPreview({ selection, onClose }: { selection: Selection; onClose: () => void }) {
  const [from, setFrom] = useState(selection.from);
  const detail = useQuery({
    ...getSearchDocumentOptions({
      path: { documentId: selection.documentId },
      query: { generation: selection.generation, from },
    }),
    retry: false,
  });
  return (
    <section
      aria-label="Document passages"
      className="mt-8 rounded-xl border border-border-default bg-surface-subtle p-5"
      tabIndex={-1}
    >
      <header className="flex items-start justify-between gap-3">
        <h2 className="font-heading-h3">{detail.data?.title ?? "Document passages"}</h2>
        <Button prominence="secondary" size="sm" onClick={onClose} aria-label="Close document">
          <X className="size-4" />
        </Button>
      </header>
      {detail.isPending ? (
        <p role="status" className="mt-4">
          Loading passages…
        </p>
      ) : detail.isError ? (
        <p role="alert" className="mt-4">
          This document is unavailable or has changed. Search again to find its current version.
        </p>
      ) : (
        <>
          {detail.data.passages?.map((passage) => (
            <article key={passage.ordinal} className="mt-5 border-t border-border-subtle pt-4">
              <p className="mb-2 text-xs text-content-muted">
                Passage {(passage.ordinal ?? 0) + 1}
              </p>
              <p className="whitespace-pre-wrap break-words text-sm text-content-primary">
                {passage.content}
              </p>
            </article>
          ))}
          <div className="mt-5 flex justify-between">
            <Button
              prominence="secondary"
              disabled={from === 0}
              onClick={() => setFrom(Math.max(0, from - 20))}
            >
              Earlier passages
            </Button>
            <Button
              prominence="secondary"
              disabled={!detail.data.hasMore}
              onClick={() => setFrom(from + 20)}
            >
              More passages
            </Button>
          </div>
        </>
      )}
    </section>
  );
}
