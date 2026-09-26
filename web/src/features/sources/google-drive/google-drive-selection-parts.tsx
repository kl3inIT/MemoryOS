import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { Search, SearchX, SlidersHorizontal } from "lucide-react";
import type { RefObject } from "react";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Empty, EmptyContent, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { HelpPopover } from "@/components/ui/help-popover";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { StatusBadge } from "@/components/ui/status-badge";
import type {
  GetGoogleDriveConfigurationResponse,
  GoogleDriveSelectionItemResponse,
  SourceOperation,
} from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";
import { sourceStatusMessage } from "@/features/sources/shared/source-errors";
import { SelectionPager } from "./google-drive-selection-pager";
import { GoogleDriveSelectionRow } from "./google-drive-selection-tree";
import type { GoogleDriveSelectionResults } from "./use-google-drive-selection-results";

/** Radix Select reserves the empty value, so the unfiltered choice has its own. */
const allKinds = "ALL";

export type SelectionKind = "" | "FOLDER" | "FILE" | "LINKED";

/** How Selected content works, from the configuration it describes. */
export function SelectionHelp({
  configuration,
}: {
  configuration: GetGoogleDriveConfigurationResponse;
}) {
  const ui = useAppTranslation();
  return (
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
          "Expand folders to browse actual accessible files, then expand a file to see its recorded linked documents. Folder and file counts describe directly selected roots, not folder descendants. Search and type filters show unique results instead of the tree, including files inside selected folders once the source has synced.",
        )}
      </p>
      <p>
        {ui(
          "Opening a file's links reads stored evidence; it does not scan content or approve it. A file with no recorded links may not have been checked. Linked targets can appear in several branches; their sync selection is shared.",
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
  );
}

/** The search toggle and the content-type filter of Selected content. */
export function SelectionFilterControls({
  searchVisible,
  kind,
  filtered,
  onToggleSearch,
  onKindChange,
  onClear,
}: {
  searchVisible: boolean;
  kind: SelectionKind;
  filtered: boolean;
  onToggleSearch: () => void;
  onKindChange: (kind: SelectionKind) => void;
  onClear: () => void;
}) {
  const ui = useAppTranslation();
  return (
    <>
      <IconButton
        size="sm"
        aria-label={searchVisible ? ui("Hide search") : ui("Show search")}
        aria-expanded={searchVisible}
        onClick={onToggleSearch}
      >
        <Search aria-hidden="true" />
      </IconButton>
      <Popover>
        <PopoverTrigger asChild>
          <IconButton size="sm" className="relative" aria-label={ui("Filter selected content")}>
            <SlidersHorizontal aria-hidden="true" />
            {kind ? (
              <span
                aria-hidden="true"
                className="absolute top-1 right-1 size-1.5 rounded-full bg-primary"
              />
            ) : null}
          </IconButton>
        </PopoverTrigger>
        <PopoverContent align="end" className="w-56">
          <div className="flex flex-col gap-1.5">
            <span className="font-secondary-action text-content-primary">{ui("Content type")}</span>
            <Select
              value={kind || allKinds}
              onValueChange={(next) =>
                onKindChange(next === allKinds ? "" : (next as SelectionKind))
              }
            >
              <SelectTrigger aria-label={ui("Content type")} className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={allKinds}>{ui("All types")}</SelectItem>
                <SelectItem value="FOLDER">{ui("Folders")}</SelectItem>
                <SelectItem value="FILE">{ui("Files")}</SelectItem>
                <SelectItem value="LINKED">{ui("Linked documents")}</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <Button prominence="tertiary" disabled={!filtered} onClick={onClear}>
            {ui("Clear filters")}
          </Button>
        </PopoverContent>
      </Popover>
    </>
  );
}

