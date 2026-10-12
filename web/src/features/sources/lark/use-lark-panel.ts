import { appText, type AppCopy } from "@/i18n/app-text";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useLayoutEffect, useRef, useState } from "react";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { useCapabilityAuthority } from "@/features/identity/application-session-context";
import {
  getLarkConfigurationOptions,
  getLarkRootsOptions,
  getLarkSelectionRequestOptions,
  listLarkCredentialsOptions,
  listSourcesQueryKey,
  replaceLarkScopeMutation,
  synchronizeLarkSourceMutation,
  updateLarkPauseMutation,
  updateLarkScheduleMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SourceOperation, SourceSummary } from "@/lib/hey-api/types.gen";
import { can } from "@/lib/resource-permissions";
import { useManualRefresh } from "@/lib/use-manual-refresh";
import { sourceMutationError, sourceStatusMessage } from "@/features/sources/shared/source-errors";
import { sourceOperationNotice } from "@/features/sources/shared/source-operation-notice";
import { waitForSourceOperation } from "@/features/sources/shared/source-operations";
import { useSourceSelectionOperation } from "@/features/sources/shared/source-selection-operation";
import { larkRegion, larkScopeRequest, type LarkScopeDraft } from "./lark-scope";

type PanelAction = "sync" | "pause" | "schedule" | "scope";

/**
 * The Lark configuration of a Source and every action on it: one at a time, a scope change
 * followed until Lark verifies it, and a synchronization followed until it settles.
 */
export function useLarkPanel({
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
  const authority = useCapabilityAuthority("SOURCES_MANAGE");
  const configurationQuery = useQuery({
    ...getLarkConfigurationOptions({ path: { sourceId: source.id } }),
    retry: false,
    refetchInterval: (query) =>
      query.state.data?.pendingWork || source.pendingWork ? 1_500 : false,
  });
  const configuration = configurationQuery.data;
  // This panel polls while work is pending, so its refresh controls follow the press, not the poll.
  const connectionRetry = useManualRefresh(configurationQuery.refetch);
  const statusRefresh = useManualRefresh(refresh);
  const credentials = useQuery({
    ...listLarkCredentialsOptions(),
    enabled: authority !== "none",
    retry: false,
  });
  const roots = useQuery({
    ...getLarkRootsOptions({ path: { sourceId: source.id } }),
    retry: false,
  });
  const synchronize = useMutation(synchronizeLarkSourceMutation());
  const updatePause = useMutation({ ...updateLarkPauseMutation(), retry: false });
  const updateSchedule = useMutation({ ...updateLarkScheduleMutation(), retry: false });
  const replaceScope = useMutation({ ...replaceLarkScopeMutation(), retry: false });
  const tracking = useSourceSelectionOperation({
    provider: "lark",
    scope: source.id,
    pending: configuration?.pendingSelectionOperation,
    recover: (requestId) => getLarkSelectionRequestOptions({ path: { requestId } }),
  });
  const [activeAction, setActiveAction] = useState<PanelAction | null>(null);
  const [error, setError] = useState<AppCopy | null>(null);
  const [scopeDraft, setScopeDraft] = useState<LarkScopeDraft | null>(null);
  const [observing, setObserving] = useState(false);
  const controller = useRef<AbortController | null>(null);
  const active = useRef(true);
  const busy = Boolean(activeAction) || observing;
  const stale = sourceStale || configurationQuery.isError;
  const verifying = Boolean(tracking.operation && !tracking.terminal);
  // Source-level pause (the header menu) outranks the schedule's own pause and blocks every change here.
  const sourcePaused = source.status === "PAUSED" || source.status === "PAUSING";
  const credential = credentials.data?.find((entry) => entry.id === configuration?.credentialId);

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

  // A settled scope change is taken in once; anything but success explains itself.
  const terminal = tracking.terminal ? tracking.operation : null;
  const [settled, setSettled] = useState<string | null>(null);
  if (terminal && settled !== terminal.id) {
    setSettled(terminal.id);
    if (terminal.status !== "SUCCEEDED")
      setError(
        terminal.status === "SUPERSEDED"
          ? "This scope change was superseded or cancelled. The saved scope is unchanged."
          : sourceStatusMessage(terminal.errorCode ?? "SOURCE_LARK_SELECTION_FAILED"),
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
          setError(sourceMutationError(cause, action === "schedule" ? "lark-schedule" : "lark"));
      })
      .finally(() => {
        if (active.current) setActiveAction(null);
      });
  }

  async function sync() {
    const operation = await synchronize.mutateAsync({ path: { sourceId: source.id } });
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
      notify(
        sourceOperationNotice(completed, {
          subject: source.name,
          titles: {
            succeeded: "Synchronization complete",
            superseded: "Synchronization superseded",
            cancelled: "Synchronization cancelled",
            failed: "Synchronization failed",
          },
          succeeded: appText("{{v1}}: content is synchronized. Indexing may still be running.", {
            v1: source.name,
          }),
          failureCode: "SOURCE_SYNC_FAILED",
          failureSubject: false,
        }),
      );
      await refresh();
    } catch {
      if (!own.signal.aborted)
        notify({
          tone: "error",
          title: "Synchronization status unavailable",
          description: appText(
            "Synchronization may still be running. Refresh the source to check its status.",
          ),
        });
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
      body: { expectedRevision: configuration.scheduleRevision, paused: !configuration.syncPaused },
    });
    await refresh();
  }

  async function saveSchedule(syncIntervalMinutes: number, scheduleRevision: number) {
    await updateSchedule.mutateAsync({
      path: { sourceId: source.id },
      headers: { "If-Match": `"${scheduleRevision}"` },
      body: { syncIntervalMinutes },
    });
    await refresh();
  }

  async function saveScope() {
    if (!configuration || !scopeDraft) return;
    const requestId = tracking.begin(true);
    const receipt = await replaceScope.mutateAsync({
      path: { sourceId: source.id },
      headers: { "If-Match": `"${configuration.scopeRevision}"` },
      body: {
        requestId,
        expectedCredentialRevision: configuration.credentialRevision,
        scope: larkScopeRequest(scopeDraft, larkRegion(credential?.region)),
      },
    });
    if (!active.current) return;
    tracking.accept(receipt);
    setScopeDraft(null);
    await refresh();
  }

  return {
    configurationQuery,
    configuration,
    credential,
    roots,
    tracking,
    connectionRetry,
    statusRefresh,
    permissions: {
      configure: can(source, "manageConfiguration"),
      schedule: can(source, "edit"),
      synchronize: can(source, "edit"),
    },
    activeAction,
    error,
    busy,
    observing,
    stale,
    verifying,
    sourcePaused,
    controlsDisabled: disabled || busy || stale || verifying || sourcePaused,
    scopeDraft,
    setScopeDraft,
    synchronize: () => run("sync", sync),
    togglePause: () => run("pause", togglePause),
    saveScope: () => run("scope", saveScope),
    saveSchedule: (syncIntervalMinutes: number, scheduleRevision: number) =>
      run("schedule", () => saveSchedule(syncIntervalMinutes, scheduleRevision)),
  };
}

export type LarkPanelState = ReturnType<typeof useLarkPanel>;
