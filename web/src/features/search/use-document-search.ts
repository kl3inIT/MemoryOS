import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import { loadDocumentSets } from "@/features/document-sets/document-sets-api";
import type { DocumentSourceType } from "@/features/documents/document-source-presentation";
import { useApplicationSession } from "@/features/identity/application-session-context";
import {
  listDocumentSetsQueryKey,
  searchDocumentsOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SearchRequest } from "@/lib/hey-api/types.gen";
import { clearRecentSearches, readRecentSearches, rememberRecentSearch } from "./recent-searches";
import { updatedSinceForTimeRange, type SearchTimeRange } from "./search-options";
import { MAX_SEARCH_PAGES, type SearchPageSearch } from "./search-params";

export const PAGE_SIZE = 10;
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
  // The filter offers every set the member may use. The listing has no name search, so the menu is the complete
  // list, read page by page under the key Agents reads it with; a change to a set refreshes it by prefix.
  const documentSets = useQuery({
    queryKey: [...listDocumentSetsQueryKey(), "all"] as const,
    queryFn: ({ signal }) => loadDocumentSets(signal),
  });
  // The search is a read sent as a POST; its generated key carries the request it sends.
  const result = useQuery({
    ...searchDocumentsOptions({ body: request ?? {} }),
    enabled: request !== null,
    retry: false,
    staleTime: 30_000,
    refetchOnWindowFocus: false,
    // Another page or filter keeps the results on screen, dimmed; a different question starts from the loader.
    placeholderData: (previous, previousQuery) =>
      previousQuery?.queryKey[0].body?.query === request?.query ? previous : undefined,
  });

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
      else
        show((current) => ({ ...current, q: text.trim(), page: undefined, doc: undefined }), false);
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
