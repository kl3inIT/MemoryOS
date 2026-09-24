import { useAppTranslation } from "@/i18n/use-app-translation";
import { appText, type AppCopy } from "@/i18n/app-text";
import { useQuery } from "@tanstack/react-query";
import {
  Clock3,
  FileStack,
  Files,
  History,
  LoaderCircle,
  Mic,
  Search,
  SearchX,
  SlidersHorizontal,
  TextSearch,
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
import { SearchSourceRail, type SearchSourceOption } from "./search-source-rail";
import { DocumentSourceIcon } from "./document-source-icon";
import type { DocumentSourceType } from "./document-source-presentation";
import { sourceProviders } from "@/features/sources/shared/source-provider-catalog";
import { SearchResultCard } from "./search-result-card";
import { friendlyMediaType } from "./search-presentation";
import {
  useApplicationSession,
  useGlobalCapability,
} from "@/features/identity/application-session-context";
import { loadDocumentSets } from "@/features/chat/chat-workspace-api";
import { captureWorkflowFailure } from "@/lib/sentry";
import { cn } from "@/lib/utils";
import { searchDocuments } from "@/lib/hey-api/sdk.gen";
import { matchingProvenance } from "./source-provenance";
import { useVoiceAvailability } from "@/features/voice/use-voice-availability";
import { useDictationInput, type DictationStatus } from "@/features/voice/use-dictation-input";
import type {
  Result as SearchResult,
  SearchRequest,
  Section,
  SourceFacets,
} from "@/lib/hey-api/types.gen";

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
  const [sourceType, setSourceType] = useState<DocumentSourceType | null>(null);
  const [timeRange, setTimeRange] = useState<SearchTimeRange>("all");
  const [documentSetId, setDocumentSetId] = useState<string | null>(null);
  const [request, setRequest] = useState<SearchRequest | null>(null);
  const [selected, setSelected] = useState<DocumentSelection | null>(null);
  const searchInputRef = useRef<HTMLInputElement | null>(null);
  const searchFormRef = useRef<HTMLFormElement | null>(null);
  const previousSearchTopRef = useRef<number | null>(null);
  const returnFocusRef = useRef<HTMLElement | null>(null);
  const reportedSearchError = useRef<unknown>(null);
  // Voice search uses the Tenant's speech-to-text provider through MemoryOS, never browser recognition.
  const voiceSearchAvailable = useVoiceAvailability().data?.sttAvailable === true;
  const documentSets = useQuery({
    queryKey: ["document-sets", actorId],
    queryFn: ({ signal }) => loadDocumentSets(signal),
  });
  const {
    status: voiceStatus,
    failure: voiceFailure,
    listening: isListening,
    toggle: toggleVoiceSearch,
  } = useDictationInput({
    text: query,
    onText: setQuery,
    onFinished: () => searchInputRef.current?.focus(),
  });
  const hasRequest = request !== null;
  const result = useQuery({
    queryKey: ["document-search", request],
    queryFn: async ({ signal }) => {
      const response = await searchDocuments({
        body: request!,
        signal,
      });
      return response.data;
    },
    enabled: request !== null,
    retry: false,
    staleTime: 30_000,
    refetchOnWindowFocus: false,
    // Another page or filter keeps the results on screen, dimmed; a different question starts from the loader.
    placeholderData: (previous, previousQuery) =>
      (previousQuery?.queryKey[1] as SearchRequest | null | undefined)?.query === request?.query
        ? previous
        : undefined,
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

  useEffect(() => {
    if (!request || !result.isError || result.error === reportedSearchError.current) return;
    reportedSearchError.current = result.error;
    captureWorkflowFailure(result.error, {
      workflow: "search",
      stage: "request",
      failureKind: "api-or-network",
    });
  }, [request, result.error, result.isError]);

  function submit(text = query) {
    if (!text.trim()) return;
    if (!request) {
      previousSearchTopRef.current = searchFormRef.current?.getBoundingClientRect().top ?? null;
    }
    setQuery(text);
    setSelected(null);
    setRecentSearches(rememberRecentSearch(actorId, text));
    const nextRequest: SearchRequest = {
      query: text.trim(),
      mediaTypes: mediaType ? [mediaType] : [],
      sourceTypes: sourceType ? [sourceType] : [],
      updatedSince: updatedSinceForTimeRange(timeRange),
      documentSetIds: documentSetId ? [documentSetId] : [],
      page: 0,
      pageSize: PAGE_SIZE,
    };
    if (JSON.stringify(nextRequest) === JSON.stringify(request)) void result.refetch();
    else setRequest(nextRequest);
  }

  function clearFilters() {
    setMediaType(null);
    setSourceType(null);
    setDocumentSetId(null);
    setTimeRange("all");
    setSelected(null);
    if (request) {
      setRequest({
        ...request,
        mediaTypes: [],
        sourceTypes: [],
        documentSetIds: [],
        updatedSince: undefined,
        page: 0,
      });
    }
  }

  function selectSourceType(value: string) {
    const nextSourceType = value === "all" ? null : (value as DocumentSourceType);
    setSourceType(nextSourceType);
    setSelected(null);
    if (request) {
      setRequest({ ...request, sourceTypes: nextSourceType ? [nextSourceType] : [], page: 0 });
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

  function selectDocumentSet(value: string) {
    const nextDocumentSetId = value === "all" ? null : value;
    setDocumentSetId(nextDocumentSetId);
    setSelected(null);
    if (request)
      setRequest({
        ...request,
        documentSetIds: nextDocumentSetId ? [nextDocumentSetId] : [],
        page: 0,
      });
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
      matches: item.sections.map((candidate) => ({
        matchingOrdinal: candidate.matchingOrdinal,
        from: Math.max(0, candidate.matchingOrdinal - 1),
        provenance: matchingProvenance(candidate.provenance, candidate.matchingOrdinal),
      })),
      activeMatchIndex,
    });
  }

  const isSearchUpdating = result.isFetching;
  const statusMessage = searchStatus(request, result, isSearchUpdating);
  const hasFilters = Boolean(mediaType || sourceType || documentSetId || timeRange !== "all");
  const sourceOptions = searchSourceOptions(result.data?.sourceFacets, sourceType);
  const documentSetOptions: SearchFilterOption[] = [
    { value: "all", label: ui("Tất cả bộ tài liệu") },
    ...(documentSets.data ?? []).map((set) => ({ value: set.id, label: set.name })),
  ];
  // Shown for every search so filters never move the search box or the results column.
  const showSourceFilter = request !== null;
  const fileTypeOptions = withResultFileTypes(result.data?.results ?? [], mediaType);
  const showLoadingScreen = result.isPending;
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
          // The rail adds its own width; the results column keeps the readable measure.
          request && showSourceFilter && "lg:max-w-[71rem]",
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
              {voiceSearchAvailable ? (
                <IconButton
                  type="button"
                  size="lg"
                  prominence="internal"
                  tone={isListening ? "danger" : "default"}
                  aria-label={isListening ? ui("Stop voice search") : ui("Search by voice")}
                  aria-pressed={isListening}
                  aria-describedby="voice-search-status"
                  pending={voiceStatus === "starting" || voiceStatus === "finishing"}
                  title={isListening ? ui("Stop listening") : ui("Search by voice")}
                  onClick={() => void toggleVoiceSearch()}
                >
                  <Mic
                    className={cn(isListening && "animate-pulse motion-reduce:animate-none")}
                    aria-hidden="true"
                  />
                </IconButton>
              ) : null}
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
                <SearchFilterMenu
                  label={ui("Bộ tài liệu")}
                  value={documentSetId ?? "all"}
                  options={documentSetOptions}
                  icon={<Files className="size-3.5" />}
                  onChange={selectDocumentSet}
                />
                {showSourceFilter ? (
                  // Large screens use the rail beside the results instead.
                  <SearchFilterMenu
                    label={ui("Source")}
                    value={sourceType ?? "all"}
                    options={sourceOptions}
                    icon={<Files className="size-3.5" />}
                    className="lg:hidden"
                    onChange={selectSourceType}
                  />
                ) : null}
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
            {voiceSearchAvailable ? (
              <p
                id="voice-search-status"
                aria-live="polite"
                className={cn(
                  "px-4 pb-2 font-secondary-body",
                  voiceStatus === "idle" && "sr-only",
                  voiceStatus === "failed"
                    ? "text-status-danger-content"
                    : "text-content-secondary",
                )}
              >
                {ui(voiceFailure ?? voiceStatusMessage(voiceStatus))}
              </p>
            ) : null}
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

        <div
          className={cn(
            "mt-6",
            request &&
              showSourceFilter &&
              "lg:grid lg:grid-cols-[13rem_minmax(0,1fr)] lg:items-start lg:gap-8",
          )}
        >
          {request && showSourceFilter ? (
            <SearchSourceRail
              options={sourceOptions}
              value={sourceType ?? "all"}
              busy={isSearchUpdating}
              onChange={selectSourceType}
              className="hidden lg:sticky lg:top-4 lg:block"
            />
          ) : null}
          <div className="min-w-0" aria-busy={isSearchUpdating}>
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
                <section
                  aria-labelledby="search-results-heading"
                  className={cn(
                    "min-w-0 transition-opacity",
                    result.isPlaceholderData && "opacity-60",
                  )}
                >
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
                    // A placeholder is still the previous page, so label the page actually shown.
                    page={result.data.page}
                    totalPages={totalPages}
                    summary={ui("Showing {{first}}–{{last}} of {{total}}", {
                      first: result.data.page * PAGE_SIZE + 1,
                      last: result.data.page * PAGE_SIZE + result.data.results.length,
                      total: totalLabel,
                    })}
                    previousDisabled={currentPage <= 0 || result.isPlaceholderData}
                    nextDisabled={
                      !result.data.hasMore ||
                      currentPage + 1 >= MAX_PAGES ||
                      result.isPlaceholderData
                    }
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

function voiceStatusMessage(status: DictationStatus) {
  if (status === "starting") return "Starting the microphone…";
  if (status === "listening") return "Listening… Speak now, then review your query.";
  if (status === "finishing") return "Finishing the transcript…";
  if (status === "failed") return "Voice search stopped unexpectedly. Please try again.";
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

/** "All sources" and every catalog connector in catalog order; a connector without results cannot be chosen. */
function searchSourceOptions(
  facets: SourceFacets | undefined,
  selected: DocumentSourceType | null,
): SearchSourceOption[] {
  const counts = new Map(facets?.types.map((facet) => [facet.type, facet.count]));
  return [
    {
      value: "all",
      label: "All sources",
      count: facets?.total ?? 0,
      icon: <TextSearch className="size-4 text-content-muted" />,
    },
    ...sourceProviders.map((provider) => {
      const Icon = provider.icon;
      const count = counts.get(provider.type) ?? 0;
      return {
        value: provider.type,
        // Result cards name uploads the same way.
        label: provider.type === "FILE" ? "Tệp tải lên" : provider.name,
        count,
        icon: <Icon className="size-4 text-content-muted" />,
        disabled: count === 0 && provider.type !== selected,
      };
    }),
  ];
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
