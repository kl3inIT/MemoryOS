import { appText } from "@/i18n/app-text";
import type { AppCopy } from "@/i18n/app-text";
import { uiLocale } from "@/i18n/format";
import { statusLabel } from "@/i18n/status-copy";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FolderTree, KeyRound, RefreshCw } from "lucide-react";
import { useLayoutEffect, useRef, useState } from "react";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { Button } from "@/components/ui/button";
import { HelpPopover } from "@/components/ui/help-popover";
import { StatusBadge } from "@/components/ui/status-badge";
import { useCapabilityAuthority } from "@/features/identity/application-session-context";
import { sameOriginMutationHeaders } from "@/lib/api";
import { can } from "@/lib/resource-permissions";
import { captureWorkflowFailure } from "@/lib/sentry";
import {
  getSharePointConfigurationOptions,
  getSharePointRootsOptions,
  getSharePointSelectionPolicyOptions,
  getSharePointSelectionRequestOptions,
  listSharePointCredentialsOptions,
  listSourcesQueryKey,
  replaceSharePointScopeMutation,
  synchronizeSharePointSourceMutation,
  updateSharePointPauseMutation,
  updateSharePointScheduleMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type {
  SharePointConfigurationResponse,
  SharePointRootPageResponse,
  SourceOperation,
  SourceSummary,
} from "@/lib/hey-api/types.gen";
import { sourceMutationError, sourceStatusMessage } from "./source-errors";
import { SourceSectionIcon } from "./source-section-icon";
import { SourceSummaryCard } from "./source-summary-card";
import { useSourceSelectionOperation } from "./source-selection-operation";
import { waitForSourceOperation } from "./source-operations";
import { SharePointScheduleFields } from "./sharepoint-schedule-fields";
import { SharePointScopeFields } from "./sharepoint-scope-fields";
import {
  emptySharePointScopeDraft,
  sharePointScheduleError,
  sharePointScopeError,
  sharePointScopeRequest,
  type SharePointScopeDraft,
} from "./sharepoint-scope";

type PanelAction = "sync" | "pause" | "schedule" | "scope";

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

  const queryClient = useQueryClient();
  const notify = useActionNotifications();
  const authority = useCapabilityAuthority("SOURCES_MANAGE");
  const canConfigure = can(source, "manageConfiguration");
  const canSchedule = can(source, "edit");
  const canSynchronize = can(source, "edit");
  const configurationQuery = useQuery({
    ...getSharePointConfigurationOptions({ path: { sourceId: source.id } }),
    retry: false,
    refetchInterval: (query) =>
      query.state.data?.pendingWork || source.pendingWork ? 1_500 : 5_000,
  });
  const configuration = configurationQuery.data;
  const credentials = useQuery({
    ...listSharePointCredentialsOptions(),
    enabled: authority !== "none",
    retry: false,
  });
  const credential = credentials.data?.find((entry) => entry.id === configuration?.credentialId);
  const policy = useQuery({ ...getSharePointSelectionPolicyOptions(), retry: false });
  const roots = useQuery({
    ...getSharePointRootsOptions({ path: { sourceId: source.id }, query: { size: 50 } }),
    enabled: configuration?.scopeMode === "SPECIFIC",
    retry: false,
  });
  const synchronize = useMutation(synchronizeSharePointSourceMutation());
  const updatePause = useMutation({ ...updateSharePointPauseMutation(), retry: false });
  const updateSchedule = useMutation({ ...updateSharePointScheduleMutation(), retry: false });
  const replaceScope = useMutation({ ...replaceSharePointScopeMutation(), retry: false });
  const tracking = useSourceSelectionOperation({
    provider: "sharepoint",
    scope: source.id,
    pending: configuration?.pendingSelectionOperation,
    recover: (requestId) => getSharePointSelectionRequestOptions({ path: { requestId } }),
  });
  const [activeAction, setActiveAction] = useState<PanelAction | null>(null);
  const [error, setError] = useState<AppCopy | null>(null);
  const [scheduleDraft, setScheduleDraft] = useState<{
    syncIntervalMinutes: string;
    pruneIntervalHours: string;
    scheduleRevision: number;
  } | null>(null);
  const [scopeDraft, setScopeDraft] = useState<SharePointScopeDraft | null>(null);
  const [observing, setObserving] = useState(false);
  const controller = useRef<AbortController | null>(null);
  const active = useRef(true);
  const busy = Boolean(activeAction) || observing;
  const stale = sourceStale || configurationQuery.isError;
  const verifying = Boolean(tracking.operation && !tracking.terminal);
  const controlsDisabled = disabled || busy || stale || verifying;

  useLayoutEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
      controller.current?.abort();
    };
  }, []);

  useLayoutEffect(() => {
    onBusyChange(busy);
    return () => onBusyChange(false);
  }, [busy, onBusyChange]);

  const terminal = tracking.terminal ? tracking.operation : null;
  const [settled, setSettled] = useState<string | null>(null);
  if (terminal && settled !== terminal.id) {
    setSettled(terminal.id);
    if (terminal.status !== "SUCCEEDED")
      setError(
        terminal.status === "SUPERSEDED"
          ? "This scope change was superseded or cancelled. The saved scope is unchanged."
          : sourceStatusMessage(terminal.errorCode ?? "SOURCE_SHAREPOINT_SELECTION_FAILED"),
      );
    void refresh();
  }

  async function refresh() {
    await Promise.all([
      configurationQuery.refetch(),
      roots.refetch(),
      queryClient.invalidateQueries({ queryKey: listSourcesQueryKey() }),
    ]);
  }

  function run(action: PanelAction, task: () => Promise<void>) {
    if (busy) return;
    setActiveAction(action);
    setError(null);
    void task()
      .catch((cause: unknown) => {
        if (active.current)
          setError(
            sourceMutationError(
              cause,
              action === "schedule" ? "sharepoint-schedule" : "sharepoint",
            ),
          );
      })
      .finally(() => {
        if (active.current) setActiveAction(null);
      });
  }

  async function sync() {
    const operation = await synchronize.mutateAsync({
      path: { sourceId: source.id },
      headers: sameOriginMutationHeaders,
    });
    notify({ tone: "info", title: "Synchronization requested", description: source.name });
    void observe(operation);
    await refresh();
  }

  async function observe(operation: SourceOperation) {
    const own = new AbortController();
    controller.current?.abort();
    controller.current = own;
    setObserving(true);
    try {
      const completed = await waitForSourceOperation(operation, own.signal);
      if (completed.status === "SUCCEEDED") {
        notify({
          tone: "success",
          title: "Synchronization complete",
          description: appText("{{v1}}: content is synchronized. Indexing may still be running.", {
            v1: source.name,
          }),
        });
      } else if (completed.status === "SUPERSEDED") {
        notify({
          tone: "info",
          title: "Synchronization superseded",
          description: appText("{{v1}}: this request was replaced by newer work.", {
            v1: source.name,
          }),
        });
      } else {
        const failureKind = completed.errorCode ?? "SOURCE_SYNC_FAILED";
        captureWorkflowFailure(new Error("SharePoint synchronization failed"), {
          workflow: "sharepoint-sync",
          stage: "operation-complete",
          failureKind,
        });
        notify({
          tone: "error",
          title: "Synchronization failed",
          description: appText(sourceStatusMessage(failureKind)),
        });
      }
      await refresh();
    } catch (cause) {
      if (!own.signal.aborted)
        captureWorkflowFailure(cause, { workflow: "sharepoint-sync", stage: "operation-status" });
    } finally {
      if (controller.current === own) {
        controller.current = null;
        if (active.current) setObserving(false);
      }
    }
  }

  async function togglePause() {
    if (!configuration) return;
    await updatePause.mutateAsync({
      path: { sourceId: source.id },
      headers: sameOriginMutationHeaders,
      body: { expectedRevision: configuration.scheduleRevision, paused: !configuration.syncPaused },
    });
    await refresh();
  }

  async function saveSchedule() {
    if (!configuration || !scheduleDraft) return;
    await updateSchedule.mutateAsync({
      path: { sourceId: source.id },
      headers: {
        ...sameOriginMutationHeaders,
        "If-Match": `"${scheduleDraft.scheduleRevision}"`,
      },
      body: {
        syncIntervalMinutes: Number(scheduleDraft.syncIntervalMinutes),
        pruneIntervalHours: Number(scheduleDraft.pruneIntervalHours),
      },
    });
    if (!active.current) return;
    setScheduleDraft(null);
    await refresh();
  }

  async function saveScope() {
    if (!configuration || !scopeDraft) return;
    const requestId = tracking.begin(true);
    const receipt = await replaceScope.mutateAsync({
      path: { sourceId: source.id },
      headers: {
        ...sameOriginMutationHeaders,
        "If-Match": `"${configuration.scopeRevision}"`,
      },
      body: {
        requestId,
        expectedCredentialRevision: configuration.credentialRevision,
        scope: sharePointScopeRequest(scopeDraft),
      },
    });
    if (!active.current) return;
    tracking.accept(receipt);
    setScopeDraft(null);
    await refresh();
  }

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
            <div className="space-y-3">
              <p role="alert" className="text-sm text-status-danger-content">
                {ui(sourceMutationError(configurationQuery.error, "sharepoint"))}
              </p>
              <Button
                prominence="secondary"
                pending={configurationQuery.isFetching}
                onClick={() => void configurationQuery.refetch()}
              >
                {ui("Retry connection status")}
              </Button>
            </div>
          )}
        </div>
      </>
    );
  }

  const scheduleError = scheduleDraft ? sharePointScheduleError(scheduleDraft) : null;
  const scopeError = scopeDraft ? sharePointScopeError(scopeDraft, policy.data) : null;

  return (
    <section aria-label={ui("SharePoint configuration")} className="space-y-6">
      <SourceSummaryCard source={source}>
        <div className="min-w-0">
          <dt className="text-content-muted">{ui("Synchronization interval")}</dt>
          <dd className="mt-2 text-content-primary">
            {ui("{{count}} minutes", { count: configuration.syncIntervalMinutes })}
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
            {configuration.syncPaused ? ui("Paused") : ui("Enabled")}
          </dd>
        </div>
      </SourceSummaryCard>

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
            disabled={disabled || busy}
            pending={configurationQuery.isFetching}
            onClick={() => void refresh()}
          >
            <RefreshCw /> {ui("Refresh status")}
          </Button>
          {canSchedule ? (
            <Button
              prominence="secondary"
              disabled={controlsDisabled}
              pending={activeAction === "pause"}
              onClick={() => run("pause", togglePause)}
            >
              {configuration.syncPaused ? ui("Resume automatic sync") : ui("Pause automatic sync")}
            </Button>
          ) : null}
          {canSynchronize ? (
            <Button
              disabled={controlsDisabled || configuration.pendingWork || source.pendingWork}
              pending={activeAction === "sync" || observing}
              onClick={() => run("sync", sync)}
            >
              <RefreshCw /> {ui("Synchronize now")}
            </Button>
          ) : null}
        </div>
      </div>

      {stale ? (
        <p role="alert" className="text-sm text-status-danger-content">
          {ui(
            "Status could not be refreshed. Displayed values may be out of date; refresh before making changes.",
          )}
        </p>
      ) : null}
      {error ? (
        <p role="alert" className="text-sm text-status-danger-content">
          {ui(error)}
        </p>
      ) : null}
      {configuration.errorCode ? (
        <p className="rounded-lg bg-status-danger-surface p-4 text-sm text-status-danger-content">
          {ui(sourceStatusMessage(configuration.errorCode))}
        </p>
      ) : null}
      {configuration.credentialStatus !== "ACTIVE" ? (
        <p className="rounded-lg bg-status-warning-surface p-4 text-sm text-status-warning-content">
          {ui(
            "This credential needs updating. Replace its authentication in SharePoint setup; saved scope and documents are retained.",
          )}
        </p>
      ) : null}
      {verifying ? (
        <p role="status" className="rounded-lg border border-border-subtle bg-surface-subtle p-4 text-sm">
          <StatusBadge tone="info">{ui("Pending validation")}</StatusBadge>{" "}
          {ui("Microsoft is resolving the submitted addresses. The saved scope still applies.")}{" "}
          <span className="break-all text-xs text-content-muted">
            {ui("Operation")} {tracking.operation?.id} ·{" "}
            {ui(statusLabel(tracking.operation?.status ?? "PENDING"))}
          </span>
        </p>
      ) : null}

      <section className="space-y-3 border-b border-border-subtle pb-5">
        <div className="flex items-center gap-3">
          <SourceSectionIcon icon={KeyRound} />
          <h2 className="font-heading-h3 text-content-primary">{ui("Credential")}</h2>
        </div>
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
      </section>

      <section className="space-y-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <SourceSectionIcon icon={FolderTree} />
            <h2 className="font-heading-h3 text-content-primary">{ui("Saved scope")}</h2>
          </div>
          {canConfigure && !scopeDraft ? (
            <Button
              prominence="secondary"
              disabled={controlsDisabled}
              onClick={() =>
                setScopeDraft({
                  ...emptySharePointScopeDraft(),
                  scopeMode: configuration.scopeMode,
                  siteUrlsText: (roots.data?.roots ?? []).map((root) => root.url).join("\n"),
                  excludedSitesText: configuration.excludedSites.join("\n"),
                  excludedPathsText: configuration.excludedPaths.join("\n"),
                  includeDocuments: configuration.includeDocuments,
                  includePages: configuration.includePages,
                  syncIntervalMinutes: String(configuration.syncIntervalMinutes),
                  pruneIntervalHours: String(configuration.pruneIntervalHours),
                })
              }
            >
              {ui("Edit scope")}
            </Button>
          ) : null}
        </div>

        {scopeDraft ? (
          <form
            className="space-y-5 rounded-xl border border-border-subtle p-4 sm:p-5"
            onSubmit={(event) => {
              event.preventDefault();
              if (!scopeError) run("scope", saveScope);
            }}
          >
            <SharePointScopeFields
              draft={scopeDraft}
              policy={policy.data}
              disabled={busy}
              onChange={setScopeDraft}
            />
            {roots.data && roots.data.total > roots.data.roots.length ? (
              <p role="alert" className="text-sm text-status-warning-content">
                {ui(
                  "Only the first {{count}} addresses are shown. Saving replaces the whole scope with what is listed here.",
                  { count: roots.data.roots.length },
                )}
              </p>
            ) : null}
            {scopeError ? (
              <p role="alert" className="text-sm text-status-danger-content">
                {ui(scopeError)}
              </p>
            ) : null}
            <div className="flex flex-wrap gap-2">
              <Button type="submit" pending={activeAction === "scope"} disabled={busy || Boolean(scopeError)}>
                {ui("Save scope")}
              </Button>
              <Button prominence="tertiary" disabled={busy} onClick={() => setScopeDraft(null)}>
                {ui("Cancel")}
              </Button>
            </div>
            <p className="text-sm text-content-muted">
              {ui(
                "Saving answers with a receipt and resolves every address with Microsoft. The running synchronization is cancelled and the Source reads its content again.",
              )}
            </p>
          </form>
        ) : (
          <SavedScope configuration={configuration} roots={roots} />
        )}
      </section>

      <section className="space-y-4 border-t border-border-subtle pt-5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="font-heading-h3 text-content-primary">{ui("Schedule")}</h2>
          {canSchedule && !scheduleDraft ? (
            <Button
              prominence="secondary"
              disabled={controlsDisabled}
              onClick={() =>
                setScheduleDraft({
                  syncIntervalMinutes: String(configuration.syncIntervalMinutes),
                  pruneIntervalHours: String(configuration.pruneIntervalHours),
                  scheduleRevision: configuration.scheduleRevision,
                })
              }
            >
              {ui("Edit intervals")}
            </Button>
          ) : null}
        </div>
        {scheduleDraft ? (
          <form
            className="space-y-4"
            onSubmit={(event) => {
              event.preventDefault();
              if (!scheduleError) run("schedule", saveSchedule);
            }}
          >
            <SharePointScheduleFields
              draft={scheduleDraft}
              disabled={busy}
              onChange={(next) => setScheduleDraft({ ...scheduleDraft, ...next })}
            />
            {scheduleError ? (
              <p role="alert" className="text-sm text-status-danger-content">
                {ui(scheduleError)}
              </p>
            ) : null}
            <div className="flex flex-wrap gap-2">
              <Button
                type="submit"
                pending={activeAction === "schedule"}
                disabled={busy || Boolean(scheduleError)}
              >
                {ui("Save intervals")}
              </Button>
              <Button prominence="tertiary" disabled={busy} onClick={() => setScheduleDraft(null)}>
                {ui("Cancel")}
              </Button>
            </div>
          </form>
        ) : null}
      </section>
    </section>
  );
}

