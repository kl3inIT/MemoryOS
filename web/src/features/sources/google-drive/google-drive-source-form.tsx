import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { ArrowLeft, ArrowRight, TriangleAlert } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import type {
  GoogleDriveCredentialResponse,
  GoogleDriveSelectionPolicyResponse,
} from "@/lib/hey-api/types.gen";
import { SetupSection } from "@/features/sources/shared/setup-section";
import { SourceAccessField, SourceGroupsField } from "@/features/sources/shared/source-form-fields";
import { sourceCreationLabel } from "@/features/sources/shared/use-source-creation";
import { GoogleDriveConnectionAccount } from "./google-drive-connection-account";
import { GoogleDriveLinks } from "./google-drive-links";
import type { GoogleDriveSourceFormApi } from "./google-drive-source-draft";

/** The second setup step: name, visibility and content of the new Source. */
export function GoogleDriveSourceForm({
  form,
  selected,
  connected,
  unavailable,
  busy,
  creating,
  globalManage,
  policy,
  policyError,
  selectionError,
  submitDisabled,
  creation,
  onBack,
}: {
  form: GoogleDriveSourceFormApi;
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
  /** Create waits for a connected credential, a name and a valid selection. */
  submitDisabled: boolean;
  creation: {
    createdSourceId: string | null;
    error: AppCopy | null;
    frozen: boolean;
    tracking: {
      uncertain: boolean;
      recoveryError: boolean;
      statusUnavailable: boolean;
    };
  };
  onBack: () => void;
}) {
  const ui = useAppTranslation();
  const { tracking, createdSourceId, frozen } = creation;
  const fieldsDisabled = busy || unavailable || frozen || Boolean(createdSourceId);

  return (
    <form
      className="flex flex-col gap-4"
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        void form.handleSubmit();
      }}
    >
      <SetupSection labelledBy="google-drive-connection-heading">
        <h2 id="google-drive-connection-heading" className="font-heading-h3 text-content-primary">
          {ui("Connection")}
        </h2>
        <GoogleDriveConnectionAccount credential={selected} connected={connected} />
        {unavailable || !connected ? (
          <Alert variant="warning">
            <TriangleAlert aria-hidden="true" />
            <AlertDescription>
              {ui(
                "Select a connected credential before creating a Source. Return to credentials to refresh or reconnect.",
              )}
            </AlertDescription>
          </Alert>
        ) : null}
      </SetupSection>
      <SetupSection labelledBy="google-drive-settings-heading">
        <h2 id="google-drive-settings-heading" className="font-heading-h3 text-content-primary">
          {ui("Source settings")}
        </h2>
        <form.AppField name="sourceName">
          {(field) => (
            <field.TextField
              label={ui("Source name")}
              maxLength={120}
              disabled={fieldsDisabled}
              placeholder={ui("e.g. Team documentation")}
              autoComplete="off"
            />
          )}
        </form.AppField>
        <form.AppField name="access">
          {() => (
            <SourceAccessField
              label={ui("Visibility")}
              modes={globalManage ? ["SYNC", "PRIVATE", "PUBLIC"] : ["SYNC", "PRIVATE"]}
              disabled={fieldsDisabled}
            />
          )}
        </form.AppField>
        {/* Readers of a Private Source are its groups; Drive file permissions decide the rest. */}
        <form.Subscribe selector={(state) => state.values.access === "PRIVATE"}>
          {(showGroups) =>
            showGroups ? (
              <form.AppField name="groupIds">
                {() => (
                  <SourceGroupsField
                    label={ui("Access groups")}
                    placeholder={
                      globalManage
                        ? ui("Select groups")
                        : ui("Select at least one group you manage.")
                    }
                    disabled={fieldsDisabled}
                  />
                )}
              </form.AppField>
            ) : null
          }
        </form.Subscribe>
      </SetupSection>
      <SetupSection labelledBy="google-drive-content-heading">
        <h2 id="google-drive-content-heading" className="font-heading-h3 text-content-primary">
          {ui("Content")}
        </h2>
        <form.AppField name="scopeMode">
          {(scope) => (
            <form.AppField name="linksText">
              {(links) => (
                <GoogleDriveLinks
                  scopeMode={scope.state.value}
                  policy={policy}
                  onScopeModeChange={scope.handleChange}
                  errorMessage={
                    creation.error ||
                    tracking.recoveryError ||
                    tracking.statusUnavailable ||
                    policyError
                      ? ""
                      : selectionError && links.state.meta.isDirty
                        ? ui(selectionError)
                        : null
                  }
                  value={links.state.value}
                  disabled={fieldsDisabled || !connected}
                  onChange={links.handleChange}
                />
              )}
            </form.AppField>
          )}
        </form.AppField>
      </SetupSection>
      <footer className="flex flex-wrap justify-between gap-3 pt-2">
        <Button prominence="secondary" disabled={busy || frozen} onClick={onBack}>
          <ArrowLeft data-icon="inline-start" aria-hidden="true" />
          {ui("Credentials")}
        </Button>
        <Button type="submit" pending={creating} disabled={submitDisabled}>
          {ui(sourceCreationLabel(creation))}
          <ArrowRight data-icon="inline-end" aria-hidden="true" />
        </Button>
      </footer>
    </form>
  );
}
