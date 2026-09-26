import { uiLocale } from "@/i18n/format";
import { statusLabel } from "@/i18n/status-copy";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { KeyRound, RefreshCw, TriangleAlert } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { HelpPopover } from "@/components/ui/help-popover";
import { StatusBadge } from "@/components/ui/status-badge";
import type {
  SharePointConfigurationResponse,
  SharePointCredentialResponse,
  SourceSummary,
} from "@/lib/hey-api/types.gen";
import { formatSyncInterval } from "@/features/sources/shared/sync-interval";
import { sourceMutationError, sourceStatusMessage } from "@/features/sources/shared/source-errors";
import { SourceSectionIcon } from "@/features/sources/shared/source-section-icon";
import { SourceSummaryCard } from "@/features/sources/shared/source-summary-card";
import { SharePointScheduleCard } from "./sharepoint-schedule-card";
import { SharePointScopeCard } from "./sharepoint-scope-card";
import { type SharePointPanelState, useSharePointPanel } from "./use-sharepoint-panel";

export function SharePointPanel({
  source,
  sourceStale,
  disabled = false,
  onBusyChange,
}: {
  source: SourceSummary;
  sourceStale: boolean;
  disabled?: boolean;
  onBusyChange: (busy: boolean) => void;
}) {
  const ui = useAppTranslation();
  const panel = useSharePointPanel({ source, sourceStale, disabled, onBusyChange });
  const { configuration, configurationQuery } = panel;

  if (!configuration) {
    return (
      <>
        <SourceSummaryCard source={source}>
          <div>
            <dt className="text-content-muted">{ui("Synchronization interval")}</dt>
            <dd className="mt-2 text-content-muted">
              {configurationQuery.isPending ? ui("Loading…") : ui("Unavailable")}
            </dd>
          </div>
        </SourceSummaryCard>
        <div className="border-b border-border-subtle p-5 sm:p-6">
          {configurationQuery.isPending ? (
            <p role="status" className="text-sm text-content-muted">
              {ui("Loading SharePoint configuration…")}
            </p>
          ) : (
            <Alert variant="destructive">
              <AlertDescription>
                {ui(sourceMutationError(configurationQuery.error, "sharepoint"))}
              </AlertDescription>
              <div className="mt-2">
                <Button
                  size="sm"
                  prominence="secondary"
                  pending={panel.connectionRetry.pending}
                  onClick={panel.connectionRetry.refresh}
                >
                  {ui("Retry connection status")}
                </Button>
              </div>
            </Alert>
          )}
        </div>
      </>
    );
  }

  return (
    <section aria-label={ui("SharePoint configuration")} className="flex flex-col gap-6">
      <SourceSummaryCard source={source}>
        <div className="min-w-0">
          <dt className="text-content-muted">{ui("Synchronization interval")}</dt>
          <dd className="mt-2 text-content-primary">
            {formatSyncInterval(configuration.syncIntervalMinutes)}
            <span className="mt-1 block text-xs text-content-muted">
              {configuration.pruneIntervalHours === 0
                ? ui("Pruning disabled")
                : ui("Prune every {{count}} hours", { count: configuration.pruneIntervalHours })}
            </span>
          </dd>
        </div>
        <div>
          <dt className="text-content-muted">{ui("Automatic synchronization")}</dt>
          <dd className="mt-2 text-content-primary">
            {panel.sourcePaused
              ? ui("Paused with the Source")
              : configuration.syncPaused
                ? ui("Paused")
                : ui("Enabled")}
          </dd>
        </div>
      </SourceSummaryCard>
      <SynchronizationHeader
        source={source}
        configuration={configuration}
        panel={panel}
        disabled={disabled}
      />
      <SharePointNotices configuration={configuration} panel={panel} />
      <CredentialCard configuration={configuration} credential={panel.credential} />
      <SharePointScopeCard
        configuration={configuration}
        roots={panel.roots}
        policy={panel.policy.data}
        draft={panel.scopeDraft}
        canConfigure={panel.permissions.configure}
        controlsDisabled={panel.controlsDisabled}
        busy={panel.busy}
        saving={panel.activeAction === "scope"}
        onDraftChange={panel.setScopeDraft}
        onSave={panel.saveScope}
      />
      <SharePointScheduleCard
        configuration={configuration}
        canSchedule={panel.permissions.schedule}
        editing={panel.editingSchedule}
        controlsDisabled={panel.controlsDisabled}
        busy={panel.busy}
        saving={panel.activeAction === "schedule"}
        onEditingChange={panel.setEditingSchedule}
        onSave={panel.saveSchedule}
      />
    </section>
  );
}

