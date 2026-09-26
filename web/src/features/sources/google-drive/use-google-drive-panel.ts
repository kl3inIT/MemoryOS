import { appText, type AppCopy } from "@/i18n/app-text";
import { replaceEqualDeep, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { useActionNotifications } from "@/components/ui/action-notifications";
import {
  useApplicationSession,
  useCapabilityAuthority,
} from "@/features/identity/application-session-context";
import {
  getGoogleDriveConfigurationOptions,
  getGoogleDriveConfigurationQueryKey,
  getGoogleDriveSelectionQueryKey,
  getGoogleDriveSelectionTreeInfiniteQueryKey,
  getSourceQueryKey,
  listGoogleDriveCredentialsOptions,
  listGoogleDriveCredentialsQueryKey,
  listSourceItemsQueryKey,
  listSourcesQueryKey,
  updateGoogleDrivePauseMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { GetGoogleDriveConfigurationResponse, SourceSummary } from "@/lib/hey-api/types.gen";
import { can } from "@/lib/resource-permissions";
import { captureWorkflowFailure } from "@/lib/sentry";
import { useManualRefresh } from "@/lib/use-manual-refresh";
import { sourceMutationError } from "@/features/sources/shared/source-errors";
import { googleDriveConfigurationConnected } from "./google-drive-credential";
import { reconcileGoogleDriveConfiguration } from "./google-drive-selection";
import { useGoogleDriveSynchronization } from "./use-google-drive-synchronization";

export type DriveAction =
  | "sync"
  | "authorize"
  | "disconnect"
  | "save-interval"
  | "reload-interval"
  | "pause";

/**
 * The Google Drive configuration of a Source and every action on it: one action at a time,
 * cancelled when the person, their authority or the credential changes, and reported through
 * `onBusyChange` so the page holds its own controls meanwhile.
 */
export function useGoogleDrivePanel({
  source,
  sourceStale,
  disabled,
  onBusyChange,
}: {
  source: SourceSummary;
  sourceStale: boolean;
  disabled: boolean;
  onBusyChange: (busy: boolean) => void;
}) {
  const queryClient = useQueryClient();
  const notify = useActionNotifications();
  const session = useApplicationSession();
  const authority = useCapabilityAuthority("SOURCES_MANAGE");
  const canListCredentials = authority !== "none";
  const canConfigure = can(source, "manageConfiguration");
  const canSchedule = can(source, "edit");
  const canSynchronize = can(source, "edit");
  const canPause = can(source, "edit");
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
  const stale = sourceStale || configurationQuery.isError;
  const connected = googleDriveConfigurationConnected(configuration);
  const synchronization = useGoogleDriveSynchronization({
    source,
    resetKey: `${source.id}:${session.actorId}:${session.authorizationVersion}:${capabilities}:${configuration?.credentialId}:${configuration?.credentialRevision}`,
    refresh,
  });

  const resourceKey = `${source.id}:${session.actorId}:${session.authorizationVersion}:${session.capabilities.join(",")}:${session.scopedCapabilities.join(",")}`;
  // A different person, authority or Source abandons the selection being edited.
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

  // Leaving the page for Google's consent screen, or coming back from it, settles the action in flight.
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
      // Selected content read under the previous revisions is read again.
      queryClient.invalidateQueries({
        queryKey: getGoogleDriveSelectionQueryKey({ path: { sourceId: source.id } }),
      }),
      queryClient.invalidateQueries({
        queryKey: getGoogleDriveSelectionTreeInfiniteQueryKey({ path: { sourceId: source.id } }),
      }),
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
    if (!connected || editingSelection || stale) return;
    await synchronization.sync();
  }

  return {
    configurationQuery,
    configuration,
    credentials,
    credential,
    statusRefresh,
    credentialRefresh,
    permissions: {
      configure: canConfigure,
      schedule: canSchedule,
      synchronize: canSynchronize,
      pause: canPause,
      reauthorize: canReauthorize,
      replaceClient: canReplaceClient,
      revoke: canRevoke,
    },
    /** Identifies the person, their authority and the Source; editors reset when it changes. */
    resourceKey,
    /** {@link resourceKey} with the Source's own permissions, for the selection editor. */
    selectionKey: `${session.actorId}:${session.authorizationVersion}:${capabilities}:${source.id}`,
    activeAction,
    leaving,
    setLeaving,
    busy,
    stale,
    connected,
    serviceAccount: configuration?.credentialAuthMethod === "SERVICE_ACCOUNT",
    error,
    editingSelection,
    setEditingSelection,
    setSelectionBusy,
    observingSync: synchronization.observing,
    controlsDisabled: disabled || busy || stale,
    perform,
    refresh,
    incorporateConfiguration,
    synchronize: () => run("sync", sync),
    togglePause: () => run("pause", togglePause),
    reloadConfiguration: async () =>
      (await configurationQuery.refetch({ throwOnError: true })).data,
  };
}

export type GoogleDrivePanelState = ReturnType<typeof useGoogleDrivePanel>;
