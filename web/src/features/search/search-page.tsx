import { useQuery } from "@tanstack/react-query";
import { Clock3, FileStack, LoaderCircle, Mic, Search, X } from "lucide-react";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { AppShell } from "@/components/app-shell/app-shell";
import { Brand } from "@/components/brand";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { DocumentPreviewDialog, type DocumentSelection } from "./document-preview-dialog";
import { SearchFilterMenu, type SearchFilterOption } from "./search-filter-menu";
import { SearchResultCard } from "./search-result-card";
import { friendlyMediaType } from "./search-presentation";
import { sameOriginMutationHeaders } from "@/lib/api";
import { cn } from "@/lib/utils";
import { searchDocuments } from "@/lib/hey-api/sdk.gen";
import type { Result as SearchResult, SearchRequest, Section } from "@/lib/hey-api/types.gen";

const FILE_TYPE_OPTIONS: readonly SearchFilterOption[] = [
  { value: "all", label: "All file types" },
  { value: "application/pdf", label: "PDF" },
  {
    value: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    label: "Word",
  },
  {
    value: "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    label: "PowerPoint",
  },
  { value: "text/plain", label: "Text" },
  { value: "text/markdown", label: "Markdown" },
];

const TIME_RANGE_OPTIONS: readonly SearchFilterOption[] = [
  { value: "all", label: "All time" },
  { value: "7d", label: "Past 7 days" },
  { value: "30d", label: "Past 30 days" },
  { value: "365d", label: "Past year" },
];

type SearchTimeRange = "all" | "7d" | "30d" | "365d";

type SpeechRecognitionAlternativeLike = {
  transcript: string;
};

type SpeechRecognitionResultLike = {
  readonly isFinal: boolean;
  readonly length: number;
  readonly [index: number]: SpeechRecognitionAlternativeLike;
};

type SpeechRecognitionEventLike = {
  readonly results: ArrayLike<SpeechRecognitionResultLike>;
};

type SpeechRecognitionErrorEventLike = {
  readonly error: string;
};

type SpeechRecognitionLike = {
  continuous: boolean;
  interimResults: boolean;
  lang: string;
  onend: (() => void) | null;
  onerror: ((event: SpeechRecognitionErrorEventLike) => void) | null;
  onresult: ((event: SpeechRecognitionEventLike) => void) | null;
  onstart: (() => void) | null;
  abort: () => void;
  start: () => void;
  stop: () => void;
};

type SpeechRecognitionConstructor = new () => SpeechRecognitionLike;

type VoiceStatus = "idle" | "listening" | "permission-denied" | "error";

