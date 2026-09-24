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
import { useCallback, useEffect, useRef, useState, type RefObject } from "react";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Input } from "@/components/ui/input";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  deleteGroupMutation,
  getGroupOptions,
  listGroupCapabilitiesOptions,
  renameGroupMutation,
  replaceGroupCapabilitiesMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { GroupCapability, GroupSummary } from "@/lib/hey-api/types.gen";
import { groupMutationError } from "./group-errors";
import { GroupMembersSection } from "./group-members-section";
import { GroupPermissionsSection } from "./group-permissions-section";
import { GroupSourcesSection } from "./group-sources-section";
import { type GroupDraftSectionHandle, type GroupDraftStateChange } from "./group-draft-section";
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
  const membersRef = useRef<GroupDraftSectionHandle>(null);
  const sourcesRef = useRef<GroupDraftSectionHandle>(null);
  const [membersDirty, setMembersDirty] = useState(false);
  const [membersPending, setMembersPending] = useState(false);
  const [sourcesDirty, setSourcesDirty] = useState(false);
  const [sourcesPending, setSourcesPending] = useState(false);
  const [saving, setSaving] = useState(false);
  const [baselineName, setBaselineName] = useState(group.name);
  const [name, setName] = useState(group.name);
  const [baselineCapabilities, setBaselineCapabilities] = useState(
    () => new Set<GroupSummary["capabilities"][number]>(group.capabilities),
  );
  const [selectedCapabilities, setSelectedCapabilities] = useState(
    () => new Set<GroupSummary["capabilities"][number]>(group.capabilities),
  );
  const [error, setError] = useState<AppCopy | null>(null);
  const systemGroup = group.systemKey === "ADMIN" || group.systemKey === "BASIC";
  const canRename = !systemGroup && can(group, "manage");
  const canManageGrants = !systemGroup && can(group, "editPermissions");
  const canDelete = !systemGroup && can(group, "delete");
  const nameDirty = canRename && name.trim() !== baselineName;
  const baselineCapabilityKey = [...baselineCapabilities].sort().join("\u0000");
  const selectedCapabilityKey = [...selectedCapabilities].sort().join("\u0000");
  const capabilitiesDirty = canManageGrants && baselineCapabilityKey !== selectedCapabilityKey;
  const settingsDirty = nameDirty || capabilitiesDirty;
  const dirty = settingsDirty || membersDirty || sourcesDirty;
  const canSave = dirty;
  const busy =
    saving ||
    membersPending ||
    sourcesPending ||
    renameGroup.isPending ||
    replaceCapabilities.isPending ||
    deleteGroup.isPending;
  const incomingCapabilityKey = [...group.capabilities].sort().join("\u0000");
  const incomingSettingsKey = `${group.name}\u0000${incomingCapabilityKey}`;
  const seededIncomingKeyRef = useRef(incomingSettingsKey);
  const [previousSettingsState, setPreviousSettingsState] = useState(() => ({
    canRename,
    canManageGrants,
    incomingSettingsKey,
  }));

  if (
    previousSettingsState.canRename !== canRename ||
    previousSettingsState.canManageGrants !== canManageGrants ||
    previousSettingsState.incomingSettingsKey !== incomingSettingsKey
  ) {
    setPreviousSettingsState({ canRename, canManageGrants, incomingSettingsKey });
    if (!canRename) {
      setName(group.name);
      setBaselineName(group.name);
    }
    if (!canManageGrants) {
      setSelectedCapabilities(new Set(group.capabilities));
      setBaselineCapabilities(new Set(group.capabilities));
    }
    if (
      (previousSettingsState.canRename && !canRename) ||
      (previousSettingsState.canManageGrants && !canManageGrants)
    ) {
      setError(null);
    }
  }

  useEffect(() => {
    if (dirty || seededIncomingKeyRef.current === incomingSettingsKey) return;
    seededIncomingKeyRef.current = incomingSettingsKey;
    setBaselineName(group.name);
    setName(group.name);
    const incoming = new Set<GroupSummary["capabilities"][number]>(group.capabilities);
    setBaselineCapabilities(incoming);
    setSelectedCapabilities(new Set(incoming));
  }, [dirty, group.capabilities, group.name, incomingSettingsKey]);

  // Set once the page leaves on purpose (saved or deleted), so that navigation is not held.
  const leaving = useRef(false);
  const blocker = useBlocker({
    shouldBlockFn: () => dirty && !leaving.current,
    enableBeforeUnload: () => dirty && !leaving.current,
    withResolver: true,
  });

  const onMembersDraftChange = useCallback<GroupDraftStateChange>((nextDirty, pending) => {
    setMembersDirty(nextDirty);
    setMembersPending(pending);
  }, []);
  const onSourcesDraftChange = useCallback<GroupDraftStateChange>((nextDirty, pending) => {
    setSourcesDirty(nextDirty);
    setSourcesPending(pending);
  }, []);

  async function saveSettings() {
    const nextName = name.trim();
    if (!canSave || !nextName || busy) return;
    setError(null);
    setSaving(true);
    try {
      const sourcesSaved = (await sourcesRef.current?.save()) ?? true;
      if (!sourcesSaved) return;
      const membersSaved = (await membersRef.current?.save()) ?? true;
      if (!membersSaved) return;
      if (canRename && nameDirty) {
        await renameGroup.mutateAsync({
          path: { groupId: group.id },
          headers: sameOriginMutationHeaders,
          body: { name: nextName },
        });
        setBaselineName(nextName);
        setName(nextName);
      }
      if (canManageGrants && capabilitiesDirty) {
        await replaceCapabilities.mutateAsync({
          path: { groupId: group.id },
          headers: sameOriginMutationHeaders,
          body: { capabilities: [...selectedCapabilities] },
        });
        setBaselineCapabilities(new Set(selectedCapabilities));
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
    membersRef.current?.reset();
    sourcesRef.current?.reset();
    if (!dirty) {
      void navigate({ to: "/admin/groups", search: { page: 0, size: 20 } });
      return;
    }
    setName(baselineName);
    setSelectedCapabilities(new Set(baselineCapabilities));
    setError(null);
  }

  async function deleteSelectedGroup() {
    await deleteGroup.mutateAsync({
      path: { groupId: group.id },
      headers: sameOriginMutationHeaders,
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
        title={baselineName}
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
          onChange={(event) => setName(event.target.value)}
        />
      </div>

      <GroupMembersSection ref={membersRef} group={group} onDraftChange={onMembersDraftChange} />
      {systemGroup || canManageGrants ? (
        <GroupPermissionsSection
          registry={registry}
          selected={systemGroup ? new Set(group.capabilities) : selectedCapabilities}
          systemKey={group.systemKey}
          editable={canManageGrants}
          loading={registryLoading}
          error={registryError}
          onRetry={onRetryRegistry}
          onChange={setSelectedCapabilities}
        />
      ) : null}
      {!systemGroup ? (
        <GroupSourcesSection ref={sourcesRef} group={group} onDraftChange={onSourcesDraftChange} />
      ) : null}

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
              title={ui("Delete {{v1}}?", { v1: baselineName })}
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
