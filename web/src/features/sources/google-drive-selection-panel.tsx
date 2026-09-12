import { uiLocale } from "@/i18n/format";
import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { FolderTree } from "lucide-react";
import { useEffect, useEffectEvent, useLayoutEffect, useMemo, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { HelpPopover } from "@/components/ui/help-popover";
import { Input, inputVariants } from "@/components/ui/input";
import { StatusBadge } from "@/components/ui/status-badge";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { ApiError, sameOriginMutationHeaders } from "@/lib/api";
import {
  getGoogleDriveConfigurationQueryKey,
  getGoogleDriveSelectionQueryKey,
  getGoogleDriveSelectionPolicyOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import {
  discoverGoogleDriveLinkedDocuments,
  getGoogleDriveSelection,
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
import { GoogleDriveSelectionRow, GoogleDriveSelectionTree } from "./google-drive-selection-tree";
import {
  isGoogleDriveRevisionConflict,
  sourceMutationError,
  sourceStatusMessage,
} from "./source-errors";
import { SourceSectionIcon } from "./source-section-icon";

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
  const [savedLinks, setSavedLinks] = useState<GoogleDriveSelectionDraftResponse | null>(null);
  const [action, setAction] = useState<"load" | "save" | "discover" | "links" | null>(null);
  const controller = useRef<AbortController | null>(null);
  const input = useRef<HTMLTextAreaElement>(null);
  const editButton = useRef<HTMLButtonElement>(null);
  const cancelButton = useRef<HTMLButtonElement>(null);
  const selectionControl = useRef<HTMLElement | null>(null);
  const draftFocus = useRef<"roots" | "approval" | null>(null);
  const [error, setError] = useState<AppCopy | null>(null);
  const [revisionConflict, setRevisionConflict] = useState(false);
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [kind, setKind] = useState<"" | "FOLDER" | "FILE" | "LINKED">("");
  const authority = `${sourceId}:${session.actorId}:${configuration.revision}:${configuration.discoveryRevision}:${configuration.credentialRevision}`;
  const filtered = Boolean(search || kind);
  const [paging, setPaging] = useState<{
    authority: string;
    cursor?: string;
    previous: Array<string | undefined>;
  }>({ authority, previous: [] });
  const cursor = paging.authority === authority ? paging.cursor : undefined;
  const previous = paging.authority === authority ? paging.previous : [];
  const pageRequest = {
    path: { sourceId },
    query: { search: search || undefined, kind: kind || undefined, size: 25, cursor },
  };
  const selection = useQuery({
    queryKey: [...getGoogleDriveSelectionQueryKey(pageRequest), authority],
    queryFn: async ({ signal }) => {
      const { data } = await getGoogleDriveSelection({
        ...pageRequest,
        signal,
        throwOnError: true,
      });
      return data;
    },
    retry: false,
    enabled: filtered,
  });
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
  const page = selection.data;
  const pageMatches =
    page?.revision === configuration.revision &&
    page.discoveryRevision === configuration.discoveryRevision &&
    page.credentialRevision === configuration.credentialRevision;
  const rows = useMemo(() => (pageMatches ? page.items : []), [pageMatches, page]);
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
        const checkbox = selectionControl.current?.querySelector<HTMLInputElement>("input");
        if (checkbox && !checkbox.disabled) checkbox.focus();
        else cancelButton.current?.focus();
      }
    }
    if (!mode && draftFocus.current) {
      const select = selectionControl.current?.querySelector<HTMLButtonElement>("button");
      if (select?.isConnected) select.focus();
      else editButton.current?.focus();
      selectionControl.current = null;
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
      setSavedLinks(null);
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
        operation.status === "SUPERSEDED"
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
    if (!draft || control) selectionControl.current = control ?? null;
    void perform("load", async (signal) => {
      const { data } = await getGoogleDriveSelectionDraft({
        path: { sourceId },
        signal,
        throwOnError: true,
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
        headers: { ...sameOriginMutationHeaders, "If-Match": `"${draft.saved.revision}"` },
        body: { ...proposal, requestId },
        signal,
        throwOnError: true,
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
            : filtered && (selection.isError || (page && !pageMatches))
              ? "This selection page is unavailable or changed. No selections were removed from your draft."
              : validation));
  const draftActions = draft ? (
    <div className="flex flex-wrap gap-2">
      <Button
        pending={action === "save"}
        disabled={
          disabled ||
          busy ||
          submitted ||
          !changed ||
          conflicted ||
          Boolean(validation) ||
          tracking.recoveryError
        }
        onClick={save}
      >
        {tracking.uncertain ? ui("Retry Save selection") : ui("Save selection")}
      </Button>
      <Button
        prominence="tertiary"
        ref={cancelButton}
        disabled={busy || tracking.uncertain}
        onClick={() => {
          setDraft(null);
          setRevisionConflict(false);
          setError(null);
        }}
      >
        {" "}
        {submitted ? ui("Close draft") : ui("Cancel")}
      </Button>
      {!submitted && !tracking.uncertain ? (
        <Button prominence="secondary" disabled={busy || disabled} onClick={() => loadDraft()}>
          {ui("Reload saved selection")}
        </Button>
      ) : null}
    </div>
  ) : null;
  return (
    <section aria-label={ui("Selected content")} className="min-w-0 space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-3">
          <SourceSectionIcon icon={FolderTree} />
          <h2 className="font-heading-h3 text-content-primary">{ui("Selected content")}</h2>
          <HelpPopover label={ui("Selected content")}>
            <p>
              {configuration.scopeMode === "GENERAL"
                ? ui(
                    "General includes supported content in this account's My Drive, excluding Shared with me and Shared Drives.",
                  )
                : ui(
                    "Specific includes directly selected files, folder contents and approved linked documents. This is not an account-wide browser.",
                  )}
            </p>
            <p>
              {ui(
                "Expand folders to browse actual accessible files, then expand a file to see its recorded linked documents. Folder and file counts describe directly selected roots, not folder descendants. Search and type filters show unique results instead of the tree.",
              )}
            </p>
            <p>
              {ui(
                "References count unique locations within source documents. Opening a file's links reads stored evidence; it does not scan content or approve it. A file with no recorded links may not have been checked. Linked targets can appear in several branches; their sync selection is shared.",
              )}
            </p>
            {configuration.scopeMode === "SPECIFIC" ? (
              <>
                <p>
                  {ui(
                    "Use Edit selection to paste file or folder links, one per line or separated by commas. Choose a folder or its descendants, not both. Links and Google access are checked when you save; OAuth permissions may be broader than this selection.",
                  )}{" "}
                  {ui(
                    "Select for sync changes linked-document approvals in a draft without editing the root links. Save selection submits it for verification; Cancel discards unsaved changes.",
                  )}
                </p>
                <p>
                  {ui(
                    "Discovery checks up to 100 inputs and 500 candidates, not the entire corpus. Discovering a link does not approve its target.",
                  )}
                </p>
              </>
            ) : null}
            <p className="text-xs text-content-muted">
              {ui("Active revision")} {configuration.revision} {ui("· Discovery")}{" "}
              {configuration.discoveryRevision}
              {" · "}
              {ui("Credential")} {configuration.credentialRevision}
              {ui(". Selection pages are pinned to these revisions.")}{" "}
              {configuration.counts.approvedLinkedDocuments.toLocaleString(uiLocale())}{" "}
              {ui("approved linked documents.")}
            </p>
          </HelpPopover>
        </div>
      </div>
      {operation ? (
        <div
          role="status"
          className="space-y-2 rounded-lg border border-border-subtle bg-surface-subtle p-3 text-sm"
        >
          <StatusBadge
            tone={pending ? "info" : operation.status === "SUCCEEDED" ? "success" : "warning"}
          >
            {pending
              ? ui("Pending validation")
              : operation.status === "SUCCEEDED"
                ? ui("Selection activated")
                : ui("Proposal not activated")}
          </StatusBadge>
          <p>
            {pending
              ? ui(
                  "The active selection remains in use until verification succeeds. Leaving this page does not cancel validation. A newer submitted proposal supersedes the pending proposal.",
                )
              : ui(
                  "The saved selection is shown below. Revision details are available in Selected content help.",
                )}
          </p>
          <p className="break-all text-xs text-content-muted">
            {ui("Operation")} {operation.id} · {ui(statusLabel(operation.status))}
          </p>
        </div>
      ) : null}
      {tracking.recovering ? (
        <p role="status" className="text-sm text-content-muted">
          {ui("Recovering submitted selection…")}
        </p>
      ) : null}
      {surfaceError ? (
        <p role="alert" className="text-sm text-status-danger-content">
          {ui(surfaceError)}
        </p>
      ) : null}
      {tracking.recoveryError ? (
        <Button prominence="secondary" onClick={() => void tracking.retryRecovery()}>
          {ui("Retry selection recovery")}
        </Button>
      ) : null}
      {tracking.recoveryMissing ? (
        <Button
          prominence="secondary"
          onClick={() => {
            tracking.forget();
            setError(null);
          }}
        >
          {ui("Discard unaccepted request")}
        </Button>
      ) : null}
      {tracking.statusUnavailable ? (
        <Button prominence="secondary" onClick={() => void tracking.retryStatus()}>
          {ui("Retry validation status")}
        </Button>
      ) : null}
      {tracking.uncertain && !busy ? (
        <p className="text-sm text-content-muted">
          {ui(
            "The response was not received. Retry Save selection with the same request ID; the server will not apply it twice.",
          )}
        </p>
      ) : null}
      {policy.isError ? (
        <Button prominence="secondary" onClick={() => void policy.refetch()}>
          {ui("Retry selection policy")}
        </Button>
      ) : null}
      {configuration.scopeMode === "SPECIFIC" ? (
        <>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <p className="text-xs text-content-muted">
              {configuration.discoveredAt
                ? ui("Last discovery · {{v1}}", {
                    v1: new Date(configuration.discoveredAt).toLocaleString(uiLocale()),
                  })
                : ui("No discovery yet.")}
            </p>
            <Button
              prominence="secondary"
              disabled={disabled || busy || Boolean(draft) || pending}
              pending={action === "discover"}
              onClick={() =>
                void perform("discover", async (signal) => {
                  await discoverGoogleDriveLinkedDocuments({
                    path: { sourceId },
                    headers: {
                      ...sameOriginMutationHeaders,
                      "If-Match": `"${configuration.revision}"`,
                    },
                    signal,
                    throwOnError: true,
                  });
                  signal.throwIfAborted();
                  await onActivated();
                })
              }
            >
              {ui("Discover linked documents")}
            </Button>
          </div>
          {rootChanges ? (
            <p className="text-xs text-content-muted">
              {ui(
                "The list below shows the active selection, not the unverified links in your draft.",
              )}
            </p>
          ) : null}
          {configuration.discoveryErrors.length ? (
            <details className="rounded-lg bg-status-warning-surface p-3 text-sm text-status-warning-content">
              <summary className="min-h-11 cursor-pointer">
                {ui("Discovery could not check")} {configuration.discoveryErrors.length}{" "}
                {ui("inputs")}
              </summary>
              <ul>
                {configuration.discoveryErrors.map((failure) => (
                  <li key={`${failure.fileId}:${failure.code}`} className="break-words">
                    {failure.fileName}: {ui(sourceStatusMessage(failure.code))}
                  </li>
                ))}
              </ul>
            </details>
          ) : null}
        </>
      ) : null}
      <form
        className="flex flex-col gap-3 sm:flex-row sm:items-end"
        onSubmit={(event) => {
          event.preventDefault();
          setSearch(searchInput.trim());
          setPaging({ authority, previous: [] });
        }}
      >
        <label className="min-w-0 flex-1 space-y-1 text-sm">
          <span>{ui("Search selected content")}</span>
          <Input
            value={searchInput}
            onChange={(event) => setSearchInput(event.target.value)}
            placeholder={ui("Name")}
          />
        </label>
        <label className="space-y-1 text-sm">
          <span>{ui("Content type")}</span>
          <select
            className={inputVariants()}
            value={kind}
            onChange={(event) => {
              setKind(event.target.value as typeof kind);
              setPaging({ authority, previous: [] });
            }}
          >
            <option value="">{ui("All types")}</option>
            <option value="FOLDER">{ui("Folders")}</option>
            <option value="FILE">{ui("Files")}</option>
            <option value="LINKED">{ui("Linked documents")}</option>
          </select>
        </label>
        <Button type="submit" prominence="secondary">
          {ui("Search")}
        </Button>
      </form>
      {filtered ? (
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="text-xs text-content-muted">
            {ui(
              "Filtered results · Each matching selected root or linked target appears once, across the full selection index. Folder descendants are browsed in the tree.",
            )}
          </p>
          <Button
            prominence="tertiary"
            onClick={() => {
              setSearchInput("");
              setSearch("");
              setKind("");
              setPaging({ authority, previous: [] });
            }}
          >
            {ui("Clear filters")}
          </Button>
        </div>
      ) : (
        <GoogleDriveSelectionTree
          key={authority}
          sourceId={sourceId}
          actorId={session.actorId}
          configuration={configuration}
          approved={draft?.approved ?? null}
          disabled={controlsDisabled || conflicted}
          allowSelection={configuration.scopeMode === "SPECIFIC"}
          onApprove={approve}
          onSelect={loadDraft}
          onRefresh={async () => {
            await onActivated().catch(() =>
              setError("Source status could not be refreshed. Your draft is retained."),
            );
          }}
        />
      )}
      {filtered ? (
        <>
          {selection.isError || (page && !pageMatches) ? (
            <Button
              prominence="secondary"
              onClick={() => {
                setPaging({ authority, previous: [] });
                void selection.refetch();
                void onActivated().catch(() =>
                  setError("Source status could not be refreshed. Your draft is retained."),
                );
              }}
            >
              {ui("Refresh selection page")}
            </Button>
          ) : selection.isPending ? (
            <p role="status" className="text-sm text-content-muted">
              {ui("Loading selection page…")}
            </p>
          ) : null}
          {pageMatches ? (
            <>
              <p className="text-xs text-content-muted">
                {page.counts.folders.toLocaleString(uiLocale())} {ui("folders ·")}{" "}
                {page.counts.files.toLocaleString(uiLocale())} {ui("files")}
                {" · "}
                {page.counts.linkedDocuments.toLocaleString(uiLocale())} {ui("linked documents")}
              </p>
              {rows.length ? (
                <ul
                  aria-label={ui("Selection results")}
                  className="divide-y divide-border-subtle border-y border-border-subtle text-sm"
                >
                  {rows.map((item) => (
                    <li key={`${item.kind}:${item.id}`} className="min-w-0">
                      <GoogleDriveSelectionRow
                        item={item}
                        approved={draft?.approved ?? null}
                        disabled={controlsDisabled || conflicted}
                        allowSelection={configuration.scopeMode === "SPECIFIC"}
                        onApprove={approve}
                        onSelect={loadDraft}
                      />
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="text-sm text-content-muted">
                  {ui(
                    "No matching selected roots or linked documents on this page. Browse folders in the tree to see their files.",
                  )}
                </p>
              )}
              <div className="flex flex-wrap items-center justify-between gap-2">
                <p className="text-xs text-content-muted">
                  {rows.length} {ui("items on this page")}
                </p>
                <div className="flex gap-2">
                  <Button
                    aria-label={ui("Previous selection page")}
                    prominence="secondary"
                    disabled={!previous.length || selection.isFetching}
                    onClick={() =>
                      setPaging({
                        authority,
                        cursor: previous.at(-1),
                        previous: previous.slice(0, -1),
                      })
                    }
                  >
                    {ui("Previous")}
                  </Button>
                  <Button
                    aria-label={ui("Next selection page")}
                    prominence="secondary"
                    disabled={!page.nextCursor || selection.isFetching}
                    onClick={() =>
                      setPaging({
                        authority,
                        cursor: page.nextCursor ?? undefined,
                        previous: [...previous, cursor],
                      })
                    }
                  >
                    {ui("Next")}
                  </Button>
                </div>
              </div>
            </>
          ) : null}
        </>
      ) : null}
      {draft && !draft.editingRoots ? draftActions : null}
      {configuration.scopeMode === "SPECIFIC" ? (
        <details className="text-sm">
          <summary className="min-h-11 cursor-pointer py-3 text-content-muted focus-visible:outline-2 focus-visible:outline-focus-ring">
            {ui("File and folder links")}
          </summary>
          <div className="flex flex-wrap items-start gap-2">
            {!draft?.editingRoots ? (
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
            {draft?.editingRoots ? (
              <div
                className="w-full space-y-3 rounded-lg border border-border-default p-4"
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
            {!draft?.editingRoots ? (
              <>
                {savedLinks && savedLinks.revision === configuration.revision ? (
                  <div className="w-full">
                    <GoogleDriveLinks
                      policy={policy.data}
                      scopeMode="SPECIFIC"
                      value={savedLinks.links.join("\n")}
                      disabled={false}
                      readOnly
                      onChange={() => {}}
                    />
                  </div>
                ) : (
                  <Button
                    prominence="secondary"
                    pending={action === "links"}
                    disabled={busy}
                    onClick={() =>
                      void perform("links", async (signal) => {
                        const { data } = await getGoogleDriveSelectionDraft({
                          path: { sourceId },
                          signal,
                          throwOnError: true,
                        });
                        signal.throwIfAborted();
                        setSavedLinks(data);
                      })
                    }
                  >
                    {ui("Load saved links")}
                  </Button>
                )}
              </>
            ) : null}
          </div>
        </details>
      ) : null}
    </section>
  );
}
import { statusLabel } from "@/i18n/status-copy";
