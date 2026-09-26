import { useAppTranslation } from "@/i18n/use-app-translation";
import { useQueryClient } from "@tanstack/react-query";
import { Link, useParams } from "@tanstack/react-router";
import { DatabaseZap, FileText, Trash2 } from "lucide-react";
import { useRef, useState, type RefObject } from "react";
import { BrandLoader } from "@/components/brand-loader";
import { DangerZone } from "@/components/composites/danger-zone";
import { DetailHeader } from "@/components/composites/detail-header";
import { EmptyState } from "@/components/composites/empty-state";
import { SettingsLayout } from "@/components/composites/settings-layout";
import { Alert, AlertAction, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Tabs } from "@/components/ui/tabs";
import type { SourceSummary } from "@/lib/hey-api/types.gen";
import { useGlobalCapability } from "@/features/identity/application-session-context";
import { sourceMutationError } from "@/features/sources/shared/source-errors";
import { findSourceProvider } from "@/features/sources/shared/source-provider-catalog";
import {
  SourceAccessBadge,
  SourceStatusBadge,
} from "@/features/sources/shared/source-status-badge";
import { SourceSummaryCard } from "@/features/sources/shared/source-summary-card";
import { SourceActionsMenu } from "./source-actions-menu";
import { SourceDetailSections } from "./source-detail-sections";
import { type SourceMetadataField, SourceMetadataDialog } from "./source-metadata-dialog";
import { fileSourceSections } from "./source-sections";
import { type SourceDetail, useSourceDetail } from "./use-source-detail";

export function SourceDetailPage() {
  const { sourceId } = useParams({
    from: "/_authenticated/admin/sources/$sourceId",
  });
  return <SourceDetailContent key={sourceId} sourceId={sourceId} />;
}

function SourceDetailContent({ sourceId }: { sourceId: string }) {
  const ui = useAppTranslation();
  const detail = useSourceDetail(sourceId);
  const { source, sourceQuery, sourceRefresh, upload, permissions } = detail;
  const queryClient = useQueryClient();
  const backLinkRef = useRef<HTMLAnchorElement>(null);
  const actionsTrigger = useRef<HTMLButtonElement>(null);
  const [requestedDialog, setSourceDialog] = useState<SourceMetadataField | "delete" | null>(null);
  // A dialog the actor lost the right to use closes.
  const sourceDialog =
    (requestedDialog === "name" && !permissions.edit) ||
    (requestedDialog === "access" && !permissions.changeAccess) ||
    (requestedDialog === "delete" && !permissions.delete)
      ? null
      : requestedDialog;
  const provider = findSourceProvider(source?.type);
  const ProviderIcon = provider?.icon ?? FileText;
  const blocked = detail.busy || source?.status === "DELETING";

  async function refreshAuthorityViews() {
    backLinkRef.current?.focus();
    await queryClient.invalidateQueries();
  }

  return (
    <SettingsLayout wide>
      <DetailHeader
        parent={{ label: ui("Sources"), to: "/admin" }}
        backRef={backLinkRef}
        icon={source ? <ProviderIcon /> : undefined}
        iconSize="lg"
        title={source?.name}
        description={
          source ? (
            <span className="flex flex-wrap items-center gap-2">
              <SourceStatusBadge status={source.status} />
              <SourceAccessBadge access={source.access} />
              {/* The header icon shows the provider but is hidden from assistive technology. */}
              {provider ? <span className="sr-only">{ui(provider.name)}</span> : null}
            </span>
          ) : undefined
        }
        actions={
          source ? (
            <SourceActionsMenu
              triggerRef={actionsTrigger}
              disabled={blocked}
              status={source.status}
              onRename={permissions.edit ? () => setSourceDialog("name") : undefined}
              onChangeAccess={
                permissions.changeAccess ? () => setSourceDialog("access") : undefined
              }
              onPause={permissions.edit ? () => detail.togglePause(false) : undefined}
              onResume={permissions.edit ? () => detail.togglePause(true) : undefined}
            />
          ) : undefined
        }
      />

      {detail.error ? (
        <Alert variant="destructive">
          <AlertDescription>{ui(detail.error)}</AlertDescription>
        </Alert>
      ) : null}

      {sourceQuery.isError && source && !providerPanel(source.type) ? (
        <Alert variant="destructive">
          <AlertDescription>
            {ui("Source status could not be refreshed. Displayed values may be out of date.")}
          </AlertDescription>
          <AlertAction>
            <Button
              size="sm"
              prominence="secondary"
              pending={sourceRefresh.pending}
              onClick={sourceRefresh.refresh}
            >
              {ui("Refresh source")}
            </Button>
          </AlertAction>
        </Alert>
      ) : null}

      {permissions.upload && upload.pendingFinalize && !upload.activePendingFinalize ? (
        <Alert role="status">
          <AlertDescription>
            {upload.pendingFinalize.filename} {ui("is stored and still needs finalization.")}
          </AlertDescription>
          <AlertAction>
            <Button asChild size="sm" prominence="secondary">
              <Link
                to="/admin/sources/$sourceId"
                params={{ sourceId: upload.pendingFinalize.sourceId }}
              >
                {ui("Return to pending upload")}
              </Link>
            </Button>
          </AlertAction>
        </Alert>
      ) : null}

      <div className="min-w-0">
        {sourceQuery.isPending && !source ? (
          <div className="flex justify-center px-6 py-16">
            <BrandLoader label={ui("Loading source")} />
          </div>
        ) : !source ? (
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
          <SourceDetailBody
            source={source}
            detail={detail}
            sourceDialog={sourceDialog}
            onSourceDialogChange={setSourceDialog}
            backLinkRef={backLinkRef}
            actionsTrigger={actionsTrigger}
            onAuthorityChanged={refreshAuthorityViews}
          />
        )}
      </div>
    </SettingsLayout>
  );
}

