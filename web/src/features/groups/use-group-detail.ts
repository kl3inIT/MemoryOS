import type { AppCopy } from "@/i18n/app-text";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useBlocker, useNavigate } from "@tanstack/react-router";
import { useMemo, useRef, useState } from "react";
import {
  deleteGroupMutation,
  getGroupQueryKey,
  renameGroupMutation,
  replaceGroupCapabilitiesMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { GroupSummary } from "@/lib/hey-api/types.gen";
import { can } from "@/lib/resource-permissions";
import { groupMutationError } from "./group-errors";
import { useGroupMembersDraft } from "./group-members-draft";
import { useGroupSourcesDraft } from "./group-sources-draft";

/**
 * The Group detail page's drafts and commands: the name and capability drafts over the saved
 * Group, the member and Source drafts, and Save Changes, Cancel and Delete over all of them.
 */
export function useGroupDetail(group: GroupSummary, onAuthorityChanged: () => Promise<void>) {
  const navigate = useNavigate({ from: "/admin/groups/$groupId" });
  const queryClient = useQueryClient();
  const renameGroup = useMutation(renameGroupMutation());
  const replaceCapabilities = useMutation(replaceGroupCapabilitiesMutation());
  const deleteGroup = useMutation(deleteGroupMutation());
  const members = useGroupMembersDraft(group);
  const sources = useGroupSourcesDraft(group);
  const [saving, setSaving] = useState(false);
  // Drafts hold only the person's edits; without one, the saved Group shows through.
  const [nameDraft, setNameDraft] = useState<string | null>(null);
  const [capabilityDraft, setCapabilityDraft] = useState<ReadonlySet<Capability> | null>(null);
  const [error, setError] = useState<AppCopy | null>(null);
  const systemGroup = group.systemKey === "ADMIN" || group.systemKey === "BASIC";
  const canRename = !systemGroup && can(group, "manage");
  const canManageGrants = !systemGroup && can(group, "editPermissions");
  const canDelete = !systemGroup && can(group, "delete");
  const name = canRename ? (nameDraft ?? group.name) : group.name;
  const selectedCapabilities = useMemo(
    () => (canManageGrants && capabilityDraft) || new Set(group.capabilities),
    [canManageGrants, capabilityDraft, group.capabilities],
  );
  const nameDirty = canRename && name.trim() !== group.name;
  const capabilitiesDirty =
    canManageGrants && capabilityKey(selectedCapabilities) !== capabilityKey(group.capabilities);
  const settingsDirty = nameDirty || capabilitiesDirty;
  const dirty = settingsDirty || members.dirty || sources.dirty;
  const canSave = dirty;
  const busy =
    saving ||
    members.pending ||
    sources.pending ||
    renameGroup.isPending ||
    replaceCapabilities.isPending ||
    deleteGroup.isPending;

  const [previousAuthority, setPreviousAuthority] = useState({ canRename, canManageGrants });
  if (
    previousAuthority.canRename !== canRename ||
    previousAuthority.canManageGrants !== canManageGrants
  ) {
    setPreviousAuthority({ canRename, canManageGrants });
    if (!canRename) setNameDraft(null);
    if (!canManageGrants) setCapabilityDraft(null);
    if (
      (previousAuthority.canRename && !canRename) ||
      (previousAuthority.canManageGrants && !canManageGrants)
    ) {
      setError(null);
    }
  }

  // Set once the page leaves on purpose (saved or deleted), so that navigation is not held.
  const leaving = useRef(false);
  const blocker = useBlocker({
    shouldBlockFn: () => dirty && !leaving.current,
    enableBeforeUnload: () => dirty && !leaving.current,
    withResolver: true,
  });

  async function saveSettings() {
    const nextName = name.trim();
    if (!canSave || !nextName || busy) return;
    setError(null);
    setSaving(true);
    try {
      // Source associations, then membership, then the Group's own settings: each step keeps
      // the authority the next one needs.
      if (!(await sources.save())) return;
      if (!(await members.save())) return;
      const groupKey = getGroupQueryKey({ path: { groupId: group.id } });
      if (canRename && nameDirty) {
        const renamed = await renameGroup.mutateAsync({
          path: { groupId: group.id },
          body: { name: nextName },
        });
        queryClient.setQueryData(groupKey, renamed);
        setNameDraft(null);
      }
      if (canManageGrants && capabilitiesDirty) {
        const granted = [...selectedCapabilities];
        await replaceCapabilities.mutateAsync({
          path: { groupId: group.id },
          body: { capabilities: granted },
        });
        queryClient.setQueryData(groupKey, (current: GroupSummary | undefined) =>
          current ? { ...current, capabilities: granted } : current,
        );
        setCapabilityDraft(null);
      }
      await onAuthorityChanged();
      leaving.current = true;
      await navigate({ to: "/admin/groups", search: { page: 0, size: 20 } });
    } catch (cause) {
      setError(groupMutationError(cause, capabilitiesDirty ? "capabilities" : "rename"));
    } finally {
      setSaving(false);
    }
  }

  function cancelSettings() {
    members.reset();
    sources.reset();
    if (!dirty) {
      void navigate({ to: "/admin/groups", search: { page: 0, size: 20 } });
      return;
    }
    setNameDraft(null);
    setCapabilityDraft(null);
    setError(null);
  }

  async function deleteSelectedGroup() {
    await deleteGroup.mutateAsync({
      path: { groupId: group.id },
    });
    leaving.current = true;
    await navigate({ to: "/admin/groups", search: { page: 0, size: 20 }, replace: true });
    await queryClient.invalidateQueries();
  }

  return {
    members,
    sources,
    systemGroup,
    canRename,
    canManageGrants,
    canDelete,
    name,
    setNameDraft,
    selectedCapabilities,
    setCapabilityDraft,
    capabilitiesDirty,
    canSave,
    busy,
    deleting: deleteGroup.isPending,
    error,
    blocker,
    saveSettings,
    cancelSettings,
    deleteSelectedGroup,
  };
}

type Capability = GroupSummary["capabilities"][number];

function capabilityKey(capabilities: Iterable<Capability>) {
  return [...capabilities].sort().join("\u0000");
}
