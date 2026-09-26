import { appText, type AppCopy } from "@/i18n/app-text";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useLayoutEffect, useRef, useState } from "react";
import { useActionNotifications } from "@/components/ui/action-notifications";
import {
  getSourceOptions,
  getSourceQueryKey,
  listSourceItemsQueryKey,
  listSourcesQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { can } from "@/lib/resource-permissions";
import { useManualRefresh } from "@/lib/use-manual-refresh";
import { sourceMutationError } from "@/features/sources/shared/source-errors";
import { IDLE_SOURCE_POLL_MS } from "@/features/sources/shared/source-polling";
import { useSourceFiles } from "./use-source-files";
import { useSourceItemActions } from "./use-source-item-actions";
import { useSourceLifecycle } from "./use-source-lifecycle";
import { useSourceUpload } from "./use-source-upload";

/**
 * One Source as its detail page works with it: the Source and its files, polled while work is
 * pending, what the actor may do with it, and the upload, file and lifecycle actions with the
 * busy state that holds the other controls still.
 */
export function useSourceDetail(sourceId: string) {
  const queryClient = useQueryClient();
  const notify = useActionNotifications();
  const active = useRef(true);
  const [error, setError] = useState<AppCopy | null>(null);
  // Provider panels report their own work in flight, which holds the page's controls too.
  const [providerBusy, setProviderBusy] = useState(false);

  useLayoutEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
    };
  }, []);

  const sourceQuery = useQuery({
    ...getSourceOptions({ path: { sourceId } }),
    retry: false,
    refetchInterval: (query) => (query.state.data?.pendingWork ? 1_500 : IDLE_SOURCE_POLL_MS),
  });
  const source = sourceQuery.data;
  const files = useSourceFiles(sourceId, source);
  // The Source polls while work is pending, so its refresh control follows the press rather than the poll.
  const sourceRefresh = useManualRefresh(async () => {
    try {
      await sourceQuery.refetch({ throwOnError: true });
      if (active.current)
        notify({ tone: "success", title: "Source refreshed", description: source?.name });
    } catch (cause) {
      if (active.current)
        notify({
          tone: "error",
          title: "Source refresh failed",
          description: appText(sourceMutationError(cause, "reindex")),
        });
    }
  });

  async function refresh(resetFiles = false) {
    const filesKey = listSourceItemsQueryKey({ path: { sourceId } });
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: listSourcesQueryKey() }),
      queryClient.invalidateQueries({ queryKey: getSourceQueryKey({ path: { sourceId } }) }),
      queryClient.invalidateQueries({
        queryKey: filesKey,
        refetchType: resetFiles ? "none" : "active",
      }),
    ]);
    if (resetFiles && active.current) {
      files.paging.reset();
      await queryClient.refetchQueries({ queryKey: filesKey, type: "active" });
    }
  }

  const paused = source?.status === "PAUSED" || source?.status === "PAUSING";
  const permissions = {
    upload: source?.type === "FILE" && can(source, "edit") && !paused,
    reindex: can(source, "edit") && !paused,
    removeItems: can(source, "removeItems"),
    delete: can(source, "delete"),
    /** Renaming, pausing and group associations follow the right to edit the Source. */
    edit: can(source, "edit"),
    changeAccess: can(source, "publish"),
  };
  const { upload, fileInput } = useSourceUpload({
    sourceId,
    canUpload: permissions.upload,
    setError,
    onAccepted: () => refresh(true),
  });
  const itemActions = useSourceItemActions({
    sourceId,
    canReindex: permissions.reindex,
    canRemove: permissions.removeItems,
    setError,
    refresh,
  });
  const lifecycle = useSourceLifecycle({
    sourceId,
    sourceName: source?.name,
    canDelete: permissions.delete,
    setError,
    refresh,
  });
  const managementBusy =
    upload.busy ||
    itemActions.busy ||
    lifecycle.deleting ||
    lifecycle.pausing ||
    sourceQuery.isError;

  return {
    sourceQuery,
    source,
    files,
    sourceRefresh,
    permissions,
    upload,
    fileInput,
    itemActions,
    lifecycle,
    error,
    setProviderBusy,
    /** Work of the page itself, which holds a provider panel's controls. */
    managementBusy,
    /** Any work on the Source, which holds the page's own controls. */
    busy: managementBusy || providerBusy,
    /** Work that holds the per-file actions. */
    itemBusy: upload.busy || lifecycle.deleting || sourceQuery.isError || providerBusy,
    /** Resumes a paused Source or pauses a running one, unless other work holds the Source. */
    togglePause(resume: boolean) {
      if (!(managementBusy || providerBusy)) void lifecycle.togglePause(resume);
    },
  };
}

export type SourceDetail = ReturnType<typeof useSourceDetail>;
