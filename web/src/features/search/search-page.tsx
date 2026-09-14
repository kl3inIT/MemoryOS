import { useAppTranslation } from "@/i18n/use-app-translation";
import { appText, type AppCopy } from "@/i18n/app-text";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import {
  Clock3,
  FileStack,
  History,
  LoaderCircle,
  Mic,
  Search,
  SearchX,
  SlidersHorizontal,
  X,
} from "lucide-react";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { AppShell } from "@/components/app-shell/app-shell";
import { BrandLoader } from "@/components/brand-loader";
import { Button } from "@/components/ui/button";
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "@/components/ui/empty";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { TablePagination } from "@/components/ui/table-pagination";
import { DocumentPreviewDialog, type DocumentSelection } from "./document-preview-dialog";
import { clearRecentSearches, readRecentSearches, rememberRecentSearch } from "./recent-searches";
import { SearchFilterMenu, type SearchFilterOption } from "./search-filter-menu";
import { DocumentSourceIcon } from "./document-source-icon";
import { SearchResultCard } from "./search-result-card";
import { friendlyMediaType } from "./search-presentation";
import {
  useApplicationSession,
  useGlobalCapability,
} from "@/features/identity/application-session-context";
import { sameOriginMutationHeaders } from "@/lib/api";
import { captureWorkflowFailure } from "@/lib/sentry";
import { cn } from "@/lib/utils";
import { searchDocuments } from "@/lib/hey-api/sdk.gen";
import type { Result as SearchResult, SearchRequest, Section } from "@/lib/hey-api/types.gen";

const FILE_TYPE_OPTIONS: readonly SearchFilterOption[] = [
  { value: "all", label: "All file types" },
  // Same friendly names as the file-type facets and result metadata.
  { value: "application/pdf", label: "PDF" },
  {
    value: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    label: "Word document",
  },
  {
    value: "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    label: "PowerPoint presentation",
  },
  { value: "text/plain", label: "Text document" },
  { value: "text/markdown", label: "Markdown" },
];

const TIME_RANGE_OPTIONS: readonly SearchFilterOption[] = [
  { value: "all", label: "All time" },
  { value: "7d", label: "Past 7 days" },
  { value: "30d", label: "Past 30 days" },
  { value: "365d", label: "Past year" },
];

type SearchTimeRange = "all" | "7d" | "30d" | "365d";

const PAGE_SIZE = 10;
/** `SearchRequest.page` accepts 0–49. */
const MAX_PAGES = 50;

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
  const ui = useAppTranslation();
  const canSearch = useGlobalCapability("SEARCH_READ");
  if (!canSearch) {
    return (
      <AppShell pageTitle={ui("Search")}>
        <section role="alert" className="mx-auto w-full max-w-5xl px-5 py-8 sm:px-8">
          <h1 className="font-heading-h2 text-content-primary">{ui("Search access denied")}</h1>
          <p className="mt-2 text-content-secondary">
            {ui(
              "Your account does not have permission to search or read documents. Ask an administrator for Basic access.",
            )}
          </p>
        </section>
      </AppShell>
    );
  }
  return <AuthorizedSearchPage />;
}