/** A loaded Source: its dialogs, summary, section tabs and Danger Zone. */
function SourceDetailBody({
  source,
  detail,
  sourceDialog,
  onSourceDialogChange,
  backLinkRef,
  actionsTrigger,
  onAuthorityChanged,
}: {
  source: SourceSummary;
  detail: SourceDetail;
  sourceDialog: SourceMetadataField | "delete" | null;
  onSourceDialogChange: (dialog: SourceMetadataField | "delete" | null) => void;
  backLinkRef: RefObject<HTMLAnchorElement | null>;
  actionsTrigger: RefObject<HTMLButtonElement | null>;
  onAuthorityChanged: () => Promise<void>;
}) {
  const ui = useAppTranslation();
  const [section, setSection] = useState("content");
  const deleteTrigger = useRef<HTMLButtonElement>(null);
  const isAdministrator = useGlobalCapability("SYSTEM_ADMIN");
  const { permissions } = detail;
  const blocked = detail.busy || source.status === "DELETING";
  // Only group access reads through groups, so other Sources have none to manage.
  const showGroups = source.access === "PRIVATE";
  const sections = fileSourceSections(showGroups, isAdministrator);

  return (
    <Tabs
      value={
        source.type !== "GOOGLE_DRIVE" && !sections.some((item) => item.value === section)
          ? "content"
          : section
      }
      onValueChange={setSection}
      className="block"
    >
      {permissions.delete ? (
        <ConfirmDialog
          open={sourceDialog === "delete"}
          onOpenChange={(open) => onSourceDialogChange(open ? "delete" : null)}
          restoreFocusRef={deleteTrigger}
          successFocusRef={backLinkRef}
          title={ui("Delete {{v1}}?", { v1: source.name })}
          description={ui(
            "Deleting “{{v1}}” makes every indexed document from this source unavailable. Cleanup continues asynchronously and cannot be undone.",
            { v1: source.name },
          )}
          confirmLabel={ui("Delete source")}
          pendingLabel={ui("Deleting source")}
          onConfirm={detail.lifecycle.remove}
          errorMessage={(cause) => sourceMutationError(cause, "delete-source")}
        />
      ) : null}
      {sourceDialog === "name" || sourceDialog === "access" ? (
        <SourceMetadataDialog
          key={sourceDialog}
          source={source}
          field={sourceDialog}
          disabled={blocked}
          restoreFocusRef={actionsTrigger}
          onClose={() => onSourceDialogChange(null)}
          onSaved={onAuthorityChanged}
        />
      ) : null}
      {!providerPanel(source.type) ? <SourceSummaryCard source={source} className="my-6" /> : null}
      {/* A paused Source is a state, not a failure: the badge carries it, and only the transient
          pausing step needs a word about the work still finishing. */}
      {source.status === "PAUSING" ? (
        <p role="status" className="mt-4 text-sm text-content-muted">
          {ui(
            "Pausing — waiting for in-flight file processing to finish. New synchronization and indexing work is blocked.",
          )}
        </p>
      ) : null}
      <SourceDetailSections
        source={source}
        detail={detail}
        section={section}
        sections={sections}
        showGroups={showGroups}
        isAdministrator={isAdministrator}
        onAuthorityChanged={onAuthorityChanged}
      />
      {permissions.delete ? (
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
              disabled={blocked}
              onClick={() => onSourceDialogChange("delete")}
            >
              {ui("Delete source")}
            </Button>
          }
        />
      ) : null}
    </Tabs>
  );
}

/** Provider Sources render their own summary, stale and error banners inside their panel. */
function providerPanel(type: string) {
  return type === "GOOGLE_DRIVE" || type === "SHAREPOINT";
}
