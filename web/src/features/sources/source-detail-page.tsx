import { appText } from "@/i18n/app-text";
import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useParams } from "@tanstack/react-router";
import { DatabaseZap, FileText, Trash2 } from "lucide-react";
import { useLayoutEffect, useRef, useState } from "react";
import { BrandLoader } from "@/components/brand-loader";
import { DangerZone } from "@/components/composites/danger-zone";
import { DetailHeader } from "@/components/composites/detail-header";
import { EmptyState } from "@/components/composites/empty-state";
import { Button } from "@/components/ui/button";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { SettingsLayout } from "@/components/ui/settings-layout";
import { Tabs, TabsContent } from "@/components/ui/tabs";
import {
  getSourceOptions,
  getSourceQueryKey,
  listSourceItemsQueryKey,
  listSourcesQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { sourceMutationError } from "@/features/sources/shared/source-errors";
import { SourceSummaryCard } from "@/features/sources/shared/source-summary-card";
import { useManualRefresh } from "@/lib/use-manual-refresh";
import { findSourceProvider } from "@/features/sources/shared/source-provider-catalog";
import { GoogleDrivePanel } from "@/features/sources/google-drive/google-drive-panel";
import { SharePointPanel } from "@/features/sources/sharepoint/sharepoint-panel";
import { SourceItemHistory } from "@/features/sources/history/source-item-history";
import { SourceRunHistory } from "@/features/sources/history/source-run-history";
import { SourceGroupsSection } from "./source-groups-section";
import { SourceManagerSection } from "./source-manager-section";
import { useGlobalCapability } from "@/features/identity/application-session-context";
import { SourceActionsMenu } from "./source-actions-menu";
import { SourceFilesPanel } from "./source-files-panel";
import { type SourceMetadataField, SourceMetadataDialog } from "./source-metadata-dialog";
import {
  SourceAccessBadge,
  SourceStatusBadge,
} from "@/features/sources/shared/source-status-badge";
import { type SourceSection, SourceSectionTabs } from "./source-section-tabs";
import { SourceUploadForm } from "./source-upload-form";
import { useSourceFiles } from "./use-source-files";
import { useSourceItemActions } from "./use-source-item-actions";
import { useSourceLifecycle } from "./use-source-lifecycle";
import { useSourceUpload } from "./use-source-upload";
import { can } from "@/lib/resource-permissions";

const fileContentSections: readonly SourceSection[] = [
  { value: "content", label: "Files" },
  { value: "history", label: "Indexing history" },
];

const googleDriveSections: readonly SourceSection[] = [
  { value: "content", label: "Content" },
  { value: "history", label: "Sync history" },
  { value: "settings", label: "Connection and settings" },
];

export function SourceDetailPage() {
  const { sourceId } = useParams({
    from: "/_authenticated/admin/sources/$sourceId",
  });
  return <SourceDetailContent key={sourceId} selectedId={sourceId} />;
}

function SourceDetailContent({ selectedId }: { selectedId: string }) {
  const ui = useAppTranslation();

  const [section, setSection] = useState("content");
  const queryClient = useQueryClient();
  const notify = useActionNotifications();
  const active = useRef(true);
  const [error, setError] = useState<AppCopy | null>(null);
  const [driveBusy, setDriveBusy] = useState(false);
  const backLinkRef = useRef<HTMLAnchorElement>(null);
  const actionsTrigger = useRef<HTMLButtonElement>(null);
  const deleteTrigger = useRef<HTMLButtonElement>(null);
  const [sourceDialog, setSourceDialog] = useState<SourceMetadataField | "delete" | null>(null);

  useLayoutEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
    };
  }, []);

  const sourceQuery = useQuery({
    ...getSourceOptions({ path: { sourceId: selectedId } }),
    retry: false,
    refetchInterval: (query) => (query.state.data?.pendingWork ? 1_500 : false),
  });
  const detail = sourceQuery.data;
  const files = useSourceFiles(selectedId, detail);
  // The Source polls while work is pending, so its refresh control follows the press rather than the poll.
  const sourceRefresh = useManualRefresh(refreshSource);

  async function refresh(resetFiles = false) {
    const filesKey = listSourceItemsQueryKey({ path: { sourceId: selectedId } });
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: listSourcesQueryKey() }),
      queryClient.invalidateQueries({
        queryKey: getSourceQueryKey({ path: { sourceId: selectedId } }),
      }),
      queryClient.invalidateQueries({
        queryKey: filesKey,
        refetchType: resetFiles ? "none" : "active",
      }),
    ]);
    if (resetFiles && active.current) {
      files.paging.reset();
      await queryClient.refetchQueries({ queryKey: filesKey, type: "active" });
    }
  }

  async function refreshSource() {
    try {
      await sourceQuery.refetch({ throwOnError: true });
      if (active.current)
        notify({ tone: "success", title: "Source refreshed", description: detail?.name });
    } catch (cause) {
      if (active.current)
        notify({
          tone: "error",
          title: "Source refresh failed",
          description: appText(sourceMutationError(cause, "reindex")),
        });
    }
  }

  const paused = detail?.status === "PAUSED" || detail?.status === "PAUSING";
  const canUpload = detail?.type === "FILE" && can(detail, "edit") && !paused;
  const canReindex = can(detail, "edit") && !paused;
  const canRemoveItems = can(detail, "removeItems");
  const canDelete = can(detail, "delete");
  const canManageGroups = can(detail, "edit");
  const { upload, fileInput } = useSourceUpload({
    sourceId: selectedId,
    canUpload,
    setError,
    onAccepted: () => refresh(true),
  });
  const itemActions = useSourceItemActions({
    sourceId: selectedId,
    canReindex,
    canRemove: canRemoveItems,
    setError,
    refresh,
  });
  const lifecycle = useSourceLifecycle({
    sourceId: selectedId,
    sourceName: detail?.name,
    canDelete,
    setError,
    refresh,
  });
  // Only group access reads through groups, so other Sources have none to manage.
  const showGroups = detail?.access === "PRIVATE";
  const canRename = can(detail, "edit");
  const canChangeAccess = can(detail, "publish");
  const isAdministrator = useGlobalCapability("SYSTEM_ADMIN");
  // The settings tab holds group associations and the administrator's manager appointment; without either it is dropped.
  const fileSections: readonly SourceSection[] = showGroups
    ? [...fileContentSections, { value: "settings", label: "Groups" }]
    : isAdministrator
      ? [...fileContentSections, { value: "settings", label: "Manager" }]
      : fileContentSections;
  if (
    (sourceDialog === "name" && !canRename) ||
    (sourceDialog === "access" && !canChangeAccess) ||
    (sourceDialog === "delete" && !canDelete)
  )
    setSourceDialog(null);
  const managementBusy =
    upload.busy ||
    itemActions.busy ||
    lifecycle.deleting ||
    lifecycle.pausing ||
    sourceQuery.isError;
  const busy = managementBusy || driveBusy;
  const itemBusy = upload.busy || lifecycle.deleting || sourceQuery.isError || driveBusy;
  const provider = findSourceProvider(detail?.type);
  const ProviderIcon = provider?.icon ?? FileText;
  async function refreshAuthorityViews() {
    backLinkRef.current?.focus();
    await queryClient.invalidateQueries();
  }

  function togglePause(resume: boolean) {
    if (!busy) void lifecycle.togglePause(resume);
  }

  const filesPanel = detail ? (
    <SourceFilesPanel
      source={detail}
      files={files}
      canUpload={canUpload}
      disabled={itemBusy}
      working={itemActions.working}
      onReindex={canReindex ? (item) => void itemActions.reindex(item) : undefined}
      onRemove={canRemoveItems ? itemActions.remove : undefined}
    />
  ) : null;

  return (
    <SettingsLayout wide>
      <DetailHeader
        parent={{ label: ui("Sources"), to: "/admin" }}
        backRef={backLinkRef}
        icon={detail ? <ProviderIcon /> : undefined}
        iconSize="lg"
        title={detail?.name}
        description={
          detail ? (
            <span className="flex flex-wrap items-center gap-2">
              <SourceStatusBadge status={detail.status} />
              <SourceAccessBadge access={detail.access} />
              {/* The header icon shows the provider but is hidden from assistive technology. */}
              {provider ? <span className="sr-only">{ui(provider.name)}</span> : null}
            </span>
          ) : undefined
        }
        actions={
          detail ? (
            <SourceActionsMenu
              triggerRef={actionsTrigger}
              disabled={busy || detail.status === "DELETING"}
              status={detail.status}
              onRename={canRename ? () => setSourceDialog("name") : undefined}
              onChangeAccess={canChangeAccess ? () => setSourceDialog("access") : undefined}
              onPause={canRename ? () => togglePause(false) : undefined}
              onResume={canRename ? () => togglePause(true) : undefined}
            />
          ) : undefined
        }
      />

      {error ? (
        <p
          role="alert"
          className="rounded-lg bg-status-danger-surface px-4 py-3 font-secondary-body text-status-danger-content"
        >
          {ui(error)}
        </p>
      ) : null}

      {sourceQuery.isError && detail && !providerPanel(detail.type) ? (
        <div className="space-y-3">
          <p role="alert" className="text-sm text-status-danger-content">
            {ui("Source status could not be refreshed. Displayed values may be out of date.")}
          </p>
          <Button
            prominence="secondary"
            pending={sourceRefresh.pending}
            onClick={sourceRefresh.refresh}
          >
            {ui("Refresh source")}
          </Button>
        </div>
      ) : null}

      {canUpload && upload.pendingFinalize && !upload.activePendingFinalize ? (
        <div className="flex flex-col gap-3 rounded-xl border border-border-subtle bg-surface-raised px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
          <p className="text-sm text-content-secondary">
            {upload.pendingFinalize.filename} {ui("is stored and still needs finalization.")}
          </p>
          <Button asChild size="sm" prominence="secondary">
            <Link
              to="/admin/sources/$sourceId"
              params={{ sourceId: upload.pendingFinalize.sourceId }}
            >
              {ui("Return to pending upload")}
            </Link>
          </Button>
        </div>
      ) : null}

      <div className="min-w-0">
        {sourceQuery.isPending && !detail ? (
          <div className="flex justify-center px-6 py-16">
            <BrandLoader label={ui("Loading source")} />
          </div>
        ) : !detail ? (
          <div className="px-6 py-16">
            <EmptyState
              role="alert"
              icon={<DatabaseZap />}
              title={ui("Source unavailable")}
              detail={ui("It may have completed deletion.")}
              action={
                <Button
                  prominence="secondary"
                  pending={sourceRefresh.pending}
                  onClick={sourceRefresh.refresh}
                >
                  {ui("Try again")}
                </Button>
              }
            />
          </div>
        ) : (
          <Tabs
            value={
              detail.type !== "GOOGLE_DRIVE" && !fileSections.some((item) => item.value === section)
                ? "content"
                : section
            }
            onValueChange={setSection}
            className="block"
          >
            {canDelete ? (
              <ConfirmDialog
                open={sourceDialog === "delete"}
                onOpenChange={(open) => setSourceDialog(open ? "delete" : null)}
                restoreFocusRef={deleteTrigger}
                successFocusRef={backLinkRef}
                title={ui("Delete {{v1}}?", { v1: detail.name })}
                description={ui(
                  "Deleting “{{v1}}” makes every indexed document from this source unavailable. Cleanup continues asynchronously and cannot be undone.",
                  { v1: detail.name },
                )}
                confirmLabel={ui("Delete source")}
                pendingLabel={ui("Deleting source")}
                onConfirm={lifecycle.remove}
                errorMessage={(cause) => sourceMutationError(cause, "delete-source")}
              />
            ) : null}
            {sourceDialog === "name" || sourceDialog === "access" ? (
              <SourceMetadataDialog
                key={sourceDialog}
                source={detail}
                field={sourceDialog}
                disabled={busy || detail.status === "DELETING"}
                restoreFocusRef={actionsTrigger}
                onClose={() => setSourceDialog(null)}
                onSaved={refreshAuthorityViews}
              />
            ) : null}
            {!providerPanel(detail.type) ? (
              <SourceSummaryCard source={detail} className="my-6" />
            ) : null}
            {/* A paused Source is a state, not a failure: the badge carries it, and only the transient
                pausing step needs a word about the work still finishing. */}
            {detail.status === "PAUSING" ? (
              <p role="status" className="mt-4 text-sm text-content-muted">
                {ui(
                  "Pausing — waiting for in-flight file processing to finish. New synchronization and indexing work is blocked.",
                )}
              </p>
            ) : null}

            {detail.type === "GOOGLE_DRIVE" ? (
              <>
                <GoogleDrivePanel
                  source={detail}
                  sourceStale={sourceQuery.isError}
                  disabled={managementBusy || detail.status === "DELETING"}
                  onBusyChange={setDriveBusy}
                  activeSection={section}
                  content={filesPanel}
                  settings={
                    <>
                      {showGroups ? (
                        <SourceGroupsSection
                          sourceId={selectedId}
                          editable={canManageGroups}
                          onAuthorityChanged={refreshAuthorityViews}
                        />
                      ) : null}
                      {isAdministrator ? (
                        <SourceManagerSection source={detail} onAssigned={refreshAuthorityViews} />
                      ) : null}
                    </>
                  }
                  navigation={<SourceSectionTabs sections={googleDriveSections} />}
                />
                <TabsContent
                  value="history"
                  className="mt-5 rounded-xl border border-border-subtle bg-surface-raised p-4 outline-none sm:p-5"
                >
                  <SourceRunHistory key={selectedId} sourceId={selectedId} />
                </TabsContent>
              </>
            ) : detail.type === "SHAREPOINT" ? (
              <>
                <SourceSectionTabs sections={fileSections} />
                <TabsContent value="content" className="space-y-6 outline-none">
                  <SharePointPanel
                    source={detail}
                    sourceStale={sourceQuery.isError}
                    disabled={managementBusy || detail.status === "DELETING"}
                    onBusyChange={setDriveBusy}
                  />
                  {filesPanel}
                </TabsContent>
                <TabsContent
                  value="history"
                  className="mt-5 space-y-6 rounded-xl border border-border-subtle bg-surface-raised p-4 outline-none sm:p-5"
                >
                  <SourceRunHistory key={`${selectedId}-runs`} sourceId={selectedId} kinds />
                  <SourceItemHistory key={selectedId} sourceId={selectedId} />
                </TabsContent>
                <TabsContent value="settings" className="mt-5 outline-none">
                  {showGroups ? (
                    <SourceGroupsSection
                      sourceId={selectedId}
                      editable={canManageGroups}
                      onAuthorityChanged={refreshAuthorityViews}
                    />
                  ) : null}
                  {isAdministrator ? (
                    <SourceManagerSection source={detail} onAssigned={refreshAuthorityViews} />
                  ) : null}
                </TabsContent>
              </>
            ) : (
              <>
                <SourceSectionTabs sections={fileSections} />
                <TabsContent value="content">
                  {canUpload ? (
                    <SourceUploadForm
                      upload={upload}
                      fileInput={fileInput}
                      disabled={busy || detail.status === "DELETING"}
                    />
                  ) : null}
                  {filesPanel}
                </TabsContent>
                <TabsContent
                  value="history"
                  className="mt-5 rounded-xl border border-border-subtle bg-surface-raised p-4 sm:p-5"
                >
                  <SourceItemHistory key={selectedId} sourceId={selectedId} />
                </TabsContent>
                <TabsContent value="settings" className="mt-5 outline-none">
                  {showGroups ? (
                    <SourceGroupsSection
                      sourceId={selectedId}
                      editable={canManageGroups}
                      onAuthorityChanged={refreshAuthorityViews}
                    />
                  ) : null}
                  {isAdministrator ? (
                    <SourceManagerSection source={detail} onAssigned={refreshAuthorityViews} />
                  ) : null}
                </TabsContent>
              </>
            )}
            {canDelete ? (
              <DangerZone
                className="mt-8"
                icon={<Trash2 />}
                title={ui("Delete this source")}
                description={ui(
                  "Every indexed document from this source becomes unavailable. Cleanup continues in the background and cannot be undone.",
                )}
                action={
                  <Button
                    ref={deleteTrigger}
                    tone="danger"
                    prominence="secondary"
                    disabled={busy || detail.status === "DELETING"}
                    onClick={() => setSourceDialog("delete")}
                  >
                    {ui("Delete source")}
                  </Button>
                }
              />
            ) : null}
          </Tabs>
        )}
      </div>
    </SettingsLayout>
  );
}

/** Provider Sources render their own summary, stale and error banners inside their panel. */
function providerPanel(type: string) {
  return type === "GOOGLE_DRIVE" || type === "SHAREPOINT";
}
