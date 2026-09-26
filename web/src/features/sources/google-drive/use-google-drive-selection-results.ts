import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { getGoogleDriveSelectionQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import { getGoogleDriveSelection } from "@/lib/hey-api/sdk.gen";
import type { GetGoogleDriveConfigurationResponse } from "@/lib/hey-api/types.gen";
import type { SelectionKind } from "./google-drive-selection-parts";
import {
  firstSelectionPage,
  nextSelectionPage,
  previousSelectionPage,
  type SelectionPaging,
} from "./google-drive-selection-paging";

/**
 * Search and type filters over Selected content: unique result pages instead of the tree, pinned
 * to the selection, discovery and credential revisions they were read at.
 */
export function useGoogleDriveSelectionResults(
  sourceId: string,
  actorId: string,
  configuration: GetGoogleDriveConfigurationResponse,
) {
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [kind, setKind] = useState<SelectionKind>("");
  const [searchOpen, setSearchOpen] = useState(false);
  const authority = `${sourceId}:${actorId}:${configuration.revision}:${configuration.discoveryRevision}:${configuration.credentialRevision}`;
  const filtered = Boolean(search || kind);
  const [paging, setPaging] = useState<{ authority: string } & SelectionPaging>({
    authority,
    ...firstSelectionPage,
  });
  const resultsPage = paging.authority === authority ? paging : firstSelectionPage;
  const pageRequest = {
    path: { sourceId },
    query: {
      search: search || undefined,
      kind: kind || undefined,
      size: 25,
      cursor: resultsPage.cursor,
    },
  };
  const selection = useQuery({
    queryKey: [...getGoogleDriveSelectionQueryKey(pageRequest), authority],
    queryFn: async ({ signal }) => {
      const { data } = await getGoogleDriveSelection({
        ...pageRequest,
        signal,
      });
      return data;
    },
    // The shown page stays while the next one loads, so the pager keeps its place and focus.
    placeholderData: keepPreviousData,
    retry: false,
    enabled: filtered,
  });
  const page = selection.data;
  const pageMatches =
    page?.revision === configuration.revision &&
    page.discoveryRevision === configuration.discoveryRevision &&
    page.credentialRevision === configuration.credentialRevision;
  const rows = useMemo(() => (pageMatches ? page.items : []), [pageMatches, page]);

  function firstPage() {
    setPaging({ authority, ...firstSelectionPage });
  }

  return {
    selection,
    filtered,
    kind,
    searchInput,
    searchVisible: searchOpen || Boolean(search),
    rows,
    pageMatches,
    nextCursor: page?.nextCursor,
    // A page kept while another authority's page loads is not a changed page.
    pageStale: Boolean(page && !pageMatches && !selection.isPlaceholderData),
    paging: resultsPage,
    firstPage,
    setSearchInput,
    submitSearch() {
      setSearch(searchInput.trim());
      firstPage();
    },
    toggleSearch() {
      if (!searchOpen && !search) {
        setSearchOpen(true);
        return;
      }
      setSearchOpen(false);
      setSearchInput("");
      if (search) {
        setSearch("");
        firstPage();
      }
    },
    changeKind(next: SelectionKind) {
      setKind(next);
      firstPage();
    },
    clearFilters() {
      setSearchInput("");
      setSearch("");
      setKind("");
      firstPage();
    },
    previousPage() {
      setPaging({ authority, ...previousSelectionPage(resultsPage) });
    },
    nextPage() {
      if (page?.nextCursor)
        setPaging({ authority, ...nextSelectionPage(resultsPage, page.nextCursor, rows.length) });
    },
  };
}

export type GoogleDriveSelectionResults = ReturnType<typeof useGoogleDriveSelectionResults>;
