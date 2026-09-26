import { useAppTranslation } from "@/i18n/use-app-translation";
import { FolderTree } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { StatusBadge } from "@/components/ui/status-badge";
import type {
  SharePointConfigurationResponse,
  SharePointRootPageResponse,
  SharePointSelectionPolicyResponse,
} from "@/lib/hey-api/types.gen";
import { SourceSectionIcon } from "@/features/sources/shared/source-section-icon";
import { SharePointScopeFields } from "./sharepoint-scope-fields";
import {
  emptySharePointScopeDraft,
  sharePointScopeError,
  type SharePointScopeDraft,
} from "./sharepoint-scope";

type SavedRoots = {
  data: SharePointRootPageResponse | undefined;
  isPending: boolean;
  isError: boolean;
};

/** The saved SharePoint scope, and the draft that replaces it as a whole. */
export function SharePointScopeCard({
  configuration,
  roots,
  policy,
  draft,
  canConfigure,
  controlsDisabled,
  busy,
  saving,
  onDraftChange,
  onSave,
}: {
  configuration: SharePointConfigurationResponse;
  roots: SavedRoots;
  policy: SharePointSelectionPolicyResponse | undefined;
  draft: SharePointScopeDraft | null;
  canConfigure: boolean;
  controlsDisabled: boolean;
  busy: boolean;
  saving: boolean;
  onDraftChange: (draft: SharePointScopeDraft | null) => void;
  onSave: () => void;
}) {
  const ui = useAppTranslation();
  const scopeError = draft ? sharePointScopeError(draft, policy) : null;

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center gap-3">
          <SourceSectionIcon icon={FolderTree} />
          <h2 className="font-heading-h3 text-content-primary">{ui("Saved scope")}</h2>
        </div>
        {canConfigure && !draft ? (
          <Button
            prominence="secondary"
            disabled={controlsDisabled}
            onClick={() =>
              onDraftChange({
                ...emptySharePointScopeDraft(),
                scopeMode: configuration.scopeMode,
                siteUrlsText: (roots.data?.roots ?? []).map((root) => root.url).join("\n"),
                excludedSitesText: configuration.excludedSites.join("\n"),
                excludedPathsText: configuration.excludedPaths.join("\n"),
                includeDocuments: configuration.includeDocuments,
                includePages: configuration.includePages,
                syncIntervalMinutes: String(configuration.syncIntervalMinutes),
                pruneIntervalHours: String(configuration.pruneIntervalHours),
              })
            }
          >
            {ui("Edit scope")}
          </Button>
        ) : null}
      </CardHeader>
      <CardContent>
        {draft ? (
          <form
            className="space-y-5 rounded-xl border border-border-subtle p-4 sm:p-5"
            onSubmit={(event) => {
              event.preventDefault();
              if (!scopeError) onSave();
            }}
          >
            <SharePointScopeFields
              draft={draft}
              policy={policy}
              disabled={busy}
              onChange={onDraftChange}
            />
            {roots.data && roots.data.total > roots.data.roots.length ? (
              <p role="alert" className="text-sm text-status-warning-content">
                {ui(
                  "Only the first {{count}} addresses are shown. Saving replaces the whole scope with what is listed here.",
                  { count: roots.data.roots.length },
                )}
              </p>
            ) : null}
            {scopeError ? (
              <p role="alert" className="text-sm text-status-danger-content">
                {ui(scopeError)}
              </p>
            ) : null}
            <div className="flex flex-wrap gap-2">
              <Button type="submit" pending={saving} disabled={busy || Boolean(scopeError)}>
                {ui("Save scope")}
              </Button>
              <Button prominence="tertiary" disabled={busy} onClick={() => onDraftChange(null)}>
                {ui("Cancel")}
              </Button>
            </div>
            <p className="text-sm text-content-muted">
              {ui(
                "Saving answers with a receipt and resolves every address with Microsoft. The running synchronization is cancelled and the Source reads its content again.",
              )}
            </p>
          </form>
        ) : (
          <SavedScope configuration={configuration} roots={roots} />
        )}
      </CardContent>
    </Card>
  );
}

function SavedScope({
  configuration,
  roots,
}: {
  configuration: SharePointConfigurationResponse;
  roots: SavedRoots;
}) {
  const ui = useAppTranslation();

  return (
    <div className="space-y-3 text-sm">
      <p className="text-content-primary">
        {configuration.scopeMode === "ALL_SITES"
          ? ui("All sites the Entra application can read")
          : ui("{{count}} sites, libraries or folders", { count: configuration.rootCount })}
      </p>
      <p className="text-content-muted">
        {configuration.includeDocuments && configuration.includePages
          ? ui("Documents and site pages")
          : configuration.includePages
            ? ui("Site pages")
            : ui("Documents")}
      </p>
      {configuration.scopeMode === "SPECIFIC" ? (
        roots.isPending ? (
          <p role="status" className="text-content-muted">
            {ui("Loading saved addresses…")}
          </p>
        ) : roots.isError ? (
          <p role="alert" className="text-status-danger-content">
            {ui("Saved addresses could not be loaded. Refresh status before editing the scope.")}
          </p>
        ) : (
          <ul className="space-y-1">
            {roots.data?.roots.map((root) => (
              <li key={root.url} className="flex flex-wrap items-center gap-2">
                <StatusBadge tone={root.verified ? "neutral" : "warning"}>
                  {root.kind === "SITE"
                    ? ui("Site")
                    : root.kind === "LIBRARY"
                      ? ui("Library")
                      : ui("Folder")}
                </StatusBadge>
                <span className="min-w-0 wrap-anywhere text-content-primary">
                  {root.displayName ?? root.url}
                </span>
                <span className="min-w-0 wrap-anywhere text-xs text-content-muted">{root.url}</span>
              </li>
            ))}
          </ul>
        )
      ) : null}
      {configuration.excludedSites.length > 0 || configuration.excludedPaths.length > 0 ? (
        <p className="text-content-muted">
          {ui("{{sites}} site and {{paths}} path exclusions", {
            sites: configuration.excludedSites.length,
            paths: configuration.excludedPaths.length,
          })}
        </p>
      ) : null}
    </div>
  );
}
