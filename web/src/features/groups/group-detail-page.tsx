import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "@tanstack/react-router";
import {
  ArrowLeft,
  LoaderCircle,
  Save,
  ShieldCheck,
  Trash2,
  UsersRound,
  WifiOff,
} from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Badge } from "@/components/ui/badge";
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

export function GroupDetailPage() {
  const { groupId } = useParams({ from: "/_authenticated/admin/groups/$groupId" });
  const queryClient = useQueryClient();
  const backRef = useRef<HTMLAnchorElement>(null);
  const group = useQuery({
    ...getGroupOptions({ path: { groupId } }),
    retry: false,
  });
  const capabilities = useQuery({
    ...listGroupCapabilitiesOptions(),
    retry: false,
  });

  async function refreshAuthorityViews() {
    backRef.current?.focus();
    await queryClient.invalidateQueries();
  }

  return (
    <section className="mx-auto w-full max-w-[var(--page-width-standard)] px-5 py-8 sm:px-8 sm:py-10">
      <Link
        ref={backRef}
        to="/admin/groups"
        search={{ page: 0, size: 20 }}
        className="inline-flex items-center gap-2 rounded-lg font-secondary-action text-content-secondary outline-none transition-colors hover:text-content-primary focus-visible:ring-3 focus-visible:ring-focus-ring/40"
      >
        <ArrowLeft className="size-4" aria-hidden="true" />
        Groups
      </Link>

      {group.isPending ? (
        <div
          role="status"
          className="mt-6 rounded-xl border border-border-subtle px-6 py-20 text-center font-main-ui-body text-content-muted"
        >
          <LoaderCircle
            className="mx-auto mb-3 size-5 animate-spin motion-reduce:animate-none"
            aria-hidden="true"
          />
          Loading group
        </div>
      ) : group.isError || !group.data ? (
        <div className="mt-6 rounded-xl border border-border-subtle px-6 py-16 text-center">
          <WifiOff className="mx-auto size-5 text-content-muted" aria-hidden="true" />
          <h1 className="mt-3 font-heading-h3 text-content-primary">Group unavailable</h1>
          <p className="mt-2 font-main-ui-body text-content-muted">
            It may have been removed, or your scoped access may have changed.
          </p>
          <Button
            size="sm"
            prominence="secondary"
            className="mt-5"
            onClick={() => void group.refetch()}
          >
            Try again
          </Button>
        </div>
      ) : (
        <GroupDetail
          group={group.data}
          registry={capabilities.data?.items ?? []}
          registryLoading={capabilities.isPending}
          registryError={capabilities.isError}
          onRetryRegistry={() => void capabilities.refetch()}
          onAuthorityChanged={refreshAuthorityViews}
        />
      )}
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
};

function GroupDetail({
  group,
  registry,
  registryLoading,
  registryError,
  onRetryRegistry,
  onAuthorityChanged,
}: GroupDetailProps) {
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
  const [error, setError] = useState<string | null>(null);
  const nameDirty = name.trim() !== baselineName;
  const baselineCapabilityKey = [...baselineCapabilities].sort().join("\u0000");
  const selectedCapabilityKey = [...selectedCapabilities].sort().join("\u0000");
  const capabilitiesDirty = baselineCapabilityKey !== selectedCapabilityKey;
  const dirty = nameDirty || capabilitiesDirty;
  const canRename = group.actions.includes("rename");
  const canManageGrants = group.actions.includes("manage_grants");
  const canDelete = group.actions.includes("delete");
  const canSave = (canRename && nameDirty) || (canManageGrants && capabilitiesDirty);
  const busy = renameGroup.isPending || replaceCapabilities.isPending || deleteGroup.isPending;
  const incomingCapabilityKey = [...group.capabilities].sort().join("\u0000");
  const incomingSettingsKey = `${group.name}\u0000${incomingCapabilityKey}`;
  const seededIncomingKeyRef = useRef(incomingSettingsKey);

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
      <header className="mt-6 border-b border-border-subtle pb-5">
        <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
          <div className="flex min-w-0 items-start gap-3">
            <span className="grid size-11 shrink-0 place-items-center rounded-xl border border-border-subtle bg-surface-subtle text-content-secondary">
              {group.systemKey === "ADMIN" ? (
                <ShieldCheck className="size-5" aria-hidden="true" />
              ) : (
                <UsersRound className="size-5" aria-hidden="true" />
              )}
            </span>
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <h1 className="truncate font-heading-h2 text-content-primary">{baselineName}</h1>
                {group.systemKey ? (
                  <Badge variant="outline" className="bg-surface-raised text-content-muted">
                    System group
                  </Badge>
                ) : null}
              </div>
              <p className="mt-1 font-main-ui-body text-content-muted">
                {group.memberCount.toLocaleString()}{" "}
                {group.memberCount === 1 ? "member" : "members"} ·{" "}
                {group.managerCount.toLocaleString()}{" "}
                {group.managerCount === 1 ? "manager" : "managers"}
              </p>
            </div>
          </div>
          {canRename || canManageGrants ? (
            <div className="flex shrink-0 gap-2">
              <Button prominence="secondary" disabled={!dirty || busy} onClick={cancelSettings}>
                Cancel
              </Button>
              <Button
                pending={renameGroup.isPending || replaceCapabilities.isPending}
                disabled={!canSave || !name.trim() || (capabilitiesDirty && registryError)}
                onClick={() => void saveSettings()}
              >
                <Save aria-hidden="true" />
                {renameGroup.isPending || replaceCapabilities.isPending
                  ? "Saving…"
                  : "Save changes"}
              </Button>
            </div>
          ) : null}
        </div>
      </header>

      {group.systemKey ? (
        <div className="mt-6 rounded-xl border border-border-subtle bg-surface-subtle px-4 py-3">
          <p className="font-main-ui-action text-content-primary">
            {group.systemKey === "ADMIN"
              ? "Protected administrator group"
              : "Automatic basic membership"}
          </p>
          <p className="mt-1 font-secondary-body text-content-muted">
            {group.systemKey === "ADMIN"
              ? "Its name and lifecycle are fixed. Protected owner and final administrator safeguards remain enforced by the server."
              : "Its name and lifecycle are fixed. New accepted members are added through the account lifecycle."}
          </p>
        </div>
      ) : null}

      {error ? (
        <p
          role="alert"
          className="mt-5 rounded-lg bg-status-danger-surface px-4 py-3 font-secondary-body text-status-danger-content"
        >
          {error}
        </p>
      ) : null}

      <div className="mt-7">
        <label htmlFor="group-name" className="font-secondary-action text-content-primary">
          Group name
        </label>
        <Input
          id="group-name"
          value={name}
          maxLength={120}
          readOnly={!canRename}
          aria-readonly={!canRename}
          className={`mt-2 ${canRename ? "" : "bg-surface-sunken text-content-secondary"}`}
          onChange={(event) => setName(event.target.value)}
        />
      </div>

      <GroupMembersSection group={group} onAuthorityChanged={onAuthorityChanged} />
      <GroupPermissionsSection
        registry={registry}
        selected={selectedCapabilities}
        editable={canManageGrants}
        loading={registryLoading}
        error={registryError}
        onRetry={onRetryRegistry}
        onChange={setSelectedCapabilities}
      />
      <GroupSourcesSection group={group} onAuthorityChanged={onAuthorityChanged} />

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
                Delete this group
              </h2>
              <p className="mt-1 font-secondary-body text-status-danger-content">
                Memberships, capability grants, and Source associations are removed. User and Source
                data stay intact.
              </p>
            </div>
            <ConfirmDialog
              trigger={
                <Button tone="danger" prominence="secondary" disabled={busy}>
                  <Trash2 aria-hidden="true" />
                  Delete group
                </Button>
              }
              title={`Delete ${baselineName}?`}
              description="This ordinary group and its access edges will be permanently removed. Users, Sources, and documents are not deleted."
              confirmLabel="Delete group"
              pendingLabel="Deleting group…"
              onConfirm={deleteSelectedGroup}
              errorMessage={(cause) => groupMutationError(cause, "delete")}
            />
          </div>
        </section>
      ) : null}
    </>
  );
}
