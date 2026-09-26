import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { ArrowLeft, ArrowRight, Pencil, TriangleAlert } from "lucide-react";
import type { ReactNode } from "react";
import { useFieldValidity } from "@/components/form/form-context";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardAction, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { FieldDescription, FieldError } from "@/components/ui/field";
import type {
  SharePointCredentialResponse,
  SharePointSelectionPolicyResponse,
} from "@/lib/hey-api/types.gen";
import { SetupSection } from "@/features/sources/shared/setup-section";
import { SourceAccessField, SourceGroupsField } from "@/features/sources/shared/source-form-fields";
import { SharePointScheduleFields } from "./sharepoint-schedule-fields";
import { SharePointScopeFields } from "./sharepoint-scope-fields";
import { parseSharePointLines, type SharePointScopeDraft } from "./sharepoint-scope";
import type { SharePointSetupStep } from "./sharepoint-setup-search";
import type { SharePointSourceFormApi } from "./use-sharepoint-source-form";

type StepProps = {
  form: SharePointSourceFormApi;
  /** Moves to another step. */
  go: (step: SharePointSetupStep) => void;
  busy: boolean;
  /** Fields hold still while a proposal is in flight or a Source already exists. */
  controlsDisabled: boolean;
};

/** Content: the sites, libraries and folders to synchronize, and what to collect from them. */
export function SharePointContentStep({
  form,
  go,
  busy,
  controlsDisabled,
  selected,
  ready,
  policy,
  scopeError,
}: StepProps & {
  selected: SharePointCredentialResponse | undefined;
  /** The chosen credential is verified. */
  ready: boolean;
  policy: SharePointSelectionPolicyResponse | undefined;
  scopeError: AppCopy | null;
}) {
  const ui = useAppTranslation();
  return (
    <form
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        if (!scopeError) go("access");
      }}
    >
      <SetupSection labelledBy="sharepoint-content-heading">
        <h2 id="sharepoint-content-heading" className="font-heading-h3">
          {ui("Choose what to synchronize")}
        </h2>
        <p className="break-words text-sm text-content-secondary">
          {ui("Credential:")} {selected?.name ?? ui("Not selected")}
          {selected?.tenantHost ? ui(" ({{v1}})", { v1: selected.tenantHost }) : ""}
        </p>
        {!ready ? (
          <Alert variant="warning">
            <TriangleAlert aria-hidden="true" />
            <AlertDescription>
              {ui(
                "Select a verified credential before choosing content. Return to the credential step to test or replace it.",
              )}
            </AlertDescription>
          </Alert>
        ) : null}
        <form.AppField name="scope">
          {() => <ScopeField policy={policy} disabled={controlsDisabled || !ready} />}
        </form.AppField>
        {scopeError ? <FieldError role="alert">{ui(scopeError)}</FieldError> : null}
        <StepFooter
          back={{ label: ui("Credential"), onClick: () => go("credential"), disabled: busy }}
          next={<StepSubmit disabled={busy || !ready || Boolean(scopeError)} />}
        />
      </SetupSection>
    </form>
  );
}

/** Name and access: what the Source is called and who reads it. */
export function SharePointAccessStep({
  form,
  go,
  busy,
  controlsDisabled,
  scoped,
}: StepProps & { scoped: boolean }) {
  const ui = useAppTranslation();
  return (
    <form
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        if (form.state.values.sourceName.trim()) go("review");
      }}
    >
      <SetupSection labelledBy="sharepoint-access-heading">
        <h2 id="sharepoint-access-heading" className="font-heading-h3">
          {ui("Name and access")}
        </h2>
        <form.AppField name="sourceName">
          {(field) => (
            <field.TextField
              label={ui("Source name")}
              maxLength={120}
              disabled={controlsDisabled}
              placeholder={ui("e.g. Finance SharePoint")}
              autoComplete="off"
            />
          )}
        </form.AppField>
        <form.AppField name="access">
          {() => (
            <SourceAccessField
              label={ui("Visibility")}
              modes={scoped ? ["PRIVATE"] : ["PRIVATE", "PUBLIC"]}
              disabled={controlsDisabled}
            />
          )}
        </form.AppField>
        <form.Subscribe selector={(state) => state.values.access === "PRIVATE"}>
          {(groupAccess) =>
            groupAccess ? (
              <div className="flex flex-col gap-2">
                <form.AppField name="groupIds">
                  {() => (
                    <SourceGroupsField
                      label={ui("Access groups")}
                      placeholder={
                        scoped ? ui("Select at least one group you manage.") : ui("Select groups")
                      }
                      disabled={controlsDisabled}
                    />
                  )}
                </form.AppField>
                <FieldDescription>
                  {ui(
                    "Group members can search and read what this Source imports. SharePoint's own per-item permissions are not synchronized.",
                  )}
                </FieldDescription>
              </div>
            ) : null
          }
        </form.Subscribe>
        <form.Subscribe selector={(state) => !state.values.sourceName.trim()}>
          {(unnamed) => (
            <StepFooter
              back={{ label: ui("Content"), onClick: () => go("content"), disabled: busy }}
              next={<StepSubmit disabled={busy || unnamed} />}
            />
          )}
        </form.Subscribe>
      </SetupSection>
    </form>
  );
}

