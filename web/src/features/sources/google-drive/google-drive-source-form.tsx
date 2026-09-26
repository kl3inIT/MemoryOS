import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { ArrowLeft, ArrowRight } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import type {
  GoogleDriveCredentialResponse,
  GoogleDriveSelectionPolicyResponse,
} from "@/lib/hey-api/types.gen";
import { SourceAccessChoice } from "@/features/sources/shared/source-access-choice";
import { sourceCreationLabel } from "@/features/sources/shared/use-source-creation";
import { SourceGroupPicker } from "@/features/sources/shared/source-group-picker";
import { GoogleDriveConnectionAccount } from "./google-drive-connection-account";
import { sectionCard } from "./google-drive-credential-step";
import { GoogleDriveLinks } from "./google-drive-links";
import type { GoogleDriveSourceDraft } from "./google-drive-source-draft";

/** The second setup step: name, visibility and content of the new Source. */
export function GoogleDriveSourceForm({
  draft,
  onChange,
  selected,
  connected,
  unavailable,
  busy,
  creating,
  globalManage,
  policy,
  policyError,
  selectionError,
  creation,
  onBack,
  onSubmit,
}: {
  draft: GoogleDriveSourceDraft;
  onChange: (change: Partial<GoogleDriveSourceDraft>) => void;
  selected: GoogleDriveCredentialResponse | undefined;
  connected: boolean;
  unavailable: boolean;
  busy: boolean;
  /** The create request is in flight. */
  creating: boolean;
  globalManage: boolean;
  policy: GoogleDriveSelectionPolicyResponse | undefined;
  policyError: boolean;
  selectionError: AppCopy | null;
  creation: {
    createdSourceId: string | null;
    error: AppCopy | null;
    pendingValidation: boolean;
    frozen: boolean;
    tracking: {
      uncertain: boolean;
      recovering: boolean;
      recoveryError: boolean;
      statusUnavailable: boolean;
    };
  };
  onBack: () => void;
  onSubmit: () => void;
}) {
  const ui = useAppTranslation();
  const { tracking, createdSourceId, frozen } = creation;
  // Readers of a Private Source are its groups; scoped managers also need groups for Auto Sync.
  // Drive file permissions decide who reads a source-permission Source, so only group access picks groups.
  const showGroups = draft.access === "PRIVATE";
  const fieldsDisabled = busy || unavailable || frozen || Boolean(createdSourceId);

  return (
    <form
      className="space-y-4"
      onSubmit={(event) => {
        event.preventDefault();
        onSubmit();
      }}
    >
      <section aria-labelledby="google-drive-connection-heading" className={sectionCard}>
        <h2 id="google-drive-connection-heading" className="font-heading-h3 text-content-primary">
          {ui("Connection")}
        </h2>
        <GoogleDriveConnectionAccount credential={selected} connected={connected} />
        {unavailable || !connected ? (
          <p role="alert" className="text-sm text-status-warning-content">
            {ui(
              "Select a connected credential before creating a Source. Return to credentials to refresh or reconnect.",
            )}
          </p>
        ) : null}
      </section>
      <section aria-labelledby="google-drive-settings-heading" className={sectionCard}>
        <h2 id="google-drive-settings-heading" className="font-heading-h3 text-content-primary">
          {ui("Source settings")}
        </h2>
        <div>
          <label
            htmlFor="google-drive-source-name"
            className="text-sm font-medium text-content-primary"
          >
            {ui("Source name")}
          </label>
          <Input
            id="google-drive-source-name"
            value={draft.sourceName}
            maxLength={120}
            required
            disabled={fieldsDisabled}
            onChange={(event) => onChange({ sourceName: event.target.value })}
            placeholder={ui("e.g. Team documentation")}
            autoComplete="off"
            className="mt-2"
          />
        </div>
        <div className="space-y-2">
          <span
            id="google-drive-source-access-label"
            className="text-sm font-medium text-content-primary"
          >
            {ui("Visibility")}
          </span>
          <SourceAccessChoice
            id="google-drive-source-access"
            labelledBy="google-drive-source-access-label"
            modes={globalManage ? ["SYNC", "PRIVATE", "PUBLIC"] : ["SYNC", "PRIVATE"]}
            value={draft.access}
            disabled={fieldsDisabled}
            onValueChange={(access) => onChange({ access })}
          />
        </div>
        {showGroups ? (
          <SourceGroupPicker
            label={ui("Access groups")}
            placeholder={
              globalManage ? ui("Select groups") : ui("Select at least one group you manage.")
            }
            selected={draft.groupIds}
            disabled={fieldsDisabled}
            onChange={(groupIds) => onChange({ groupIds })}
          />
        ) : null}
      </section>
      <section aria-labelledby="google-drive-content-heading" className={sectionCard}>
        <h2 id="google-drive-content-heading" className="font-heading-h3 text-content-primary">
          {ui("Content")}
        </h2>
        <GoogleDriveLinks
          scopeMode={draft.scopeMode}
          policy={policy}
          onScopeModeChange={(scopeMode) => onChange({ scopeMode })}
          errorMessage={
            creation.error || tracking.recoveryError || tracking.statusUnavailable || policyError
              ? ""
              : selectionError && draft.linksTouched
                ? ui(selectionError)
                : null
          }
          value={draft.linksText}
          disabled={fieldsDisabled || !connected}
          onChange={(linksText) => onChange({ linksTouched: true, linksText })}
        />
      </section>
      <footer className="flex flex-wrap justify-between gap-3 pt-2">
        <Button prominence="secondary" disabled={busy || frozen} onClick={onBack}>
          <ArrowLeft /> {ui("Credentials")}
        </Button>
        <Button
          type="submit"
          pending={creating}
          disabled={
            busy ||
            creation.pendingValidation ||
            tracking.recovering ||
            tracking.recoveryError ||
            (!createdSourceId &&
              !tracking.uncertain &&
              (unavailable || !connected || !draft.sourceName.trim() || Boolean(selectionError)))
          }
        >
          {ui(sourceCreationLabel(creation))} <ArrowRight />
        </Button>
      </footer>
    </form>
  );
}
