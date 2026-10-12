import { statusLabel } from "@/i18n/status-copy";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { BookOpen, Folder, FolderTree, KeyRound, RefreshCw, TriangleAlert } from "lucide-react";
import { useState } from "react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardAction, CardContent, CardHeader } from "@/components/ui/card";
import { FieldError } from "@/components/ui/field";
import { StatusBadge } from "@/components/ui/status-badge";
import type {
  LarkConfigurationResponse,
  LarkCredentialResponse,
  SourceSummary,
} from "@/lib/hey-api/types.gen";
import { formatSyncInterval } from "@/features/sources/shared/sync-interval";
import { sourceMutationError, sourceStatusMessage } from "@/features/sources/shared/source-errors";
import { SourceSectionIcon } from "@/features/sources/shared/source-section-icon";
import { SourceSummaryCard } from "@/features/sources/shared/source-summary-card";
import { LarkMissingScopes } from "./lark-app-guide";
import { LarkIntervalField, LarkScopeFields } from "./lark-scope-fields";
import {
  emptyLarkScopeDraft,
  larkInterval,
  larkIntervalMinutes,
  larkRegion,
  larkScopeError,
} from "./lark-scope";
import { type LarkPanelState, useLarkPanel } from "./use-lark-panel";

export function LarkPanel({
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
  const panel = useLarkPanel({ source, sourceStale, disabled, onBusyChange });
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
              {ui("Loading Lark configuration…")}
            </p>
          ) : (
            <Alert variant="destructive">
              <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
                <AlertDescription>
                  {ui(sourceMutationError(configurationQuery.error, "lark"))}
                </AlertDescription>
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
    <section aria-label={ui("Lark configuration")} className="flex flex-col gap-6">
      <SourceSummaryCard source={source}>
        <div className="min-w-0">
          <dt className="text-content-muted">{ui("Synchronization interval")}</dt>
          <dd className="mt-2 text-content-primary">
            {formatSyncInterval(configuration.syncIntervalMinutes)}
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
      <LarkNotices configuration={configuration} panel={panel} />
      <CredentialCard configuration={configuration} credential={panel.credential} />
      <ScopeCard configuration={configuration} panel={panel} />
      <ScheduleCard configuration={configuration} panel={panel} />
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
  configuration: LarkConfigurationResponse;
  panel: LarkPanelState;
  disabled: boolean;
}) {
  const ui = useAppTranslation();
  return (
    <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
      <div className="flex items-center gap-3">
        <SourceSectionIcon icon={RefreshCw} />
        <h2 className="font-heading-h3 text-content-primary">{ui("Synchronization")}</h2>
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
function LarkNotices({
  configuration,
  panel,
}: {
  configuration: LarkConfigurationResponse;
  panel: LarkPanelState;
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
              "This credential needs updating. Replace its App Secret in Lark setup; saved scope and documents are retained.",
            )}
          </AlertDescription>
        </Alert>
      ) : null}
      {panel.credential && panel.credential.missingScopes.length > 0 ? (
        <LarkMissingScopes scopes={panel.credential.missingScopes} />
      ) : null}
      {panel.verifying ? (
        <Alert variant="info" role="status">
          <AlertDescription>
            <StatusBadge tone="info">{ui("Pending validation")}</StatusBadge>{" "}
            {ui(
              "Lark is checking the submitted spaces and folders. The saved scope still applies.",
            )}{" "}
            <span className="text-xs break-all">
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
  configuration: LarkConfigurationResponse;
  credential: LarkCredentialResponse | undefined;
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
            <dt className="text-content-muted">{ui("App ID")}</dt>
            <dd className="mt-1 font-mono text-xs wrap-anywhere text-content-primary">
              {credential?.appId ?? ui("Unavailable")}
            </dd>
          </div>
          <div>
            <dt className="text-content-muted">{ui("Bot")}</dt>
            <dd className="mt-1 wrap-anywhere text-content-primary">
              {credential?.botName ?? ui("Not resolved yet")}
            </dd>
          </div>
        </dl>
      </CardContent>
    </Card>
  );
}

