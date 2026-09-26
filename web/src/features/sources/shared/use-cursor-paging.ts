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
