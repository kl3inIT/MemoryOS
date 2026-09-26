import { uiLocale } from "@/i18n/format";
import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { FolderTree } from "lucide-react";
import { useEffect, useEffectEvent, useLayoutEffect, useMemo, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { ApiError } from "@/lib/api";
import {
  getGoogleDriveConfigurationQueryKey,
  getGoogleDriveSelectionPolicyOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import {
  discoverGoogleDriveLinkedDocuments,
  getGoogleDriveSelectionTree,
  getGoogleDriveSelectionDraft,
  replaceGoogleDriveRoots,
} from "@/lib/hey-api/sdk.gen";
import type {
  GetGoogleDriveConfigurationResponse,
  GoogleDriveSelectionItemResponse,
  GoogleDriveSelectionDraftResponse,
} from "@/lib/hey-api/types.gen";
import { GoogleDriveLinks } from "./google-drive-links";
import { googleDriveSelectionError, parseGoogleDriveLinks } from "./google-drive-selection";
import { useGoogleDriveSelectionOperation } from "./google-drive-selection-operation";
import {
  DiscoveryErrors,
  SelectionDraftActions,
  SelectionFilterControls,
  SelectionHelp,
  SelectionOperationStatus,
  SelectionResults,
  SelectionSearchForm,
} from "./google-drive-selection-parts";
import { GoogleDriveSelectionTree } from "./google-drive-selection-tree";
import { useGoogleDriveSelectionResults } from "./use-google-drive-selection-results";
import {
  isGoogleDriveRevisionConflict,
  sourceMutationError,
  sourceStatusMessage,
} from "@/features/sources/shared/source-errors";
import { SourceSectionIcon } from "@/features/sources/shared/source-section-icon";

type Draft = {
  saved: GoogleDriveSelectionDraftResponse;
  links: string;
  approved: Set<string>;
  actorId: string;
  editingRoots: boolean;
};

export function GoogleDriveSelectionPanel({
  sourceId,
  configuration,
  disabled,
  onEditingChange,
  onBusyChange,
  onActivated,
}: {
  sourceId: string;
  configuration: GetGoogleDriveConfigurationResponse;
  disabled: boolean;
  onEditingChange: (editing: boolean) => void;
  onBusyChange: (busy: boolean) => void;
  onActivated: () => Promise<void>;
}) {
  const ui = useAppTranslation();

  const session = useApplicationSession();
  const client = useQueryClient();
  const policy = useQuery({
    ...getGoogleDriveSelectionPolicyOptions(),
    retry: false,
    staleTime: 60_000,
  });
  const [draft, setDraft] = useState<Draft | null>(null);
  const [action, setAction] = useState<"load" | "save" | "discover" | null>(null);
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
  const results = useGoogleDriveSelectionResults(sourceId, session.actorId, configuration);
  const { filtered, selection, pageStale } = results;
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
      draft &&
      (draft.saved.revision !== configuration.revision ||
        draft.saved.discoveryRevision !== configuration.discoveryRevision ||
        draft.saved.credentialRevision !== configuration.credentialRevision ||
        draft.actorId !== session.actorId),
    );
  const links = useMemo(() => parseGoogleDriveLinks(draft?.links ?? ""), [draft?.links]);
  const changed = Boolean(
    draft &&
    (links.length !== draft.saved.links.length ||
      links.some((link, index) => link !== draft.saved.links[index]) ||
      draft.approved.size !== draft.saved.linkedDocumentIds.length ||
      draft.saved.linkedDocumentIds.some((id) => !draft.approved.has(id))),
  );
  const rootChanges = Boolean(
    draft &&
    (links.length !== draft.saved.links.length ||
      links.some((link, index) => link !== draft.saved.links[index])),
  );
  const proposal = draft
    ? {
        scopeMode: configuration.scopeMode,
        links,
        linkedDocumentIds: [...draft.approved],
        discoveryRevision: draft.saved.discoveryRevision,
        credentialRevision: draft.saved.credentialRevision,
        requestId: tracking.requestId ?? "00000000-0000-4000-8000-000000000000",
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

  async function perform(
    next: NonNullable<typeof action>,
    task: (signal: AbortSignal) => Promise<void>,
  ) {
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
  function loadDraft(target?: GoogleDriveSelectionItemResponse, control?: HTMLElement) {
    if (target && (target.coveredByRoots || (!target.selected && target.status !== "AVAILABLE")))
      return;
    if (!draft || control) {
      selectionControl.current = control ?? null;
      selectionItem.current = control ? (target?.id ?? null) : null;
    }
    void perform("load", async (signal) => {
      const { data } = await getGoogleDriveSelectionDraft({
        path: { sourceId },
        signal,
      });
      signal.throwIfAborted();
      if (
        target &&
        (data.revision !== configuration.revision ||
          data.discoveryRevision !== configuration.discoveryRevision ||
          data.credentialRevision !== configuration.credentialRevision)
      ) {
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
      await client.invalidateQueries({
        queryKey: getGoogleDriveConfigurationQueryKey({ path: { sourceId } }),
      });
    });
  }
  function approveBranch(parentId: string, selectAll: boolean) {
    void perform("load", async (signal) => {
      const ids = new Set<string>();
      let cursor: string | undefined;
      do {
        const { data } = await getGoogleDriveSelectionTree({
          path: { sourceId },
          query: { parentId, size: 100, cursor },
          signal,
        });
        signal.throwIfAborted();
        if (
          data.revision !== configuration.revision ||
          data.discoveryRevision !== configuration.discoveryRevision ||
          data.credentialRevision !== configuration.credentialRevision
        ) {
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
      const { data } = await getGoogleDriveSelectionDraft({
        path: { sourceId },
        signal,
      });
      signal.throwIfAborted();
      if (
        data.revision !== configuration.revision ||
        data.discoveryRevision !== configuration.discoveryRevision ||
        data.credentialRevision !== configuration.credentialRevision
      ) {
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
    await client.invalidateQueries({
      queryKey: getGoogleDriveConfigurationQueryKey({ path: { sourceId } }),
    });
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
      const { data } = await replaceGoogleDriveRoots({
        path: { sourceId },
        headers: { "If-Match": `"${draft.saved.revision}"` },
        body: { ...proposal, requestId },
        signal,
      });
      signal.throwIfAborted();
      tracking.accept(data);
      await client.invalidateQueries({
        queryKey: getGoogleDriveConfigurationQueryKey({ path: { sourceId } }),
      });
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
  const surfaceError = conflicted
    ? "The active selection, discovery snapshot, or credential changed. Your draft is retained but cannot be saved. Reload the complete saved selection before continuing."
    : (error ??
      (tracking.recoveryError
        ? "The submitted selection could not be recovered. Retry recovery before submitting another proposal."
        : tracking.statusUnavailable
          ? "Validation status is unavailable. Work may still be running; this is not a failed validation."
          : policy.isError
            ? "Selection limits are unavailable; saving is disabled."
            : filtered && (selection.isError || pageStale)
              ? "This selection page is unavailable or changed. No selections were removed from your draft."
              : validation));
  const draftActions = draft ? (
    <SelectionDraftActions
      cancelRef={cancelButton}
      saving={action === "save"}
      saveDisabled={
        disabled ||
        busy ||
        submitted ||
        !changed ||
        conflicted ||
        Boolean(validation) ||
        tracking.recoveryError
      }
      busy={busy}
      uncertain={tracking.uncertain}
      submitted={submitted}
      conflicted={conflicted}
      reloadDisabled={busy || disabled}
      onSave={save}
      onCancel={() => {
        setDraft(null);
        setRevisionConflict(false);
        setError(null);
      }}
      onReload={() => loadDraft()}
    />
  ) : null;
  return (
    <section ref={panel} aria-label={ui("Selected content")} className="min-w-0 space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-3">
          <SourceSectionIcon icon={FolderTree} />
          <h2 className="font-heading-h3 text-content-primary">{ui("Selected content")}</h2>
          <SelectionHelp configuration={configuration} />
        </div>
      </div>
      <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
        {configuration.scopeMode === "SPECIFIC" && configuration.discoveredAt ? (
          <p className="min-w-0 text-xs text-content-muted">
            {ui("Last discovery · {{v1}}", {
              v1: new Date(configuration.discoveredAt).toLocaleString(uiLocale()),
            })}
          </p>
        ) : null}
        <div className="ml-auto flex flex-wrap items-center gap-1">
          {configuration.scopeMode === "SPECIFIC" && !draft?.editingRoots ? (
            <Button
              ref={editButton}
              prominence="secondary"
              disabled={disabled || busy || tracking.recovering || tracking.uncertain}
              pending={action === "load"}
              onClick={() => {
                if (draft) {
                  selectionControl.current = null;
                  setDraft({ ...draft, editingRoots: true });
                } else loadDraft();
              }}
            >
              {pending ? ui("Edit replacement proposal") : ui("Edit selection")}
            </Button>
          ) : null}
          {configuration.scopeMode === "SPECIFIC" ? (
            <Button
              prominence="secondary"
              disabled={disabled || busy || Boolean(draft) || pending}
              pending={action === "discover"}
              onClick={() =>
                void perform("discover", async (signal) => {
                  await discoverGoogleDriveLinkedDocuments({
                    path: { sourceId },
                    headers: {
                      "If-Match": `"${configuration.revision}"`,
                    },
                    signal,
                  });
                  signal.throwIfAborted();
                  await onActivated();
                })
              }
            >
              {ui("Find links in files")}
            </Button>
          ) : null}
          <SelectionFilterControls
            searchVisible={results.searchVisible}
            kind={results.kind}
            filtered={filtered}
            onToggleSearch={results.toggleSearch}
            onKindChange={results.changeKind}
            onClear={results.clearFilters}
          />
        </div>
      </div>
      <SelectionOperationStatus
        operation={operation}
        pending={pending}
        busy={busy}
        tracking={tracking}
        onDiscard={() => {
          tracking.forget();
          setError(null);
        }}
      />
      {surfaceError ? (
        <p role="alert" className="text-sm text-status-danger-content">
          {ui(surfaceError)}
        </p>
      ) : null}
      {policy.isError ? (
        <Button prominence="secondary" onClick={() => void policy.refetch()}>
          {ui("Retry selection policy")}
        </Button>
      ) : null}
      {draft && !draft.editingRoots ? (
        <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 rounded-lg border border-border-default bg-surface-subtle px-3 py-2">
          <p className="min-w-0 text-xs text-content-muted">
            {ui(
              "{{v1}} linked documents selected. Save selection applies the checked documents on the next sync.",
              {
                v1: draft.approved.size.toLocaleString(uiLocale()),
              },
            )}
          </p>
          {draftActions}
        </div>
      ) : null}
      {configuration.scopeMode === "SPECIFIC" && draft?.editingRoots ? (
        <div
          className="space-y-3 rounded-lg border border-border-default p-4"
          onKeyDown={(event) => {
            if (event.key === "Escape" && !busy && !submitted && !tracking.uncertain) {
              event.preventDefault();
              setDraft(null);
              setError(null);
            }
          }}
        >
          <GoogleDriveLinks
            policy={policy.data}
            scopeMode={configuration.scopeMode}
            value={draft.links}
            inputRef={input}
            disabled={controlsDisabled || conflicted}
            errorMessage=""
            onChange={(value) => {
              if (tracking.terminal) tracking.forget();
              setDraft({ ...draft, links: value });
              setError(null);
            }}
          />
          {draftActions}
        </div>
      ) : null}
      {configuration.scopeMode === "SPECIFIC" ? (
        <DiscoveryErrors failures={configuration.discoveryErrors} />
      ) : null}
      {results.searchVisible ? (
        <SelectionSearchForm
          value={results.searchInput}
          onChange={results.setSearchInput}
          onSubmit={results.submitSearch}
        />
      ) : null}
      {rootChanges ? (
        <p className="text-xs text-content-muted">
          {ui("The list below shows the active selection, not the unverified links in your draft.")}
        </p>
      ) : null}
      {filtered ? null : (
        <GoogleDriveSelectionTree
          key={`${sourceId}:${session.actorId}`}
          sourceId={sourceId}
          actorId={session.actorId}
          configuration={configuration}
          approved={draft?.approved ?? null}
          disabled={controlsDisabled || conflicted}
          allowSelection={configuration.scopeMode === "SPECIFIC"}
          onApprove={approve}
          onApproveBranch={approveBranch}
          onSelect={loadDraft}
          onRefresh={async () => {
            await onActivated().catch(() =>
              setError("Source status could not be refreshed. Your draft is retained."),
            );
          }}
        />
      )}
      {filtered ? (
        <SelectionResults
          results={results}
          approved={draft?.approved ?? null}
          disabled={controlsDisabled || conflicted}
          allowSelection={configuration.scopeMode === "SPECIFIC"}
          onApprove={approve}
          onApproveBranch={approveBranch}
          onSelect={loadDraft}
          onRefresh={() =>
            void onActivated().catch(() =>
              setError("Source status could not be refreshed. Your draft is retained."),
            )
          }
        />
      ) : null}
    </section>
  );
}