/** The saved spaces and folders, and the draft that replaces them as a whole. */
function ScopeCard({
  configuration,
  panel,
}: {
  configuration: LarkConfigurationResponse;
  panel: LarkPanelState;
}) {
  const ui = useAppTranslation();
  const draft = panel.scopeDraft;
  const saved = panel.roots.data ?? [];
  const region = larkRegion(panel.credential?.region);
  const scopeError = draft ? larkScopeError(draft, region) : null;
  const saving = panel.activeAction === "scope";

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center gap-3">
          <SourceSectionIcon icon={FolderTree} />
          <h2 className="font-heading-h3 text-content-primary">{ui("Saved scope")}</h2>
        </div>
        {panel.permissions.configure && !draft ? (
          <CardAction>
            <Button
              prominence="secondary"
              disabled={panel.controlsDisabled || !panel.roots.isSuccess}
              onClick={() =>
                panel.setScopeDraft({
                  ...emptyLarkScopeDraft(),
                  spaceIds: saved
                    .filter((root) => root.kind === "WIKI_SPACE")
                    .map((root) => root.token),
                  folderLinksText: saved
                    .filter((root) => root.kind === "DRIVE_FOLDER")
                    .map((root) => root.token)
                    .join("\n"),
                  interval: larkInterval(configuration.syncIntervalMinutes),
                })
              }
            >
              {ui("Edit scope")}
            </Button>
          </CardAction>
        ) : null}
      </CardHeader>
      <CardContent>
        {draft ? (
          <form
            className="flex flex-col gap-5 rounded-xl border border-border-subtle p-4 sm:p-5"
            noValidate
            onSubmit={(event) => {
              event.preventDefault();
              if (!scopeError) panel.saveScope();
            }}
          >
            <LarkScopeFields
              credentialId={configuration.credentialId}
              botName={panel.credential?.botName}
              savedRoots={saved}
              draft={draft}
              disabled={panel.busy}
              onChange={panel.setScopeDraft}
            />
            {scopeError ? <FieldError role="alert">{ui(scopeError)}</FieldError> : null}
            <div className="flex flex-wrap gap-2">
              <Button type="submit" pending={saving} disabled={panel.busy || Boolean(scopeError)}>
                {ui("Save scope")}
              </Button>
              <Button
                prominence="tertiary"
                disabled={panel.busy}
                onClick={() => panel.setScopeDraft(null)}
              >
                {ui("Cancel")}
              </Button>
            </div>
          </form>
        ) : panel.roots.isPending ? (
          <p role="status" className="text-sm text-content-muted">
            {ui("Loading saved spaces and folders…")}
          </p>
        ) : panel.roots.isError ? (
          <p role="alert" className="text-sm text-status-danger-content">
            {ui(
              "Saved spaces and folders could not be loaded. Refresh status before editing the scope.",
            )}
          </p>
        ) : (
          <ul className="flex flex-col gap-2 text-sm">
            {saved.map((root) => (
              <li key={`${root.kind}:${root.token}`} className="flex items-center gap-2">
                {root.kind === "WIKI_SPACE" ? (
                  <BookOpen className="size-4 shrink-0 text-content-muted" aria-hidden="true" />
                ) : (
                  <Folder className="size-4 shrink-0 text-content-muted" aria-hidden="true" />
                )}
                <span className="sr-only">
                  {root.kind === "WIKI_SPACE" ? ui("Wiki space") : ui("Drive folder")}
                </span>
                <span className="min-w-0 wrap-anywhere text-content-primary">
                  {root.displayName ?? root.token}
                </span>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}

/** The automatic interval, edited against the schedule revision it was opened from. */
function ScheduleCard({
  configuration,
  panel,
}: {
  configuration: LarkConfigurationResponse;
  panel: LarkPanelState;
}) {
  const ui = useAppTranslation();
  const [draft, setDraft] = useState<{
    interval: ReturnType<typeof larkInterval>;
    scheduleRevision: number;
  } | null>(null);
  const minutes = draft ? larkIntervalMinutes(draft.interval) : null;
  const invalid = draft !== null && minutes === null;
  const conflicted = draft !== null && draft.scheduleRevision !== configuration.scheduleRevision;

  return (
    <Card>
      <CardHeader>
        <h2 className="font-heading-h3 text-content-primary">{ui("Schedule")}</h2>
        {panel.permissions.schedule && !draft ? (
          <CardAction>
            <Button
              prominence="secondary"
              disabled={panel.controlsDisabled}
              onClick={() =>
                setDraft({
                  interval: larkInterval(configuration.syncIntervalMinutes),
                  scheduleRevision: configuration.scheduleRevision,
                })
              }
            >
              {ui("Edit interval")}
            </Button>
          </CardAction>
        ) : null}
      </CardHeader>
      {draft ? (
        <CardContent>
          <form
            className="flex flex-col gap-4"
            noValidate
            onSubmit={(event) => {
              event.preventDefault();
              if (minutes === null || conflicted) return;
              panel.saveSchedule(minutes, draft.scheduleRevision);
              setDraft(null);
            }}
          >
            <LarkIntervalField
              interval={draft.interval}
              disabled={panel.busy}
              onChange={(interval) => setDraft({ ...draft, interval })}
            />
            {invalid ? (
              <FieldError role="alert">
                {ui("Choose a synchronization interval between one minute and one year.")}
              </FieldError>
            ) : conflicted ? (
              <FieldError role="alert">
                {ui(
                  "The schedule changed in another session. Reload the saved interval before trying again.",
                )}
              </FieldError>
            ) : null}
            <div className="flex flex-wrap gap-2">
              <Button
                type="submit"
                pending={panel.activeAction === "schedule"}
                disabled={panel.busy || invalid || conflicted}
              >
                {ui("Save interval")}
              </Button>
              <Button prominence="tertiary" disabled={panel.busy} onClick={() => setDraft(null)}>
                {ui("Cancel")}
              </Button>
            </div>
          </form>
        </CardContent>
      ) : null}
    </Card>
  );
}