/** The search field of Selected content; a search applies when it is submitted. */
export function SelectionSearchForm({
  value,
  onChange,
  onSubmit,
}: {
  value: string;
  onChange: (value: string) => void;
  onSubmit: () => void;
}) {
  const ui = useAppTranslation();
  return (
    <form
      className="flex flex-col gap-2 sm:flex-row sm:items-center"
      onSubmit={(event) => {
        event.preventDefault();
        onSubmit();
      }}
    >
      <label className="min-w-0 flex-1">
        <span className="sr-only">{ui("Search selected files")}</span>
        <Input
          autoFocus
          value={value}
          onChange={(event) => onChange(event.target.value)}
          placeholder={ui("Search selected files")}
        />
      </label>
      <HelpPopover label={ui("Search scope")}>
        <p>
          {ui(
            "Search finds selected folders, selected files, linked documents and files synchronized from selected folders.",
          )}
        </p>
      </HelpPopover>
      <Button type="submit" prominence="secondary" size="sm">
        {ui("Search")}
      </Button>
    </form>
  );
}

/** A submitted selection proposal: its verification and the ways back when its answer was lost. */
export function SelectionOperationStatus({
  operation,
  pending,
  busy,
  tracking,
  onDiscard,
}: {
  operation: SourceOperation | null | undefined;
  pending: boolean;
  busy: boolean;
  tracking: {
    recovering: boolean;
    recoveryError: boolean;
    recoveryMissing: boolean;
    statusUnavailable: boolean;
    uncertain: boolean;
    retryRecovery: () => unknown;
    retryStatus: () => unknown;
  };
  onDiscard: () => void;
}) {
  const ui = useAppTranslation();
  if (!operation && !tracking.recovering && !tracking.uncertain) return null;
  return (
    <div className="space-y-2 rounded-lg border border-border-subtle bg-surface-subtle p-3 text-sm">
      {operation ? (
        <div role="status" className="space-y-2">
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
        </div>
      ) : null}
      {tracking.recovering ? (
        <p role="status" className="text-content-muted">
          {ui("Recovering submitted selection…")}
        </p>
      ) : null}
      {tracking.recoveryError ? (
        <Button prominence="secondary" onClick={() => void tracking.retryRecovery()}>
          {ui("Retry selection recovery")}
        </Button>
      ) : null}
      {tracking.recoveryMissing ? (
        <Button prominence="secondary" onClick={onDiscard}>
          {ui("Discard unaccepted request")}
        </Button>
      ) : null}
      {tracking.statusUnavailable ? (
        <Button prominence="secondary" onClick={() => void tracking.retryStatus()}>
          {ui("Retry validation status")}
        </Button>
      ) : null}
      {tracking.uncertain && !busy ? (
        <p className="text-content-muted">
          {ui(
            "The response was not received. Retry Save selection with the same request ID; the server will not apply it twice.",
          )}
        </p>
      ) : null}
    </div>
  );
}

