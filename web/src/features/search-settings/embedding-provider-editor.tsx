import { useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, CircleAlert, PlugZap } from "lucide-react";
import { useLayoutEffect, useRef, useState } from "react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Field, FieldGroup, FieldLabel, FieldLegend, FieldSet } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { NativeSelect } from "@/components/ui/native-select";
import { appText } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import { createEmbeddingProvider, updateEmbeddingProvider } from "@/lib/hey-api/sdk.gen";
import type { EmbeddingProviderRequest, EmbeddingProviderResponse } from "@/lib/hey-api/types.gen";
import { CatalogDialog } from "@/components/composites/catalog-dialog";
import { DataBoundaryField, type DataBoundary } from "@/features/models/data-boundary";
import type { ProviderTestOutcome } from "@/features/models/provider-test";
import { useModelMutation } from "@/features/models/model-mutation";
import { useEmbeddingTest } from "./embedding-connection";
import { refreshSearchSettings, searchSettingsError } from "./search-settings";

type KeyAction = "KEEP" | "REPLACE" | "REMOVE";

/** A connection check's outcome, with the provider's own reason under a failure. */
export function ConnectionOutcome({ outcome }: { outcome: ProviderTestOutcome | null }) {
  const ui = useAppTranslation();
  if (!outcome) return null;
  return outcome.ok ? (
    <Alert variant="success" role="status">
      <CheckCircle2 aria-hidden="true" />
      <AlertTitle className="break-words">{ui(outcome.message)}</AlertTitle>
    </Alert>
  ) : (
    <Alert variant="destructive" role="alert">
      <CircleAlert aria-hidden="true" />
      <AlertTitle className="break-words">{ui(outcome.message)}</AlertTitle>
      {outcome.detail && (
        <AlertDescription>
          <p className="break-words font-mono">{outcome.detail}</p>
        </AlertDescription>
      )}
    </Alert>
  );
}

/** Create or edit one OpenAI-compatible embedding endpoint; the key is never shown back. */
export function EmbeddingProviderEditor({
  initial,
  testModel,
  onClose,
}: {
  initial?: EmbeddingProviderResponse;
  /** The model a connection check asks for, prefilled from the generation that uses this provider. */
  testModel: { model: string; dimensions: number | null };
  onClose: () => void;
}) {
  const ui = useAppTranslation();
  const client = useQueryClient();
  const connection = useEmbeddingTest();
  const [name, setName] = useState(initial?.name ?? "");
  const [endpoint, setEndpoint] = useState(initial?.endpoint ?? "");
  const [dataBoundary, setDataBoundary] = useState<DataBoundary>(
    initial?.dataBoundary ?? "EXTERNAL",
  );
  const [keyAction, setKeyAction] = useState<KeyAction>(initial ? "KEEP" : "REPLACE");
  const [model, setModel] = useState(testModel.model);
  const [dimensions, setDimensions] = useState(
    testModel.dimensions == null ? "" : String(testModel.dimensions),
  );
  const keyInput = useRef<HTMLInputElement>(null);
  const secret = useRef("");
  const [keyTyped, setKeyTyped] = useState(false);

  function clearSecret() {
    secret.current = "";
    if (keyInput.current) keyInput.current.value = "";
    setKeyTyped(false);
  }

  useLayoutEffect(() => {
    const input = keyInput.current;
    const clear = () => {
      secret.current = "";
      if (input) input.value = "";
    };
    window.addEventListener("pagehide", clear);
    return () => {
      clear();
      window.removeEventListener("pagehide", clear);
    };
  }, []);

  // A stored key stays with the endpoint it was saved for; a moved endpoint needs a new key or none.
  const keepBlocked = Boolean(initial?.hasApiKey) && endpoint.trim() !== initial?.endpoint;

  // A new provider may run without a key; replacing one on an existing provider needs the new key typed.
  const invalid =
    (keepBlocked && keyAction === "KEEP") ||
    !name.trim() ||
    !endpoint.trim() ||
    (initial != null && keyAction === "REPLACE" && !keyTyped);
  const dimensionsValid = !dimensions.trim() || /^\d+$/.test(dimensions.trim());
  const testable = Boolean(endpoint.trim() && model.trim() && dimensionsValid);

  function apiKey(): string | null {
    if (keyAction === "REMOVE") return "";
    if (keyAction === "REPLACE" && secret.current.trim()) return secret.current;
    return null;
  }

  async function test() {
    if (!testable) return;
    // An unchanged saved provider is checked by id so its stored key is used; an edited draft sends its fields.
    const saved =
      initial && keyAction === "KEEP" && endpoint.trim() === initial.endpoint ? initial.id : null;
    await connection.run({
      providerId: saved,
      endpoint: saved ? null : endpoint.trim(),
      apiKey: saved ? null : apiKey(),
      model: model.trim(),
      dimensions: dimensions.trim() ? Number(dimensions.trim()) : null,
    });
  }

  const action = useModelMutation(
    async (signal) => {
      // The key is read when the write starts and cleared at once; it is never a mutation variable.
      const body: EmbeddingProviderRequest = {
        name: name.trim(),
        endpoint: endpoint.trim(),
        apiKey: apiKey(),
        dataBoundary,
        revision: initial?.revision ?? null,
      };
      clearSecret();
      try {
        if (initial)
          await updateEmbeddingProvider({
            path: { providerId: initial.id },
            body,
            signal,
          });
        else await createEmbeddingProvider({ body, signal });
      } finally {
        body.apiKey = null;
      }
      signal.throwIfAborted();
      await refreshSearchSettings(client);
    },
    { describe: (cause) => searchSettingsError(cause, "saveProvider") },
  );

  function changeEndpoint(next: string) {
    setEndpoint(next);
    if (initial?.hasApiKey && next.trim() !== initial.endpoint && keyAction === "KEEP") {
      setKeyAction("REPLACE");
      clearSecret();
    }
    connection.reset();
  }

  async function save() {
    if (invalid || action.pending) return;
    try {
      await action.run();
      onClose();
    } catch {
      /* The mutation settles with the safe message shown below. */
    }
  }

  function close() {
    clearSecret();
    action.cancel();
    connection.reset();
    onClose();
  }

  return (
    <CatalogDialog
      title={
        initial
          ? ui(appText("Sửa provider {{name}}", { name: initial.name }))
          : ui("Thêm provider embedding")
      }
      onClose={close}
    >
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          void save();
        }}
      >
        <FieldSet disabled={action.pending}>
          <FieldGroup>
            <Field>
              <FieldLabel htmlFor="embedding-name">{ui("Tên")}</FieldLabel>
              <Input
                id="embedding-name"
                required
                maxLength={200}
                value={name}
                onChange={(event) => setName(event.target.value)}
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="embedding-endpoint">{ui("Endpoint")}</FieldLabel>
              <Input
                id="embedding-endpoint"
                required
                type="url"
                placeholder="http://10.0.0.5:8080/v1"
                value={endpoint}
                onChange={(event) => changeEndpoint(event.target.value)}
              />
            </Field>
            {initial && (
              <Field>
                <FieldLabel htmlFor="embedding-key-action">{ui("API key")}</FieldLabel>
                <NativeSelect
                  id="embedding-key-action"
                  value={keyAction}
                  onChange={(event) => {
                    setKeyAction(event.target.value as KeyAction);
                    clearSecret();
                    connection.reset();
                  }}
                >
                  <option value="KEEP" disabled={keepBlocked}>
                    {initial.hasApiKey ? ui("Keep existing key") : ui("Không có khóa")}
                  </option>
                  <option value="REPLACE">{ui("Replace key")}</option>
                  {initial.hasApiKey && <option value="REMOVE">{ui("Remove key")}</option>}
                </NativeSelect>
              </Field>
            )}
            <Field className={cn(keyAction !== "REPLACE" && "hidden")}>
              <FieldLabel htmlFor="embedding-key">{ui("API key")}</FieldLabel>
              <Input
                id="embedding-key"
                ref={keyInput}
                type="password"
                autoComplete="off"
                spellCheck={false}
                disabled={keyAction !== "REPLACE" || action.pending}
                onChange={(event) => {
                  secret.current = event.target.value;
                  setKeyTyped(Boolean(secret.current.trim()));
                  connection.reset();
                }}
              />
            </Field>
            <DataBoundaryField value={dataBoundary} onChange={setDataBoundary} />
          </FieldGroup>
        </FieldSet>
        <ConnectionCheck
          disabled={action.pending}
          model={model}
          dimensions={dimensions}
          dimensionsValid={dimensionsValid}
          testable={testable}
          connection={connection}
          onModel={(next) => {
            setModel(next);
            connection.reset();
          }}
          onDimensions={(next) => {
            setDimensions(next);
            connection.reset();
          }}
          onTest={() => void test()}
        />
        {action.error && (
          <Alert variant="destructive" role="alert">
            <AlertDescription>{ui(action.error)}</AlertDescription>
          </Alert>
        )}
        <div className="flex flex-wrap justify-end gap-2">
          <Button prominence="secondary" onClick={close}>
            {ui("Đóng")}
          </Button>
          <Button type="submit" pending={action.pending} disabled={invalid}>
            {ui("Lưu provider")}
          </Button>
        </div>
      </form>
    </CatalogDialog>
  );
}

