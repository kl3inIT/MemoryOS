import { CheckCircle2, CircleAlert, PlugZap } from "lucide-react";
import { CatalogDialog } from "@/components/composites/catalog-dialog";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Field, FieldGroup, FieldLabel, FieldLegend, FieldSet } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { NativeSelect } from "@/components/ui/native-select";
import { RadioGroup } from "@/components/ui/radio-group";
import { Switch } from "@/components/ui/switch";
import { GroupAccessPicker } from "@/features/groups/group-access-picker";
import { appText } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { listChatGroupOptionsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import { cn } from "@/lib/utils";
import { DataBoundaryField, RadioCard } from "./data-boundary";
import type { CredentialAction, ManagedModel } from "./model-catalog";
import { ProviderModelsField } from "./provider-models-field";
import { useProviderEditor, type ProviderEditorProps } from "./use-provider-editor";

type Editor = ReturnType<typeof useProviderEditor>;

export function ProviderEditor({
  models = [],
  onClose,
  ...props
}: ProviderEditorProps & { models?: ManagedModel[]; onClose: () => void }) {
  const ui = useAppTranslation();
  const editor = useProviderEditor(props);
  const { form, baseline, connection, pending, busy, conflicted } = editor;
  const close = () => {
    editor.close();
    onClose();
  };

  return (
    <CatalogDialog
      title={
        baseline
          ? ui(appText("Edit provider: {{name}}", { name: baseline.name }))
          : ui("Add provider")
      }
      onClose={close}
    >
      <form
        noValidate
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          void form.handleSubmit();
        }}
      >
        <FieldSet disabled={pending}>
          <FieldGroup>
            <form.AppField name="name">
              {(field) => <field.TextField label={ui("Provider name")} required maxLength={200} />}
            </form.AppField>
            <ProtocolField editor={editor} adapters={props.adapters} />
            <form.AppField name="baseUrl">
              {(field) => <field.TextField label={ui("Endpoint URL")} required type="url" />}
            </form.AppField>
            <form.AppField name="enabled">
              {(field) => (
                <Field orientation="horizontal">
                  <FieldLabel htmlFor={field.name} className="flex-1">
                    {ui("Provider enabled")}
                  </FieldLabel>
                  <Switch
                    id={field.name}
                    checked={field.state.value}
                    onCheckedChange={field.handleChange}
                  />
                </Field>
              )}
            </form.AppField>
            <AccessFields editor={editor} />
            <form.AppField name="dataBoundary">
              {(field) => (
                <DataBoundaryField value={field.state.value} onChange={field.handleChange} />
              )}
            </form.AppField>
            <CredentialFields editor={editor} />
          </FieldGroup>
        </FieldSet>
        <ProviderModelsField
          connection={editor.draftConnection}
          configured={models}
          selected={editor.chosenModels}
          onSelected={editor.setChosenModels}
          disabled={pending || conflicted}
        />
        <ProviderFeedback editor={editor} />
        <div className="flex flex-wrap justify-end gap-2">
          <Button
            prominence="secondary"
            className="mr-auto"
            pending={connection.pending}
            disabled={!editor.testable || busy}
            onClick={() => void editor.testConnection()}
          >
            <PlugZap data-icon="inline-start" aria-hidden="true" />
            {ui("Test connection")}
          </Button>
          <Button prominence="secondary" onClick={close}>
            {ui("Close")}
          </Button>
          <Button
            type="submit"
            pending={editor.saving.pending}
            disabled={editor.blocked || conflicted || connection.pending || busy}
          >
            {ui("Save provider")}
          </Button>
        </div>
      </form>
    </CatalogDialog>
  );
}

/** One installed protocol offers no choice; a second one makes the selection meaningful. */
function ProtocolField({
  editor,
  adapters,
}: {
  editor: Editor;
  adapters: ProviderEditorProps["adapters"];
}) {
  const ui = useAppTranslation();
  const { form, adapter, baseline } = editor;
  if (adapters.length <= 1 && adapter) return null;
  return (
    <form.AppField name="adapterType" listeners={{ onChange: editor.clearSecret }}>
      {(field) => (
        <Field>
          <FieldLabel htmlFor={field.name}>{ui("Protocol")}</FieldLabel>
          <NativeSelect
            id={field.name}
            value={field.state.value}
            disabled={Boolean(baseline)}
            onChange={(event) => field.handleChange(event.target.value)}
          >
            {!adapter && (
              <option value={field.state.value}>
                {ui(
                  appText("{{adapter}} (unavailable)", {
                    adapter: field.state.value || appText("Choose installed adapter"),
                  }),
                )}
              </option>
            )}
            {adapters.map((entry) => (
              <option key={entry.type} value={entry.type}>
                {entry.type === "openai" ? ui("OpenAI-compatible") : entry.type}
              </option>
            ))}
          </NativeSelect>
        </Field>
      )}
    </form.AppField>
  );
}

