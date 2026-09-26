import { ChevronDown } from "lucide-react";
import type { ComponentProps } from "react";
import { CatalogDialog } from "@/components/composites/catalog-dialog";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import {
  Field,
  FieldDescription,
  FieldGroup,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { NativeSelect } from "@/components/ui/native-select";
import { appText, type AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { compactTokens, millionTokenPrice, type ModelDraft } from "./model-catalog";
import { useModelEditor, type ChangeDraft, type ModelEditorProps } from "./use-model-editor";

type DraftProps = { draft: ModelDraft; change: ChangeDraft };

/** One labelled text or number input bound to a draft field. */
function DraftInput({
  name,
  label,
  draft,
  change,
  className,
  ...input
}: DraftProps & {
  name: keyof ModelDraft & string;
  label: string;
  className?: string;
} & Omit<ComponentProps<typeof Input>, "id" | "name" | "value" | "onChange">) {
  const id = `model-${name}`;
  return (
    <Field className={className}>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <Input
        {...input}
        id={id}
        name={name}
        value={String(draft[name])}
        onChange={(event) => change(name, event.target.value)}
      />
    </Field>
  );
}

/** One checkbox bound to a boolean draft field. */
function DraftCheckbox({
  name,
  label,
  draft,
  change,
}: DraftProps & {
  name: "toolCalling" | "vision" | "reasoning" | "visible" | "completionTokens";
  label: string;
}) {
  const id = `model-${name}`;
  return (
    <Field orientation="horizontal">
      <Checkbox
        id={id}
        checked={draft[name]}
        onCheckedChange={(checked) => change(name, checked === true)}
      />
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
    </Field>
  );
}

/** Limits and capabilities the runtime enforces; typed only for a model the catalog does not declare. */
function Specs(props: DraftProps) {
  const ui = useAppTranslation();
  return (
    <FieldGroup>
      <div className="grid gap-4 sm:grid-cols-2">
        <DraftInput
          {...props}
          name="contextWindow"
          label={ui("Context window (tokens)")}
          type="number"
          required
          min={256}
          max={10_000_000}
          step={1}
        />
        <DraftInput
          {...props}
          name="maxOutputTokens"
          label={ui("Maximum output (tokens)")}
          type="number"
          min={1}
          step={1}
          placeholder={ui("Provider default")}
        />
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        <DraftCheckbox {...props} name="toolCalling" label={ui("Tool calling")} />
        <DraftCheckbox {...props} name="vision" label={ui("Vision input")} />
        <DraftCheckbox {...props} name="reasoning" label={ui("Reasoning")} />
        <Field orientation="horizontal" data-disabled="true">
          <Checkbox id="model-streaming" checked disabled />
          <FieldLabel htmlFor="model-streaming">{ui("Streaming (required)")}</FieldLabel>
        </Field>
      </div>
    </FieldGroup>
  );
}

function Prices(props: DraftProps) {
  const ui = useAppTranslation();
  return (
    <FieldSet>
      <FieldLegend variant="label">{ui("Pricing · USD per million tokens")}</FieldLegend>
      <div className="grid gap-3 sm:grid-cols-2">
        <DraftInput
          {...props}
          name="inputPrice"
          label={ui("Input price")}
          type="number"
          min={0}
          step="any"
        />
        <DraftInput
          {...props}
          name="outputPrice"
          label={ui("Output price")}
          type="number"
          min={0}
          step="any"
        />
        <DraftInput
          {...props}
          name="cachedInputPrice"
          label={ui("Cache-read price (optional)")}
          type="number"
          min={0}
          step="any"
          placeholder={ui("Defaults to input price")}
          className="sm:col-span-2"
        />
      </div>
    </FieldSet>
  );
}

/** What the installed catalog declares for this model, read rather than typed. */
function DeclaredSpecs({ draft }: { draft: ModelDraft }) {
  const ui = useAppTranslation();
  const rows: [AppCopy, string][] = [
    ["Context window (tokens)", compactTokens(Number(draft.contextWindow))],
    [
      "Maximum output (tokens)",
      draft.maxOutputTokens.trim() === ""
        ? ui("Provider default")
        : compactTokens(Number(draft.maxOutputTokens)),
    ],
    ["Input price", millionTokenPrice(Number(draft.inputPrice)) ?? ui("Unknown")],
    ["Output price", millionTokenPrice(Number(draft.outputPrice)) ?? ui("Unknown")],
    [
      "Cache-read price",
      draft.cachedInputPrice === ""
        ? ui("Defaults to input price")
        : (millionTokenPrice(Number(draft.cachedInputPrice)) ?? ""),
    ],
  ];
  return (
    <dl className="grid gap-x-6 gap-y-2 font-main-ui-body sm:grid-cols-2">
      {rows.map(([label, value]) => (
        <div key={label as string} className="flex justify-between gap-4">
          <dt className="text-content-muted">{ui(label)}</dt>
          <dd className="tabular-nums">{value}</dd>
        </div>
      ))}
      <div className="flex justify-between gap-4 sm:col-span-2">
        <dt className="text-content-muted">{ui("Capabilities")}</dt>
        <dd>
          {[
            draft.toolCalling ? ui("Tool calling") : null,
            draft.vision ? ui("Vision input") : null,
            draft.reasoning ? ui("Reasoning") : null,
            ui("Streaming (required)"),
          ]
            .filter(Boolean)
            .join(" · ")}
        </dd>
      </div>
    </dl>
  );
}

function RequestOptions({ draft, change }: DraftProps) {
  const ui = useAppTranslation();
  return (
    <FieldSet>
      <FieldLegend variant="label">{ui("Request options")}</FieldLegend>
      <DraftCheckbox
        draft={draft}
        change={change}
        name="completionTokens"
        label={ui("Use maxCompletionTokens option family")}
      />
      {!draft.completionTokens && !draft.reasoning && (
        <DraftInput
          draft={draft}
          change={change}
          name="temperature"
          label={ui("Temperature")}
          type="number"
          min={0}
          max={2}
          step="any"
          className="max-w-xs"
        />
      )}
      {draft.reasoning && (
        <Field>
          <FieldLabel htmlFor="model-reasoningEffort">{ui("Reasoning effort")}</FieldLabel>
          <NativeSelect
            id="model-reasoningEffort"
            value={draft.reasoningEffort}
            onChange={(event) => change("reasoningEffort", event.target.value)}
          >
            <option value="">{ui("Provider default (omitted)")}</option>
            {["minimal", "low", "medium", "high"].map((effort) => (
              <option key={effort} value={effort}>
                {effort}
              </option>
            ))}
          </NativeSelect>
        </Field>
      )}
    </FieldSet>
  );
}

export function ModelEditor({ onClose, ...props }: ModelEditorProps & { onClose: () => void }) {
  const ui = useAppTranslation();
  const editor = useModelEditor(props);
  const { baseline, draft, change, busy, dirty, conflicted, invalid, declared } = editor;
  const close = () => {
    editor.cancelAll();
    onClose();
  };

  return (
    <CatalogDialog
      title={
        baseline
          ? ui(appText("Edit model: {{name}}", { name: baseline.displayName }))
          : ui("Add model")
      }
      description={props.provider.name}
      onClose={close}
    >
      <form
        className="flex flex-col gap-5"
        onSubmit={(event) => {
          event.preventDefault();
          void editor.save();
        }}
      >
        <FieldSet disabled={editor.saving.pending || editor.reconciling.pending}>
          <FieldGroup>
            <div className="grid gap-4 sm:grid-cols-2">
              <DraftInput
                draft={draft}
                change={change}
                name="modelName"
                label={ui("API model name")}
                required
                list="known-chat-models"
                maxLength={200}
              />
              <DraftInput
                draft={draft}
                change={change}
                name="displayName"
                label={ui("Display name")}
                required
                maxLength={200}
              />
            </div>
            <datalist id="known-chat-models">
              {props.adapter?.knownModels.map((known) => (
                <option
                  key={known.modelName}
                  value={known.modelName}
                  aria-label={known.modelName}
                />
              ))}
            </datalist>
            {declared ? <DeclaredSpecs draft={draft} /> : <Specs draft={draft} change={change} />}
            <DraftCheckbox
              draft={draft}
              change={change}
              name="visible"
              label={ui("Visible in selection lists")}
            />
            <Collapsible className="group/advanced">
              <CollapsibleTrigger asChild>
                <Button prominence="tertiary">
                  <ChevronDown
                    data-icon="inline-start"
                    aria-hidden="true"
                    className="transition-transform group-data-[state=open]/advanced:rotate-180"
                  />
                  {ui("Advanced options")}
                </Button>
              </CollapsibleTrigger>
              <CollapsibleContent>
                <FieldGroup className="mt-4">
                  {declared && (
                    <>
                      <Specs draft={draft} change={change} />
                      <Prices draft={draft} change={change} />
                    </>
                  )}
                  <RequestOptions draft={draft} change={change} />
                </FieldGroup>
              </CollapsibleContent>
            </Collapsible>
            {!declared && <Prices draft={draft} change={change} />}
          </FieldGroup>
        </FieldSet>
        {invalid && <FieldDescription role="status">{ui(invalid)}</FieldDescription>}
        {conflicted && (
          <Alert variant="warning" role="alert">
            <AlertDescription>
              <div className="flex flex-col items-start gap-2">
                <p>
                  {ui(
                    "The catalog changed or conflicted. Reconcile the saved revision, review your draft, and retry manually.",
                  )}
                </p>
                <Button
                  prominence="secondary"
                  size="sm"
                  disabled={busy}
                  onClick={() => void editor.reconcile()}
                >
                  {ui("Reconcile saved model")}
                </Button>
              </div>
            </AlertDescription>
          </Alert>
        )}
        {editor.actionError && (
          <Alert variant="destructive" role="alert">
            <AlertDescription>{ui(editor.actionError)}</AlertDescription>
          </Alert>
        )}
        {editor.saved && <FieldDescription role="status">{ui("Model saved.")}</FieldDescription>}
        {editor.displayedValidation && (
          <FieldDescription role="status">{editor.displayedValidation}</FieldDescription>
        )}
        <div className="flex flex-wrap justify-end gap-2">
          <Button prominence="secondary" onClick={close}>
            {ui("Close")}
          </Button>
          <Button
            prominence="secondary"
            pending={editor.validating.pending}
            disabled={!baseline || dirty || conflicted || Boolean(invalid) || busy}
            onClick={() => void editor.validate()}
          >
            {ui("Validate saved connection")}
          </Button>
          <Button
            type="submit"
            pending={editor.saving.pending || editor.reconciling.pending}
            disabled={!dirty || conflicted || Boolean(invalid) || busy}
          >
            {ui("Save model")}
          </Button>
        </div>
      </form>
    </CatalogDialog>
  );
}
