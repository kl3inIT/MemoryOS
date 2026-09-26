import { appText } from "@/i18n/app-text";
import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { replaceEqualDeep, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Play, RefreshCw } from "lucide-react";
import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { TabsContent } from "@/components/ui/tabs";
import { Button } from "@/components/ui/button";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { HelpPopover } from "@/components/ui/help-popover";
import {
  useApplicationSession,
  useCapabilityAuthority,
} from "@/features/identity/application-session-context";
import { captureWorkflowFailure } from "@/lib/sentry";
import { useManualRefresh } from "@/lib/use-manual-refresh";
import {
  getGoogleDriveConfigurationOptions,
  getGoogleDriveConfigurationQueryKey,
  getSourceQueryKey,
  listSourceItemsQueryKey,
  listSourcesQueryKey,
  listGoogleDriveCredentialsOptions,
  listGoogleDriveCredentialsQueryKey,
  updateGoogleDrivePauseMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { GetGoogleDriveConfigurationResponse, SourceSummary } from "@/lib/hey-api/types.gen";
import { googleDriveConfigurationConnected } from "./google-drive-credential";
import { reconcileGoogleDriveConfiguration } from "./google-drive-selection";
import { GoogleDriveConnectionSection } from "./google-drive-connection-section";
import { GoogleDriveSelectionPanel } from "./google-drive-selection-panel";
import { GoogleDriveSyncInterval } from "./google-drive-sync-interval";
import { useGoogleDriveSynchronization } from "./use-google-drive-synchronization";
import { sourceMutationError, sourceStatusMessage } from "@/features/sources/shared/source-errors";
import { SourceSummaryCard } from "@/features/sources/shared/source-summary-card";
import { can } from "@/lib/resource-permissions";

type DriveAction =
  | "sync"
  | "authorize"
  | "disconnect"
  | "save-interval"
  | "reload-interval"
  | "pause";

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

  const queryClient = useQueryClient();
  const notify = useActionNotifications();
  const session = useApplicationSession();
  const authority = useCapabilityAuthority("SOURCES_MANAGE");
  const canListCredentials = authority !== "none";
  const canConfigure = can(source, "manageConfiguration");
  const canSchedule = can(source, "edit");
  const canSynchronize = can(source, "edit");
  const capabilities = `${session.capabilities.join(",")}:${session.scopedCapabilities.join(",")}:${JSON.stringify(source.permissions)}`;
  const configurationKey = getGoogleDriveConfigurationQueryKey({ path: { sourceId: source.id } });
  const configurationQuery = useQuery({
    ...getGoogleDriveConfigurationOptions({ path: { sourceId: source.id } }),
    retry: false,
    structuralSharing: (current, incoming) =>
      replaceEqualDeep(
        current,
        reconcileGoogleDriveConfiguration(
          current as GetGoogleDriveConfigurationResponse | undefined,
          incoming as GetGoogleDriveConfigurationResponse,
        ),
      ),
    refetchInterval: (query) =>
      query.state.data?.pendingWork || source.pendingWork ? 1_500 : false,
  });
  const credentials = useQuery({
    ...listGoogleDriveCredentialsOptions(),
    enabled: canListCredentials,
    retry: false,
  });
  // This panel polls while work is pending, so its refresh controls follow the press, not the poll.
  const statusRefresh = useManualRefresh(refreshStatus);
  const credentialRefresh = useManualRefresh(refreshCredentials);
  const credential = credentials.data?.find(
    (entry) => entry.id === configurationQuery.data?.credentialId,
  );
  const canReauthorize =
    canListCredentials &&
    !credentials.isError &&
    (credential?.actions.includes("reauthorize") ?? false);
  const canReplaceClient =
    canReauthorize &&
    authority === "global" &&
    (credential?.actions.includes("replace_oauth_client") ?? false);
  const canRevoke =
    canListCredentials && !credentials.isError && (credential?.actions.includes("revoke") ?? false);
  const updatePause = useMutation({ ...updateGoogleDrivePauseMutation(), retry: false });
  const [editingSelection, setEditingSelection] = useState(false);
  const [selectionBusy, setSelectionBusy] = useState(false);
  const [activeAction, setActiveAction] = useState<DriveAction | null>(null);
  const [leaving, setLeaving] = useState(false);
  const [error, setError] = useState<AppCopy | null>(null);
  const actionLock = useRef(false);
  const actionController = useRef<AbortController | null>(null);
  const refreshController = useRef<AbortController | null>(null);
  const credentialRefreshController = useRef<AbortController | null>(null);
  const busy = activeAction !== null || leaving;
  const configuration = configurationQuery.data;
  const canPause = can(source, "edit");
  const stale = sourceStale || configurationQuery.isError;
  const controlsDisabled = disabled || busy || stale;
  const connected = googleDriveConfigurationConnected(configuration);
  const serviceAccount = configuration?.credentialAuthMethod === "SERVICE_ACCOUNT";
  const hasSelectionChanges = editingSelection;
  const synchronization = useGoogleDriveSynchronization({
    source,
    resetKey: `${source.id}:${session.actorId}:${session.authorizationVersion}:${capabilities}:${configuration?.credentialId}:${configuration?.credentialRevision}`,
    refresh,
  });

  const resourceKey = `${source.id}:${session.actorId}:${session.authorizationVersion}:${session.capabilities.join(",")}:${session.scopedCapabilities.join(",")}`;
  const [previousAuthority, setPreviousAuthority] = useState({ resourceKey, canConfigure });
  if (
    previousAuthority.resourceKey !== resourceKey ||
    previousAuthority.canConfigure !== canConfigure
  ) {
    setPreviousAuthority({ resourceKey, canConfigure });
    if (previousAuthority.resourceKey !== resourceKey || !canConfigure) {
      setEditingSelection(false);
      setSelectionBusy(false);
    }
  }

  useEffect(() => {
    onBusyChange(busy || selectionBusy);
    return () => onBusyChange(false);
  }, [busy, selectionBusy, onBusyChange]);

  useLayoutEffect(() => {
    actionController.current?.abort();
    const restore = () => setLeaving(false);
    const clear = () => actionController.current?.abort();
    window.addEventListener("pageshow", restore);
    window.addEventListener("pagehide", clear);
    return () => {
      clear();
      window.removeEventListener("pageshow", restore);
      window.removeEventListener("pagehide", clear);
    };
  }, [
    source.id,
    session.actorId,
    session.authorizationVersion,
    capabilities,
    configuration?.credentialId,
    configuration?.credentialRevision,
  ]);

  useLayoutEffect(
    () => () => {
      refreshController.current?.abort();
      credentialRefreshController.current?.abort();
    },
    [source.id, session.actorId, session.authorizationVersion, capabilities],
  );

  async function refresh(throwOnError = false) {
    await queryClient.cancelQueries({ queryKey: configurationKey });
    await Promise.all([
      configurationQuery.refetch({ throwOnError }),
      queryClient.invalidateQueries(
        { queryKey: getSourceQueryKey({ path: { sourceId: source.id } }) },
        { throwOnError },
      ),
      queryClient.invalidateQueries(
        {
          queryKey: listSourceItemsQueryKey({ path: { sourceId: source.id } }),
          refetchType: "active",
        },
        { throwOnError },
      ),
      queryClient.invalidateQueries({ queryKey: listSourcesQueryKey() }, { throwOnError }),
      queryClient.invalidateQueries(
        { queryKey: listGoogleDriveCredentialsQueryKey() },
        { throwOnError },
      ),
    ]);
  }

  async function refreshStatus() {
    if (refreshController.current) return;
    const controller = new AbortController();
    refreshController.current = controller;
    try {
      await refresh(true);
      if (!controller.signal.aborted)
        notify({ tone: "success", title: "Drive status refreshed", description: source.name });
    } catch {
      if (!controller.signal.aborted)
        notify({
          tone: "error",
          title: "Drive status refresh failed",
          description: appText(
            "{{v1}}: displayed values may be out of date. Try refreshing again.",
            { v1: source.name },
          ),
        });
    } finally {
      if (refreshController.current === controller) refreshController.current = null;
    }
  }

  async function refreshCredentials() {
    if (credentialRefreshController.current) return;
    const controller = new AbortController();
    credentialRefreshController.current = controller;
    try {
      await credentials.refetch({ throwOnError: true });
      if (!controller.signal.aborted)
        notify({
          tone: "success",
          title: "Credential details refreshed",
          description: source.name,
        });
    } catch (cause) {
      if (!controller.signal.aborted)
        notify({
          tone: "error",
          title: "Credential refresh failed",
          description: appText(sourceMutationError(cause, "google-drive")),
        });
    } finally {
      if (credentialRefreshController.current === controller)
        credentialRefreshController.current = null;
    }
  }

  async function perform(action: DriveAction, task: (signal: AbortSignal) => Promise<void>) {
    const allowed =
      action === "sync"
        ? canSynchronize
        : action === "authorize"
          ? canReauthorize
          : action === "disconnect"
            ? canRevoke
            : action === "pause"
              ? canPause
              : canSchedule;
    if (actionLock.current || disabled || !allowed || leaving)
      throw new Error("This source action is not currently available");
    actionLock.current = true;
    const controller = new AbortController();
    actionController.current = controller;
    refreshController.current?.abort();
    credentialRefreshController.current?.abort();
    setActiveAction(action);
    setError(null);
    try {
      await task(controller.signal);
    } catch (cause) {
      if (controller.signal.aborted) return;
      void configurationQuery.refetch();
      throw cause;
    } finally {
      if (actionController.current === controller) actionController.current = null;
      actionLock.current = false;
      setActiveAction(null);
    }
  }

  function run(action: DriveAction, task: (signal: AbortSignal) => Promise<void>) {
    setError(null);
    void perform(action, task).catch((cause: unknown) => {
      if (action === "sync")
        captureWorkflowFailure(cause, {
          workflow: "google-drive-sync",
          stage: "request",
          failureKind: "api-or-network",
        });
      setError(sourceMutationError(cause, "google-drive"));
    });
  }

  async function incorporateConfiguration(saved: GetGoogleDriveConfigurationResponse) {
    await queryClient.cancelQueries({ queryKey: configurationKey });
    queryClient.setQueryData(
      configurationKey,
      (current: GetGoogleDriveConfigurationResponse | undefined) =>
        reconcileGoogleDriveConfiguration(current, saved),
    );
  }

  async function togglePause(signal: AbortSignal) {
    if (!configuration || stale) return;
    const saved = await updatePause.mutateAsync({
      path: { sourceId: source.id },
      body: { expectedRevision: configuration.scheduleRevision, paused: !configuration.syncPaused },
      signal,
    });
    signal.throwIfAborted();
    await incorporateConfiguration(saved);
    notify({
      tone: "success",
      title: saved.syncPaused
        ? "Automatic synchronization paused"
        : "Automatic synchronization resumed",
      description: appText("Current work and manual synchronization are unchanged."),
    });
    await refresh();
  }

  async function sync() {
    if (!connected || hasSelectionChanges || stale) return;
    await synchronization.sync();
  }

  if (!configuration) {
    return (
      <>
        <SourceSummaryCard source={source} className="mt-6">
          <div>
            <dt className="text-content-muted">{ui("Automatic synchronization")}</dt>
            <dd className="mt-1 flex min-h-8 items-center text-content-muted">
              {configurationQuery.isPending ? ui("Loading…") : ui("Unavailable")}
            </dd>
          </div>
        </SourceSummaryCard>
        <div className="border-b border-border-subtle p-5 sm:p-6">
          {configurationQuery.isPending ? (
            <p role="status" className="text-sm text-content-muted">
              {ui("Loading Google Drive connection…")}
            </p>
          ) : (
            <div className="space-y-3">
              <p role="alert" className="text-sm text-status-danger-content">
                {ui(sourceMutationError(configurationQuery.error, "google-drive"))}
              </p>
              <Button
                prominence="secondary"
                pending={statusRefresh.pending}
                onClick={statusRefresh.refresh}
              >
                {ui("Retry connection status")}
              </Button>
            </div>
          )}
        </div>
        {navigation}
        <TabsContent value="content">{content}</TabsContent>
        <TabsContent value="settings">{settings}</TabsContent>
      </>
    );
  }

  return (
    <section aria-label={ui("Google Drive configuration")} className="mt-6 space-y-5">
      <SourceSummaryCard
        source={source}
        header={
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
                  disabled={disabled || busy}
                  pending={statusRefresh.pending}
                  onClick={statusRefresh.refresh}
                >
                  <RefreshCw aria-hidden="true" />
                  {ui("Refresh status")}
                </Button>
                {connected && canSynchronize ? (
                  <Button
                    disabled={
                      controlsDisabled ||
                      hasSelectionChanges ||
                      configuration.pendingWork ||
                      source.pendingWork
                    }
                    pending={activeAction === "sync" || synchronization.observing}
                    onClick={() => run("sync", sync)}
                  >
                    <Play /> {ui("Synchronize now")}
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
        }
      >
        <GoogleDriveSyncInterval
          sourceId={source.id}
          resourceKey={resourceKey}
          configuration={configuration}
          canSchedule={canSchedule}
          canPause={canPause}
          disabled={disabled}
          busy={busy}
          stale={stale}
          activeAction={activeAction}
          perform={perform}
          onTogglePause={() => run("pause", togglePause)}
          incorporate={incorporateConfiguration}
          refresh={refresh}
          reloadConfiguration={async () =>
            (await configurationQuery.refetch({ throwOnError: true })).data
          }
        />
      </SourceSummaryCard>
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
      {!connected ? (
        <p className="rounded-lg bg-status-warning-surface p-4 text-sm text-status-warning-content">
          {serviceAccount
            ? ui(
                "Replace the key of this service account in Google Drive credentials to save links and synchronize. Saved roots are retained.",
              )
            : ui(
                "Reconnect the same Google account to save links and synchronize. Saved roots are retained.",
              )}
        </p>
      ) : null}
      {navigation}
      <TabsContent
        value="settings"
        forceMount
        hidden={activeSection !== "settings"}
        className="space-y-5 outline-none"
      >
        <GoogleDriveConnectionSection
          sourceId={source.id}
          resourceKey={`${resourceKey}:${JSON.stringify(source.permissions)}`}
          configuration={configuration}
          credential={credential}
          credentialsUnavailable={credentials.isError}
          credentialRefresh={credentialRefresh}
          connected={connected}
          canReauthorize={canReauthorize}
          canReplaceClient={canReplaceClient}
          canRevoke={canRevoke}
          controlsDisabled={controlsDisabled}
          hasSelectionChanges={hasSelectionChanges}
          activeAction={activeAction}
          leaving={leaving}
          stale={stale}
          perform={perform}
          onLeavingChange={setLeaving}
        />
        {settings}
      </TabsContent>
      <TabsContent
        value="content"
        forceMount
        hidden={activeSection !== "content"}
        className="outline-none"
      >
        {canConfigure ? (
          <div className="rounded-xl border border-border-subtle bg-surface-raised px-4 py-5 sm:px-5">
            <GoogleDriveSelectionPanel
              key={`${session.actorId}:${session.authorizationVersion}:${capabilities}:${source.id}`}
              sourceId={source.id}
              configuration={configuration}
              disabled={controlsDisabled || !connected}
              onEditingChange={setEditingSelection}
              onBusyChange={setSelectionBusy}
              onActivated={refresh}
            />
          </div>
        ) : (
          <section aria-label={ui("Saved Drive selection")} className="space-y-3">
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
