import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { listSourceItemsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SourceSummary } from "@/lib/hey-api/types.gen";
import { useCursorPaging } from "@/components/data-table/use-cursor-paging";

/** The current files of a Source, a page at a time, polled while any of them is still changing. */
export function useSourceFiles(sourceId: string, source: SourceSummary | undefined) {
  const [size, setSize] = useState(10);
  const paging = useCursorPaging();
  const items = useQuery({
    ...listSourceItemsOptions({
      path: { sourceId },
      query: { size, cursor: paging.cursor },
    }),
    enabled: Boolean(source),
    retry: false,
    staleTime: 0,
    placeholderData: keepPreviousData,
    refetchInterval: (query) =>
      source?.pendingWork ||
      query.state.data?.items.some(
        (item) =>
          item.status === "PENDING" ||
          item.status === "DELETING" ||
          item.searchStatus === "INDEXING",
      )
        ? 1_500
        : false,
  });
  const totalPages = items.data ? Math.ceil(items.data.totalItems / size) : undefined;
  paging.clamp(totalPages);

  return {
    items,
    paging,
    size,
    totalPages,
    changeSize(next: number) {
      setSize(next);
      paging.reset();
    },
  };
}

export type SourceFiles = ReturnType<typeof useSourceFiles>;
