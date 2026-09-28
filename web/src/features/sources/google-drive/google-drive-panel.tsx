import { useAppTranslation } from "@/i18n/use-app-translation";
import { Play, RefreshCw, TriangleAlert } from "lucide-react";
import type { ReactNode } from "react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { HelpPopover } from "@/components/ui/help-popover";
import { TabsContent } from "@/components/ui/tabs";
import type { SourceSummary } from "@/lib/hey-api/types.gen";
import { sourceMutationError, sourceStatusMessage } from "@/features/sources/shared/source-errors";
import { SourceSummaryCard } from "@/features/sources/shared/source-summary-card";
import { GoogleDriveConnectionSection } from "./google-drive-connection-section";
import { GoogleDriveSelectionPanel } from "./google-drive-selection-panel";
import { GoogleDriveSyncInterval } from "./google-drive-sync-interval";
import { type GoogleDrivePanelState, useGoogleDrivePanel } from "./use-google-drive-panel";

export function GoogleDrivePanel({
  source,
  sourceStale,
  disabled = false,
  onBusyChange,
  activeSection = "content",
  navigation,
  content,
  settings,
}: {
  source: SourceSummary;
  sourceStale: boolean;
  disabled?: boolean;
  onBusyChange: (busy: boolean) => void;
  activeSection?: string;
  navigation?: ReactNode;
  content: ReactNode;
  settings: ReactNode;
}) {
  const ui = useAppTranslation();
  const drive = useGoogleDrivePanel({ source, sourceStale, disabled, onBusyChange });
  const { configuration, permissions } = drive;

  if (!configuration) {
    return (
      <>
        <SourceSummaryCard source={source} className="mt-6">
          <div>
            <dt className="text-content-muted">{ui("Automatic synchronization")}</dt>
            <dd className="mt-1 flex min-h-8 items-center text-content-muted">
              {drive.configurationQuery.isPending ? ui("Loading…") : ui("Unavailable")}
            </dd>
          </div>
        </SourceSummaryCard>
        <div className="border-b border-border-subtle p-5 sm:p-6">
          {drive.configurationQuery.isPending ? (
            <p role="status" className="text-sm text-content-muted">
              {ui("Loading Google Drive connection…")}
            </p>
          ) : (
            <Alert variant="destructive">
              <AlertDescription>
                {ui(sourceMutationError(drive.configurationQuery.error, "google-drive"))}
              </AlertDescription>
              <div className="mt-2">
                <Button
                  size="sm"
                  prominence="secondary"
                  pending={drive.statusRefresh.pending}
                  onClick={drive.statusRefresh.refresh}
                >
                  {ui("Retry connection status")}
                </Button>
              </div>
            </Alert>
          )}
        </div>
        {navigation}
        <TabsContent value="content">{content}</TabsContent>
        <TabsContent value="settings">{settings}</TabsContent>
      </>
    );
  }

  return (
    <section aria-label={ui("Google Drive configuration")} className="mt-6 flex flex-col gap-5">
      <SourceSummaryCard
        source={source}
        header={<SynchronizationHeader source={source} drive={drive} disabled={disabled} />}
      >
        <GoogleDriveSyncInterval
          sourceId={source.id}
          resourceKey={drive.resourceKey}
          configuration={configuration}
          canSchedule={permissions.schedule}
          canPause={permissions.pause}
          disabled={disabled}
          busy={drive.busy}
          stale={drive.stale}
          activeAction={drive.activeAction}
          perform={drive.perform}
          onTogglePause={drive.togglePause}
          incorporate={drive.incorporateConfiguration}
          refresh={drive.refresh}
          reloadConfiguration={drive.reloadConfiguration}
        />
      </SourceSummaryCard>
      {drive.stale ? (
        <Alert variant="destructive">
          <AlertDescription>
            {ui(
              "Status could not be refreshed. Displayed values may be out of date; refresh before making changes.",
            )}
          </AlertDescription>
        </Alert>
      ) : null}
      {drive.error ? (
        <Alert variant="destructive">
          <AlertDescription>{ui(drive.error)}</AlertDescription>
        </Alert>
      ) : null}
      {!drive.connected ? (
        <Alert variant="warning" role="note">
          <TriangleAlert aria-hidden="true" />
          <AlertDescription>
            {drive.serviceAccount
              ? ui(
                  "Replace the key of this service account in Google Drive credentials to save links and synchronize. Saved roots are retained.",
                )
              : ui(
                  "Reconnect the same Google account to save links and synchronize. Saved roots are retained.",
                )}
          </AlertDescription>
        </Alert>
      ) : null}
      {navigation}
      {/* Both panels stay mounted so an edit in progress survives switching tabs. */}
      <TabsContent value="settings" forceMount hidden={activeSection !== "settings"}>
        <div className="flex flex-col gap-5">
          <GoogleDriveConnectionSection
            sourceId={source.id}
            resourceKey={`${drive.resourceKey}:${JSON.stringify(source.permissions)}`}
            configuration={configuration}
            credential={drive.credential}
            credentialsUnavailable={drive.credentials.isError}
            credentialRefresh={drive.credentialRefresh}
            connected={drive.connected}
            canReauthorize={permissions.reauthorize}
            canReplaceClient={permissions.replaceClient}
            canRevoke={permissions.revoke}
            controlsDisabled={drive.controlsDisabled}
            hasSelectionChanges={drive.editingSelection}
            activeAction={drive.activeAction}
            leaving={drive.leaving}
            stale={drive.stale}
            perform={drive.perform}
            onLeavingChange={drive.setLeaving}
          />
          {settings}
        </div>
      </TabsContent>
      <TabsContent value="content" forceMount hidden={activeSection !== "content"}>
        {permissions.configure ? (
          <Card size="sm">
            <CardContent>
              <GoogleDriveSelectionPanel
                key={drive.selectionKey}
                sourceId={source.id}
                configuration={configuration}
                disabled={drive.controlsDisabled || !drive.connected}
                onEditingChange={drive.setEditingSelection}
                onBusyChange={drive.setSelectionBusy}
                onActivated={drive.refresh}
              />
            </CardContent>
          </Card>
        ) : (
          <section aria-label={ui("Saved Drive selection")} className="flex flex-col gap-3">
            <h2 className="font-heading-h3">{ui("Saved selection")}</h2>
            <p className="text-sm text-content-muted">
              {configuration.scopeMode === "GENERAL"
                ? ui("Whole Google account")
                : ui("Specific files and folders")}
              {ui(". Selection configuration is read-only.")}
            </p>
            <p className="text-sm text-content-primary">
              {ui("{{v1}} folders · {{v2}} files · {{v3}} approved linked documents", {
                v1: configuration.counts.folders,
                v2: configuration.counts.files,
                v3: configuration.counts.approvedLinkedDocuments,
              })}
            </p>
          </section>
        )}
        {content}
      </TabsContent>
    </section>
  );
}