export function SearchPage() {
  const [query, setQuery] = useState("");
  const [mediaType, setMediaType] = useState<string | null>(null);
  const [timeRange, setTimeRange] = useState<SearchTimeRange>("all");
  const [request, setRequest] = useState<SearchRequest | null>(null);
  const [selected, setSelected] = useState<DocumentSelection | null>(null);
  const searchInputRef = useRef<HTMLInputElement | null>(null);
  const searchFormRef = useRef<HTMLFormElement | null>(null);
  const previousSearchTopRef = useRef<number | null>(null);
  const returnFocusRef = useRef<HTMLElement | null>(null);
  const speechRecognitionRef = useRef<SpeechRecognitionLike | null>(null);
  const voiceQueryPrefixRef = useRef("");
  const [voiceStatus, setVoiceStatus] = useState<VoiceStatus>("idle");
  const SpeechRecognition = getSpeechRecognitionConstructor();
  const voiceSearchSupported = SpeechRecognition !== null;
  const isListening = voiceStatus === "listening";
  const hasRequest = request !== null;
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

  useLayoutEffect(() => {
    const previousTop = previousSearchTopRef.current;
    const form = searchFormRef.current;
    previousSearchTopRef.current = null;
    if (
      previousTop === null ||
      !form ||
      typeof form.animate !== "function" ||
      window.matchMedia?.("(prefers-reduced-motion: reduce)").matches
    ) {
      return;
    }
    const offset = previousTop - form.getBoundingClientRect().top;
    if (Math.abs(offset) < 1) return;
    const animation = form.animate(
      [{ transform: `translateY(${offset}px)` }, { transform: "translateY(0)" }],
      { duration: 320, easing: "cubic-bezier(0.22, 1, 0.36, 1)" },
    );
    return () => animation.cancel();
  }, [hasRequest]);

  useEffect(
    () => () => {
      const recognition = speechRecognitionRef.current;
      speechRecognitionRef.current = null;
      recognition?.abort();
    },
    [],
  );

  function toggleVoiceSearch() {
    if (isListening) {
      speechRecognitionRef.current?.stop();
      return;
    }
    if (!SpeechRecognition) return;

    const recognition = new SpeechRecognition();
    voiceQueryPrefixRef.current = query.trim();
    recognition.lang = document.documentElement.lang || navigator.language || "vi-VN";
    recognition.continuous = false;
    recognition.interimResults = true;
    recognition.onstart = () => setVoiceStatus("listening");
    recognition.onresult = (event) => {
      const transcript = Array.from(event.results)
        .map((speechResult) => speechResult[0]?.transcript ?? "")
        .join(" ")
        .trim();
      const prefix = voiceQueryPrefixRef.current;
      setQuery([prefix, transcript].filter(Boolean).join(" "));
    };
    recognition.onerror = (event) => {
      setVoiceStatus(
        event.error === "not-allowed" || event.error === "service-not-allowed"
          ? "permission-denied"
          : "error",
      );
    };
    recognition.onend = () => {
      speechRecognitionRef.current = null;
      setVoiceStatus((current) => (current === "listening" ? "idle" : current));
      searchInputRef.current?.focus();
    };
    speechRecognitionRef.current = recognition;
    setVoiceStatus("listening");
    try {
      recognition.start();
    } catch {
      speechRecognitionRef.current = null;
      setVoiceStatus("error");
    }
  }

  function submit() {
    if (!query.trim()) return;
    if (!request) {
      previousSearchTopRef.current = searchFormRef.current?.getBoundingClientRect().top ?? null;
    }
    setSelected(null);
    const nextRequest: SearchRequest = {
      query: query.trim(),
      mediaTypes: mediaType ? [mediaType] : [],
      updatedSince: updatedSinceForTimeRange(timeRange),
      page: 0,
      pageSize: 10,
    };
    if (JSON.stringify(nextRequest) === JSON.stringify(request)) void result.refetch();
    else setRequest(nextRequest);
  }

  function clearFilters() {
    setMediaType(null);
    setTimeRange("all");
    setSelected(null);
    if (request) {
      setRequest({ ...request, mediaTypes: [], updatedSince: undefined, page: 0 });
    }
  }

  function selectMediaType(value: string) {
    const nextMediaType = value === "all" ? null : value;
    setMediaType(nextMediaType);
    setSelected(null);
    if (request) {
      setRequest({ ...request, mediaTypes: nextMediaType ? [nextMediaType] : [], page: 0 });
    }
  }

  function selectTimeRange(value: string) {
    const nextTimeRange = value as SearchTimeRange;
    setTimeRange(nextTimeRange);
    setSelected(null);
    if (request) {
      setRequest({
        ...request,
        updatedSince: updatedSinceForTimeRange(nextTimeRange),
        page: 0,
      });
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
  const hasFilters = Boolean(mediaType || timeRange !== "all");
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
            ref={searchFormRef}
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
              <div className="flex min-w-0 flex-1 items-center rounded-lg border border-transparent bg-surface-sunken pr-1 transition-[border-color,box-shadow] duration-150 focus-within:border-focus-ring focus-within:ring-3 focus-within:ring-focus-ring/30 hover:border-border-subtle motion-reduce:transition-none">
                <Input
                  ref={searchInputRef}
                  size="lg"
                  aria-label="Search documents"
                  value={query}
                  maxLength={1000}
                  placeholder="Search connected sources"
                  className="min-w-0 flex-1 border-transparent bg-transparent pr-2 pl-4 hover:border-transparent focus-visible:border-transparent focus-visible:ring-0"
                  onChange={(event) => setQuery(event.target.value)}
                />
                {query ? (
                  <button
                    type="button"
                    aria-label="Clear search"
                    className="flex size-8 shrink-0 cursor-pointer items-center justify-center rounded-md text-content-muted outline-none transition-colors duration-150 hover:bg-surface-subtle hover:text-content-primary focus-visible:ring-3 focus-visible:ring-focus-ring/30 motion-reduce:transition-none"
                    onClick={() => {
                      setQuery("");
                      searchInputRef.current?.focus();
                    }}
                  >
                    <X className="size-3.5" aria-hidden="true" />
                  </button>
                ) : null}
                <IconButton
                  type="button"
                  size="lg"
                  prominence="internal"
                  tone={isListening ? "danger" : "default"}
                  aria-label={isListening ? "Stop voice search" : "Search by voice"}
                  aria-pressed={isListening}
                  aria-describedby="voice-search-status"
                  disabled={!voiceSearchSupported}
                  title={
                    voiceSearchSupported
                      ? isListening
                        ? "Stop listening"
                        : "Search by voice"
                      : "Voice search is not supported in this browser"
                  }
                  onClick={toggleVoiceSearch}
                >
                  <Mic
                    className={cn(isListening && "animate-pulse motion-reduce:animate-none")}
                    aria-hidden="true"
                  />
                </IconButton>
                <IconButton type="submit" size="lg" aria-label="Search" disabled={!query.trim()}>
                  <Search aria-hidden="true" />
                </IconButton>
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
              <div className="mt-2 flex animate-in flex-wrap items-center gap-1 border-t border-border-subtle px-1 pt-2 duration-200 fade-in slide-in-from-top-1 motion-reduce:animate-none">
                <SearchFilterMenu
                  label="Updated"
                  value={timeRange}
                  options={TIME_RANGE_OPTIONS}
                  icon={<Clock3 className="size-3.5" />}
                  onChange={selectTimeRange}
                />
                <SearchFilterMenu
                  label="File type"
                  value={mediaType ?? "all"}
                  options={FILE_TYPE_OPTIONS}
                  icon={<FileStack className="size-3.5" />}
                  onChange={selectMediaType}
                />
                {hasFilters ? (
                  <Button
                    type="button"
                    size="sm"
                    prominence="tertiary"
                    className="ml-auto"
                    onClick={clearFilters}
                  >
                    Clear filters
                  </Button>
                ) : null}
              </div>
            ) : null}
            <p
              id="voice-search-status"
              aria-live="polite"
              className={cn(
                "px-2 font-secondary-body",
                voiceStatus === "idle" && "sr-only",
                isListening ? "mt-2 text-content-secondary" : "mt-2 text-status-danger-content",
              )}
            >
              {voiceStatusMessage(voiceStatus, voiceSearchSupported)}
            </p>
          </form>
        </div>

        <p role="status" aria-live="polite" className="sr-only">
          {statusMessage}
        </p>

        <div className="mt-5" aria-busy={result.isFetching}>
          {!request ? null : result.isFetching ? (
            <div className="flex animate-in items-center justify-center gap-2 py-12 text-content-secondary duration-200 fade-in motion-reduce:animate-none">
              <LoaderCircle
                className="size-5 animate-spin motion-reduce:animate-none"
                aria-hidden="true"
              />
              Searching documents…
            </div>
          ) : result.isError ? (
            <div
              role="alert"
              className="mx-auto max-w-lg animate-in rounded-xl border border-border-default p-6 duration-200 fade-in slide-in-from-bottom-1 motion-reduce:animate-none"
            >
              <h2 className="font-heading-h3">Search is temporarily unavailable</h2>
              <p className="mt-2 text-content-secondary">Please try again in a moment.</p>
              <Button className="mt-4" onClick={() => void result.refetch()}>
                Try again
              </Button>
            </div>
          ) : !result.data?.results.length ? (
            <div className="animate-in py-12 text-center duration-200 fade-in slide-in-from-bottom-1 motion-reduce:animate-none">
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
            <div className="grid min-w-0 animate-in gap-8 duration-200 fade-in slide-in-from-bottom-1 motion-reduce:animate-none lg:grid-cols-[minmax(0,1fr)_14rem]">
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

function getSpeechRecognitionConstructor(): SpeechRecognitionConstructor | null {
  if (typeof window === "undefined") return null;
  const speechWindow = window as typeof window & {
    SpeechRecognition?: SpeechRecognitionConstructor;
    webkitSpeechRecognition?: SpeechRecognitionConstructor;
  };
  return speechWindow.SpeechRecognition ?? speechWindow.webkitSpeechRecognition ?? null;
}

function voiceStatusMessage(status: VoiceStatus, supported: boolean) {
  if (!supported) return "Voice search is not supported in this browser.";
  if (status === "listening") return "Listening… Speak now, then review your query.";
  if (status === "permission-denied") {
    return "Microphone access was not granted. Enable it in your browser and try again.";
  }
  if (status === "error") return "Voice search stopped unexpectedly. Please try again.";
  return "Voice search is ready.";
}

function updatedSinceForTimeRange(timeRange: SearchTimeRange): string | undefined {
  if (timeRange === "all") return undefined;
  const days = timeRange === "7d" ? 7 : timeRange === "30d" ? 30 : 365;
  const since = new Date();
  since.setUTCHours(0, 0, 0, 0);
  since.setUTCDate(since.getUTCDate() - days);
  return since.toISOString();
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
