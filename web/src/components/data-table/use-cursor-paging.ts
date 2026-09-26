import type { PaginationState, Updater } from "@tanstack/react-table";
import { useState } from "react";

/**
 * Forward-only cursor pages walked back through the cursors already seen. The page number is how
 * many pages lie before the current one.
 */
export function useCursorPaging() {
  const [cursor, setCursor] = useState<string>();
  const [previous, setPrevious] = useState<Array<string | undefined>>([]);

  function reset() {
    setCursor(undefined);
    setPrevious([]);
  }

  return {
    cursor,
    page: previous.length,
    hasPrevious: previous.length > 0,
    goNext(nextCursor: string | null | undefined) {
      setPrevious((pages) => [...pages, cursor]);
      setCursor(nextCursor ?? undefined);
    },
    goPrevious() {
      setCursor(previous.at(-1));
      setPrevious((pages) => pages.slice(0, -1));
    },
    reset,
    /**
     * Returns to the first page once the current one lies past the last, as when items were
     * removed. Call it while rendering, with the total the latest page reported.
     */
    clamp(totalPages: number | undefined) {
      if (totalPages !== undefined && previous.length >= Math.max(totalPages, 1)) reset();
    },
  };
}

export type CursorPaging = ReturnType<typeof useCursorPaging>;

/**
 * The server-side paging options of a TanStack table over cursor pages: the page index is the
 * number of pages walked, and moving one page forward or back follows the cursors, so a
 * `TablePagination` footer drives the table with `table.nextPage()`/`table.previousPage()`.
 */
export function cursorTablePaging(
  paging: CursorPaging,
  pageSize: number,
  nextCursor: string | null | undefined,
  totalPages: number | undefined,
) {
  const pagination: PaginationState = { pageIndex: paging.page, pageSize };
  return {
    manualPagination: true,
    pageCount: totalPages ?? paging.page + (nextCursor ? 2 : 1),
    state: { pagination },
    onPaginationChange: (updater: Updater<PaginationState>) => {
      const next = typeof updater === "function" ? updater(pagination) : updater;
      if (next.pageIndex > pagination.pageIndex) paging.goNext(nextCursor);
      else if (next.pageIndex < pagination.pageIndex) paging.goPrevious();
    },
  } as const;
}
