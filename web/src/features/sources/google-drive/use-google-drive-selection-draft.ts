import type { AppCopy } from "@/i18n/app-text";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useEffectEvent, useLayoutEffect, useMemo, useRef, useState } from "react";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { ApiError } from "@/lib/api";
import {
  discoverGoogleDriveLinkedDocumentsMutation,
  getGoogleDriveConfigurationQueryKey,
  getGoogleDriveSelectionDraftOptions,
  getGoogleDriveSelectionPolicyOptions,
  getGoogleDriveSelectionTreeOptions,
  replaceGoogleDriveRootsMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type {
  GetGoogleDriveConfigurationResponse,
  GoogleDriveSelectionDraftResponse,
  GoogleDriveSelectionItemResponse,
} from "@/lib/hey-api/types.gen";
import {
  isGoogleDriveRevisionConflict,
  sourceMutationError,
  sourceStatusMessage,
} from "@/features/sources/shared/source-errors";
import { googleDriveSelectionError, parseGoogleDriveLinks } from "./google-drive-selection";
import { useGoogleDriveSelectionOperation } from "./google-drive-selection-operation";
import type { GoogleDriveSelectionResults } from "./use-google-drive-selection-results";

type Draft = {
  saved: GoogleDriveSelectionDraftResponse;
  links: string;
  approved: Set<string>;
  actorId: string;
  editingRoots: boolean;
};

type SelectionAction = "load" | "save" | "discover";

const unusedRequestId = "00000000-0000-4000-8000-000000000000";

/** Whether a read still belongs to the selection, discovery and credential the panel shows. */
function sameRevisions(
  read: Pick<
    GoogleDriveSelectionDraftResponse,
    "revision" | "discoveryRevision" | "credentialRevision"
  >,
  configuration: GetGoogleDriveConfigurationResponse,
) {
  return (
    read.revision === configuration.revision &&
    read.discoveryRevision === configuration.discoveryRevision &&
    read.credentialRevision === configuration.credentialRevision
  );
}

/**
 * A replacement proposal for the Selected content of a Google Drive Source: root links and
 * linked-document approvals edited in a draft loaded from the saved selection, fenced by the
 * revisions it was read at, submitted once per idempotency key and followed until the server
 * validates it. Focus follows the draft: into it when it opens, back to its origin when it closes.
 */
export function useGoogleDriveSelectionDraft({
  sourceId,
  configuration,
  results,
  disabled,
  onEditingChange,
  onBusyChange,
  onActivated,
}: {
  sourceId: string;
  configuration: GetGoogleDriveConfigurationResponse;
  results: GoogleDriveSelectionResults;
  disabled: boolean;
  onEditingChange: (editing: boolean) => void;
  onBusyChange: (busy: boolean) => void;
  onActivated: () => Promise<void>;
}) {
  const session = useApplicationSession();
  const client = useQueryClient();
  const policy = useQuery({
    ...getGoogleDriveSelectionPolicyOptions(),
    retry: false,
    staleTime: 60_000,
  });
  const replaceRoots = useMutation(replaceGoogleDriveRootsMutation());
  const discoverLinks = useMutation(discoverGoogleDriveLinkedDocumentsMutation());
  const [draft, setDraft] = useState<Draft | null>(null);
  const [action, setAction] = useState<SelectionAction | null>(null);
  const controller = useRef<AbortController | null>(null);
  const input = useRef<HTMLTextAreaElement>(null);
  const editButton = useRef<HTMLButtonElement>(null);
  const cancelButton = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLElement>(null);
  const selectionControl = useRef<HTMLElement | null>(null);
  const selectionItem = useRef<string | null>(null);
  const draftFocus = useRef<"roots" | "approval" | null>(null);
  const [error, setError] = useState<AppCopy | null>(null);
  const [revisionConflict, setRevisionConflict] = useState(false);
  const tracking = useGoogleDriveSelectionOperation(
    sourceId,
    configuration.pendingSelectionOperation,
  );
  const [processed, setProcessed] = useState<string | null>(null);
  const operation = tracking.operation;
  const pending = Boolean(operation && !tracking.terminal);
  const submitted = Boolean(tracking.receipt && pending);
  const busy = action !== null;
  const conflicted =
    revisionConflict ||
    Boolean(
      draft && (!sameRevisions(draft.saved, configuration) || draft.actorId !== session.actorId),
    );
  const links = useMemo(() => parseGoogleDriveLinks(draft?.links ?? ""), [draft?.links]);
  const rootChanges = Boolean(
    draft &&
    (links.length !== draft.saved.links.length ||
      links.some((link, index) => link !== draft.saved.links[index])),
  );
  const changed = Boolean(
    draft &&
    (rootChanges ||
      draft.approved.size !== draft.saved.linkedDocumentIds.length ||
      draft.saved.linkedDocumentIds.some((id) => !draft.approved.has(id))),
  );
  const proposal = draft
    ? {
        scopeMode: configuration.scopeMode,
        links,
        linkedDocumentIds: [...draft.approved],
        discoveryRevision: draft.saved.discoveryRevision,
        credentialRevision: draft.saved.credentialRevision,
        requestId: tracking.requestId ?? unusedRequestId,
      }
    : null;
  const validation = proposal ? googleDriveSelectionError(proposal, policy.data) : null;
  const controlsDisabled =
    disabled || busy || submitted || tracking.uncertain || tracking.recovering;

  useEffect(() => {
    onEditingChange(changed);
    return () => onEditingChange(false);
  }, [changed, onEditingChange]);
  useEffect(() => {
    onBusyChange(busy);
    return () => onBusyChange(false);
  }, [busy, onBusyChange]);
  useLayoutEffect(() => {
    if (busy) return;
    const mode = draft ? (draft.editingRoots ? "roots" : "approval") : null;
    if (mode && mode !== draftFocus.current) {
      if (mode === "roots") input.current?.focus();
      else {
        // The registry checkbox is a button with role=checkbox, not an input.
        const checkbox = selectionControl.current?.querySelector<HTMLElement>('[role="checkbox"]');
        const usable =
          checkbox && !checkbox.matches(':disabled, [aria-disabled="true"], [data-disabled]');
        if (usable) checkbox.focus();
        else cancelButton.current?.focus();
      }
    }
    if (!mode && draftFocus.current) {
      // Paging a tree branch away and back replaces its rows, so find the item's control again.
      const recorded = selectionControl.current;
      const control =
        !recorded || recorded.isConnected
          ? recorded
          : [
              ...(panel.current?.querySelectorAll<HTMLElement>("[data-selection-control]") ?? []),
            ].find((element) => element.dataset.selectionControl === selectionItem.current);
      const select = control?.querySelector<HTMLElement>('[role="checkbox"]');
      const usable =
        select && !select.matches(':disabled, [aria-disabled="true"], [data-disabled]');
      if (usable) select.focus();
      else editButton.current?.focus();
      selectionControl.current = null;
      selectionItem.current = null;
    }
    draftFocus.current = mode;
  }, [draft, busy]);
  useLayoutEffect(
    () => () => {
      controller.current?.abort();
    },
    [sourceId, session.actorId],
  );
  // A settled operation is taken in once: success closes the draft, anything else explains itself.
  if (operation && tracking.terminal && processed !== operation.id) {
    setProcessed(operation.id);
    if (operation.status === "SUCCEEDED") {
      setDraft(null);
      setError(null);
      setRevisionConflict(false);
    } else {
      setError(
        operation.status === "SUPERSEDED"
          ? "This proposal was superseded or cancelled. The active selection was not replaced by this proposal. Reload the saved selection before editing."
          : sourceStatusMessage(operation.errorCode ?? "SOURCE_GOOGLE_SELECTION_FAILED"),
      );
      if (
        operation.errorCode === "SOURCE_GOOGLE_REVISION_CONFLICT" ||
        operation.status === "SUPERSEDED" ||
        operation.status === "CANCELLED"
      )
        setRevisionConflict(true);
    }
  }
  const handleTerminalOperation = useEffectEvent(() => {
    if (operation?.status !== "SUCCEEDED") return;
    void onActivated()
      .then(() => tracking.forget())
      .catch(() =>
        setError(
          "Selection activated, but status could not be refreshed. Refresh the Source before editing.",
        ),
      );
  });
  useEffect(() => {
    handleTerminalOperation();
  }, [operation, tracking.terminal]);

  async function perform(next: SelectionAction, task: (signal: AbortSignal) => Promise<void>) {
    if (controller.current) return;
    const current = new AbortController();
    controller.current = current;
    setAction(next);
    setError(null);
    try {
      await task(current.signal);
    } catch (cause) {
      if (!current.signal.aborted) {
        if (
          next === "save" &&
          cause instanceof ApiError &&
          cause.status &&
          cause.status >= 400 &&
          cause.status < 500
        )
          tracking.forget();
        setError(
          sourceMutationError(
            cause,
            next === "discover" ? "google-drive-discovery" : "google-drive",
          ),
        );
        if (isGoogleDriveRevisionConflict(cause)) setRevisionConflict(true);
      }
    } finally {
      if (controller.current === current) {
        controller.current = null;
        setAction(null);
      }
    }
  }

  /** The saved selection read afresh; a draft always starts from the complete saved state. */
  function readSavedSelection() {
    return client.fetchQuery({
      ...getGoogleDriveSelectionDraftOptions({ path: { sourceId } }),
      staleTime: 0,
    });
  }

  function invalidateConfiguration() {
    return client.invalidateQueries({
      queryKey: getGoogleDriveConfigurationQueryKey({ path: { sourceId } }),
    });
  }

  function loadDraft(target?: GoogleDriveSelectionItemResponse, control?: HTMLElement) {
    if (target && (target.coveredByRoots || (!target.selected && target.status !== "AVAILABLE")))
      return;
    if (!draft || control) {
      selectionControl.current = control ?? null;
      selectionItem.current = control ? (target?.id ?? null) : null;
    }
    void perform("load", async (signal) => {
      const data = await readSavedSelection();
      signal.throwIfAborted();
      if (target && !sameRevisions(data, configuration)) {
        await onActivated();
        setError("The selection changed. Select the document again from the refreshed content.");
        return;
      }
      const approved = new Set(data.linkedDocumentIds);
      if (target) {
        if (target.selected) approved.delete(target.id);
        else approved.add(target.id);
      }
      tracking.forget();
      setRevisionConflict(false);
      setDraft({
        saved: data,
        links: data.links.join("\n"),
        approved,
        actorId: session.actorId,
        editingRoots: target ? false : (draft?.editingRoots ?? true),
      });
      await invalidateConfiguration();
    });
  }

  function approveBranch(parentId: string, selectAll: boolean) {
    void perform("load", async (signal) => {
      const ids = new Set<string>();
      let cursor: string | undefined;
      do {
        const data = await client.fetchQuery({
          ...getGoogleDriveSelectionTreeOptions({
            path: { sourceId },
            query: { parentId, size: 100, cursor },
          }),
          staleTime: 0,
        });
        signal.throwIfAborted();
        if (!sameRevisions(data, configuration)) {
          await onActivated();
          setError("The selection changed. Select the documents again from the refreshed content.");
          return;
        }
        for (const item of data.items) {
          if (item.kind === "LINKED" && !item.coveredByRoots) ids.add(item.id);
        }
        cursor = data.nextCursor ?? undefined;
      } while (cursor);
      const approved = new Set(draft?.approved ?? []);
      for (const id of ids) {
        if (selectAll) approved.add(id);
        else approved.delete(id);
      }
      await applyApprovals(approved, signal);
    });
  }

  async function applyApprovals(approved: Set<string>, signal: AbortSignal) {
    let saved = draft?.saved ?? null;
    if (!saved) {
      const data = await readSavedSelection();
      signal.throwIfAborted();
      if (!sameRevisions(data, configuration)) {
        await onActivated();
        setError("The selection changed. Select the documents again from the refreshed content.");
        return;
      }
      saved = data;
    }
    tracking.forget();
    setRevisionConflict(false);
    setDraft({
      saved,
      links: draft?.links ?? saved.links.join("\n"),
      approved,
      actorId: session.actorId,
      editingRoots: draft?.editingRoots ?? false,
    });
    await invalidateConfiguration();
  }

  function save() {
    if (
      !draft ||
      !proposal ||
      !changed ||
      conflicted ||
      validation ||
      disabled ||
      busy ||
      submitted
    )
      return;
    void perform("save", async (signal) => {
      const requestId = tracking.begin(tracking.terminal);
      const data = await replaceRoots.mutateAsync({
        path: { sourceId },
        headers: { "If-Match": `"${draft.saved.revision}"` },
        body: { ...proposal, requestId },
        signal,
      });
      signal.throwIfAborted();
      tracking.accept(data);
      await invalidateConfiguration();
    });
  }

  function discover() {
    void perform("discover", async (signal) => {
      await discoverLinks.mutateAsync({
        path: { sourceId },
        headers: { "If-Match": `"${configuration.revision}"` },
        signal,
      });
      signal.throwIfAborted();
      await onActivated();
    });
  }

  function approve(id: string, checked: boolean) {
    if (controlsDisabled || conflicted) return;
    if (tracking.terminal) tracking.forget();
    setError(null);
    setDraft((current) => {
      if (!current) return null;
      const approved = new Set(current.approved);
      if (checked) approved.add(id);
      else approved.delete(id);
      return { ...current, approved };
    });
  }

  const surfaceError: AppCopy | null = conflicted
    ? "The active selection, discovery snapshot, or credential changed. Your draft is retained but cannot be saved. Reload the complete saved selection before continuing."
    : (error ??
      (tracking.recoveryError
        ? "The submitted selection could not be recovered. Retry recovery before submitting another proposal."
        : tracking.statusUnavailable
          ? "Validation status is unavailable. Work may still be running; this is not a failed validation."
          : policy.isError
            ? "Selection limits are unavailable; saving is disabled."
            : results.filtered && (results.selection.isError || results.pageStale)
              ? "This selection page is unavailable or changed. No selections were removed from your draft."
              : validation));

  return {
    policy,
    draft,
    action,
    busy,
    pending,
    submitted,
    conflicted,
    rootChanges,
    controlsDisabled,
    tracking,
    operation,
    surfaceError,
    saveDisabled:
      disabled ||
      busy ||
      submitted ||
      !changed ||
      conflicted ||
      Boolean(validation) ||
      tracking.recoveryError,
    refs: { input, editButton, cancelButton, panel },
    loadDraft,
    approveBranch,
    approve,
    save,
    discover,
    /** Opens the root links of the draft, loading the saved selection first when there is none. */
    editRoots() {
      if (draft) {
        selectionControl.current = null;
        setDraft({ ...draft, editingRoots: true });
      } else loadDraft();
    },
    editLinks(value: string) {
      if (!draft) return;
      if (tracking.terminal) tracking.forget();
      setDraft({ ...draft, links: value });
      setError(null);
    },
    cancel() {
      setDraft(null);
      setRevisionConflict(false);
      setError(null);
    },
    /** Escape leaves a root edit unless a submission holds it. */
    escapeRoots() {
      if (busy || submitted || tracking.uncertain) return false;
      setDraft(null);
      setError(null);
      return true;
    },
    discardOperation() {
      tracking.forget();
      setError(null);
    },
    reportRefreshFailure() {
      setError("Source status could not be refreshed. Your draft is retained.");
    },
  };
}
