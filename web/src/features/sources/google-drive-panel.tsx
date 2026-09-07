import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronDown, RefreshCw, Unplug } from "lucide-react";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { HelpPopover } from "@/components/ui/help-popover";
import { Input } from "@/components/ui/input";
import { StatusBadge } from "@/components/ui/status-badge";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { isUnauthenticated, sameOriginMutationHeaders } from "@/lib/api";
import {
  getCurrentIdentityQueryKey,
  getGoogleDriveConfigurationOptions,
  getGoogleDriveConfigurationQueryKey,
  getSourceQueryKey,
  listSourcesQueryKey,
  listGoogleDriveCredentialsOptions,
  listGoogleDriveCredentialsQueryKey,
  replaceGoogleDriveRootsMutation,
  revokeGoogleDriveCredentialMutation,
  synchronizeGoogleDriveSourceMutation,
  updateGoogleDriveScheduleMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type {
  GetGoogleDriveConfigurationResponse,
  SourceOperation,
  SourceSummary,
} from "@/lib/hey-api/types.gen";
import { startGoogleDriveAuthorization } from "@/lib/hey-api/sdk.gen";
import { launchGoogleDriveAuthorization } from "./google-drive-authorization";
import { waitForSourceOperation } from "./source-operations";
import { GoogleDriveLinks } from "./google-drive-links";
import {
  googleDriveRootLink,
  parseGoogleDriveLinks,
  MAX_GOOGLE_DRIVE_ROOTS,
  MAX_GOOGLE_DRIVE_LINK_LENGTH,
} from "./google-drive-selection";
import {
  GoogleDriveOAuthClientInput,
  type GoogleDriveOAuthClientInputHandle,
} from "./google-drive-oauth-client-input";
import {
  isGoogleDriveRevisionConflict,
  sourceMutationError,
  sourceStatusMessage,
} from "./source-errors";
import { GoogleDriveIcon } from "./google-drive-icon";

type RootDraft = Pick<
  GetGoogleDriveConfigurationResponse,
  "revision" | "credentialRevision" | "credentialId" | "scopeMode"
> & {
  actorId: string;
  links: string;
};
type IntervalDraft = Pick<GetGoogleDriveConfigurationResponse, "scheduleRevision"> & {
  minutes: string;
};
type DriveAction =
  | "save"
  | "sync"
  | "authorize"
  | "disconnect"
  | "reload"
  | "save-interval"
  | "reload-interval";
const MAX_SYNC_INTERVAL_MINUTES = 2_147_483_647;

export function GoogleDrivePanel({
  source,
  disabled = false,
  onBusyChange,
}: {
  source: SourceSummary;
  disabled?: boolean;
  onBusyChange: (busy: boolean) => void;
}) {
  const queryClient = useQueryClient();
  const notify = useActionNotifications();
  const session = useApplicationSession();
  const canManage = session.capabilities.includes("SOURCES_MANAGE");
  const capabilities = session.capabilities.join(",");
  const clientInput = useRef<GoogleDriveOAuthClientInputHandle>(null);
  const [clientReady, setClientReady] = useState(false);
  const [replaceClient, setReplaceClient] = useState(false);
  const configurationKey = getGoogleDriveConfigurationQueryKey({ path: { sourceId: source.id } });
  const configurationQuery = useQuery({
    ...getGoogleDriveConfigurationOptions({ path: { sourceId: source.id } }),
    enabled: canManage,
    retry: false,
    refetchInterval: (query) =>
      query.state.data?.pendingWork || source.pendingWork ? 1_500 : 5_000,
  });
  const credentials = useQuery({
    ...listGoogleDriveCredentialsOptions(),
    enabled: canManage,
    retry: false,
  });
  const credential = credentials.data?.find(
    (entry) => entry.id === configurationQuery.data?.credentialId,
  );
  const replaceRoots = useMutation(replaceGoogleDriveRootsMutation());
  const synchronize = useMutation(synchronizeGoogleDriveSourceMutation());
  const revoke = useMutation(revokeGoogleDriveCredentialMutation());
  const updateSchedule = useMutation({ ...updateGoogleDriveScheduleMutation(), retry: false });
  const [intervalDraft, setIntervalDraft] = useState<IntervalDraft | null>(null);
  const [intervalRevisionConflict, setIntervalRevisionConflict] = useState(false);
  const [intervalError, setIntervalError] = useState<string | null>(null);
  const intervalInput = useRef<HTMLInputElement>(null);
  const intervalEditButton = useRef<HTMLButtonElement>(null);
  const wasEditingInterval = useRef(false);
  const [draft, setDraft] = useState<RootDraft | null>(null);
  const [revisionConflict, setRevisionConflict] = useState(false);
  const [activeAction, setActiveAction] = useState<DriveAction | null>(null);
  const [leaving, setLeaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const actionLock = useRef(false);
  const authorizationController = useRef<AbortController | null>(null);
  const synchronizationController = useRef<AbortController | null>(null);
  const actionController = useRef<AbortController | null>(null);
  const refreshController = useRef<AbortController | null>(null);
  const credentialRefreshController = useRef<AbortController | null>(null);
  const [observingSynchronization, setObservingSynchronization] = useState(false);
  const busy = activeAction !== null || leaving;
  const configuration = configurationQuery.data;
  const stale = configurationQuery.isError;
  const controlsDisabled = disabled || !canManage || busy || stale;
  const connected =
    configuration?.credentialStatus === "ACTIVE" && configuration.oauthClientConfigured;
  const needsClient = !configuration?.oauthClientConfigured || replaceClient;
  const conflicted =
    revisionConflict ||
    Boolean(
      draft &&
      configuration &&
      (draft.revision !== configuration.revision ||
        draft.credentialRevision !== configuration.credentialRevision ||
        draft.credentialId !== configuration.credentialId ||
        draft.actorId !== session.actorId),
    );
  const savedLinks = (configuration?.roots ?? []).map(googleDriveRootLink);
  const linksText = draft?.links ?? savedLinks.join("\n");
  const scopeMode = draft?.scopeMode ?? configuration?.scopeMode;
  const links = scopeMode === "GENERAL" ? [] : parseGoogleDriveLinks(linksText);
  const validSelection =
    scopeMode === "GENERAL" ||
    (scopeMode === "SPECIFIC" &&
      links.length >= 1 &&
      links.length <= MAX_GOOGLE_DRIVE_ROOTS &&
      links.every((link) => link.length <= MAX_GOOGLE_DRIVE_LINK_LENGTH));
  const hasSelectionChanges = Boolean(
    draft &&
    (conflicted ||
      scopeMode !== configuration?.scopeMode ||
      (scopeMode === "SPECIFIC" &&
        (links.length !== savedLinks.length ||
          links.some((link, index) => link !== savedLinks[index])))),
  );
  const hasSavedSelection =
    configuration?.scopeMode === "GENERAL" ||
    (configuration?.scopeMode === "SPECIFIC" && configuration.roots.length > 0);

  const editingInterval = intervalDraft !== null;
  const intervalMinutes = Number(intervalDraft?.minutes);
  const intervalValidation =
    intervalDraft &&
    (!/^\d+$/.test(intervalDraft.minutes) ||
      !Number.isInteger(intervalMinutes) ||
      intervalMinutes < 1 ||
      intervalMinutes > MAX_SYNC_INTERVAL_MINUTES)
      ? "Enter a whole number of minutes from 1 to 2147483647."
      : null;
  const intervalConflicted =
    intervalRevisionConflict ||
    Boolean(intervalDraft && configuration?.scheduleRevision !== intervalDraft.scheduleRevision);

  useLayoutEffect(() => {
    if (busy) return;
    if (editingInterval) intervalInput.current?.focus();
    else if (wasEditingInterval.current) intervalEditButton.current?.focus();
    wasEditingInterval.current = editingInterval;
  }, [editingInterval, busy]);
  useEffect(() => {
    onBusyChange(busy);
    return () => onBusyChange(false);
  }, [busy, onBusyChange]);

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
  }, [session.actorId, session.tenant.role, capabilities, configuration?.credentialRevision]);

  useLayoutEffect(
    () => () => {
      refreshController.current?.abort();
      credentialRefreshController.current?.abort();
    },
    [source.id, session.actorId, session.tenant.role, capabilities],
  );

  async function refresh(throwOnError = false) {
    await queryClient.cancelQueries({ queryKey: configurationKey });
    await Promise.all([
      configurationQuery.refetch({ throwOnError }),
      queryClient.invalidateQueries(
        { queryKey: getSourceQueryKey({ path: { sourceId: source.id } }) },
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
          description: `${source.name}: displayed values may be out of date. Try refreshing again.`,
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
          description: sourceMutationError(cause, "google-drive"),
        });
    } finally {
      if (credentialRefreshController.current === controller)
        credentialRefreshController.current = null;
    }
  }

  async function perform(action: DriveAction, task: (signal: AbortSignal) => Promise<void>) {
    if (actionLock.current || disabled || !canManage || leaving)
      throw new Error("A source action is already in progress");
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
      if (action !== "sync") {
        const titles: Record<Exclude<DriveAction, "sync">, string> = {
          save: "Selection save failed",
          authorize: "Reconnect could not start",
          disconnect: "Credential disconnect failed",
          reload: "Selection reload failed",
          "save-interval": "Automatic interval save failed",
          "reload-interval": "Interval reload failed",
        };
        notify({
          tone: "error",
          title: titles[action],
          description: sourceMutationError(
            cause,
            action === "save-interval" || action === "reload-interval"
              ? "google-drive-schedule"
              : "google-drive",
          ),
        });
      }
      if (isGoogleDriveRevisionConflict(cause) && action === "save") setRevisionConflict(true);
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
    void perform(action, task).catch((cause: unknown) =>
      setError(sourceMutationError(cause, "google-drive")),
    );
  }

  async function incorporateConfiguration(saved: GetGoogleDriveConfigurationResponse) {
    await queryClient.cancelQueries({ queryKey: configurationKey });
    queryClient.setQueryData(
      configurationKey,
      (current: GetGoogleDriveConfigurationResponse | undefined) => {
        if (!current) return saved;
        const scope =
          current.revision > saved.revision || current.credentialRevision > saved.credentialRevision
            ? current
            : saved;
        const schedule = current.scheduleRevision > saved.scheduleRevision ? current : saved;
        return {
          ...scope,
          syncIntervalMinutes: schedule.syncIntervalMinutes,
          scheduleRevision: schedule.scheduleRevision,
        };
      },
    );
  }

  async function save(signal: AbortSignal) {
    if (!draft || !hasSelectionChanges || !connected || conflicted || stale || !validSelection)
      return;
    const saved = await replaceRoots.mutateAsync({
      path: { sourceId: source.id },
      headers: { ...sameOriginMutationHeaders, "If-Match": `"${draft.revision}"` },
      body: { scopeMode: draft.scopeMode, links },
      signal,
    });
    signal.throwIfAborted();
    await incorporateConfiguration(saved);
    signal.throwIfAborted();
    setDraft(null);
    setRevisionConflict(false);
    notify({
      tone: "success",
      title: "Selection saved",
      description: "Automatic synchronization will use the saved scope.",
    });
    await refresh();
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
    if (!intervalDraft || intervalValidation || intervalConflicted || stale) return;
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
      description: `Synchronizes every ${saved.syncIntervalMinutes} ${saved.syncIntervalMinutes === 1 ? "minute" : "minutes"}. Current work is unchanged.`,
    });
    await refresh();
  }

  async function reloadInterval(signal: AbortSignal) {
    const { data: saved } = await configurationQuery.refetch({ throwOnError: true });
    signal.throwIfAborted();
    if (!saved) return;
    setIntervalDraft({
      minutes: String(saved.syncIntervalMinutes),
      scheduleRevision: saved.scheduleRevision,
    });
    setIntervalRevisionConflict(false);
    notify({
      tone: "info",
      title: "Saved interval loaded",
      description: "Local interval changes were discarded.",
    });
    intervalInput.current?.focus();
  }

  function cancelInterval() {
    setIntervalDraft(null);
    setIntervalRevisionConflict(false);
    setIntervalError(null);
  }

  async function sync() {
    if (
      !connected ||
      hasSelectionChanges ||
      stale ||
      !hasSavedSelection ||
      synchronizationController.current
    )
      return;
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
      notify({
        tone: "error",
        title: "Synchronization could not start",
        description: sourceMutationError(cause, "google-drive"),
      });
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
          description: `${source.name}: selected content is synchronized. Indexing may still be running.`,
        });
      } else if (completed.status === "SUPERSEDED") {
        notify({
          tone: "info",
          title: "Synchronization superseded",
          description: `${source.name}: this request was replaced by newer work.`,
        });
      } else {
        notify({
          tone: "error",
          title: "Synchronization failed",
          description: sourceStatusMessage(completed.errorCode ?? "SOURCE_SYNC_FAILED"),
        });
      }
      await refresh();
    } catch {
      if (!controller.signal.aborted)
        notify({
          tone: "error",
          title: "Synchronization status unavailable",
          description:
            "Synchronization may still be running. Refresh the source to check its status.",
        });
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
      description: `${credential?.name ?? configuration.accountEmail}: synchronization is stopped for every attached Source. Saved links and documents are retained.`,
    });
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: [{ _id: "getGoogleDriveConfiguration" }] }),
      queryClient.invalidateQueries({ queryKey: [{ _id: "getSource" }] }),
      queryClient.invalidateQueries({ queryKey: listSourcesQueryKey() }),
      queryClient.invalidateQueries({ queryKey: listGoogleDriveCredentialsQueryKey() }),
    ]);
  }

  function changeSelection(change: Partial<Pick<RootDraft, "links" | "scopeMode">>) {
    if (!configuration) return;
    setDraft((current) => ({
      revision: configuration.revision,
      credentialRevision: configuration.credentialRevision,
      credentialId: configuration.credentialId,
      actorId: session.actorId,
      scopeMode: configuration.scopeMode,
      links: savedLinks.join("\n"),
      ...current,
      ...change,
    }));
    setError(null);
  }

  async function reloadSelection(signal: AbortSignal) {
    await configurationQuery.refetch({ throwOnError: true });
    signal.throwIfAborted();
    setDraft(null);
    setRevisionConflict(false);
    notify({
      tone: "info",
      title: "Saved selection loaded",
      description: "Local selection changes were discarded.",
    });
  }

  if (!configuration) {
    return (
      <div className="border-b border-border-subtle p-5 sm:p-6">
        {configurationQuery.isPending ? (
          <p role="status" className="text-sm text-content-muted">
            Loading Google Drive connection…
          </p>
        ) : (
          <div className="space-y-3">
            <p role="alert" className="text-sm text-status-danger-content">
              {sourceMutationError(configurationQuery.error, "google-drive")}
            </p>
            <Button
              prominence="secondary"
              pending={configurationQuery.isFetching}
              onClick={() => void refreshStatus()}
            >
              Retry connection status
            </Button>
          </div>
        )}
      </div>
    );
  }

  return (
    <section aria-label="Google Drive configuration" className="space-y-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-2">
          <h2 className="font-heading-h3 text-content-primary">Synchronization</h2>
          <HelpPopover label="Synchronization">
            <p>
              Automatic sync uses this Source’s saved interval. Saving an interval schedules the
              next run from the save time and does not interrupt current work. Start time depends on
              availability and pending work. Synchronize now requests a run.
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
            <RefreshCw /> Refresh status
          </Button>
          {connected ? (
            <Button
              disabled={
                controlsDisabled ||
                hasSelectionChanges ||
                !hasSavedSelection ||
                configuration.pendingWork ||
                source.pendingWork
              }
              pending={activeAction === "sync" || observingSynchronization}
              onClick={() => run("sync", sync)}
            >
              <RefreshCw /> Synchronize now
            </Button>
          ) : null}
        </div>
      </div>
      {stale ? (
        <p role="alert" className="text-sm text-status-danger-content">
          Status could not be refreshed. Displayed values may be out of date; refresh before making
          changes.
        </p>
      ) : null}
      {error ? (
        <p role="alert" className="text-sm text-status-danger-content">
          {error}
        </p>
      ) : null}
      {configuration.errorCode ? (
        <p className="text-sm text-status-danger-content">
          {sourceStatusMessage(configuration.errorCode)}
        </p>
      ) : null}
      <dl
        className="grid gap-5 rounded-lg border border-border-subtle p-4 text-sm sm:grid-cols-3"
        aria-live="polite"
      >
        <div>
          <dt className="text-content-muted">Current work</dt>
          <dd className="mt-2 text-content-primary">
            {configuration.pendingWork || source.pendingWork
              ? "Acquisition or indexing in progress"
              : "No work pending"}
          </dd>
        </div>
        <div>
          <dt className="text-content-muted">Last synchronized</dt>
          <dd className="mt-2 text-content-primary">
            {configuration.lastSyncedAt ? (
              <time dateTime={configuration.lastSyncedAt}>
                {new Date(configuration.lastSyncedAt).toLocaleString()}
              </time>
            ) : (
              "Not yet"
            )}
          </dd>
        </div>
        <div>
          <dt className="text-content-muted">Automatic interval</dt>
          <dd className="mt-2 space-y-3 text-content-primary">
            <div className="flex flex-wrap items-center gap-2">
              <span>
                {configuration.syncIntervalMinutes}{" "}
                {configuration.syncIntervalMinutes === 1 ? "minute" : "minutes"}
              </span>
              {!editingInterval ? (
                <Button
                  ref={intervalEditButton}
                  prominence="tertiary"
                  disabled={controlsDisabled}
                  onClick={() => {
                    setIntervalDraft({
                      minutes: String(configuration.syncIntervalMinutes),
                      scheduleRevision: configuration.scheduleRevision,
                    });
                    setIntervalError(null);
                  }}
                >
                  Edit interval
                </Button>
              ) : null}
            </div>
            {intervalDraft ? (
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
                <label className="block space-y-1">
                  <span>Interval in minutes</span>
                  <Input
                    ref={intervalInput}
                    type="number"
                    inputMode="numeric"
                    min={1}
                    max={MAX_SYNC_INTERVAL_MINUTES}
                    step={1}
                    required
                    value={intervalDraft.minutes}
                    disabled={controlsDisabled}
                    aria-invalid={Boolean(intervalValidation)}
                    aria-describedby={
                      intervalValidation ? `sync-interval-error-${source.id}` : undefined
                    }
                    onChange={(event) => {
                      setIntervalDraft({ ...intervalDraft, minutes: event.target.value });
                      setIntervalError(null);
                    }}
                  />
                </label>
                {intervalValidation ? (
                  <p
                    id={`sync-interval-error-${source.id}`}
                    role="alert"
                    className="text-status-danger-content"
                  >
                    {intervalValidation}
                  </p>
                ) : null}
                {intervalConflicted ? (
                  <p role="alert" className="text-status-warning-content">
                    The automatic interval changed while you were editing. Your interval draft has
                    not been saved. Reload the saved interval before continuing.
                  </p>
                ) : null}
                <div className="flex flex-wrap gap-2">
                  <Button
                    type="submit"
                    disabled={controlsDisabled || intervalConflicted || Boolean(intervalValidation)}
                    pending={activeAction === "save-interval"}
                  >
                    Save interval
                  </Button>
                  <Button prominence="tertiary" disabled={busy} onClick={cancelInterval}>
                    Cancel
                  </Button>
                  {intervalConflicted ? (
                    <Button
                      prominence="secondary"
                      disabled={disabled || busy || !canManage}
                      pending={activeAction === "reload-interval"}
                      onClick={() => runInterval("reload-interval", reloadInterval)}
                    >
                      Reload saved interval
                    </Button>
                  ) : null}
                </div>
              </form>
            ) : null}
            {intervalError ? (
              <p role="alert" className="text-status-danger-content">
                {intervalError}
              </p>
            ) : null}
          </dd>
        </div>
      </dl>
      {!connected ? (
        <p className="rounded-lg bg-status-warning-surface p-4 text-sm text-status-warning-content">
          Reconnect the same Google account to save links and synchronize. Saved roots are retained.
        </p>
      ) : null}
      <details
        className="group border-b border-border-subtle pb-5"
        open={!connected ? true : undefined}
      >
        <summary className="flex min-h-11 cursor-pointer list-none flex-wrap items-center gap-3 rounded-lg text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus-ring">
          <GoogleDriveIcon className="size-5" aria-hidden="true" />
          <span className="font-medium text-content-primary">Credentials</span>
          <StatusBadge tone={connected ? "success" : "warning"}>
            {connected ? "Connected" : "Needs reconnect"}
          </StatusBadge>
          <span className="min-w-0 break-all text-content-muted">{configuration.accountEmail}</span>
          <span className="ml-auto inline-flex items-center gap-2 text-content-muted">
            <span className="group-open:hidden">Manage connection</span>
            <span className="hidden group-open:inline">Close</span>
            <ChevronDown
              className="size-4 group-open:rotate-180 motion-safe:transition-transform"
              aria-hidden="true"
            />
          </span>
        </summary>
        <div className="mt-4 space-y-4">
          <p className="rounded-lg bg-status-warning-surface p-4 text-sm text-status-warning-content">
            This credential is shared. Reconnecting or disconnecting affects all Sources using it
            {credential ? ` (${credential.sourceCount} Sources)` : ""}, not just this Source. Saved
            links and indexed documents are retained.
          </p>
          {credentials.isError ? (
            <div className="space-y-2">
              <p role="alert" className="text-sm text-status-danger-content">
                Credential details could not be loaded. Refresh before reconnecting.
              </p>
              <Button
                prominence="secondary"
                pending={credentials.isFetching}
                onClick={() => void refreshCredentials()}
              >
                Retry credential details
              </Button>
            </div>
          ) : null}
          <div className="space-y-3">
            {!configuration.oauthClientConfigured ? (
              <p className="rounded-lg bg-status-warning-surface p-4 text-sm text-status-warning-content">
                This connection has no owner-supplied OAuth app. Upload or paste your Google Web
                OAuth client JSON below, then reconnect the same Google account. Saved files and
                folders are retained.
              </p>
            ) : (
              <>
                <p className="text-sm text-content-secondary">
                  Reconnect reuses the OAuth app saved with this shared credential unless you
                  replace it.
                </p>
                <label className="flex items-center gap-2 text-sm text-content-primary">
                  <input
                    type="checkbox"
                    checked={replaceClient}
                    disabled={controlsDisabled || hasSelectionChanges}
                    className="size-4 accent-primary focus-visible:ring-3 focus-visible:ring-focus-ring"
                    onChange={(event) => {
                      clientInput.current?.clear();
                      setClientReady(false);
                      setReplaceClient(event.target.checked);
                    }}
                  />
                  Replace OAuth app on reconnect
                </label>
              </>
            )}
            {needsClient ? (
              <GoogleDriveOAuthClientInput
                key={configuration.credentialRevision}
                ref={clientInput}
                disabled={controlsDisabled || hasSelectionChanges}
                onReadyChange={setClientReady}
              />
            ) : null}
          </div>
          <div className="flex flex-wrap gap-2">
            <ConfirmDialog
              trigger={
                <Button
                  prominence="secondary"
                  disabled={
                    controlsDisabled ||
                    !credential ||
                    credentials.isError ||
                    hasSelectionChanges ||
                    (needsClient && !clientReady)
                  }
                  pending={activeAction === "authorize" || leaving}
                >
                  Reconnect Google Drive
                </Button>
              }
              title="Reconnect shared Google credential?"
              description={`Reconnecting changes the authorization used by all ${credential?.sourceCount ?? "attached"} Sources, including other Sources. Use the same Google account. Saved links and indexed documents are retained.`}
              confirmLabel="Reconnect"
              pendingLabel="Reconnecting"
              onConfirm={() => perform("authorize", reconnect)}
              errorMessage={(cause) => sourceMutationError(cause, "google-drive")}
            />
            {configuration.credentialStatus !== "REVOKED" ? (
              <ConfirmDialog
                trigger={
                  <Button tone="danger" prominence="tertiary" disabled={controlsDisabled}>
                    <Unplug /> Disconnect
                  </Button>
                }
                title="Disconnect shared Google credential?"
                description={`Disconnecting stops acquisition for all ${credential?.sourceCount ?? "attached"} Sources using this credential, including other Sources. Stored data is not deleted. Reconnect the same Google account to resume.`}
                confirmLabel="Disconnect"
                pendingLabel="Disconnecting"
                onConfirm={() => perform("disconnect", disconnect)}
                errorMessage={(cause) => sourceMutationError(cause, "google-drive")}
              />
            ) : null}
          </div>
          <p className="text-xs text-content-muted">
            Document access and viewing are not configured by this connection.
          </p>
        </div>
      </details>
      <div className="flex min-w-0 flex-col gap-6 md:flex-row">
        <div className="min-w-0 flex-1 space-y-8">
          <section aria-label="Selected content" className="space-y-4">
            <GoogleDriveLinks
              roots={configuration.roots}
              scopeMode={draft?.scopeMode ?? configuration.scopeMode}
              savedScopeMode={configuration.scopeMode}
              onScopeModeChange={(mode) => changeSelection({ scopeMode: mode })}
              value={linksText}
              disabled={controlsDisabled || !connected}
              onChange={(value) => changeSelection({ links: value })}
            />
            {conflicted ? (
              <p
                role="alert"
                className="rounded-lg bg-status-warning-surface p-4 text-sm text-status-warning-content"
              >
                The saved selection or Google connection changed while you were editing. Your draft
                has not been saved. Reload the saved selection before continuing.
              </p>
            ) : hasSelectionChanges ? (
              <p role="status" className="text-sm text-content-muted">
                You have unsaved selection changes.
              </p>
            ) : null}
            <div className="flex flex-wrap items-center justify-end gap-2">
              {hasSelectionChanges || revisionConflict ? (
                <Button
                  prominence="secondary"
                  disabled={disabled || busy}
                  pending={activeAction === "reload"}
                  onClick={() => run("reload", reloadSelection)}
                >
                  Reload saved selection
                </Button>
              ) : null}
              {connected ? (
                <Button
                  disabled={
                    controlsDisabled || !hasSelectionChanges || conflicted || !validSelection
                  }
                  pending={activeAction === "save"}
                  onClick={() => run("save", save)}
                >
                  Save selection
                </Button>
              ) : null}
            </div>
          </section>
        </div>
      </div>
    </section>
  );
}
