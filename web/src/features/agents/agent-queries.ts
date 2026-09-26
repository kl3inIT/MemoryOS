import { queryOptions } from "@tanstack/react-query";
import { loadDocumentSets } from "@/features/document-sets/document-sets-api";
import { allPages } from "@/lib/all-pages";
import {
  listChatPersonasForAdministrationQueryKey,
  listDocumentSetsQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { listChatPersonasForAdministration } from "@/lib/hey-api/sdk.gen";

/**
 * Every Document Set the actor can give an agent. The key extends the generated list key, so
 * `invalidateDocumentSets` refreshes it with the paged reads.
 */
export function agentDocumentSetsOptions() {
  return queryOptions({
    queryKey: [...listDocumentSetsQueryKey(), "all"] as const,
    queryFn: ({ signal }) => loadDocumentSets(signal),
  });
}

/**
 * Every agent of the Tenant for administration, in display order. The cache keeps the views as the API sends
 * them, so a reorder can show the new order at once; readers narrow them with `personaOf`.
 */
export function administrationAgentsOptions(includeDeleted: boolean) {
  return queryOptions({
    queryKey: [
      ...listChatPersonasForAdministrationQueryKey({ query: { includeDeleted } }),
      "all",
    ] as const,
    queryFn: ({ signal }) =>
      allPages(
        async (offset) =>
          (
            await listChatPersonasForAdministration({
              query: { includeDeleted, offset, limit: 100 },
              signal,
            })
          ).data,
      ),
  });
}
