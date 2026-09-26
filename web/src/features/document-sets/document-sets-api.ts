import type { QueryClient } from "@tanstack/react-query";
import { allPages } from "@/lib/all-pages";
import {
  getDocumentSetQueryKey,
  listDocumentSetsQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { listDocumentSets } from "@/lib/hey-api/sdk.gen";
import type { View } from "@/lib/hey-api/types.gen";

/**
 * A Document Set as the API sends it. The published contract marks every field of `View` optional
 * (and its int64 `revision` makes the generated zod schema coerce to bigint), although the API always
 * sends them; the view is narrowed once here instead of at every use.
 */
export function documentSetOf({
  id,
  revision,
  name = "",
  description = "",
  isPublic = false,
  sourceIds = [],
  sources = [],
  hiddenSources = 0,
  userShares = [],
  groupShares = [],
  permissions = {},
}: View) {
  if (id === undefined || revision === undefined) {
    throw new TypeError("A Document Set view carries its id and revision.");
  }
  return {
    id,
    revision,
    name,
    description,
    isPublic,
    sourceIds,
    sources: sources.flatMap((source) =>
      source.id ? [{ id: source.id, name: source.name ?? "" }] : [],
    ),
    /** Sources in the set that the viewer may not select, shown only as a count. */
    hiddenSources,
    userShares: userShares.flatMap((person) =>
      person.actorId ? [{ actorId: person.actorId, name: person.name, email: person.email }] : [],
    ),
    groupShares: groupShares.flatMap((group) =>
      group.id ? [{ id: group.id, name: group.name ?? "" }] : [],
    ),
    permissions: {
      edit: permissions.edit ?? false,
      share: permissions.share ?? false,
      delete: permissions.delete ?? false,
      manage: permissions.manage ?? false,
    },
  };
}
export type DocumentSet = ReturnType<typeof documentSetOf>;

/** Query key of the complete set list that Agents and Search read through {@link loadDocumentSets}. */
const documentSetsKey = ["document-sets"] as const;

/** Every Document Set the actor can use, for pickers that need the whole list. */
export function loadDocumentSets(signal: AbortSignal) {
  return allPages(async (offset) =>
    (await listDocumentSets({ query: { offset, limit: 100 }, signal })).data.map(documentSetOf),
  );
}

/** Refreshes every read of Document Sets after a change: the paged list, the changed set and the complete list. */
export function invalidateDocumentSets(cache: QueryClient, documentSetId?: string) {
  return Promise.all([
    cache.invalidateQueries({ queryKey: listDocumentSetsQueryKey() }),
    documentSetId === undefined
      ? undefined
      : cache.invalidateQueries({ queryKey: getDocumentSetQueryKey({ path: { documentSetId } }) }),
    cache.invalidateQueries({ queryKey: documentSetsKey }),
  ]);
}
