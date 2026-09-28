import type { AppCopy } from "@/i18n/app-text";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useApplicationSession } from "@/features/identity/application-session-context";
import {
  addGroupMembersMutation,
  assignGroupManagerMutation,
  listGroupMembersQueryKey,
  removeGroupManagerMutation,
  removeGroupMemberMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { GroupMember, GroupSummary } from "@/lib/hey-api/types.gen";
import { can } from "@/lib/resource-permissions";
import { groupMutationError } from "./group-errors";

type ManagerChange = { baseline: boolean; target: boolean };

/**
 * The person's unsaved membership changes over the Group's saved members: additions, removals
 * and manager changes. Only the page-level Save Changes commits them; Cancel drops them.
 */
export function useGroupMembersDraft(group: GroupSummary) {
  const queryClient = useQueryClient();
  const currentActorId = useApplicationSession().actorId;
  const canManageMembers = can(group, "manageMembers");
  const canManageManagers = can(group, "manage");
  const [added, setAdded] = useState<ReadonlyMap<string, GroupMember>>(() => new Map());
  const [removed, setRemoved] = useState<ReadonlySet<string>>(() => new Set());
  const [managerChanges, setManagerChanges] = useState<ReadonlyMap<string, ManagerChange>>(
    () => new Map(),
  );
  const [error, setError] = useState<AppCopy | null>(null);
  const [generation, setGeneration] = useState(0);
  const addMembers = useMutation(addGroupMembersMutation());
  const removeMember = useMutation(removeGroupMemberMutation());
  const assignManager = useMutation(assignGroupManagerMutation());
  const removeManager = useMutation(removeGroupManagerMutation());
  const pending =
    addMembers.isPending ||
    removeMember.isPending ||
    assignManager.isPending ||
    removeManager.isPending;
  const dirty = added.size > 0 || removed.size > 0 || managerChanges.size > 0;

  const [previousAuthority, setPreviousAuthority] = useState(() => ({
    canManageMembers,
    canManageManagers,
  }));
  if (
    previousAuthority.canManageMembers !== canManageMembers ||
    previousAuthority.canManageManagers !== canManageManagers
  ) {
    setPreviousAuthority({ canManageMembers, canManageManagers });
    if (
      (previousAuthority.canManageMembers && !canManageMembers) ||
      (previousAuthority.canManageManagers && !canManageManagers)
    ) {
      setError(null);
    }
  }

  /** A saved member as the draft shows it, or null once the draft removes them. */
  function staged(member: GroupMember): GroupMember | null {
    if (removed.has(member.actorId)) return null;
    return { ...member, isManager: managerChanges.get(member.actorId)?.target ?? member.isManager };
  }

  function add(members: readonly GroupMember[]) {
    if (!canManageMembers || members.length === 0 || pending) return;
    setAdded((current) => {
      const next = new Map(current);
      for (const member of members) next.set(member.actorId, member);
      return next;
    });
  }

  function remove(member: GroupMember) {
    if (
      !canManageMembers ||
      member.protectedOwner ||
      (member.isManager && (member.actorId === currentActorId || !canManageManagers))
    )
      return;
    setError(null);
    if (added.has(member.actorId)) {
      setAdded((current) => {
        const next = new Map(current);
        next.delete(member.actorId);
        return next;
      });
    } else {
      setRemoved((current) => new Set(current).add(member.actorId));
    }
    setManagerChanges((current) => {
      const next = new Map(current);
      next.delete(member.actorId);
      return next;
    });
  }

  function toggleManager(member: GroupMember) {
    if (
      !canManageManagers ||
      member.protectedOwner ||
      (member.isManager && member.actorId === currentActorId)
    )
      return;
    setError(null);
    setManagerChanges((current) => {
      const next = new Map(current);
      const currentChange = current.get(member.actorId);
      const baseline =
        currentChange?.baseline ?? added.get(member.actorId)?.isManager ?? member.isManager;
      const target = !member.isManager;
      if (target === baseline) next.delete(member.actorId);
      else next.set(member.actorId, { baseline, target });
      return next;
    });
  }

  function reset() {
    setAdded(new Map());
    setRemoved(new Set());
    setManagerChanges(new Map());
    setError(null);
    setGeneration((current) => current + 1);
  }

  /** Commits the draft; false keeps it, with the reason shown in the section. */
  async function save() {
    if (!dirty) return true;
    setError(null);
    try {
      if (added.size > 0) {
        await addMembers.mutateAsync({
          path: { groupId: group.id },
          body: { actorIds: [...added.keys()] },
        });
      }
      for (const [actorId, change] of managerChanges) {
        if (removed.has(actorId)) continue;
        const mutation = change.target ? assignManager : removeManager;
        await mutation.mutateAsync({
          path: { groupId: group.id, actorId },
        });
      }
      for (const actorId of removed) {
        await removeMember.mutateAsync({
          path: { groupId: group.id, actorId },
        });
      }
      // Read the saved members before dropping the draft, so the list never shows the old ones.
      await queryClient.invalidateQueries({
        queryKey: listGroupMembersQueryKey({ path: { groupId: group.id } }),
      });
      reset();
      return true;
    } catch (cause) {
      setError(groupMutationError(cause, managerChanges.size > 0 ? "manager" : "members"));
      return false;
    }
  }

  return {
    canManageMembers,
    canManageManagers,
    currentActorId,
    added,
    staged,
    dirty,
    pending,
    /** The member whose manager change is being committed, for its pending control. */
    managerPendingFor: (actorId: string) =>
      (assignManager.isPending && assignManager.variables?.path.actorId === actorId) ||
      (removeManager.isPending && removeManager.variables?.path.actorId === actorId),
    error,
    clearError: () => setError(null),
    /** Changes when the draft is discarded, so the section can close its candidate picker. */
    generation,
    add,
    remove,
    toggleManager,
    reset,
    save,
  };
}

export type GroupMembersDraft = ReturnType<typeof useGroupMembersDraft>;
