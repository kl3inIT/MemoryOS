import { useAppTranslation } from "@/i18n/use-app-translation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useParams } from "@tanstack/react-router";
import { Info, Trash2, Users, WifiOff } from "lucide-react";
import { BrandLoader } from "@/components/brand-loader";
import { DangerZone } from "@/components/composites/danger-zone";
import { DetailHeader } from "@/components/composites/detail-header";
import { EmptyState } from "@/components/composites/empty-state";
import { SettingsLayout } from "@/components/composites/settings-layout";
import { useRef, type RefObject } from "react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Field, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import {
  getGroupOptions,
  listGroupCapabilitiesOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { GroupCapability, GroupSummary } from "@/lib/hey-api/types.gen";
import { groupMutationError } from "./group-errors";
import { GroupMembersSection } from "./group-members-section";
import { GroupPermissionsSection } from "./group-permissions-section";
import { GroupSourcesSection } from "./group-sources-section";
import { useGroupDetail } from "./use-group-detail";
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

  const {
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
    deleting,
    error,
    blocker,
    saveSettings,
    cancelSettings,
    deleteSelectedGroup,
  } = useGroupDetail(group, onAuthorityChanged);

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
              pending={busy && !deleting}
              disabled={!canSave || busy || !name.trim() || (capabilitiesDirty && registryError)}
              onClick={() => void saveSettings()}
            >
              {busy && !deleting ? ui("Saving…") : ui("Save Changes")}
            </Button>
          </>
        }
      />

      {systemGroup ? (
        <Alert variant="info" role="note">
          <Info aria-hidden="true" />
          <AlertTitle>{ui("System group")}</AlertTitle>
          <AlertDescription>
            {ui(
              "MemoryOS manages this group. Its name and permissions are fixed. Membership changes follow the group’s access rules.",
            )}
          </AlertDescription>
        </Alert>
      ) : null}

      {error ? (
        <Alert variant="destructive">
          <AlertDescription>{ui(error)}</AlertDescription>
        </Alert>
      ) : null}

      <Field>
        <FieldLabel htmlFor="group-name">{ui("Group Name")}</FieldLabel>
        <Input
          id="group-name"
          value={name}
          maxLength={120}
          readOnly={!canRename}
          aria-readonly={!canRename}
          className="max-w-md"
          onChange={(event) => setNameDraft(event.target.value)}
        />
      </Field>

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
