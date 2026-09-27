import type { AppCopy } from "@/i18n/app-text";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { useCapabilityAuthority } from "@/features/identity/application-session-context";
import {
  listGroupSourcesOptions,
  listSourceGroupsOptions,
  listSourceGroupsQueryKey,
  listSourcesOptions,
  removeGroupSourceMutation,
  updateSourceGroupsMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { GroupSummary } from "@/lib/hey-api/types.gen";
import { can } from "@/lib/resource-permissions";
import { groupMutationError } from "./group-errors";

const associationAccessChanged = "Source association access has changed";

/**
 * The Group's Source associations as the server holds them, with the person's unsaved additions
 * and removals on top. Only the page-level Save Changes commits them; Cancel drops them.
 */
export function useGroupSourcesDraft(group: GroupSummary) {
  const queryClient = useQueryClient();
  const sourceAuthority = useCapabilityAuthority("SOURCES_READ");
  const ordinaryGroup = group.systemKey === null;
  const canManage = ordinaryGroup && can(group, "manageSources");
  const canOpenSources = sourceAuthority === "global" || canManage;
  const associated = useQuery({
    ...listGroupSourcesOptions({ path: { groupId: group.id } }),
    enabled: ordinaryGroup && (canOpenSources || canManage),
    retry: false,
  });
  const allSources = useQuery({
    ...listSourcesOptions(),
    enabled: canManage,
    retry: false,
  });
  const [added, setAdded] = useState<ReadonlySet<string>>(() => new Set());
  const [removed, setRemoved] = useState<ReadonlySet<string>>(() => new Set());
  const [error, setError] = useState<AppCopy | null>(null);
  const [generation, setGeneration] = useState(0);
  const savedIds = useMemo(
    () => new Set((associated.data?.items ?? []).map((source) => source.id)),
    [associated.data?.items],
  );
  const removableIds = useMemo(
    () => new Set(associated.data?.removableSourceIds ?? []),
    [associated.data?.removableSourceIds],
  );
  // Without the authority to change them, the saved associations are all there is.
  const additions = canManage ? [...added].filter((id) => !savedIds.has(id)) : [];
  const removals = canManage ? [...removed].filter((id) => savedIds.has(id)) : [];
  const selectedIds = new Set([...savedIds, ...additions]);
  for (const id of removals) selectedIds.delete(id);
  const dirty = additions.length > 0 || removals.length > 0;

  const [previousCanManage, setPreviousCanManage] = useState(canManage);
  if (previousCanManage !== canManage) {
    setPreviousCanManage(canManage);
    if (!canManage) {
      setAdded(new Set());
      setRemoved(new Set());
      setError(null);
      setGeneration((current) => current + 1);
    }
  }

  const updateSourceGroups = useMutation(updateSourceGroupsMutation());
  const removeGroupSource = useMutation(removeGroupSourceMutation());
  const saveAssociations = useMutation({
    mutationFn: async () => {
      if (!canManage) throw new Error(associationAccessChanged);
      if (
        additions.some(
          (sourceId) =>
            !allSources.data?.some((source) => source.id === sourceId && can(source, "edit")),
        ) ||
        removals.some((sourceId) => !removableIds.has(sourceId))
      )
        throw new Error(associationAccessChanged);
      const additionReplacements = await Promise.all(
        additions.map(async (sourceId) => {
          // The Source's current Groups, read fresh: the association replaces them all.
          const data = await queryClient.fetchQuery({
            ...listSourceGroupsOptions({ path: { sourceId } }),
            staleTime: 0,
          });
          const groupIds = new Set(
            data.items.filter((item) => item.systemKey === null).map((item) => item.id),
          );
          groupIds.add(group.id);
          return { sourceId, groupIds: [...groupIds] };
        }),
      );
      await Promise.all([
        ...additionReplacements.map((replacement) =>
          updateSourceGroups.mutateAsync({
            path: { sourceId: replacement.sourceId },
            body: { groupIds: replacement.groupIds },
          }),
        ),
        ...removals.map((sourceId) =>
          removeGroupSource.mutateAsync({
            path: { groupId: group.id, sourceId },
          }),
        ),
      ]);
    },
    // Each changed Source's own Group list is stale now, saved or not.
    onSettled: () =>
      Promise.all(
        [...additions, ...removals].map((sourceId) =>
          queryClient.invalidateQueries({
            queryKey: listSourceGroupsQueryKey({ path: { sourceId } }),
          }),
        ),
      ),
  });

  function add(sourceId: string) {
    setAdded((current) => new Set(current).add(sourceId));
    setRemoved((current) => withoutId(current, sourceId));
  }

  function remove(sourceId: string) {
    setRemoved((current) => new Set(current).add(sourceId));
    setAdded((current) => withoutId(current, sourceId));
  }

  function reset() {
    setAdded(new Set());
    setRemoved(new Set());
    setError(null);
    setGeneration((current) => current + 1);
  }

  /** Commits the draft; false keeps it, with the reason shown in the section. */
  async function save() {
    if (!canManage || !dirty || saveAssociations.isPending) return true;
    setError(null);
    try {
      await saveAssociations.mutateAsync();
      // Read the saved associations before dropping the draft, so the section never shows the old ones.
      await associated.refetch();
      setAdded(new Set());
      setRemoved(new Set());
      return true;
    } catch (cause) {
      setError(
        cause instanceof Error && cause.message === associationAccessChanged
          ? "You can no longer change one of these Sources for this group. Refresh and try again."
          : groupMutationError(cause, "sources"),
      );
      return false;
    }
  }

  return {
    ordinaryGroup,
    canManage,
    canOpenSources,
    associated,
    allSources,
    savedIds,
    removableIds,
    selectedIds,
    dirty,
    pending: saveAssociations.isPending,
    error,
    /** Changes when the draft is discarded, so the section can close its own search. */
    generation,
    add,
    remove,
    reset,
    save,
  };
}

export type GroupSourcesDraft = ReturnType<typeof useGroupSourcesDraft>;

function withoutId(ids: ReadonlySet<string>, id: string) {
  if (!ids.has(id)) return ids;
  const next = new Set(ids);
  next.delete(id);
  return next;
}