/** The synchronization title with the status refresh and the manual synchronization. */
function SynchronizationHeader({
  source,
  drive,
  disabled,
}: {
  source: SourceSummary;
  drive: GoogleDrivePanelState;
  disabled: boolean;
}) {
  const ui = useAppTranslation();
  const configuration = drive.configuration;
  if (!configuration) return null;
  return (
    <>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-1">
          <h2 className="font-heading-h3 text-content-primary">{ui("Synchronization")}</h2>
          <HelpPopover label={ui("Synchronization")}>
            <p>
              {ui(
                "Automatic sync uses this Source’s saved interval. Saving an interval schedules the next run from the save time and does not interrupt current work. Start time depends on availability and pending work. Synchronize now requests a run.",
              )}
            </p>
          </HelpPopover>
        </div>
        <div className="flex items-center gap-2">
          <Button
            prominence="tertiary"
            disabled={disabled || drive.busy}
            pending={drive.statusRefresh.pending}
            onClick={drive.statusRefresh.refresh}
          >
            <RefreshCw data-icon="inline-start" aria-hidden="true" />
            {ui("Refresh status")}
          </Button>
          {drive.connected && drive.permissions.synchronize ? (
            <Button
              disabled={
                drive.controlsDisabled ||
                drive.editingSelection ||
                configuration.pendingWork ||
                source.pendingWork
              }
              pending={drive.activeAction === "sync" || drive.observingSync}
              onClick={drive.synchronize}
            >
              <Play data-icon="inline-start" aria-hidden="true" />
              {ui("Synchronize now")}
            </Button>
          ) : null}
        </div>
      </div>
      {configuration.errorCode ? (
        <p className="mt-2 text-sm text-status-danger-content">
          {ui(sourceStatusMessage(configuration.errorCode))}
        </p>
      ) : null}
    </>
  );
}