function AuthorizedSearchPage() {
  const ui = useAppTranslation();
  const { actorId } = useApplicationSession();
  const [recentSearches, setRecentSearches] = useState(() => readRecentSearches(actorId));
  const [query, setQuery] = useState("");
  const [mediaType, setMediaType] = useState<string | null>(null);
  const [timeRange, setTimeRange] = useState<SearchTimeRange>("all");
  const [request, setRequest] = useState<SearchRequest | null>(null);
  const [submitFeedback, setSubmitFeedback] = useState(false);
  const [selected, setSelected] = useState<DocumentSelection | null>(null);
  const searchInputRef = useRef<HTMLInputElement | null>(null);
  const searchFormRef = useRef<HTMLFormElement | null>(null);
  const previousSearchTopRef = useRef<number | null>(null);
  const returnFocusRef = useRef<HTMLElement | null>(null);
  const speechRecognitionRef = useRef<SpeechRecognitionLike | null>(null);
  const reportedSearchError = useRef<unknown>(null);
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
    staleTime: 30_000,
    refetchOnWindowFocus: false,
    placeholderData: keepPreviousData,
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

  useEffect(() => {
    if (!submitFeedback || result.isFetching) return;
    const timeout = window.setTimeout(() => setSubmitFeedback(false), 180);
    return () => window.clearTimeout(timeout);
  }, [result.isFetching, submitFeedback]);

  useEffect(() => {
    if (!request || !result.isError || result.error === reportedSearchError.current) return;
    reportedSearchError.current = result.error;
    captureWorkflowFailure(result.error, {
      workflow: "search",
      stage: "request",
      failureKind: "api-or-network",
    });
  }, [request, result.error, result.isError]);

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

  function submit(text = query) {
    if (!text.trim()) return;
    if (!request) {
      previousSearchTopRef.current = searchFormRef.current?.getBoundingClientRect().top ?? null;
    }
    setQuery(text);
    setSelected(null);
    setSubmitFeedback(true);
    setRecentSearches(rememberRecentSearch(actorId, text));
    const nextRequest: SearchRequest = {
      query: text.trim(),
      mediaTypes: mediaType ? [mediaType] : [],
      updatedSince: updatedSinceForTimeRange(timeRange),
      page: 0,
      pageSize: PAGE_SIZE,
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
      mediaType: item.mediaType,
      sourceTypes: item.sourceTypes,
      providerUrl: item.providerUrl,
      provenance:
        (section ?? item.sections[0])?.provenance.map((entry) => entry.provenanceJson) ?? [],
      matches: item.sections.map((candidate) => ({
        matchingOrdinal: candidate.matchingOrdinal,
        from: Math.max(0, candidate.matchingOrdinal - 1),
      })),
      activeMatchIndex,
    });
  }

  const isSearchUpdating = result.isFetching || submitFeedback;
  const statusMessage = searchStatus(request, result, isSearchUpdating);
  const hasFilters = Boolean(mediaType || timeRange !== "all");
  const fileTypeOptions = withResultFileTypes(result.data?.results ?? [], mediaType);
  const showLoadingScreen = isSearchUpdating;
  const currentPage = request?.page ?? 0;
  const totalResults = result.data?.totalResults ?? 0;
  // The total counts readable Documents among bounded candidates; at the bound there may be more.
  const totalLabel =
    result.data && totalResults >= result.data.candidateLimit
      ? `${totalResults}+`
      : String(totalResults);
  const totalPages = Math.min(MAX_PAGES, Math.max(1, Math.ceil(totalResults / PAGE_SIZE)));

  return (
    <AppShell pageTitle={ui("Search documents")}>
      <section
        className={cn(
          "mx-auto w-full max-w-4xl px-5 py-5 sm:px-8 sm:py-7",
          !request && "flex min-h-full flex-col",
        )}
      >
        <h1 className="sr-only">{ui("Search documents")}</h1>
        <div className={cn(!request && "my-auto w-full max-w-3xl self-center pb-[10dvh]")}>
          {!request ? (
            <header className="mb-6">
              <h2 className="font-heading-h2 text-content-primary">
                {ui("Search your workspace")}
              </h2>
            </header>
          ) : null}
          <form
            ref={searchFormRef}
            onSubmit={(event) => {
              event.preventDefault();
              submit();
            }}
            className={cn(
              "rounded-2xl border border-border-default bg-surface-raised transition-[border-color,box-shadow] duration-200 focus-within:border-border-strong motion-reduce:transition-none",
              request ? "shadow-xs" : "shadow-sm focus-within:shadow-md",
            )}
          >
            <div className="flex min-w-0 items-center gap-0.5 py-1.5 pr-1.5 pl-1">
              <Input
                ref={searchInputRef}
                size="lg"
                aria-label={ui("Search documents")}
                value={query}
                maxLength={1000}
                placeholder={ui("Search connected sources")}
                className="min-w-0 flex-1 border-transparent bg-transparent pr-2 pl-3 shadow-none hover:border-transparent focus-visible:border-transparent focus-visible:shadow-none focus-visible:ring-0"
                onChange={(event) => setQuery(event.target.value)}
              />
              {query ? (
                <button
                  type="button"
                  aria-label={ui("Clear search")}
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
                aria-label={isListening ? ui("Stop voice search") : ui("Search by voice")}
                aria-pressed={isListening}
                aria-describedby="voice-search-status"
                disabled={!voiceSearchSupported}
                title={
                  voiceSearchSupported
                    ? isListening
                      ? ui("Stop listening")
                      : ui("Search by voice")
                    : ui("Voice search is not supported in this browser")
                }
                onClick={toggleVoiceSearch}
              >
                <Mic
                  className={cn(isListening && "animate-pulse motion-reduce:animate-none")}
                  aria-hidden="true"
                />
              </IconButton>
              {isSearchUpdating ? (
                <IconButton
                  type="button"
                  size="lg"
                  aria-label={ui("Search is loading")}
                  title={ui("Searching documents")}
                  disabled
                >
                  <LoaderCircle
                    className="animate-spin motion-reduce:animate-none"
                    aria-hidden="true"
                  />
                </IconButton>
              ) : (
                <IconButton
                  type="submit"
                  size="lg"
                  aria-label={ui("Search")}
                  disabled={!query.trim()}
                >
                  <Search aria-hidden="true" />
                </IconButton>
              )}
            </div>

            {request ? (
              <div className="flex animate-in flex-wrap items-center gap-2 border-t border-border-subtle px-2 py-2 duration-200 fade-in slide-in-from-top-1 motion-reduce:animate-none">
                <span className="inline-flex h-8 items-center gap-2 px-2 font-secondary-action text-content-muted">
                  <SlidersHorizontal className="size-3.5" aria-hidden="true" />
                  {ui("Filters")}
                </span>
                <SearchFilterMenu
                  label={ui("Updated")}
                  value={timeRange}
                  options={TIME_RANGE_OPTIONS}
                  icon={<Clock3 className="size-3.5" />}
                  onChange={selectTimeRange}
                />
                <SearchFilterMenu
                  label={ui("File type")}
                  value={mediaType ?? "all"}
                  options={fileTypeOptions}
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
                    {ui("Clear filters")}
                  </Button>
                ) : null}
              </div>
            ) : null}
            <p
              id="voice-search-status"
              aria-live="polite"
              className={cn(
                "px-4 pb-2 font-secondary-body",
                voiceStatus === "idle" && "sr-only",
                isListening ? "text-content-secondary" : "text-status-danger-content",
              )}
            >
              {ui(voiceStatusMessage(voiceStatus, voiceSearchSupported))}
            </p>
          </form>

          {!request ? (
            <>
              {/* Preselects the filter for the first search; a query is still required. */}
              <div role="group" aria-label={ui("File type")} className="mt-4 flex flex-wrap gap-2">
                {FILE_TYPE_OPTIONS.filter((option) => option.value !== "all").map((option) => (
                  <button
                    key={option.value}
                    type="button"
                    aria-pressed={mediaType === option.value}
                    className="inline-flex h-8 cursor-pointer items-center gap-1.5 rounded-full border border-border-subtle bg-surface-base pr-3 pl-2 font-secondary-action text-content-secondary outline-none transition-colors duration-150 hover:bg-surface-subtle hover:text-content-primary focus-visible:ring-3 focus-visible:ring-focus-ring/30 aria-pressed:border-content-primary aria-pressed:bg-surface-sunken aria-pressed:text-content-primary motion-reduce:transition-none"
                    onClick={() => {
                      setMediaType(mediaType === option.value ? null : option.value);
                      searchInputRef.current?.focus();
                    }}
                  >
                    <DocumentSourceIcon mediaType={option.value} size="xs" />
                    {ui(option.label)}
                  </button>
                ))}
              </div>

              {recentSearches.length ? (
                <section aria-labelledby="recent-searches-heading" className="mt-8">
                  <div className="flex items-center justify-between gap-3 pl-2">
                    <h2
                      id="recent-searches-heading"
                      className="font-secondary-action text-content-muted"
                    >
                      {ui("Recent searches")}
                    </h2>
                    <Button
                      type="button"
                      size="sm"
                      prominence="internal"
                      onClick={() => {
                        clearRecentSearches(actorId);
                        setRecentSearches([]);
                        searchInputRef.current?.focus();
                      }}
                    >
                      {ui("Clear all")}
                    </Button>
                  </div>
                  <ul className="mt-1">
                    {recentSearches.map((item) => (
                      <li key={item}>
                        <button
                          type="button"
                          className="flex min-h-10 w-full cursor-pointer items-center gap-3 rounded-lg px-2 text-left font-main-ui-body text-content-secondary outline-none transition-colors duration-150 hover:bg-surface-subtle hover:text-content-primary focus-visible:ring-3 focus-visible:ring-focus-ring/30 motion-reduce:transition-none"
                          onClick={() => submit(item)}
                        >
                          <History
                            className="size-4 shrink-0 text-content-muted"
                            aria-hidden="true"
                          />
                          <span className="min-w-0 truncate">{item}</span>
                        </button>
                      </li>
                    ))}
                  </ul>
                </section>
              ) : null}
            </>
          ) : null}
        </div>

        <p role="status" aria-live="polite" className="sr-only">
          {ui(statusMessage)}
        </p>

        <div className="mt-6" aria-busy={isSearchUpdating}>
          {!request ? null : showLoadingScreen ? (
            <div className="flex animate-in justify-center py-12 duration-200 fade-in motion-reduce:animate-none">
              <BrandLoader label={ui("Searching documents…")} />
            </div>
          ) : result.isError ? (
            <Empty
              role="alert"
              className="min-h-72 animate-in duration-200 fade-in slide-in-from-bottom-1 motion-reduce:animate-none"
            >
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
          ) : !result.data?.results.length ? (
            <Empty className="min-h-72 animate-in duration-200 fade-in slide-in-from-bottom-1 motion-reduce:animate-none">
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
              {hasFilters ? (
                <Button prominence="secondary" onClick={clearFilters}>
                  {ui("Clear filters")}
                </Button>
              ) : null}
            </Empty>
          ) : (
            <div className="min-w-0 animate-in duration-200 fade-in slide-in-from-bottom-1 motion-reduce:animate-none">
              <section aria-labelledby="search-results-heading" className="min-w-0">
                <header className="flex flex-wrap items-baseline justify-between gap-3 border-b border-border-subtle pb-3">
                  <h2
                    id="search-results-heading"
                    className="font-main-ui-action text-content-primary"
                  >
                    {totalLabel === "1"
                      ? ui("{{count}} result for “{{query}}”", {
                          count: totalLabel,
                          query: request.query,
                        })
                      : ui("{{count}} results for “{{query}}”", {
                          count: totalLabel,
                          query: request.query,
                        })}
                  </h2>
                  {isSearchUpdating ? (
                    <span className="inline-flex items-center gap-1.5 font-secondary-action text-content-muted">
                      <LoaderCircle
                        className="size-3.5 animate-spin motion-reduce:animate-none"
                        aria-hidden="true"
                      />
                      {ui("Updating")}
                    </span>
                  ) : null}
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
                <TablePagination
                  label={ui("Search results pages")}
                  className="px-0"
                  page={currentPage}
                  totalPages={totalPages}
                  summary={ui("Showing {{first}}–{{last}} of {{total}}", {
                    first: currentPage * PAGE_SIZE + 1,
                    last: currentPage * PAGE_SIZE + result.data.results.length,
                    total: totalLabel,
                  })}
                  previousDisabled={currentPage <= 0}
                  nextDisabled={!result.data.hasMore || currentPage + 1 >= MAX_PAGES}
                  onPrevious={() => {
                    setSelected(null);
                    setRequest({ ...request, page: currentPage - 1 });
                  }}
                  onNext={() => {
                    setSelected(null);
                    setRequest({ ...request, page: currentPage + 1 });
                  }}
                />
              </section>
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

/** The fixed filters plus every media type on this page (and the selected one), so every result type is selectable. */
function withResultFileTypes(
  results: readonly SearchResult[],
  selected: string | null | undefined,
): SearchFilterOption[] {
  const known = new Set(FILE_TYPE_OPTIONS.map((option) => option.value));
  const extra = [...new Set([...results.map((item) => item.mediaType), selected])]
    .filter((value): value is string => !!value && !known.has(value))
    .map((value) => ({ value, label: friendlyMediaType(value) }));
  return [...FILE_TYPE_OPTIONS, ...extra];
}

function searchStatus(
  request: SearchRequest | null,
  result: {
    isError: boolean;
    data?: { results: unknown[] };
  },
  isSearching: boolean,
): AppCopy {
  if (!request) return "";
  if (isSearching) return "Searching documents.";
  if (result.isError) return "Search is temporarily unavailable.";
  const count = result.data?.results.length ?? 0;
  if (count === 0) return "No matching documents.";
  return appText(
    count === 1 ? "{{count}} result on this page." : "{{count}} results on this page.",
    { count },
  );
}