/** Review: everything entered so far, the schedule, and the create action. */
export function SharePointReviewStep({
  form,
  go,
  busy,
  controlsDisabled,
  frozen,
  selected,
  scopeError,
  creating,
  submitLabel,
  submitDisabled,
}: StepProps & {
  /** A submitted proposal cannot change until it settles. */
  frozen: boolean;
  selected: SharePointCredentialResponse | undefined;
  scopeError: AppCopy | null;
  creating: boolean;
  submitLabel: string;
  submitDisabled: boolean;
}) {
  const ui = useAppTranslation();
  const values = form.state.values;
  const rootCount =
    values.scope.scopeMode === "ALL_SITES"
      ? 0
      : parseSharePointLines(values.scope.siteUrlsText).length;
  const edit = (step: SharePointSetupStep) => (
    <CardAction>
      <Button size="sm" prominence="tertiary" disabled={busy || frozen} onClick={() => go(step)}>
        <Pencil data-icon="inline-start" aria-hidden="true" />
        {ui("Edit")}
      </Button>
    </CardAction>
  );
  return (
    <form
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        void form.handleSubmit();
      }}
    >
      <SetupSection labelledBy="sharepoint-review-heading">
        <h2 id="sharepoint-review-heading" className="font-heading-h3">
          {ui("Review and create")}
        </h2>
        <div className="grid gap-3">
          <ReviewCard title={ui("Credential")} action={edit("credential")}>
            <ReviewFact label={ui("Name")}>{selected?.name ?? ui("Not selected")}</ReviewFact>
            <ReviewFact label={ui("SharePoint host")}>
              {selected?.tenantHost ?? ui("Not resolved yet")}
            </ReviewFact>
          </ReviewCard>
          <ReviewCard title={ui("Content")} action={edit("content")}>
            <ReviewFact label={ui("Scope")}>
              {values.scope.scopeMode === "ALL_SITES"
                ? ui("All sites")
                : ui("{{count}} addresses", { count: rootCount })}
            </ReviewFact>
            <ReviewFact label={ui("Collects")}>
              {values.scope.includeDocuments && values.scope.includePages
                ? ui("Documents and site pages")
                : values.scope.includePages
                  ? ui("Site pages")
                  : ui("Documents")}
            </ReviewFact>
          </ReviewCard>
          <ReviewCard title={ui("Name and access")} action={edit("access")}>
            <ReviewFact label={ui("Source name")}>
              {values.sourceName.trim() || ui("Not set")}
            </ReviewFact>
            <ReviewFact label={ui("Visibility")}>
              {values.access === "PUBLIC"
                ? ui("Public · everyone in this Tenant")
                : ui("Private · selected group members")}
            </ReviewFact>
            {values.access === "PRIVATE" ? (
              <ReviewFact label={ui("Access groups")}>
                {values.groupIds.size > 0
                  ? ui("{{v1}} selected", { v1: values.groupIds.size })
                  : ui("None")}
              </ReviewFact>
            ) : null}
          </ReviewCard>
        </div>
        <form.AppField name="scope">
          {() => <ScheduleField disabled={controlsDisabled} />}
        </form.AppField>
        {scopeError ? <FieldError role="alert">{ui(scopeError)}</FieldError> : null}
        <p className="text-sm text-content-muted">
          {ui(
            "Creating answers immediately with a receipt. Every address is then resolved with Microsoft, and the Source starts its first run once they all resolve.",
          )}
        </p>
        <StepFooter
          back={{ label: ui("Access"), onClick: () => go("access"), disabled: busy || frozen }}
          next={
            <Button type="submit" pending={creating} disabled={submitDisabled}>
              {submitLabel}
              <ArrowRight data-icon="inline-end" aria-hidden="true" />
            </Button>
          }
        />
      </SetupSection>
    </form>
  );
}

function ScopeField({
  policy,
  disabled,
}: {
  policy: SharePointSelectionPolicyResponse | undefined;
  disabled: boolean;
}) {
  const { field } = useFieldValidity<SharePointScopeDraft>();
  return (
    <SharePointScopeFields
      draft={field.state.value}
      policy={policy}
      disabled={disabled}
      onChange={field.handleChange}
    />
  );
}

/** The schedule is part of the scope draft; this edits only its two intervals. */
function ScheduleField({ disabled }: { disabled: boolean }) {
  const { field } = useFieldValidity<SharePointScopeDraft>();
  return (
    <SharePointScheduleFields
      draft={field.state.value}
      disabled={disabled}
      onChange={(schedule) => field.handleChange({ ...field.state.value, ...schedule })}
    />
  );
}

function StepSubmit({ disabled }: { disabled: boolean }) {
  const ui = useAppTranslation();
  return (
    <Button type="submit" disabled={disabled}>
      {ui("Continue")}
      <ArrowRight data-icon="inline-end" aria-hidden="true" />
    </Button>
  );
}

function StepFooter({
  back,
  next,
}: {
  back: { label: string; onClick: () => void; disabled: boolean };
  next: ReactNode;
}) {
  return (
    <footer className="flex flex-wrap justify-between gap-3">
      <Button prominence="secondary" disabled={back.disabled} onClick={back.onClick}>
        <ArrowLeft data-icon="inline-start" aria-hidden="true" />
        {back.label}
      </Button>
      {next}
    </footer>
  );
}

function ReviewCard({
  title,
  action,
  children,
}: {
  title: string;
  action: ReactNode;
  children: ReactNode;
}) {
  return (
    <Card size="sm">
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        {action}
      </CardHeader>
      <CardContent>
        <dl className="grid gap-3 text-sm sm:grid-cols-2">{children}</dl>
      </CardContent>
    </Card>
  );
}

function ReviewFact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <dt className="text-content-muted">{label}</dt>
      <dd className="mt-1 wrap-anywhere text-content-primary">{children}</dd>
    </div>
  );
}