/** A real embedding call with the draft's endpoint and key, for the model and dimensions typed here. */
function ConnectionCheck({
  disabled,
  model,
  dimensions,
  dimensionsValid,
  testable,
  connection,
  onModel,
  onDimensions,
  onTest,
}: {
  disabled: boolean;
  model: string;
  dimensions: string;
  dimensionsValid: boolean;
  testable: boolean;
  connection: ReturnType<typeof useEmbeddingTest>;
  onModel: (model: string) => void;
  onDimensions: (dimensions: string) => void;
  onTest: () => void;
}) {
  const ui = useAppTranslation();
  return (
    <Card size="sm">
      <CardContent>
        <FieldSet disabled={disabled}>
          <FieldLegend variant="label">{ui("Kiểm tra kết nối")}</FieldLegend>
          <div className="grid gap-3 sm:grid-cols-4">
            <Field className="min-w-0 sm:col-span-3">
              <FieldLabel htmlFor="embedding-test-model">{ui("Tên model")}</FieldLabel>
              <Input
                id="embedding-test-model"
                value={model}
                spellCheck={false}
                onChange={(event) => onModel(event.target.value)}
              />
            </Field>
            <Field data-invalid={!dimensionsValid || undefined}>
              <FieldLabel htmlFor="embedding-test-dimensions">{ui("Số chiều")}</FieldLabel>
              <Input
                id="embedding-test-dimensions"
                inputMode="numeric"
                value={dimensions}
                aria-invalid={!dimensionsValid}
                onChange={(event) => onDimensions(event.target.value)}
              />
            </Field>
          </div>
          <ConnectionOutcome outcome={connection.outcome} />
          <Button
            prominence="secondary"
            size="sm"
            className="self-start"
            pending={connection.pending}
            disabled={!testable}
            onClick={onTest}
          >
            <PlugZap data-icon="inline-start" aria-hidden="true" />
            {ui("Kiểm tra")}
          </Button>
        </FieldSet>
      </CardContent>
    </Card>
  );
}