function AccessFields({ editor }: { editor: Editor }) {
  const ui = useAppTranslation();
  const { form, values, pending } = editor;
  return (
    <FieldSet>
      <FieldLegend variant="label">{ui("Who can use this provider")}</FieldLegend>
      <form.AppField name="isPublic">
        {(field) => (
          <RadioGroup
            value={field.state.value ? "public" : "groups"}
            onValueChange={(value) => field.handleChange(value === "public")}
          >
            <RadioCard
              id="provider-access-public"
              value="public"
              title={ui("Every Tenant member")}
            />
            <RadioCard
              id="provider-access-groups"
              value="groups"
              title={ui("Selected Groups only")}
            />
          </RadioGroup>
        )}
      </form.AppField>
      {!values.isPublic && (
        <form.AppField name="groupIds">
          {(field) => (
            <GroupAccessPicker
              selected={new Set(field.state.value)}
              required
              disabled={pending}
              load={(query) => listChatGroupOptionsOptions({ query })}
              description={appText("Members of the selected Groups can use this provider in Chat.")}
              onChange={(next) => field.handleChange([...next])}
            />
          )}
        </form.AppField>
      )}
    </FieldSet>
  );
}

function CredentialFields({ editor }: { editor: Editor }) {
  const ui = useAppTranslation();
  const { form, values, pending } = editor;
  const replacing = values.credentialAction === "REPLACE";
  return (
    <>
      <form.AppField name="credentialAction" listeners={{ onChange: editor.clearSecret }}>
        {(field) => (
          <Field>
            <FieldLabel htmlFor={field.name}>{ui("Credential action")}</FieldLabel>
            <NativeSelect
              id={field.name}
              value={field.state.value}
              onChange={(event) => field.handleChange(event.target.value as CredentialAction)}
            >
              <option value="KEEP">{ui("Keep existing key")}</option>
              <option value="REPLACE">{ui("Replace key")}</option>
              <option value="REMOVE">{ui("Remove key")}</option>
            </NativeSelect>
          </Field>
        )}
      </form.AppField>
      {/* The key input stays mounted, so clearing it never depends on which action is shown. */}
      <Field className={cn(!replacing && "hidden")}>
        <FieldLabel htmlFor="provider-api-key">{ui("API key")}</FieldLabel>
        <Input
          id="provider-api-key"
          key={editor.keyVersion}
          type="password"
          autoComplete="off"
          spellCheck={false}
          disabled={!replacing || pending}
          onChange={(event) => editor.typeSecret(event.target.value)}
        />
      </Field>
      {editor.credentialMissing && (
        <Alert variant="warning" role="alert">
          <CircleAlert aria-hidden="true" />
          <AlertDescription>
            {ui(
              "An enabled provider requires a configured key. Replace the key or explicitly disable this provider.",
            )}
          </AlertDescription>
        </Alert>
      )}
    </>
  );
}

function ProviderFeedback({ editor }: { editor: Editor }) {
  const ui = useAppTranslation();
  const { connection, busy, values } = editor;
  return (
    <>
      {editor.unsavedModels.length > 0 && (
        <Alert variant="destructive" role="alert">
          <AlertDescription>
            {ui(
              appText("The provider was saved. These models were not added: {{models}}", {
                models: editor.unsavedModels.join(", "),
              }),
            )}
          </AlertDescription>
        </Alert>
      )}
      {editor.conflicted && (
        <Alert variant="warning" role="alert">
          <CircleAlert aria-hidden="true" />
          <AlertDescription>
            <div className="flex flex-col items-start gap-2">
              <p>
                {ui(
                  "The saved catalog changed or conflicted. Reconcile the complete revision and Access baseline, review your non-secret draft, then retry manually. The key has not been retained.",
                )}
              </p>
              <Button
                prominence="secondary"
                size="sm"
                disabled={busy}
                onClick={() => void editor.reconcile()}
              >
                {ui("Reconcile saved provider")}
              </Button>
            </div>
          </AlertDescription>
        </Alert>
      )}
      {connection.outcome?.ok && (
        <Alert variant="success" role="status">
          <CheckCircle2 aria-hidden="true" />
          <AlertTitle>{ui(connection.outcome.message)}</AlertTitle>
        </Alert>
      )}
      {connection.outcome && !connection.outcome.ok && (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{ui(connection.outcome.message)}</AlertDescription>
        </Alert>
      )}
      {editor.actionError && (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{ui(editor.actionError)}</AlertDescription>
        </Alert>
      )}
      {editor.saved && (
        <p role="status" className="font-secondary-body text-content-muted">
          {values.enabled
            ? ui("Provider saved.")
            : ui("Provider saved. No connectivity claim has been made.")}
        </p>
      )}
    </>
  );
}
