import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { FolderTree } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { useApplicationSession } from "@/features/identity/application-session-context";
import type { GetGoogleDriveConfigurationResponse } from "@/lib/hey-api/types.gen";
import { SourceSectionIcon } from "@/features/sources/shared/source-section-icon";
import { GoogleDriveLinks } from "./google-drive-links";
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
import { useGoogleDriveSelectionDraft } from "./use-google-drive-selection-draft";
import { useGoogleDriveSelectionResults } from "./use-google-drive-selection-results";

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
  const results = useGoogleDriveSelectionResults(sourceId, configuration);
  const selection = useGoogleDriveSelectionDraft({
    sourceId,
    configuration,
    results,
    disabled,
    onEditingChange,
    onBusyChange,
    onActivated,
  });
  const { draft, busy, tracking, refs } = selection;
  const specific = configuration.scopeMode === "SPECIFIC";
  const refreshContent = () => onActivated().catch(selection.reportRefreshFailure);
  const draftActions = draft ? (
    <SelectionDraftActions
      cancelRef={refs.cancelButton}
      saving={selection.action === "save"}
      saveDisabled={selection.saveDisabled}
      busy={busy}
      uncertain={tracking.uncertain}
      submitted={selection.submitted}
      conflicted={selection.conflicted}
      reloadDisabled={busy || disabled}
      onSave={selection.save}
      onCancel={selection.cancel}
      onReload={() => selection.loadDraft()}
    />
  ) : null;
  return (
    <section
      ref={refs.panel}
      aria-label={ui("Selected content")}
      className="flex min-w-0 flex-col gap-3"
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-3">
          <SourceSectionIcon icon={FolderTree} />
          <h2 className="font-heading-h3 text-content-primary">{ui("Selected content")}</h2>
          <SelectionHelp configuration={configuration} />
        </div>
      </div>
      <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
        {specific && configuration.discoveredAt ? (
          <p className="min-w-0 text-xs text-content-muted">
            {ui("Last discovery · {{v1}}", {
              v1: new Date(configuration.discoveredAt).toLocaleString(uiLocale()),
            })}
          </p>
        ) : null}
        <div className="ml-auto flex flex-wrap items-center gap-1">
          {specific && !draft?.editingRoots ? (
            <Button
              ref={refs.editButton}
              prominence="secondary"
              disabled={disabled || busy || tracking.recovering || tracking.uncertain}
              pending={selection.action === "load"}
              onClick={selection.editRoots}
            >
              {selection.pending ? ui("Edit replacement proposal") : ui("Edit selection")}
            </Button>
          ) : null}
          {specific ? (
            <Button
              prominence="secondary"
              disabled={disabled || busy || Boolean(draft) || selection.pending}
              pending={selection.action === "discover"}
              onClick={selection.discover}
            >
              {ui("Find links in files")}
            </Button>
          ) : null}
          <SelectionFilterControls
            searchVisible={results.searchVisible}
            kind={results.kind}
            filtered={results.filtered}
            onToggleSearch={results.toggleSearch}
            onKindChange={results.changeKind}
            onClear={results.clearFilters}
          />
        </div>
      </div>
      <SelectionOperationStatus
        operation={selection.operation}
        pending={selection.pending}
        busy={busy}
        tracking={tracking}
        onDiscard={selection.discardOperation}
      />
      {selection.surfaceError ? (
        <Alert variant="destructive">
          <AlertDescription>{ui(selection.surfaceError)}</AlertDescription>
        </Alert>
      ) : null}
      {selection.policy.isError ? (
        <Button prominence="secondary" onClick={() => void selection.policy.refetch()}>
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
      {specific && draft?.editingRoots ? (
        // Escape inside the root editor leaves it, as Cancel does.
        <div
          role="group"
          aria-label={ui("File or folder links")}
          className="flex flex-col gap-3 rounded-lg border border-border-default p-4"
          onKeyDown={(event) => {
            if (event.key === "Escape" && selection.escapeRoots()) event.preventDefault();
          }}
        >
          <GoogleDriveLinks
            policy={selection.policy.data}
            scopeMode={configuration.scopeMode}
            value={draft.links}
            inputRef={refs.input}
            disabled={selection.controlsDisabled || selection.conflicted}
            errorMessage=""
            onChange={selection.editLinks}
          />
          {draftActions}
        </div>
      ) : null}
      {specific ? <DiscoveryErrors failures={configuration.discoveryErrors} /> : null}
      {results.searchVisible ? (
        <SelectionSearchForm
          value={results.searchInput}
          onChange={results.setSearchInput}
          onSubmit={results.submitSearch}
        />
      ) : null}
      {selection.rootChanges ? (
        <p className="text-xs text-content-muted">
          {ui("The list below shows the active selection, not the unverified links in your draft.")}
        </p>
      ) : null}
      {results.filtered ? null : (
        <GoogleDriveSelectionTree
          key={`${sourceId}:${session.actorId}`}
          sourceId={sourceId}
          configuration={configuration}
          approved={draft?.approved ?? null}
          disabled={selection.controlsDisabled || selection.conflicted}
          allowSelection={specific}
          onApprove={selection.approve}
          onApproveBranch={selection.approveBranch}
          onSelect={selection.loadDraft}
          onRefresh={refreshContent}
        />
      )}
      {results.filtered ? (
        <SelectionResults
          results={results}
          approved={draft?.approved ?? null}
          disabled={selection.controlsDisabled || selection.conflicted}
          allowSelection={specific}
          onApprove={selection.approve}
          onApproveBranch={selection.approveBranch}
          onSelect={selection.loadDraft}
          onRefresh={() => void refreshContent()}
        />
      ) : null}
    </section>
  );
}
