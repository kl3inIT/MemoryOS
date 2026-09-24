import { useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, PlugZap } from "lucide-react";
import { useLayoutEffect, useRef, useState } from "react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { appText } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { createEmbeddingProvider, updateEmbeddingProvider } from "@/lib/hey-api/sdk.gen";
import type { EmbeddingProviderRequest, EmbeddingProviderResponse } from "@/lib/hey-api/types.gen";
import { CatalogDialog } from "@/features/models/catalog-dialog";
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
    <div
      role="alert"
      className="space-y-1 rounded-lg bg-status-danger-surface px-4 py-3 text-sm text-status-danger-content"
    >
      <p>{ui(outcome.message)}</p>
      {outcome.detail && <p className="break-words font-mono text-xs">{outcome.detail}</p>}
    </div>
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
        className="space-y-4"
        onSubmit={(event) => {
          event.preventDefault();
          void save();
        }}
      >
        <fieldset disabled={action.pending} className="space-y-4">
          <label className="block space-y-1">
            {ui("Tên")}
            <Input
              required
              maxLength={200}
              value={name}
              onChange={(event) => setName(event.target.value)}
            />
          </label>
          <label className="block space-y-1">
            {ui("Endpoint")}
            <Input
              required
              type="url"
              placeholder="http://10.0.0.5:8080/v1"
              value={endpoint}
              onChange={(event) => {
                const next = event.target.value;
                setEndpoint(next);
                if (
                  initial?.hasApiKey &&
                  next.trim() !== initial.endpoint &&
                  keyAction === "KEEP"
                ) {
                  setKeyAction("REPLACE");
                  clearSecret();
                }
                connection.reset();
              }}
            />
          </label>
          {initial && (
            <label className="block space-y-1">
              {ui("API key")}
              <Select
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
              </Select>
            </label>
          )}
          <label className={keyAction === "REPLACE" ? "block space-y-1" : "hidden"}>
            {ui("API key")}
            <Input
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
          </label>
          <DataBoundaryField value={dataBoundary} onChange={setDataBoundary} />
        </fieldset>
        <fieldset
          disabled={action.pending}
          className="space-y-3 rounded-xl border border-border-subtle p-3"
        >
          <legend className="px-1 font-main-ui-action">{ui("Kiểm tra kết nối")}</legend>
          <div className="grid gap-3 sm:grid-cols-[1fr_8rem]">
            <label className="block min-w-0 space-y-1">
              {ui("Tên model")}
              <Input
                value={model}
                spellCheck={false}
                onChange={(event) => {
                  setModel(event.target.value);
                  connection.reset();
                }}
              />
            </label>
            <label className="block space-y-1">
              {ui("Số chiều")}
              <Input
                inputMode="numeric"
                value={dimensions}
                aria-invalid={!dimensionsValid}
                onChange={(event) => {
                  setDimensions(event.target.value);
                  connection.reset();
                }}
              />
            </label>
          </div>
          <ConnectionOutcome outcome={connection.outcome} />
          <Button
            prominence="secondary"
            size="sm"
            pending={connection.pending}
            disabled={!testable}
            onClick={() => void test()}
          >
            <PlugZap aria-hidden="true" />
            {ui("Kiểm tra")}
          </Button>
        </fieldset>
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
