import { Alert, AlertTitle } from "@/components/ui/alert";
import { useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, PlugZap } from "lucide-react";
import { useLayoutEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Select } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { appText } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { sameOriginMutationHeaders } from "@/lib/api";
import { GroupAccessPicker } from "@/features/groups/group-access-picker";
import {
  listChatGroupOptionsOptions,
  listChatProvidersOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { createChatModel, createChatProvider, updateChatProvider } from "@/lib/hey-api/sdk.gen";
import type { ProviderTestInput } from "@/lib/hey-api/types.gen";
import { CatalogDialog } from "./catalog-dialog";
import { DataBoundaryField, radioCard, type DataBoundary } from "./data-boundary";
import {
  modelBody,
  refreshModelCatalog,
  reportedDraft,
  type CredentialAction,
  type InstalledAdapter,
  type ManagedModel,
  type ManagedProvider,
  type ProviderBody,
  type ReportedModel,
} from "./model-catalog";
import { useProviderTest } from "./provider-test";
import { ProviderModelsField } from "./provider-models-field";
import { useModelAction } from "./use-model-action";

export function ProviderEditor({
  initial,
  models = [],
  providers,
  adapters,
  preferredAdapterType,
  preferredBaseUrl,
  preferredName,
  onClose,
}: {
  initial?: ManagedProvider;
  models?: ManagedModel[];
  providers: ManagedProvider[];
  adapters: InstalledAdapter[];
  preferredAdapterType?: string;
  preferredBaseUrl?: string;
  preferredName?: string;
  onClose: () => void;
}) {
  const client = useQueryClient();
  const ui = useAppTranslation();
  const action = useModelAction();
  // Revision and Access are one snapshot, never assembled from a background refetch and an old draft.
  const [baseline, setBaseline] = useState(initial);
  const [name, setName] = useState(initial?.name ?? preferredName ?? "");
  const [adapterType, setAdapterType] = useState(
    initial?.adapterType ?? preferredAdapterType ?? adapters[0]?.type ?? "",
  );
  const [baseUrl, setBaseUrl] = useState(initial?.baseUrl ?? preferredBaseUrl ?? "");
  const [enabled, setEnabled] = useState(initial?.enabled ?? true);
  const [isPublic, setIsPublic] = useState(initial?.isPublic ?? true);
  // A provider is External until its administrator states it may receive internal documents.
  const [dataBoundary, setDataBoundary] = useState<DataBoundary>(
    initial?.dataBoundary ?? "EXTERNAL",
  );
  const [groupIds, setGroupIds] = useState<ReadonlySet<string>>(
    () => new Set(initial?.groupIds ?? []),
  );
  const [credentialAction, setCredentialAction] = useState<CredentialAction>(
    initial ? "KEEP" : "REPLACE",
  );
  const keyInput = useRef<HTMLInputElement>(null);
  const secret = useRef("");
  const [keyReady, setKeyReady] = useState(false);
  const [saved, setSaved] = useState(false);
  // Models chosen from the endpoint listing are created once the provider itself is saved.
  const [chosenModels, setChosenModels] = useState<ReportedModel[]>([]);
  const [unsavedModels, setUnsavedModels] = useState<string[]>([]);
  const connection = useProviderTest();
  const adapter = adapters.find((entry) => entry.type === adapterType);
  const latest = baseline && providers.find((provider) => provider.id === baseline.id);
  const stale = Boolean(baseline && (!latest || latest.revision !== baseline.revision));
  const conflicted = action.conflict || stale;
  const credentialMissing =
    adapter?.credentialRequirement === "REQUIRED" &&
    enabled &&
    (credentialAction === "REMOVE" ||
      (credentialAction === "KEEP" && !baseline?.credentialConfigured));
  const invalid =
    !name.trim() ||
    !baseUrl.trim() ||
    !adapter ||
    credentialMissing ||
    (!isPublic && groupIds.size === 0) ||
    (credentialAction === "REPLACE" && !keyReady);

  // A draft can be checked once it names an endpoint and has a key to send: a typed one, or the saved one kept.
  const testable =
    Boolean(adapter && baseUrl.trim()) &&
    (credentialAction === "REPLACE"
      ? keyReady
      : credentialAction === "KEEP" && Boolean(baseline?.credentialConfigured));

  function draftConnection(): ProviderTestInput | null {
    if (!testable) return null;
    return {
      adapterType,
      baseUrl: baseUrl.trim(),
      providerId: baseline?.id,
      credential:
        credentialAction === "REPLACE"
          ? { action: "REPLACE", value: secret.current }
          : { action: "KEEP" },
    };
  }

  function changed() {
    setSaved(false);
    connection.reset();
  }

  async function testConnection() {
    if (!testable || action.pending || connection.pending) return;
    await connection.run({
      adapterType,
      baseUrl: baseUrl.trim(),
      providerId: baseline?.id,
      credential:
        credentialAction === "REPLACE"
          ? { action: "REPLACE", value: secret.current }
          : { action: "KEEP" },
    });
  }

  function clearSecret() {
    secret.current = "";
    if (keyInput.current) keyInput.current.value = "";
    setKeyReady(false);
  }

  useLayoutEffect(() => {
    const input = keyInput.current;
    const clear = () => {
      secret.current = "";
      if (input) input.value = "";
      setKeyReady(false);
    };
    window.addEventListener("pagehide", clear);
    return () => {
      clear();
      window.removeEventListener("pagehide", clear);
    };
  }, []);

  async function save() {
    if (invalid || conflicted || action.pending) return;
    const body: ProviderBody = {
      name: name.trim(),
      adapterType,
      baseUrl: baseUrl.trim(),
      enabled,
      isPublic,
      groupIds: [...groupIds],
      personaIds: baseline?.personaIds ?? [],
      dataBoundary,
      credential:
        credentialAction === "REPLACE"
          ? { action: "REPLACE", value: secret.current }
          : { action: credentialAction },
    };
    clearSecret();
    changed();
    try {
      await action.run(async (signal) => {
        const result = baseline
          ? await updateChatProvider({
              path: { providerId: baseline.id },
              query: { revision: baseline.revision },
              body,
              headers: sameOriginMutationHeaders,
              signal,
              throwOnError: true,
            })
          : await createChatProvider({
              body,
              headers: sameOriginMutationHeaders,
              signal,
              throwOnError: true,
            });
        signal.throwIfAborted();
        setBaseline(result.data);
        setCredentialAction("KEEP");
        // The provider exists now, so its chosen models are created one by one; a failure keeps the
        // editor open naming what is left, because the provider itself is already saved.
        const failed: string[] = [];
        for (const model of chosenModels) {
          try {
            await createChatModel({
              path: { providerId: result.data.id },
              body: modelBody(reportedDraft(model, adapter)),
              headers: sameOriginMutationHeaders,
              signal,
              throwOnError: true,
            });
          } catch (cause) {
            signal.throwIfAborted();
            if (cause instanceof Error && cause.name === "AbortError") throw cause;
            failed.push(model.modelName);
          }
          signal.throwIfAborted();
        }
        setChosenModels(chosenModels.filter((model) => failed.includes(model.modelName)));
        setUnsavedModels(failed);
        await refreshModelCatalog(client);
        signal.throwIfAborted();
        setSaved(failed.length === 0);
      });
    } catch {
      /* Safe action-local feedback is owned by useModelAction. */
    } finally {
      delete body.credential?.value;
      clearSecret();
    }
  }

  async function reconcile() {
    clearSecret();
    setSaved(false);
    action.cancel();
    try {
      await action.run(async (signal) => {
        await client.invalidateQueries({
          queryKey: listChatProvidersOptions().queryKey,
          refetchType: "none",
        });
        signal.throwIfAborted();
        const currentProviders = await client.fetchQuery({
          ...listChatProvidersOptions(),
          retry: false,
          staleTime: 0,
        });
        signal.throwIfAborted();
        if (baseline) {
          const current = currentProviders.find((provider) => provider.id === baseline.id);
          if (!current) throw new Error("Provider unavailable");
          setBaseline(current);
          setAdapterType(current.adapterType);
          setIsPublic(current.isPublic);
          setDataBoundary(current.dataBoundary);
          setGroupIds(new Set(current.groupIds));
        }
        setCredentialAction("KEEP");
        action.reconciled();
      });
    } catch {
      /* No secret or raw SDK error is retained. */
    }
  }

  return (
    <CatalogDialog
      title={
        baseline
          ? ui(appText("Edit provider: {{name}}", { name: baseline.name }))
          : ui("Add provider")
      }
      onClose={() => {
        clearSecret();
        action.cancel();
        onClose();
      }}
    >
      <form
        onSubmit={(event) => {
          event.preventDefault();
          void save();
        }}
        className="space-y-4"
      >
        <fieldset disabled={action.pending} className="space-y-4">
          <label className="block space-y-1">
            {ui("Provider name")}
            <Input
              required
              maxLength={200}
              value={name}
              onChange={(event) => {
                setName(event.target.value);
                changed();
              }}
            />
          </label>
          {/* One installed protocol offers no choice; a second one makes the selection meaningful. */}
          {(adapters.length > 1 || !adapter) && (
            <label className="block space-y-1">
              {ui("Protocol")}
              <Select
                value={adapterType}
                disabled={Boolean(baseline)}
                onChange={(event) => {
                  setAdapterType(event.target.value);
                  clearSecret();
                  changed();
                }}
              >
                {!adapter && (
                  <option value={adapterType}>
                    {ui(
                      appText("{{adapter}} (unavailable)", {
                        adapter: adapterType || appText("Choose installed adapter"),
                      }),
                    )}
                  </option>
                )}
                {adapters.map((entry) => (
                  <option key={entry.type} value={entry.type}>
                    {entry.type === "openai" ? ui("OpenAI-compatible") : entry.type}
                  </option>
                ))}
              </Select>
            </label>
          )}
          <label className="block space-y-1">
            {ui("Endpoint URL")}
            <Input
              required
              type="url"
              value={baseUrl}
              onChange={(event) => {
                setBaseUrl(event.target.value);
                changed();
              }}
            />
          </label>
          <label className="flex items-center justify-between gap-3 font-main-ui-body">
            {ui("Provider enabled")}
            <Switch
              checked={enabled}
              onCheckedChange={(checked) => {
                setEnabled(checked);
                changed();
              }}
            />
          </label>
          <fieldset className="space-y-3">
            <legend className="mb-2 font-main-ui-action">{ui("Who can use this provider")}</legend>
            <RadioGroup
              value={isPublic ? "public" : "groups"}
              onValueChange={(value) => {
                setIsPublic(value === "public");
                changed();
              }}
            >
              <label className={`${radioCard} items-center font-main-ui-body`}>
                <RadioGroupItem value="public" />
                {ui("Every Tenant member")}
              </label>
              <label className={`${radioCard} items-center font-main-ui-body`}>
                <RadioGroupItem value="groups" />
                {ui("Selected Groups only")}
              </label>
            </RadioGroup>
            {!isPublic && (
              <GroupAccessPicker
                selected={groupIds}
                required
                disabled={action.pending}
                load={(query) => listChatGroupOptionsOptions({ query })}
                description={appText(
                  "Members of the selected Groups can use this provider in Chat.",
                )}
                onChange={(next) => {
                  setGroupIds(next);
                  changed();
                }}
              />
            )}
          </fieldset>
          <DataBoundaryField
            value={dataBoundary}
            onChange={(next) => {
              setDataBoundary(next);
              changed();
            }}
          />
          <label className="block space-y-1">
            {ui("Credential action")}
            <Select
              value={credentialAction}
              onChange={(event) => {
                setCredentialAction(event.target.value as CredentialAction);
                clearSecret();
                changed();
              }}
            >
              <option value="KEEP">{ui("Keep existing key")}</option>
              <option value="REPLACE">{ui("Replace key")}</option>
              <option value="REMOVE">{ui("Remove key")}</option>
            </Select>
          </label>
          <label className={credentialAction === "REPLACE" ? "block space-y-1" : "hidden"}>
            {ui("API key")}
            <Input
              ref={keyInput}
              type="password"
              autoComplete="off"
              spellCheck={false}
              disabled={credentialAction !== "REPLACE" || action.pending}
              onChange={(event) => {
                secret.current = event.target.value;
                setKeyReady(Boolean(secret.current.trim()));
                changed();
              }}
            />
          </label>
          {credentialMissing && (
            <p role="alert">
              {ui(
                "An enabled provider requires a configured key. Replace the key or explicitly disable this provider.",
              )}
            </p>
          )}
        </fieldset>
        <ProviderModelsField
          connection={draftConnection}
          configured={models}
          selected={chosenModels}
          onSelected={setChosenModels}
          disabled={action.pending || conflicted}
        />
        {unsavedModels.length > 0 && (
          <p role="alert">
            {ui(
              appText("The provider was saved. These models were not added: {{models}}", {
                models: unsavedModels.join(", "),
              }),
            )}
          </p>
        )}
        {conflicted && (
          <div role="alert" className="space-y-2">
            <p>
              {ui(
                "The saved catalog changed or conflicted. Reconcile the complete revision and Access baseline, review your non-secret draft, then retry manually. The key has not been retained.",
              )}
            </p>
            <Button
              prominence="secondary"
              disabled={action.pending}
              onClick={() => void reconcile()}
            >
              {ui("Reconcile saved provider")}
            </Button>
          </div>
        )}
        {connection.outcome?.ok && (
          <Alert variant="success" role="status">
            <CheckCircle2 aria-hidden="true" />
            <AlertTitle>{ui(connection.outcome.message)}</AlertTitle>
          </Alert>
        )}
        {connection.outcome && !connection.outcome.ok && (
          <p
            role="alert"
            className="rounded-lg bg-status-danger-surface px-4 py-3 text-sm text-status-danger-content"
          >
            {ui(connection.outcome.message)}
          </p>
        )}
        {action.error && <p role="alert">{ui(action.error)}</p>}
        {saved && (
          <p role="status">
            {enabled
              ? ui("Provider saved.")
              : ui("Provider saved. No connectivity claim has been made.")}
          </p>
        )}
        <div className="flex flex-wrap justify-end gap-2">
          <Button
            prominence="secondary"
            className="mr-auto"
            pending={connection.pending}
            disabled={!testable || action.pending}
            onClick={() => void testConnection()}
          >
            <PlugZap aria-hidden="true" />
            {ui("Test connection")}
          </Button>
          <Button
            prominence="secondary"
            onClick={() => {
              clearSecret();
              action.cancel();
              onClose();
            }}
          >
            {ui("Close")}
          </Button>
          <Button
            type="submit"
            pending={action.pending}
            disabled={invalid || conflicted || connection.pending}
          >
            {ui("Save provider")}
          </Button>
        </div>
      </form>
    </CatalogDialog>
  );
}