/** Search or filter results over Selected content, a page at a time. */
export function SelectionResults({
  results,
  approved,
  disabled,
  allowSelection,
  onApprove,
  onApproveBranch,
  onSelect,
  onRefresh,
}: {
  results: GoogleDriveSelectionResults;
  approved: ReadonlySet<string> | null;
  disabled: boolean;
  allowSelection: boolean;
  onApprove: (id: string, checked: boolean) => void;
  onApproveBranch: (parentId: string, selectAll: boolean) => void;
  onSelect: (item: GoogleDriveSelectionItemResponse, control: HTMLElement) => void;
  /** Reads the Source's selection again after this page proved unavailable or changed. */
  onRefresh: () => void;
}) {
  const ui = useAppTranslation();
  const { selection, rows, paging } = results;
  const placeholder = selection.isPlaceholderData;
  return (
    <>
      {selection.isError || results.pageStale ? (
        <Button
          prominence="secondary"
          onClick={() => {
            results.firstPage();
            void selection.refetch();
            onRefresh();
          }}
        >
          {ui("Refresh selection page")}
        </Button>
      ) : selection.isPending ? (
        <p role="status" className="text-sm text-content-muted">
          {ui("Loading selection page…")}
        </p>
      ) : null}
      {results.pageMatches ? (
        <>
          {rows.length ? (
            <ul
              aria-label={ui("Selection results")}
              aria-busy={placeholder || undefined}
              className={cn(
                "divide-y divide-border-subtle border-y border-border-subtle text-sm transition-opacity motion-reduce:transition-none",
                placeholder && "opacity-60",
              )}
            >
              {rows.map((item) => (
                <li key={`${item.kind}:${item.id}`} className="min-w-0">
                  <GoogleDriveSelectionRow
                    item={item}
                    approved={approved}
                    disabled={disabled}
                    allowSelection={allowSelection}
                    onApprove={onApprove}
                    onApproveBranch={onApproveBranch}
                    onSelect={onSelect}
                  />
                </li>
              ))}
            </ul>
          ) : (
            <Empty className="py-8">
              <EmptyHeader>
                <EmptyMedia variant="icon">
                  <SearchX aria-hidden="true" />
                </EmptyMedia>
                <EmptyTitle>{ui("No matching items")}</EmptyTitle>
              </EmptyHeader>
              <EmptyContent>
                <Button prominence="secondary" onClick={results.clearFilters}>
                  {ui("Clear filters")}
                </Button>
              </EmptyContent>
            </Empty>
          )}
          {paging.previous.length || results.nextCursor ? (
            <SelectionPager
              paging={paging}
              count={rows.length}
              hasNext={Boolean(results.nextCursor)}
              busy={placeholder}
              previousLabel={ui("Previous selection page")}
              nextLabel={ui("Next selection page")}
              onPrevious={results.previousPage}
              onNext={results.nextPage}
            />
          ) : null}
        </>
      ) : null}
    </>
  );
}

/** The commands of an open selection draft. */
export function SelectionDraftActions({
  cancelRef,
  saving,
  saveDisabled,
  busy,
  uncertain,
  submitted,
  conflicted,
  reloadDisabled,
  onSave,
  onCancel,
  onReload,
}: {
  cancelRef: RefObject<HTMLButtonElement | null>;
  saving: boolean;
  saveDisabled: boolean;
  busy: boolean;
  /** The last save got no answer; it is retried under the same request. */
  uncertain: boolean;
  submitted: boolean;
  conflicted: boolean;
  reloadDisabled: boolean;
  onSave: () => void;
  onCancel: () => void;
  onReload: () => void;
}) {
  const ui = useAppTranslation();
  return (
    <div className="flex flex-wrap gap-2">
      <Button pending={saving} disabled={saveDisabled} onClick={onSave}>
        {uncertain ? ui("Retry Save selection") : ui("Save selection")}
      </Button>
      <Button prominence="tertiary" ref={cancelRef} disabled={busy || uncertain} onClick={onCancel}>
        {" "}
        {submitted ? ui("Close draft") : ui("Cancel")}
      </Button>
      {conflicted && !submitted && !uncertain ? (
        <Button prominence="secondary" disabled={reloadDisabled} onClick={onReload}>
          {ui("Reload saved selection")}
        </Button>
      ) : null}
    </div>
  );
}

/** Inputs the last link discovery could not check, and why. */
export function DiscoveryErrors({
  failures,
}: {
  failures: GetGoogleDriveConfigurationResponse["discoveryErrors"];
}) {
  const ui = useAppTranslation();
  if (!failures.length) return null;
  return (
    <Collapsible className="rounded-lg bg-status-warning-surface p-3 text-sm text-status-warning-content">
      <CollapsibleTrigger className="min-h-11 cursor-pointer">
        {ui("Discovery could not check")} {failures.length} {ui("inputs")}
      </CollapsibleTrigger>
      <CollapsibleContent>
        <ul>
          {failures.map((failure) => (
            <li key={`${failure.fileId}:${failure.code}`} className="break-words">
              {failure.fileName}: {ui(sourceStatusMessage(failure.code))}
            </li>
          ))}
        </ul>
      </CollapsibleContent>
    </Collapsible>
  );
}
