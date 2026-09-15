import { appText } from "@/i18n/app-text";
import type { AppCopy } from "@/i18n/app-text";
import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { replaceEqualDeep, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronDown, KeyRound, Pencil, RefreshCw, Unplug } from "lucide-react";
import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { TabsContent } from "@/components/ui/tabs";
import { Button } from "@/components/ui/button";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { HelpPopover } from "@/components/ui/help-popover";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/radix-select";
import { StatusBadge } from "@/components/ui/status-badge";
import { Checkbox } from "@/components/ui/checkbox";
import { Switch } from "@/components/ui/switch";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import {
  useApplicationSession,
  useCapabilityAuthority,
} from "@/features/identity/application-session-context";
import { isUnauthenticated, sameOriginMutationHeaders } from "@/lib/api";
import { captureWorkflowFailure } from "@/lib/sentry";
import {
  getCurrentIdentityQueryKey,
  getGoogleDriveConfigurationOptions,
  getGoogleDriveConfigurationQueryKey,
  getSourceQueryKey,
  listSourceItemsQueryKey,
  listSourcesQueryKey,
  listGoogleDriveCredentialsOptions,
  listGoogleDriveCredentialsQueryKey,
  revokeGoogleDriveCredentialMutation,
  synchronizeGoogleDriveSourceMutation,
  updateGoogleDriveScheduleMutation,
  updateGoogleDrivePauseMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type {
  GetGoogleDriveConfigurationResponse,
  SourceOperation,
  SourceSummary,
} from "@/lib/hey-api/types.gen";
import { startGoogleDriveAuthorization } from "@/lib/hey-api/sdk.gen";
import { launchGoogleDriveAuthorization } from "./google-drive-authorization";
import { waitForSourceOperation } from "./source-operations";
import { reconcileGoogleDriveConfiguration } from "./google-drive-selection";
import { GoogleDriveSelectionPanel } from "./google-drive-selection-panel";
import {
  GoogleDriveOAuthClientInput,
  type GoogleDriveOAuthClientInputHandle,
} from "./google-drive-oauth-client-input";
import {
  isGoogleDriveRevisionConflict,
  sourceMutationError,
  sourceStatusMessage,
} from "./source-errors";
import { SourceSectionIcon } from "./source-section-icon";
import { SourceSummaryCard } from "./source-summary-card";
import {
  formatSyncInterval,
  maxSyncIntervalValue,
  splitSyncInterval,
  syncIntervalMinutes,
  syncIntervalUnitName,
  syncIntervalUnits,
  type SyncIntervalUnit,
} from "./sync-interval";
import { can } from "@/lib/resource-permissions";

type IntervalDraft = Pick<GetGoogleDriveConfigurationResponse, "scheduleRevision"> & {
  value: string;
  unit: SyncIntervalUnit;
};

function intervalDraftOf({
  syncIntervalMinutes: minutes,
  scheduleRevision,
}: GetGoogleDriveConfigurationResponse): IntervalDraft {
  const { value, unit } = splitSyncInterval(minutes);
  return { value: String(value), unit, scheduleRevision };
}
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
  const clientInput = useRef<GoogleDriveOAuthClientInputHandle>(null);
  const [clientReady, setClientReady] = useState(false);
  const [replaceClient, setReplaceClient] = useState(false);
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
      query.state.data?.pendingWork || source.pendingWork ? 1_500 : 5_000,
  });
  const credentials = useQuery({
    ...listGoogleDriveCredentialsOptions(),
    enabled: canListCredentials,
    retry: false,
  });
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
  const synchronize = useMutation(synchronizeGoogleDriveSourceMutation());
  const revoke = useMutation(revokeGoogleDriveCredentialMutation());
  const updateSchedule = useMutation({ ...updateGoogleDriveScheduleMutation(), retry: false });
  const updatePause = useMutation({ ...updateGoogleDrivePauseMutation(), retry: false });
  const [intervalDraft, setIntervalDraft] = useState<IntervalDraft | null>(null);
  const [intervalRevisionConflict, setIntervalRevisionConflict] = useState(false);
  const [intervalError, setIntervalError] = useState<AppCopy | null>(null);
  const intervalInput = useRef<HTMLInputElement>(null);
  const intervalEditButton = useRef<HTMLButtonElement>(null);
  const wasEditingInterval = useRef(false);
  const [editingSelection, setEditingSelection] = useState(false);
  const [selectionBusy, setSelectionBusy] = useState(false);
  const [activeAction, setActiveAction] = useState<DriveAction | null>(null);
  const [leaving, setLeaving] = useState(false);
  const [error, setError] = useState<AppCopy | null>(null);
  const actionLock = useRef(false);
  const authorizationController = useRef<AbortController | null>(null);
  const synchronizationController = useRef<AbortController | null>(null);
  const actionController = useRef<AbortController | null>(null);
  const refreshController = useRef<AbortController | null>(null);
  const credentialRefreshController = useRef<AbortController | null>(null);
  const [observingSynchronization, setObservingSynchronization] = useState(false);
  const busy = activeAction !== null || leaving;
  const configuration = configurationQuery.data;
  const canPause = can(source, "edit");
  const stale = sourceStale || configurationQuery.isError;
  const controlsDisabled = disabled || busy || stale;
  const connected =
    configuration?.credentialStatus === "ACTIVE" && configuration.oauthClientConfigured;
  const savedClientConfigured =
    credential?.oauthClientConfigured ?? configuration?.oauthClientConfigured;
  const needsClient = canReplaceClient && (!savedClientConfigured || replaceClient);
  const missingClient = !savedClientConfigured && !canReplaceClient;
  const hasSelectionChanges = editingSelection;

  const editingInterval = intervalDraft !== null;
  const intervalMinutes = intervalDraft
    ? syncIntervalMinutes(intervalDraft.value, intervalDraft.unit)
    : null;
  const intervalValidation =
    intervalDraft && intervalMinutes === null
      ? appText("Enter a whole number from 1 to {{v1}}.", {
          v1: maxSyncIntervalValue(intervalDraft.unit).toLocaleString(uiLocale()),
        })
      : null;
  const intervalConflicted =
    intervalRevisionConflict ||
    Boolean(intervalDraft && configuration?.scheduleRevision !== intervalDraft.scheduleRevision);

  const resourceKey = `${source.id}:${session.actorId}:${session.authorizationVersion}:${session.capabilities.join(",")}:${session.scopedCapabilities.join(",")}`;
  const credentialKey = `${configuration?.credentialId}:${configuration?.credentialRevision}`;
  const [previousAuthority, setPreviousAuthority] = useState({
    resourceKey,
    credentialKey,
    canSchedule,
    canConfigure,
    canReauthorize,
    canReplaceClient,
  });
  if (
    previousAuthority.resourceKey !== resourceKey ||
    previousAuthority.credentialKey !== credentialKey ||
    previousAuthority.canSchedule !== canSchedule ||
    previousAuthority.canConfigure !== canConfigure ||
    previousAuthority.canReauthorize !== canReauthorize ||
    previousAuthority.canReplaceClient !== canReplaceClient
  ) {
    const resourceChanged = previousAuthority.resourceKey !== resourceKey;
    setPreviousAuthority({
      resourceKey,
      credentialKey,
      canSchedule,
      canConfigure,
      canReauthorize,
      canReplaceClient,
    });
    if (resourceChanged || !canSchedule) {
      setIntervalDraft(null);
      setIntervalError(null);
      setIntervalRevisionConflict(false);
    }
    if (resourceChanged || !canConfigure) {
      setEditingSelection(false);
      setSelectionBusy(false);
    }
    if (
      resourceChanged ||
      previousAuthority.credentialKey !== credentialKey ||
      !canReauthorize ||
      !canReplaceClient
    ) {
      setReplaceClient(false);
      setClientReady(false);
    }
  }

  useLayoutEffect(() => {
    if (!canReauthorize) {
      clientInput.current?.clear();
      authorizationController.current?.abort();
    }
  }, [canReauthorize]);
  useLayoutEffect(() => {
    if (!canReplaceClient) {
      clientInput.current?.clear();
      authorizationController.current?.abort();
    }
  }, [canReplaceClient]);
  useLayoutEffect(() => {
    if (busy) return;
    if (editingInterval) intervalInput.current?.focus();
    else if (wasEditingInterval.current) intervalEditButton.current?.focus();
    wasEditingInterval.current = editingInterval;
  }, [editingInterval, busy]);
  useEffect(() => {
    onBusyChange(busy || selectionBusy);
    return () => onBusyChange(false);
  }, [busy, selectionBusy, onBusyChange]);

  useLayoutEffect(() => {
    clientInput.current?.clear();
    authorizationController.current?.abort();
    actionController.current?.abort();
    synchronizationController.current?.abort();
    synchronizationController.current = null;
    const restore = () => {
      setLeaving(false);
      if (synchronizationController.current?.signal.aborted) {
        synchronizationController.current = null;
        setObservingSynchronization(false);
      }
    };
    const clear = () => {
      clientInput.current?.clear();
      authorizationController.current?.abort();
      actionController.current?.abort();
      synchronizationController.current?.abort();
    };
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
      if (isGoogleDriveRevisionConflict(cause) && action === "save-interval")
        setIntervalRevisionConflict(true);
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

  function runInterval(
    action: "save-interval" | "reload-interval",
    task: (signal: AbortSignal) => Promise<void>,
  ) {
    setIntervalError(null);
    void perform(action, task).catch((cause: unknown) => {
      if (!isGoogleDriveRevisionConflict(cause))
        setIntervalError(sourceMutationError(cause, "google-drive-schedule"));
    });
  }

  async function saveInterval(signal: AbortSignal) {
    if (!intervalDraft || intervalMinutes === null || intervalConflicted || stale) return;
    const saved = await updateSchedule.mutateAsync({
      path: { sourceId: source.id },
      headers: { ...sameOriginMutationHeaders, "If-Match": `"${intervalDraft.scheduleRevision}"` },
      body: { syncIntervalMinutes: intervalMinutes },
      signal,
    });
    signal.throwIfAborted();
    await incorporateConfiguration(saved);
    signal.throwIfAborted();
    setIntervalDraft(null);
    setIntervalRevisionConflict(false);
    notify({
      tone: "success",
      title: "Automatic interval saved",
      description: appText("Synchronizes every {{v1}}. Current work is unchanged.", {
        v1: formatSyncInterval(saved.syncIntervalMinutes),
      }),
    });
    await refresh();
  }

  async function togglePause(signal: AbortSignal) {
    if (!configuration || stale) return;
    const saved = await updatePause.mutateAsync({
      path: { sourceId: source.id },
      headers: sameOriginMutationHeaders,
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

  async function reloadInterval(signal: AbortSignal) {
    const { data: saved } = await configurationQuery.refetch({ throwOnError: true });
    signal.throwIfAborted();
    if (!saved) return;
    setIntervalDraft(intervalDraftOf(saved));
    setIntervalRevisionConflict(false);
    notify({
      tone: "info",
      title: "Saved interval loaded",
      description: appText("Local interval changes were discarded."),
    });
    intervalInput.current?.focus();
  }

  function cancelInterval() {
    setIntervalDraft(null);
    setIntervalRevisionConflict(false);
    setIntervalError(null);
  }

  async function sync() {
    if (!connected || hasSelectionChanges || stale || synchronizationController.current) return;
    const controller = new AbortController();
    synchronizationController.current = controller;
    setObservingSynchronization(true);
    let operation: SourceOperation;
    try {
      operation = await synchronize.mutateAsync({
        path: { sourceId: source.id },
        headers: sameOriginMutationHeaders,
        signal: controller.signal,
      });
      controller.signal.throwIfAborted();
    } catch (cause) {
      if (synchronizationController.current === controller)
        synchronizationController.current = null;
      if (!synchronizationController.current) setObservingSynchronization(false);
      if (controller.signal.aborted) return;
      throw cause;
    }
    notify({ tone: "info", title: "Synchronization requested", description: source.name });
    void observeSynchronization(operation, controller);
    await refresh();
  }

  async function observeSynchronization(operation: SourceOperation, controller: AbortController) {
    try {
      const completed = await waitForSourceOperation(operation, controller.signal);
      if (completed.status === "SUCCEEDED") {
        notify({
          tone: "success",
          title: "Synchronization complete",
          description: appText(
            "{{v1}}: selected content is synchronized. Indexing may still be running.",
            { v1: source.name },
          ),
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
        if (isSystemSynchronizationFailure(failureKind))
          captureWorkflowFailure(new Error("Google Drive synchronization failed"), {
            workflow: "google-drive-sync",
            stage: "operation-complete",
            failureKind,
          });
        notify({
          tone: "error",
          title: "Synchronization failed",
          description: appText(sourceStatusMessage(completed.errorCode ?? "SOURCE_SYNC_FAILED")),
        });
      }
      await refresh();
    } catch (cause) {
      if (!controller.signal.aborted) {
        captureWorkflowFailure(cause, {
          workflow: "google-drive-sync",
          stage: "operation-status",
          failureKind: "status-unavailable",
        });
        notify({
          tone: "error",
          title: "Synchronization status unavailable",
          description: appText(
            "Synchronization may still be running. Refresh the source to check its status.",
          ),
        });
      }
    } finally {
      if (synchronizationController.current === controller)
        synchronizationController.current = null;
      if (!synchronizationController.current) setObservingSynchronization(false);
    }
  }

  async function reconnect() {
    if (
      !configuration ||
      !credential ||
      credentials.isError ||
      stale ||
      !canReauthorize ||
      missingClient ||
      (needsClient && !clientReady)
    )
      return;
    const controller = new AbortController();
    authorizationController.current = controller;
    try {
      // Keep client JSON outside React Query variables, data, and errors.
      const { data: response } = await startGoogleDriveAuthorization({
        headers: sameOriginMutationHeaders,
        body: {
          name: credential.name,
          credentialId: configuration.credentialId,
          expectedCredentialRevision: configuration.credentialRevision,
          ...(needsClient ? { oauthClientJson: clientInput.current?.takeJson() } : {}),
        },
        signal: controller.signal,
        throwOnError: true,
      });
      controller.signal.throwIfAborted();
      setLeaving(true);
      launchGoogleDriveAuthorization(response.authorizationUrl);
    } catch (cause) {
      if (controller.signal.aborted) return;
      setLeaving(false);
      if (isUnauthenticated(cause))
        void queryClient.resetQueries({ queryKey: getCurrentIdentityQueryKey(), exact: true });
      throw cause;
    } finally {
      if (authorizationController.current === controller) authorizationController.current = null;
    }
  }

  async function disconnect(signal: AbortSignal) {
    if (!configuration || stale) throw new Error("Refresh the connection before disconnecting");
    await revoke.mutateAsync({
      path: { credentialId: configuration.credentialId },
      headers: sameOriginMutationHeaders,
      body: { expectedCredentialRevision: configuration.credentialRevision },
      signal,
    });
    signal.throwIfAborted();
    await queryClient.cancelQueries({ queryKey: configurationKey });
    queryClient.setQueryData(
      configurationKey,
      (current: GetGoogleDriveConfigurationResponse | undefined) =>
        current && current.credentialRevision === configuration.credentialRevision
          ? { ...current, credentialStatus: "REVOKED", pendingWork: false }
          : current,
    );
    signal.throwIfAborted();
    notify({
      tone: "success",
      title: "Google credential disconnected",
      description: appText(
        "{{v1}}: synchronization is stopped for every attached Source. Saved links and documents are retained.",
        { v1: credential?.name ?? configuration.accountEmail },
      ),
    });
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: [{ _id: "getGoogleDriveConfiguration" }] }),
      queryClient.invalidateQueries({ queryKey: [{ _id: "getSource" }] }),
      queryClient.invalidateQueries({ queryKey: listSourcesQueryKey() }),
      queryClient.invalidateQueries({ queryKey: listGoogleDriveCredentialsQueryKey() }),
    ]);
  }

  if (!configuration) {
    return (
      <>
        <SourceSummaryCard source={source}>
          <div>
            <dt className="text-content-muted">{ui("Automatic interval")}</dt>
            <dd className="mt-2 text-content-muted">
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
                pending={configurationQuery.isFetching}
                onClick={() => void refreshStatus()}
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
      <div className="rounded-xl border border-border-subtle bg-surface-raised px-4 pb-4 sm:px-5 [&>dl]:my-0 [&>dl]:rounded-none [&>dl]:border-0 [&>dl]:px-0">
        <SourceSummaryCard source={source}>
          <div className="min-w-0" aria-live="polite">
            <dt className="text-content-muted">{ui("Automatic interval")}</dt>
            <dd className="mt-2 min-w-0 space-y-3 text-content-primary">
              <div className="flex flex-wrap items-center gap-2">
                <span>{formatSyncInterval(configuration.syncIntervalMinutes)}</span>
                {canSchedule && !editingInterval ? (
                  <IconButton
                    ref={intervalEditButton}
                    size="sm"
                    aria-label={ui("Edit interval")}
                    prominence="tertiary"
                    disabled={controlsDisabled}
                    onClick={() => {
                      setIntervalDraft(intervalDraftOf(configuration));
                      setIntervalError(null);
                    }}
                  >
                    <Pencil aria-hidden="true" />
                  </IconButton>
                ) : null}
              </div>
              {canSchedule && intervalDraft ? (
                <form
                  className="space-y-3"
                  noValidate
                  onSubmit={(event) => {
                    event.preventDefault();
                    if (!controlsDisabled && !intervalConflicted && !intervalValidation)
                      runInterval("save-interval", saveInterval);
                  }}
                  onKeyDown={(event) => {
                    if (event.key === "Escape" && !busy) {
                      event.preventDefault();
                      cancelInterval();
                    }
                  }}
                >
                  <div className="space-y-2">
                    <span
                      id={`sync-interval-label-${source.id}`}
                      className="block text-sm font-medium text-content-primary"
                    >
                      {ui("Sync every")}
                    </span>
                    <div className="flex items-center gap-2">
                      <Input
                        ref={intervalInput}
                        type="number"
                        inputMode="numeric"
                        min={1}
                        max={maxSyncIntervalValue(intervalDraft.unit)}
                        step={1}
                        required
                        value={intervalDraft.value}
                        disabled={controlsDisabled}
                        aria-labelledby={`sync-interval-label-${source.id}`}
                        aria-invalid={Boolean(intervalValidation)}
                        aria-describedby={
                          intervalValidation ? `sync-interval-error-${source.id}` : undefined
                        }
                        className="w-24"
                        onChange={(event) => {
                          setIntervalDraft({ ...intervalDraft, value: event.target.value });
                          setIntervalError(null);
                        }}
                      />
                      <Select
                        value={intervalDraft.unit}
                        disabled={controlsDisabled}
                        onValueChange={(next) => {
                          const unit = syncIntervalUnits.find((entry) => entry === next);
                          if (!unit) return;
                          setIntervalDraft({ ...intervalDraft, unit });
                          setIntervalError(null);
                        }}
                      >
                        <SelectTrigger
                          aria-label={ui("Interval unit")}
                          className="h-(--control-height-md) min-w-28"
                        >
                          <SelectValue />
                        </SelectTrigger>
                        <SelectContent position="popper">
                          {syncIntervalUnits.map((unit) => (
                            <SelectItem key={unit} value={unit}>
                              {syncIntervalUnitName(unit, Number(intervalDraft.value) || 0)}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </div>
                  </div>
                  {intervalValidation ? (
                    <p
                      id={`sync-interval-error-${source.id}`}
                      role="alert"
                      className="text-status-danger-content"
                    >
                      {ui(intervalValidation)}
                    </p>
                  ) : null}
                  {intervalConflicted ? (
                    <p role="alert" className="text-status-warning-content">
                      {ui(
                        "The automatic interval changed while you were editing. Your interval draft has not been saved. Reload the saved interval before continuing.",
                      )}
                    </p>
                  ) : null}
                  <div className="flex flex-wrap gap-2">
                    <Button
                      type="submit"
                      disabled={
                        controlsDisabled || intervalConflicted || Boolean(intervalValidation)
                      }
                      pending={activeAction === "save-interval"}
                    >
                      {ui("Save interval")}
                    </Button>
                    <Button prominence="tertiary" disabled={busy} onClick={cancelInterval}>
                      {ui("Cancel")}
                    </Button>
                    {intervalConflicted ? (
                      <Button
                        prominence="secondary"
                        disabled={disabled || busy || !canSchedule}
                        pending={activeAction === "reload-interval"}
                        onClick={() => runInterval("reload-interval", reloadInterval)}
                      >
                        {ui("Reload saved interval")}
                      </Button>
                    ) : null}
                  </div>
                </form>
              ) : null}
              {intervalError ? (
                <p role="alert" className="text-status-danger-content">
                  {ui(intervalError)}
                </p>
              ) : null}
            </dd>
          </div>
          <div>
            <dt className="text-content-muted">{ui("Automatic synchronization")}</dt>
            <dd className="mt-2 flex min-h-8 items-center gap-2 text-content-primary">
              {canPause ? (
                <Switch
                  checked={!configuration.syncPaused}
                  disabled={controlsDisabled}
                  aria-label={ui("Automatic synchronization")}
                  onCheckedChange={() => run("pause", togglePause)}
                />
              ) : null}
              {configuration.syncPaused ? ui("Paused") : ui("Enabled")}
            </dd>
          </div>
        </SourceSummaryCard>
        <div className="mt-4 flex flex-col gap-3 border-t border-border-subtle pt-4 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex items-center gap-3">
            <SourceSectionIcon icon={RefreshCw} />
            <h2 className="font-heading-h3 text-content-primary">{ui("Synchronization")}</h2>
            <HelpPopover label={ui("Synchronization")}>
              <p>
                {ui(
                  "Automatic sync uses this Source’s saved interval. Saving an interval schedules the next run from the save time and does not interrupt current work. Start time depends on availability and pending work. Synchronize now requests a run.",
                )}
              </p>
            </HelpPopover>
          </div>
          <div className="flex flex-wrap gap-2">
            <Button
              prominence="tertiary"
              disabled={disabled || busy}
              pending={configurationQuery.isFetching}
              onClick={() => void refreshStatus()}
            >
              <RefreshCw /> {ui("Refresh status")}
            </Button>
            {connected && canSynchronize ? (
              <Button
                disabled={
                  controlsDisabled ||
                  hasSelectionChanges ||
                  configuration.pendingWork ||
                  source.pendingWork
                }
                pending={activeAction === "sync" || observingSynchronization}
                onClick={() => run("sync", sync)}
              >
                <RefreshCw /> {ui("Synchronize now")}
              </Button>
            ) : null}
          </div>
        </div>
        {configuration.errorCode ? (
          <p className="mt-3 text-sm text-status-danger-content">
            {ui(sourceStatusMessage(configuration.errorCode))}
          </p>
        ) : null}
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
      {!connected ? (
        <p className="rounded-lg bg-status-warning-surface p-4 text-sm text-status-warning-content">
          {ui(
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
        <section
          aria-labelledby="source-credentials-heading"
          className="space-y-4 rounded-xl border border-border-subtle bg-surface-raised p-5"
        >
          <div className="flex items-center gap-3">
            <SourceSectionIcon icon={KeyRound} />
            <h2 id="source-credentials-heading" className="font-heading-h3 text-content-primary">
              {ui("Credentials")}
            </h2>
          </div>
          <Collapsible className="group" defaultOpen={Boolean(!connected ? true : undefined)}>
            <CollapsibleTrigger className="flex min-h-11 cursor-pointer list-none flex-wrap items-center gap-3 rounded-lg text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus-ring">
              <StatusBadge tone={connected ? "success" : "warning"}>
                {connected ? ui("Connected") : ui("Needs reconnect")}
              </StatusBadge>
              <span className="min-w-0 break-all text-content-muted">
                {configuration.accountEmail}
              </span>
              <span className="ml-auto inline-flex items-center gap-2 text-content-muted">
                <span className="group-data-[state=open]:hidden">
                  {canReauthorize || canRevoke ? ui("Manage connection") : ui("Connection details")}
                </span>
                <span className="hidden group-data-[state=open]:inline">{ui("Close")}</span>
                <ChevronDown
                  className="size-4 group-data-[state=open]:rotate-180 motion-safe:transition-transform"
                  aria-hidden="true"
                />
              </span>
            </CollapsibleTrigger>
            <CollapsibleContent>
              <div className="mt-4 space-y-4">
                <p className="rounded-lg bg-status-warning-surface p-4 text-sm text-status-warning-content">
                  {ui(
                    "This credential is shared. Reconnecting or disconnecting affects all Sources using it",
                  )}
                  {credential ? ui(" ({{v1}} Sources)", { v1: credential.sourceCount }) : ""}
                  {ui(", not just this Source. Saved links and indexed documents are retained.")}
                </p>
                {credentials.isError ? (
                  <div className="space-y-2">
                    <p role="alert" className="text-sm text-status-danger-content">
                      {ui("Credential details could not be loaded. Refresh before reconnecting.")}
                    </p>
                    <Button
                      prominence="secondary"
                      pending={credentials.isFetching}
                      onClick={() => void refreshCredentials()}
                    >
                      {ui("Retry credential details")}
                    </Button>
                  </div>
                ) : null}
                {canReauthorize ? (
                  <div className="space-y-3">
                    {!savedClientConfigured ? (
                      <p className="rounded-lg bg-status-warning-surface p-4 text-sm text-status-warning-content">
                        {canReplaceClient
                          ? ui(
                              "This connection has no saved OAuth app. Upload or paste your Google Web OAuth client JSON below, then reconnect the same Google account. Saved files and folders are retained.",
                            )
                          : ui(
                              "This connection has no saved OAuth app. Ask a tenant administrator with global Source management permission to add the app and reconnect this credential.",
                            )}
                      </p>
                    ) : (
                      <>
                        <p className="text-sm text-content-secondary">
                          {ui("Reconnect reuses the OAuth app saved with this shared credential.")}
                        </p>
                        {canReplaceClient ? (
                          <label className="flex items-center gap-2 text-sm text-content-primary">
                            <Checkbox
                              checked={replaceClient}
                              disabled={controlsDisabled || hasSelectionChanges}
                              onCheckedChange={(event) => {
                                clientInput.current?.clear();
                                setClientReady(false);
                                setReplaceClient(event === true);
                              }}
                            />
                            {ui("Replace OAuth app on reconnect")}
                          </label>
                        ) : null}
                      </>
                    )}
                    {needsClient ? (
                      <GoogleDriveOAuthClientInput
                        key={`${resourceKey}:${credentialKey}`}
                        ref={clientInput}
                        disabled={controlsDisabled || hasSelectionChanges}
                        onReadyChange={setClientReady}
                      />
                    ) : null}
                  </div>
                ) : null}
                <div className="flex flex-wrap gap-2">
                  {canReauthorize ? (
                    <ConfirmDialog
                      trigger={
                        <Button
                          prominence="secondary"
                          disabled={
                            controlsDisabled ||
                            !credential ||
                            credentials.isError ||
                            hasSelectionChanges ||
                            missingClient ||
                            (needsClient && !clientReady)
                          }
                          pending={activeAction === "authorize" || leaving}
                        >
                          {ui("Reconnect Google Drive")}
                        </Button>
                      }
                      title={ui("Reconnect shared Google credential?")}
                      description={ui(
                        "Reconnecting changes the authorization used by all {{v1}} Sources, including other Sources. Use the same Google account. Saved links and indexed documents are retained.",
                        { v1: credential?.sourceCount ?? ui("attached") },
                      )}
                      confirmLabel={ui("Reconnect")}
                      pendingLabel={ui("Reconnecting")}
                      onConfirm={() => perform("authorize", reconnect)}
                      errorMessage={(cause) => sourceMutationError(cause, "google-drive")}
                    />
                  ) : null}
                  {canRevoke && configuration.credentialStatus !== "REVOKED" ? (
                    <ConfirmDialog
                      trigger={
                        <Button tone="danger" prominence="tertiary" disabled={controlsDisabled}>
                          <Unplug /> {ui("Disconnect")}
                        </Button>
                      }
                      title={ui("Disconnect shared Google credential?")}
                      description={ui(
                        "Disconnecting stops acquisition for all {{v1}} Sources using this credential, including other Sources. Stored data is not deleted. Reconnect the same Google account to resume.",
                        { v1: credential?.sourceCount ?? ui("attached") },
                      )}
                      confirmLabel={ui("Disconnect")}
                      pendingLabel={ui("Disconnecting")}
                      onConfirm={() => perform("disconnect", disconnect)}
                      errorMessage={(cause) => sourceMutationError(cause, "google-drive")}
                    />
                  ) : null}
                </div>
                <p className="text-xs text-content-muted">
                  {ui(
                    "This credential authorizes importing files. MemoryOS Source groups control who can search and read the imported documents.",
                  )}
                </p>
              </div>
            </CollapsibleContent>
          </Collapsible>
        </section>
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

function isSystemSynchronizationFailure(errorCode: string) {
  return (
    errorCode.startsWith("SOURCE_STORAGE_") ||
    errorCode === "SOURCE_ACQUISITION_INTERNAL" ||
    errorCode === "SOURCE_GOOGLE_INTERNAL" ||
    errorCode === "SOURCE_GOOGLE_INCOMPLETE"
  );
}
