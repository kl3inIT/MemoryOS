import type { InvalidateOptions, QueryClient } from "@tanstack/react-query";
import {
  listSourcePageQueryKey,
  listSourcesQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";

/** Rereads every list of Sources: the whole list and the pages of the administration page. */
export function invalidateSourceLists(queryClient: QueryClient, options?: InvalidateOptions) {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: listSourcesQueryKey() }, options),
    queryClient.invalidateQueries({ queryKey: listSourcePageQueryKey() }, options),
  ]);
}
