import { useEffect, useMemo, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import { documentSetOf } from "@/features/document-sets/document-sets-api";
import type { DocumentSourceType } from "@/features/documents/document-source-presentation";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { listDocumentSetsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import { searchDocuments } from "@/lib/hey-api/sdk.gen";
import type { SearchRequest } from "@/lib/hey-api/types.gen";
import { captureWorkflowFailure } from "@/lib/sentry";
import { clearRecentSearches, readRecentSearches, rememberRecentSearch } from "./recent-searches";
import { updatedSinceForTimeRange, type SearchTimeRange } from "./search-options";
import { MAX_SEARCH_PAGES, type SearchPageSearch } from "./search-params";

export const PAGE_SIZE = 10;
/** The most Document Sets the filter offers, which is what one page of the listing may hold. */
const documentSetLimit = 100;
const searchRoute = getRouteApi("/_authenticated/search");

/**
 * The Search page's state: the submitted question, its filters and the result page live in the address, so a
 * reload or a shared link shows the same results; the box holds what is typed. The results of a request are
 * one query, and the Document Sets the member may filter by another.
 */
export function useDocumentSearch() {
  const { actorId } = useApplicationSession();
  const [recentSearches, setRecentSearches] = useState(() => readRecentSearches(actorId));
  const search = searchRoute.useSearch();
  const navigate = searchRoute.useNavigate();
  const [query, setQuery] = useState(search.q ?? "");
  const [shownQuery, setShownQuery] = useState(search.q);
  if (shownQuery !== search.q) {
    // Back and forward change the submitted question; the box follows it.
    setShownQuery(search.q);
    if ((query.trim() || undefined) !== search.q) setQuery(search.q ?? "");
  }
  const request = useMemo<SearchRequest | null>(
    () =>
      search.q
        ? {
            query: search.q,
            mediaTypes: search.type ? [search.type] : [],
            sourceTypes: search.source ? [search.source] : [],
            updatedSince: updatedSinceForTimeRange(search.time ?? "all"),
            documentSetIds: search.set ? [search.set] : [],
            page: search.page ?? 0,
            pageSize: PAGE_SIZE,
          }
        : null,
    [search.q, search.type, search.source, search.time, search.set, search.page],
  );
  const documentSets = useQuery({
    ...listDocumentSetsOptions({ query: { offset: 0, limit: documentSetLimit } }),
    select: (views) => views.map(documentSetOf),
  });
  // The search is a POST read, for which no generated query exists; its key is the request it sends.
  const result = useQuery({
    queryKey: ["document-search", request],
    queryFn: async ({ signal }) => (await searchDocuments({ body: request!, signal })).data,
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
  const reportedError = useRef<unknown>(null);
  useEffect(() => {
    // A failed search is reported to error monitoring once, however often the page renders it.
    if (!request || !result.isError || result.error === reportedError.current) return;
    reportedError.current = result.error;
    captureWorkflowFailure(result.error, {
      workflow: "search",
      stage: "request",
      failureKind: "api-or-network",
    });
  }, [request, result.error, result.isError]);

  /** A filter applies to the question on screen from its first page, replacing the entry it refines. */
  const show = (next: (current: SearchPageSearch) => SearchPageSearch, replace: boolean) =>
    void navigate({ search: next, replace, resetScroll: false });
  const setFilter = (filter: Partial<SearchPageSearch>) =>
    show((current) => ({ ...current, ...filter, page: undefined }), true);

  const mediaType = search.type ?? null;
  const sourceType: DocumentSourceType | null = search.source ?? null;
  const timeRange: SearchTimeRange = search.time ?? "all";
  const documentSetId = search.set ?? null;
  return {
    query,
    setQuery,
    request,
    result,
    documentSets: documentSets.data ?? [],
    mediaType,
    sourceType,
    timeRange,
    documentSetId,
    hasFilters: Boolean(mediaType || sourceType || documentSetId || timeRange !== "all"),
    currentPage: request?.page ?? 0,
    setFilter,
    clearFilters: () =>
      setFilter({ type: undefined, source: undefined, time: undefined, set: undefined }),
    showPage: (page: number) => show((current) => ({ ...current, page: page || undefined }), false),
    /** Asks `text`; asking the question on screen again reads it again instead of adding a history entry. */
    submit: (text: string) => {
      setQuery(text);
      setRecentSearches(rememberRecentSearch(actorId, text));
      if (text.trim() === search.q && !search.page) void result.refetch();
      else show((current) => ({ ...current, q: text.trim(), page: undefined }), false);
    },
    recentSearches,
    clearRecentSearches: () => {
      clearRecentSearches(actorId);
      setRecentSearches([]);
    },
    maxPages: MAX_SEARCH_PAGES,
  };
}

export type DocumentSearch = ReturnType<typeof useDocumentSearch>;
