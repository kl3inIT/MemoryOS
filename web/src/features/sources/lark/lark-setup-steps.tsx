import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { ArrowLeft, ArrowRight, Pencil, TriangleAlert } from "lucide-react";
import type { ReactNode } from "react";
import { useFieldValidity } from "@/components/form/form-context";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardAction, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { FieldError } from "@/components/ui/field";
import type { LarkCredentialResponse } from "@/lib/hey-api/types.gen";
import { SetupSection } from "@/features/sources/shared/setup-section";
import { SourceAccessField, SourceGroupsField } from "@/features/sources/shared/source-form-fields";
import { sourceAccessPresentation } from "@/features/sources/shared/source-status-presentation";
import { formatSyncInterval } from "@/features/sources/shared/sync-interval";
import { LarkMissingScopes } from "./lark-app-guide";
import { LarkScopeFields } from "./lark-scope-fields";
import {
  larkFolderToken,
  larkIntervalMinutes,
  larkRegion,
  parseLarkLines,
  type LarkScopeDraft,
} from "./lark-scope";
import type { LarkSetupStep } from "./lark-setup-search";
import type { LarkSourceFormApi } from "./use-lark-source-form";

type StepProps = {
  form: LarkSourceFormApi;
  /** Moves to another step. */
  go: (step: LarkSetupStep) => void;
  busy: boolean;
  /** Fields hold still while a proposal is in flight or a Source already exists. */
  controlsDisabled: boolean;
};

/** Content: the Wiki spaces and Drive folders to synchronize, and how often. */
export function LarkContentStep({
  form,
  go,
  busy,
  controlsDisabled,
  selected,
  ready,
  scopeError,
}: StepProps & {
  selected: LarkCredentialResponse | undefined;
  /** The chosen credential is verified. */
  ready: boolean;
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
      <SetupSection labelledBy="lark-content-heading">
        <h2 id="lark-content-heading" className="font-heading-h3">
          {ui("Choose what to synchronize")}
        </h2>
        <p className="text-sm break-words text-content-secondary">
          {ui("Credential:")} {selected?.name ?? ui("Not selected")}
          {selected?.botName ? ui(" ({{v1}})", { v1: selected.botName }) : ""}
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
        {selected && selected.missingScopes.length > 0 ? (
          <LarkMissingScopes scopes={selected.missingScopes} />
        ) : null}
        {selected && ready ? (
          <form.AppField name="scope">
            {() => (
              <ScopeField
                credentialId={selected.id}
                botName={selected.botName}
                disabled={controlsDisabled}
              />
            )}
          </form.AppField>
        ) : null}
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
export function LarkAccessStep({
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
      <SetupSection labelledBy="lark-access-heading">
        <h2 id="lark-access-heading" className="font-heading-h3">
          {ui("Name and access")}
        </h2>
        <form.AppField name="sourceName">
          {(field) => (
            <field.TextField
              label={ui("Source name")}
              maxLength={120}
              disabled={controlsDisabled}
              placeholder={ui("e.g. Sales Wiki")}
              autoComplete="off"
            />
          )}
        </form.AppField>
        <form.AppField name="access">
          {() => (
            <SourceAccessField
              label={ui("Visibility")}
              modes={scoped ? ["SYNC", "PRIVATE"] : ["SYNC", "PRIVATE", "PUBLIC"]}
              disabled={controlsDisabled}
            />
          )}
        </form.AppField>
        <form.Subscribe selector={(state) => state.values.access === "PRIVATE"}>
          {(groupAccess) =>
            groupAccess ? (
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

/** Review: everything entered so far and the create action. */
export function LarkReviewStep({
  form,
  go,
  busy,
  frozen,
  selected,
  scopeError,
  creating,
  submitLabel,
  submitDisabled,
}: StepProps & {
  /** A submitted proposal cannot change until it settles. */
  frozen: boolean;
  selected: LarkCredentialResponse | undefined;
  scopeError: AppCopy | null;
  creating: boolean;
  submitLabel: string;
  submitDisabled: boolean;
}) {
  const ui = useAppTranslation();
  const values = form.state.values;
  const region = larkRegion(selected?.region);
  const folders = new Set(
    parseLarkLines(values.scope.folderLinksText).map((line) => larkFolderToken(line, region)),
  ).size;
  const minutes = larkIntervalMinutes(values.scope.interval);
  const edit = (step: LarkSetupStep) => (
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
      <SetupSection labelledBy="lark-review-heading">
        <h2 id="lark-review-heading" className="font-heading-h3">
          {ui("Review and create")}
        </h2>
        <div className="grid gap-3">
          <ReviewCard title={ui("Credential")} action={edit("credential")}>
            <ReviewFact label={ui("Name")}>{selected?.name ?? ui("Not selected")}</ReviewFact>
            <ReviewFact label={ui("Bot")}>{selected?.botName ?? ui("Not resolved yet")}</ReviewFact>
          </ReviewCard>
          <ReviewCard title={ui("Content")} action={edit("content")}>
            <ReviewFact label={ui("Wiki spaces")}>{values.scope.spaceIds.length}</ReviewFact>
            <ReviewFact label={ui("Drive folders")}>{folders}</ReviewFact>
            <ReviewFact label={ui("Sync every")}>
              {minutes === null ? ui("Not set") : formatSyncInterval(minutes)}
            </ReviewFact>
          </ReviewCard>
          <ReviewCard title={ui("Name and access")} action={edit("access")}>
            <ReviewFact label={ui("Source name")}>
              {values.sourceName.trim() || ui("Not set")}
            </ReviewFact>
            <ReviewFact label={ui("Visibility")}>
              {values.access === "PUBLIC"
                ? ui("Public · everyone in this organization")
                : values.access === "SYNC"
                  ? ui(sourceAccessPresentation.SYNC.label)
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
        {scopeError ? <FieldError role="alert">{ui(scopeError)}</FieldError> : null}
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
  credentialId,
  botName,
  disabled,
}: {
  credentialId: string;
  botName: string | undefined;
  disabled: boolean;
}) {
  const { field } = useFieldValidity<LarkScopeDraft>();
  return (
    <LarkScopeFields
      credentialId={credentialId}
      botName={botName}
      draft={field.state.value}
      disabled={disabled}
      onChange={field.handleChange}
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
