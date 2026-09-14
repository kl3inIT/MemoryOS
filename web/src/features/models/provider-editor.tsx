import { useQueryClient } from "@tanstack/react-query";
import { useLayoutEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { appText } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { sameOriginMutationHeaders } from "@/lib/api";
import { GroupAccessPicker } from "@/features/groups/group-access-picker";
import {
  listChatGroupOptionsOptions,
  listChatProvidersOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { createChatProvider, updateChatProvider } from "@/lib/hey-api/sdk.gen";
import { CatalogDialog } from "./catalog-dialog";
import {
  refreshModelCatalog,
  type CredentialAction,
  type InstalledAdapter,
  type ManagedProvider,
  type ProviderBody,
} from "./model-catalog";
import { useModelAction } from "./use-model-action";

export function ProviderEditor({
  initial,
  providers,
  adapters,
  preferredAdapterType,
  preferredBaseUrl,
  preferredName,
  onClose,
}: {
  initial?: ManagedProvider;
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
      credential:
        credentialAction === "REPLACE"
          ? { action: "REPLACE", value: secret.current }
          : { action: credentialAction },
    };
    clearSecret();
    setSaved(false);
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
        await refreshModelCatalog(client);
        signal.throwIfAborted();
        setSaved(true);
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
                setSaved(false);
              }}
            />
          </label>
          <label className="block space-y-1">
            {ui("Adapter")}
            <Select
              value={adapterType}
              disabled={Boolean(baseline)}
              onChange={(event) => {
                setAdapterType(event.target.value);
                clearSecret();
                setSaved(false);
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
                  {entry.type}
                </option>
              ))}
            </Select>
          </label>
          <label className="block space-y-1">
            {ui("Endpoint URL")}
            <Input
              required
              type="url"
              value={baseUrl}
              onChange={(event) => {
                setBaseUrl(event.target.value);
                setSaved(false);
              }}
            />
          </label>
          <label className="flex items-center gap-2">
            <input
              type="checkbox"
              checked={enabled}
              onChange={(event) => {
                setEnabled(event.target.checked);
                setSaved(false);
              }}
            />
            {ui("Provider enabled")}
          </label>
          <fieldset className="space-y-3">
            <legend className="font-main-ui-action">{ui("Who can use this provider")}</legend>
            <label className="flex items-center gap-2">
              <input
                type="radio"
                name="provider-access"
                checked={isPublic}
                onChange={() => {
                  setIsPublic(true);
                  setSaved(false);
                }}
              />
              {ui("Every Tenant member")}
            </label>
            <label className="flex items-center gap-2">
              <input
                type="radio"
                name="provider-access"
                checked={!isPublic}
                onChange={() => {
                  setIsPublic(false);
                  setSaved(false);
                }}
              />
              {ui("Selected Groups only")}
            </label>
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
                  setSaved(false);
                }}
              />
            )}
          </fieldset>
          <label className="block space-y-1">
            {ui("Credential action")}
            <Select
              value={credentialAction}
              onChange={(event) => {
                setCredentialAction(event.target.value as CredentialAction);
                clearSecret();
                setSaved(false);
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
                setSaved(false);
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
        {baseline && (
          <p className="break-all font-secondary-body text-content-muted">
            {ui(
              appText("Provider {{id}} · revision {{revision}}", {
                id: baseline.id,
                revision: baseline.revision,
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
        {action.error && <p role="alert">{ui(action.error)}</p>}
        {saved && <p role="status">{ui("Provider saved. No connectivity claim has been made.")}</p>}
        <div className="flex flex-wrap justify-end gap-2">
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
          <Button type="submit" pending={action.pending} disabled={invalid || conflicted}>
            {ui("Save provider")}
          </Button>
        </div>
      </form>
    </CatalogDialog>
  );
}