function SavedScope({
  configuration,
  roots,
}: {
  configuration: SharePointConfigurationResponse;
  roots: {
    data: SharePointRootPageResponse | undefined;
    isPending: boolean;
    isError: boolean;
  };
}) {
  const ui = useAppTranslation();

  return (
    <div className="space-y-3 text-sm">
      <p className="text-content-primary">
        {configuration.scopeMode === "ALL_SITES"
          ? ui("All sites the Entra application can read")
          : ui("{{count}} sites, libraries or folders", { count: configuration.rootCount })}
      </p>
      <p className="text-content-muted">
        {configuration.includeDocuments && configuration.includePages
          ? ui("Documents and site pages")
          : configuration.includePages
            ? ui("Site pages")
            : ui("Documents")}
      </p>
      {configuration.scopeMode === "SPECIFIC" ? (
        roots.isPending ? (
          <p role="status" className="text-content-muted">
            {ui("Loading saved addresses…")}
          </p>
        ) : roots.isError ? (
          <p role="alert" className="text-status-danger-content">
            {ui("Saved addresses could not be loaded. Refresh status before editing the scope.")}
          </p>
        ) : (
          <ul className="space-y-1">
            {roots.data?.roots.map((root) => (
              <li key={root.url} className="flex flex-wrap items-center gap-2">
                <StatusBadge tone={root.verified ? "neutral" : "warning"}>
                  {root.kind === "SITE"
                    ? ui("Site")
                    : root.kind === "LIBRARY"
                      ? ui("Library")
                      : ui("Folder")}
                </StatusBadge>
                <span className="min-w-0 wrap-anywhere text-content-primary">
                  {root.displayName ?? root.url}
                </span>
                <span className="min-w-0 wrap-anywhere text-xs text-content-muted">{root.url}</span>
              </li>
            ))}
          </ul>
        )
      ) : null}
      {configuration.excludedSites.length > 0 || configuration.excludedPaths.length > 0 ? (
        <p className="text-content-muted">
          {ui("{{sites}} site and {{paths}} path exclusions", {
            sites: configuration.excludedSites.length,
            paths: configuration.excludedPaths.length,
          })}
        </p>
      ) : null}
    </div>
  );
}
