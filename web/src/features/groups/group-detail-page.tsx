import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useBlocker, useNavigate, useParams } from "@tanstack/react-router";
import { Info, Trash2, Users, WifiOff } from "lucide-react";
import { BrandLoader } from "@/components/brand-loader";
import { DangerZone } from "@/components/composites/danger-zone";
import { DetailHeader } from "@/components/composites/detail-header";
import { EmptyState } from "@/components/composites/empty-state";
import { SettingsLayout } from "@/components/ui/settings-layout";
import { useMemo, useRef, useState, type RefObject } from "react";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Input } from "@/components/ui/input";
import {
  deleteGroupMutation,
  getGroupOptions,
  getGroupQueryKey,
  listGroupCapabilitiesOptions,
  renameGroupMutation,
  replaceGroupCapabilitiesMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { GroupCapability, GroupSummary } from "@/lib/hey-api/types.gen";
import { groupMutationError } from "./group-errors";
import { useGroupMembersDraft } from "./group-members-draft";
import { GroupMembersSection } from "./group-members-section";
import { GroupPermissionsSection } from "./group-permissions-section";
import { useGroupSourcesDraft } from "./group-sources-draft";
import { GroupSourcesSection } from "./group-sources-section";
import { can } from "@/lib/resource-permissions";

export function GroupDetailPage() {
  const ui = useAppTranslation();

  const { groupId } = useParams({ from: "/_authenticated/admin/groups/$groupId" });
  const queryClient = useQueryClient();
  const cancelRef = useRef<HTMLButtonElement>(null);
  const group = useQuery({
    ...getGroupOptions({ path: { groupId } }),
    retry: false,
  });
  const capabilities = useQuery({
    ...listGroupCapabilitiesOptions(),
    enabled:
      !!group.data &&
      (group.data.systemKey === "ADMIN" ||
        group.data.systemKey === "BASIC" ||
        can(group.data, "editPermissions")),
    retry: false,
  });

  async function refreshAuthorityViews() {
    cancelRef.current?.focus();
    await queryClient.invalidateQueries();
  }

  return (
    <SettingsLayout>
      {group.isPending ? (
        <div
          role="status"
          className="flex justify-center rounded-xl border border-border-subtle px-6 py-20"
        >
          <BrandLoader label={ui("Loading group")} />
        </div>
      ) : group.isError || !group.data ? (
        <EmptyState
          role="alert"
          icon={<WifiOff />}
          title={ui("Group unavailable")}
          detail={ui("It may have been removed, or your scoped access may have changed.")}
          action={
            <Button
              ref={cancelRef}
              size="sm"
              prominence="secondary"
              onClick={() => void group.refetch()}
            >
              {ui("Try again")}
            </Button>
          }
        />
      ) : (
        <GroupDetail
          key={group.data.id}
          group={group.data}
          registry={capabilities.data?.items ?? []}
          registryLoading={capabilities.isPending}
          registryError={capabilities.isError}
          onRetryRegistry={() => void capabilities.refetch()}
          onAuthorityChanged={refreshAuthorityViews}
          cancelRef={cancelRef}
        />
      )}
    </SettingsLayout>
  );
}

type GroupDetailProps = {
  group: GroupSummary;
  registry: readonly GroupCapability[];
  registryLoading: boolean;
  registryError: boolean;
  onRetryRegistry: () => void;
  onAuthorityChanged: () => Promise<void>;
  cancelRef: RefObject<HTMLButtonElement | null>;
};

function GroupDetail({
  group,
  registry,
  registryLoading,
  registryError,
  onRetryRegistry,
  onAuthorityChanged,
  cancelRef,
}: GroupDetailProps) {
  const ui = useAppTranslation();

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

  return (
    <>
      <DetailHeader
        parent={{ label: ui("Groups"), to: "/admin/groups", search: { page: 0, size: 20 } }}
        icon={<Users />}
        title={group.name}
        description={ui("Membership, permissions and the Sources this group may read.")}
        actions={
          <>
            <Button ref={cancelRef} prominence="secondary" disabled={busy} onClick={cancelSettings}>
              {ui("Cancel")}
            </Button>
            <Button
              pending={busy && !deleteGroup.isPending}
              disabled={!canSave || busy || !name.trim() || (capabilitiesDirty && registryError)}
              onClick={() => void saveSettings()}
            >
              {busy && !deleteGroup.isPending ? ui("Saving…") : ui("Save Changes")}
            </Button>
          </>
        }
      />

      {systemGroup ? (
        <div className="flex items-start gap-3 rounded-xl border border-border-subtle bg-surface-subtle px-4 py-3">
          <Info className="mt-0.5 size-4 shrink-0 text-status-info-strong" aria-hidden="true" />
          <div>
            <p className="font-main-ui-action text-content-primary">{ui("System group")}</p>
            <p className="mt-0.5 font-secondary-body text-content-secondary">
              {ui(
                "MemoryOS manages this group. Its name and permissions are fixed. Membership changes follow the group’s access rules.",
              )}
            </p>
          </div>
        </div>
      ) : null}

      {error ? (
        <p
          role="alert"
          className="rounded-lg bg-status-danger-surface px-4 py-3 font-secondary-body text-status-danger-content"
        >
          {ui(error)}
        </p>
      ) : null}

      <div className="flex flex-col gap-2">
        <label htmlFor="group-name" className="font-heading-h3 text-content-primary">
          {ui("Group Name")}
        </label>
        <Input
          id="group-name"
          value={name}
          maxLength={120}
          readOnly={!canRename}
          aria-readonly={!canRename}
          className="max-w-md"
          onChange={(event) => setNameDraft(event.target.value)}
        />
      </div>

      <GroupMembersSection group={group} draft={members} />
      {systemGroup || canManageGrants ? (
        <GroupPermissionsSection
          registry={registry}
          selected={systemGroup ? new Set(group.capabilities) : selectedCapabilities}
          systemKey={group.systemKey}
          editable={canManageGrants}
          loading={registryLoading}
          error={registryError}
          onRetry={onRetryRegistry}
          onChange={setCapabilityDraft}
        />
      ) : null}
      {!systemGroup ? <GroupSourcesSection draft={sources} /> : null}

      {canDelete ? (
        <DangerZone
          icon={<Trash2 />}
          title={ui("Delete this group")}
          description={ui(
            "Memberships, capability grants, and Source associations are removed. User and Source data stay intact.",
          )}
          action={
            <ConfirmDialog
              trigger={
                <Button tone="danger" prominence="secondary" disabled={busy}>
                  {ui("Delete group")}
                </Button>
              }
              title={ui("Delete {{v1}}?", { v1: group.name })}
              description={ui(
                "This ordinary group and its access edges will be permanently removed. Users, Sources, and documents are not deleted.",
              )}
              confirmLabel={ui("Delete group")}
              pendingLabel={ui("Deleting group…")}
              onConfirm={deleteSelectedGroup}
              errorMessage={(cause) => groupMutationError(cause, "delete")}
            />
          }
        />
      ) : null}

      <ConfirmDialog
        open={blocker.status === "blocked"}
        onOpenChange={(open) => {
          if (!open && blocker.status === "blocked") blocker.reset();
        }}
        title={ui("Bỏ thay đổi chưa lưu?")}
        description={ui("Unsaved changes to this group will be lost.")}
        confirmLabel={ui("Rời trang")}
        pendingLabel={ui("Đang rời trang…")}
        onConfirm={async () => blocker.proceed?.()}
      />
    </>
  );
}

type Capability = GroupSummary["capabilities"][number];

function capabilityKey(capabilities: Iterable<Capability>) {
  return [...capabilities].sort().join("\u0000");
}
