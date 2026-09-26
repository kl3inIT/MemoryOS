import { useEffect, useMemo, useState } from "react";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useSearch } from "@tanstack/react-router";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import {
  getChatLibraryTrashWindowOptions,
  getChatLibraryUsageOptions,
  searchChatLibraryContentOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { libraryOptions, type LibraryFilter } from "./library";
import { librarySearchDefaults, type LibrarySearch } from "./library-search";

/** A deleted file stays restorable for days, so the window is read again only after minutes. */
const trashWindowStaleTime = 5 * 60_000;

/**
 * What the library shows and the reads behind it. The view, filters, order and page live in the address, so a
 * reload, Back or a link opens the same list; outside the library route (a test mounting the page alone) the
 * defaults apply. The search box holds what is typed and the address follows it once typing settles.
 */
export function useLibraryView() {
  const cache = useQueryClient();
  const shown =
    useSearch({ from: "/_authenticated/library", shouldThrow: false }) ?? librarySearchDefaults;
  const navigate = useNavigate({ from: "/library" });
  const { mode, category: categories, source: sources, sort, view, size } = shown;
  const [search, setSearch] = useState(shown.q);
  const [shownSearch, setShownSearch] = useState(shown.q);
  if (shownSearch !== shown.q) {
    // Back or a cleared filter changes the address; the box follows it.
    setShownSearch(shown.q);
    if (search !== shown.q) setSearch(shown.q);
  }
  const offset = shown.page * size;
  const query = useDebouncedValue(search.trim(), 250);
  const [selected, setSelected] = useState<string[]>([]);

  /** A selection belongs to the page it was made on, so leaving that page drops it. */
  const show = (next: Partial<LibrarySearch>, replace = false) => {
    setSelected([]);
    void navigate({ search: (current) => ({ ...current, ...next }), replace, resetScroll: false });
  };
  /** Every filter change starts the list again: page 3 of the previous filter means nothing. */
  const filterBy = (next: Partial<LibrarySearch>, replace = false) =>
    show({ ...next, page: 0 }, replace);

  useEffect(() => {
    // Only the settled search is written to the address, replacing the entry: an address changed by Back sets
    // the box instead, and the other filters navigate themselves.
    if (query !== shown.q) filterBy({ q: query }, true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query]);

  const filter = useMemo<LibraryFilter>(
    () => ({
      query: mode === "content" ? "" : query,
      sources,
      categories,
      // The trash reads by when a file was deleted, not by when it was made.
      sort: view === "trash" ? "DELETED" : sort,
      favorite: view === "favorite" || undefined,
      status: view === "pending" ? "PENDING" : view === "trash" ? "TRASH" : undefined,
    }),
    [mode, query, sources, categories, sort, view],
  );
  const page = useQuery({
    ...libraryOptions(filter, offset, size),
    // Paging keeps the page being read on screen until the next one arrives, instead of emptying the list.
    placeholderData: keepPreviousData,
    // An upload being processed becomes usable on its own; the view follows without a manual refresh.
    refetchInterval: (current) =>
      current.state.data?.items.some((file) => file.status === "PROCESSING") ? 3000 : false,
  });
  const hasMore = page.data?.hasMore === true && !page.isPlaceholderData;
  useEffect(() => {
    // The next page is fetched while this one is read, so the next-page button shows it without a wait.
    if (hasMore) void cache.prefetchQuery({ ...libraryOptions(filter, offset + size, size) });
  }, [cache, filter, offset, size, hasMore]);
  const usage = useQuery(getChatLibraryUsageOptions());
  const trashWindow = useQuery({
    ...getChatLibraryTrashWindowOptions(),
    select: (window) => window.days,
    staleTime: trashWindowStaleTime,
  });
  const matches = useQuery({
    ...searchChatLibraryContentOptions({ query: { query } }),
    enabled: mode === "content" && query.length > 0,
  });

  return {
    search,
    setSearch,
    query,
    mode,
    sources,
    categories,
    sort,
    view,
    size,
    offset,
    selected,
    setSelected,
    filtered: query.length > 0 || sources.length > 0 || categories.length > 0,
    files: page.data?.items ?? [],
    page,
    usage,
    trashWindow,
    matches,
    filterBy,
    showPage: (nextOffset: number) => show({ page: Math.floor(nextOffset / size) }),
    /** After a change to the files the list restarts in place, without an entry to return to. */
    showFirstPage: () => show({ page: 0 }, true),
    clearFilters: () => filterBy({ source: [], category: [], q: "" }),
  };
}

export type LibraryViewState = ReturnType<typeof useLibraryView>;
