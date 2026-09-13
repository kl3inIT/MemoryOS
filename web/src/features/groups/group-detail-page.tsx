import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useParams } from "@tanstack/react-router";
import { Info, LoaderCircle, Trash2, WifiOff } from "lucide-react";
import { useEffect, useRef, useState, type RefObject } from "react";
import { OnyxUsersIcon } from "@/components/icons/identity-icons";
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
import "./groups-list.css";
import "./group-detail.css";

export function GroupDetailPage() {
  const ui = useAppTranslation();

  const { groupId } = useParams({ from: "/_authenticated/admin/groups/$groupId" });
  const queryClient = useQueryClient();
  const navigate = useNavigate();
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
        group.data.actions.includes("manage_grants")),
    retry: false,
  });

  async function refreshAuthorityViews() {
    cancelRef.current?.focus();
    await queryClient.invalidateQueries();
  }

  return (
    <section className="groups-list-page group-detail-page min-h-full px-5 py-12 sm:px-8">
      <div className="mx-auto w-full max-w-[840px]">
        {group.isPending || group.isError || !group.data ? (
          <Button
            ref={cancelRef}
            prominence="secondary"
            onClick={() => void navigate({ to: "/admin/groups", search: { page: 0, size: 20 } })}
          >
            {ui("Cancel")}
          </Button>
        ) : null}

        {group.isPending ? (
          <div
            role="status"
            className="mt-6 rounded-xl border border-border-subtle px-6 py-20 text-center font-main-ui-body text-content-muted"
          >
            <LoaderCircle
              className="mx-auto mb-3 size-5 animate-spin motion-reduce:animate-none"
              aria-hidden="true"
            />
            {ui("Loading group")}
          </div>
        ) : group.isError || !group.data ? (
          <div className="mt-6 rounded-xl border border-border-subtle px-6 py-16 text-center">
            <WifiOff className="mx-auto size-5 text-content-muted" aria-hidden="true" />
            <h1 className="mt-3 font-heading-h3 text-content-primary">{ui("Group unavailable")}</h1>
            <p className="mt-2 font-main-ui-body text-content-muted">
              {ui("It may have been removed, or your scoped access may have changed.")}
            </p>
            <Button
              size="sm"
              prominence="secondary"
              className="mt-5"
              onClick={() => void group.refetch()}
            >
              {ui("Try again")}
            </Button>
          </div>
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
      </div>
    </section>
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
  const canRename = !systemGroup && group.actions.includes("rename");
  const canManageGrants = !systemGroup && group.actions.includes("manage_grants");
  const canDelete = !systemGroup && group.actions.includes("delete");
  const nameDirty = canRename && name.trim() !== baselineName;
  const baselineCapabilityKey = [...baselineCapabilities].sort().join("\u0000");
  const selectedCapabilityKey = [...selectedCapabilities].sort().join("\u0000");
  const capabilitiesDirty = canManageGrants && baselineCapabilityKey !== selectedCapabilityKey;
  const dirty = nameDirty || capabilitiesDirty;
  const canSave = dirty;
  const busy = renameGroup.isPending || replaceCapabilities.isPending || deleteGroup.isPending;
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

  useEffect(() => {
    if (!dirty) return;
    const warnBeforeUnload = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener("beforeunload", warnBeforeUnload);
    return () => window.removeEventListener("beforeunload", warnBeforeUnload);
  }, [dirty]);

  async function saveSettings() {
    const nextName = name.trim();
    if (!canSave || !nextName || busy) return;
    setError(null);
    try {
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
    } catch (cause) {
      setError(groupMutationError(cause, capabilitiesDirty ? "capabilities" : "rename"));
    }
  }

  function cancelSettings() {
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
    await navigate({ to: "/admin/groups", search: { page: 0, size: 20 }, replace: true });
    await queryClient.invalidateQueries();
  }

  return (
    <>
      <header className="group-detail-header border-b border-border-subtle pb-6">
        <div>
          <OnyxUsersIcon className="size-8 text-content-secondary" aria-hidden="true" />
          <h1 className="mt-2 text-2xl font-semibold leading-8 text-content-primary">
            {ui("Edit Group")}
          </h1>
        </div>
        <div className="group-detail-actions flex shrink-0 gap-2">
          <Button ref={cancelRef} prominence="secondary" disabled={busy} onClick={cancelSettings}>
            {ui("Cancel")}
          </Button>
          <Button
            className="groups-list-action"
            pending={renameGroup.isPending || replaceCapabilities.isPending}
            disabled={!canSave || busy || !name.trim() || (capabilitiesDirty && registryError)}
            onClick={() => void saveSettings()}
          >
            {renameGroup.isPending || replaceCapabilities.isPending
              ? ui("Saving…")
              : ui("Save Changes")}
          </Button>
        </div>
      </header>

      {systemGroup ? (
        <div className="groups-list-info mt-6 flex items-start gap-3 px-3 py-3">
          <Info
            className="mt-0.5 size-4 shrink-0 text-[var(--groups-info-icon)]"
            aria-hidden="true"
          />
          <div>
            <p className="text-sm font-semibold leading-5 text-content-primary">
              {ui("System group")}
            </p>
            <p className="mt-0.5 text-xs leading-4 text-content-secondary">
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
          className="mt-5 rounded-lg bg-status-danger-surface px-4 py-3 font-secondary-body text-status-danger-content"
        >
          {ui(error)}
        </p>
      ) : null}

      <div className="group-detail-name mt-8">
        <label htmlFor="group-name" className="font-heading-h3 text-content-primary">
          {ui("Group Name")}
        </label>
        <Input
          id="group-name"
          value={name}
          maxLength={120}
          readOnly={!canRename}
          aria-readonly={!canRename}
          className={`mt-2 ${canRename ? "" : "group-detail-name-readonly"}`}
          onChange={(event) => setName(event.target.value)}
        />
      </div>

      <GroupMembersSection group={group} onAuthorityChanged={onAuthorityChanged} />
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
        <GroupSourcesSection group={group} onAuthorityChanged={onAuthorityChanged} />
      ) : null}

      {canDelete ? (
        <section
          aria-labelledby="delete-group-heading"
          className="mt-7 border-t border-border-subtle pt-7"
        >
          <div className="flex flex-col gap-4 rounded-xl border border-status-danger-content/20 bg-status-danger-surface p-4 sm:flex-row sm:items-center sm:justify-between sm:p-5">
            <div>
              <h2
                id="delete-group-heading"
                className="font-main-ui-action text-status-danger-content"
              >
                {ui("Delete this group")}
              </h2>
              <p className="mt-1 font-secondary-body text-status-danger-content">
                {ui(
                  "Memberships, capability grants, and Source associations are removed. User and Source data stay intact.",
                )}
              </p>
            </div>
            <ConfirmDialog
              trigger={
                <Button tone="danger" prominence="secondary" disabled={busy}>
                  <Trash2 aria-hidden="true" />
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
          </div>
        </section>
      ) : null}
    </>
  );
}