/** The synchronization title with the status refresh, the automatic pause and the manual run. */
function SynchronizationHeader({
  source,
  configuration,
  panel,
  disabled,
}: {
  source: SourceSummary;
  configuration: SharePointConfigurationResponse;
  panel: SharePointPanelState;
  disabled: boolean;
}) {
  const ui = useAppTranslation();
  return (
    <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
      <div className="flex items-center gap-3">
        <SourceSectionIcon icon={RefreshCw} />
        <h2 className="font-heading-h3 text-content-primary">{ui("Synchronization")}</h2>
        <HelpPopover label={ui("Synchronization")}>
          <p>
            {ui(
              "A refresh reads each library's change log from where the previous run stopped, with a thirty-minute overlap, and applies deletions the log reports.",
            )}
          </p>
          <p>
            {ui(
              "A prune lists the whole scope and removes what it no longer finds, but only after the listing completes.",
            )}
          </p>
        </HelpPopover>
      </div>
      <div className="flex flex-wrap gap-2">
        <Button
          prominence="tertiary"
          disabled={disabled || panel.busy}
          pending={panel.statusRefresh.pending}
          onClick={panel.statusRefresh.refresh}
        >
          <RefreshCw data-icon="inline-start" aria-hidden="true" />
          {ui("Refresh status")}
        </Button>
        {panel.permissions.schedule ? (
          <Button
            prominence="secondary"
            disabled={panel.controlsDisabled}
            pending={panel.activeAction === "pause"}
            onClick={panel.togglePause}
          >
            {configuration.syncPaused ? ui("Resume automatic sync") : ui("Pause automatic sync")}
          </Button>
        ) : null}
        {panel.permissions.synchronize ? (
          <Button
            disabled={panel.controlsDisabled || configuration.pendingWork || source.pendingWork}
            pending={panel.activeAction === "sync" || panel.observing}
            onClick={panel.synchronize}
          >
            <RefreshCw data-icon="inline-start" aria-hidden="true" />
            {ui("Synchronize now")}
          </Button>
        ) : null}
      </div>
    </div>
  );
}

/** Stale status, a failed action, the Source's last error, a credential to fix and a scope in verification. */
function SharePointNotices({
  configuration,
  panel,
}: {
  configuration: SharePointConfigurationResponse;
  panel: SharePointPanelState;
}) {
  const ui = useAppTranslation();
  const operation = panel.tracking.operation;
  return (
    <>
      {panel.stale ? (
        <Alert variant="destructive">
          <AlertDescription>
            {ui(
              "Status could not be refreshed. Displayed values may be out of date; refresh before making changes.",
            )}
          </AlertDescription>
        </Alert>
      ) : null}
      {panel.error ? (
        <Alert variant="destructive">
          <AlertDescription>{ui(panel.error)}</AlertDescription>
        </Alert>
      ) : null}
      {configuration.errorCode ? (
        // The last run's error is a standing state of the Source, not news.
        <Alert variant="destructive" role="note">
          <AlertDescription>{ui(sourceStatusMessage(configuration.errorCode))}</AlertDescription>
        </Alert>
      ) : null}
      {configuration.credentialStatus !== "ACTIVE" ? (
        <Alert variant="warning" role="note">
          <TriangleAlert aria-hidden="true" />
          <AlertDescription>
            {ui(
              "This credential needs updating. Replace its authentication in SharePoint setup; saved scope and documents are retained.",
            )}
          </AlertDescription>
        </Alert>
      ) : null}
      {panel.verifying ? (
        <Alert variant="info" role="status">
          <AlertDescription>
            <StatusBadge tone="info">{ui("Pending validation")}</StatusBadge>{" "}
            {ui("Microsoft is resolving the submitted addresses. The saved scope still applies.")}{" "}
            <span className="break-all text-xs">
              {ui("Operation")} {operation?.id} · {ui(statusLabel(operation?.status ?? "PENDING"))}
            </span>
          </AlertDescription>
        </Alert>
      ) : null}
    </>
  );
}

function CredentialCard({
  configuration,
  credential,
}: {
  configuration: SharePointConfigurationResponse;
  credential: SharePointCredentialResponse | undefined;
}) {
  const ui = useAppTranslation();
  return (
    <Card>
      <CardHeader>
        <div className="flex items-center gap-3">
          <SourceSectionIcon icon={KeyRound} />
          <h2 className="font-heading-h3 text-content-primary">{ui("Credential")}</h2>
        </div>
      </CardHeader>
      <CardContent>
        <dl className="grid gap-4 text-sm sm:grid-cols-2">
          <div>
            <dt className="text-content-muted">{ui("Name")}</dt>
            <dd className="mt-1 wrap-anywhere text-content-primary">
              {configuration.credentialName}
            </dd>
          </div>
          <div>
            <dt className="text-content-muted">{ui("Authentication")}</dt>
            <dd className="mt-1 text-content-primary">
              {credential?.authMethod === "CERTIFICATE" ? ui("Certificate") : ui("Client secret")}
              {credential?.certificateNotAfter ? (
                <span className="mt-1 block text-xs text-content-muted">
                  {ui("Expires {{v1}}", {
                    v1: new Date(credential.certificateNotAfter).toLocaleDateString(uiLocale()),
                  })}
                </span>
              ) : null}
            </dd>
          </div>
          <div>
            <dt className="text-content-muted">{ui("SharePoint host")}</dt>
            <dd className="mt-1 wrap-anywhere text-content-primary">
              {configuration.tenantHost ?? ui("Not resolved yet")}
            </dd>
          </div>
          <div>
            <dt className="text-content-muted">{ui("Last prune")}</dt>
            <dd className="mt-1 text-content-primary">
              {configuration.lastPrunedAt ? (
                <time dateTime={configuration.lastPrunedAt}>
                  {new Date(configuration.lastPrunedAt).toLocaleString(uiLocale())}
                </time>
              ) : (
                ui("Not yet")
              )}
            </dd>
          </div>
        </dl>
      </CardContent>
    </Card>
  );
}
