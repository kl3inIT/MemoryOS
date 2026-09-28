import { revalidateLogic, useStore } from "@tanstack/react-form";
import { useQueryClient } from "@tanstack/react-query";
import { useLayoutEffect, useRef, useState } from "react";
import { flushSync } from "react-dom";
import { z } from "zod";
import { useAppForm } from "@/components/form/app-form";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { listChatProvidersOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import { createChatModel, createChatProvider, updateChatProvider } from "@/lib/hey-api/sdk.gen";
import type { ProviderTestInput } from "@/lib/hey-api/types.gen";
import type { DataBoundary } from "./data-boundary";
import {
  modelBody,
  refreshModelCatalog,
  reportedDraft,
  type CredentialAction,
  type InstalledAdapter,
  type ManagedProvider,
  type ProviderBody,
  type ReportedModel,
} from "./model-catalog";
import { useModelCatalogBusy, useModelMutation } from "./model-mutation";
import { useProviderTest } from "./provider-test";

export type ProviderEditorProps = {
  initial?: ManagedProvider;
  providers: ManagedProvider[];
  adapters: InstalledAdapter[];
  preferredAdapterType?: string;
  preferredBaseUrl?: string;
  preferredName?: string;
};

type ProviderValues = {
  name: string;
  adapterType: string;
  baseUrl: string;
  enabled: boolean;
  isPublic: boolean;
  dataBoundary: DataBoundary;
  groupIds: string[];
  credentialAction: CredentialAction;
};

function providerDefaults({
  initial,
  adapters,
  preferredAdapterType,
  preferredBaseUrl,
  preferredName,
}: ProviderEditorProps): ProviderValues {
  return {
    name: initial?.name ?? preferredName ?? "",
    adapterType: initial?.adapterType ?? preferredAdapterType ?? adapters[0]?.type ?? "",
    baseUrl: initial?.baseUrl ?? preferredBaseUrl ?? "",
    enabled: initial?.enabled ?? true,
    isPublic: initial?.isPublic ?? true,
    // A provider is External until its administrator states it may receive internal documents.
    dataBoundary: initial?.dataBoundary ?? "EXTERNAL",
    groupIds: initial?.groupIds ?? [],
    credentialAction: initial ? "KEEP" : "REPLACE",
  };
}

/**
 * The typed API key. It never enters form state or a mutation: it lives in an uncontrolled input and a
 * ref, is read when a write or check starts, and is cleared at once, on a protocol or credential change,
 * on close and when the page is hidden. Clearing remounts the input empty.
 */
function useProviderSecret(onType: () => void) {
  const secret = useRef("");
  const [ready, setReady] = useState(false);
  const [version, setVersion] = useState(0);

  useLayoutEffect(() => {
    // The typed key leaves the page before it can enter the back/forward cache.
    const hide = () => {
      secret.current = "";
      flushSync(() => {
        setReady(false);
        setVersion((current) => current + 1);
      });
    };
    window.addEventListener("pagehide", hide);
    return () => {
      secret.current = "";
      window.removeEventListener("pagehide", hide);
    };
  }, []);

  return {
    ready,
    version,
    read: () => secret.current,
    type(value: string) {
      secret.current = value;
      setReady(Boolean(value.trim()));
      onType();
    },
    clear() {
      secret.current = "";
      setReady(false);
      setVersion((current) => current + 1);
    },
  };
}

/** The provider form and its catalog operations: save with the chosen models, reconcile after a conflict. */
export function useProviderEditor(props: ProviderEditorProps) {
  const { initial, providers, adapters } = props;
  const client = useQueryClient();
  const ui = useAppTranslation();
  const busy = useModelCatalogBusy();
  const [conflict, setConflict] = useState(false);
  // Revision and Access are one snapshot, never assembled from a background refetch and an old draft.
  const [baseline, setBaseline] = useState(initial);
  const secret = useProviderSecret(() => changed());
  const keyReady = secret.ready;
  const [saved, setSaved] = useState(false);
  // Models chosen from the endpoint listing are created once the provider itself is saved.
  const [chosenModels, setChosenModels] = useState<ReportedModel[]>([]);
  const [unsavedModels, setUnsavedModels] = useState<string[]>([]);
  const connection = useProviderTest();

  const schema = z.object({
    name: z.string().trim().min(1, ui("Enter a provider name.")),
    adapterType: z.string(),
    baseUrl: z.string().trim().min(1, ui("Enter the endpoint URL.")),
    enabled: z.boolean(),
    isPublic: z.boolean(),
    dataBoundary: z.enum(["EXTERNAL", "INTERNAL"]),
    groupIds: z.array(z.string()),
    credentialAction: z.enum(["KEEP", "REPLACE", "REMOVE"]),
  });
  const form = useAppForm({
    defaultValues: providerDefaults(props),
    validationLogic: revalidateLogic(),
    validators: { onDynamic: schema },
    // Any edit makes an earlier check or save outcome stale.
    listeners: { onChange: () => changed() },
    onSubmit: () => save(),
  });
  const values = useStore(form.store, (state) => state.values);

  const adapter = adapters.find((entry) => entry.type === values.adapterType);
  const latest = baseline && providers.find((provider) => provider.id === baseline.id);
  const stale = Boolean(baseline && (!latest || latest.revision !== baseline.revision));
  const conflicted = conflict || stale;
  const credentialMissing =
    adapter?.credentialRequirement === "REQUIRED" &&
    values.enabled &&
    (values.credentialAction === "REMOVE" ||
      (values.credentialAction === "KEEP" && !baseline?.credentialConfigured));
  /** What the form cannot send at all; name and endpoint are validated as fields on submit. */
  const blocked =
    !adapter ||
    credentialMissing ||
    (!values.isPublic && values.groupIds.length === 0) ||
    (values.credentialAction === "REPLACE" && !keyReady);

  // A draft can be checked once it names an endpoint and has a key to send: a typed one, or the saved one kept.
  const testable =
    Boolean(adapter && values.baseUrl.trim()) &&
    (values.credentialAction === "REPLACE"
      ? keyReady
      : values.credentialAction === "KEEP" && Boolean(baseline?.credentialConfigured));

  function credential(): ProviderTestInput["credential"] {
    return form.state.values.credentialAction === "REPLACE"
      ? { action: "REPLACE", value: secret.read() }
      : { action: "KEEP" };
  }

  function draftConnection(): ProviderTestInput | null {
    if (!testable) return null;
    return {
      adapterType: form.state.values.adapterType,
      baseUrl: form.state.values.baseUrl.trim(),
      providerId: baseline?.id,
      credential: credential(),
    };
  }

  function changed() {
    setSaved(false);
    connection.reset();
  }

  async function testConnection() {
    const body = draftConnection();
    if (!body || busy || connection.pending) return;
    await connection.run(body);
  }

  const clearSecret = secret.clear;

  const saving = useModelMutation(
    async (signal) => {
      const draft = form.state.values;
      // The key is read when the write starts and cleared at once; it is never a mutation variable.
      const body: ProviderBody = {
        name: draft.name.trim(),
        adapterType: draft.adapterType,
        baseUrl: draft.baseUrl.trim(),
        enabled: draft.enabled,
        isPublic: draft.isPublic,
        groupIds: draft.groupIds,
        personaIds: baseline?.personaIds ?? [],
        dataBoundary: draft.dataBoundary,
        credential:
          draft.credentialAction === "REPLACE"
            ? { action: "REPLACE", value: secret.read() }
            : { action: draft.credentialAction },
      };
      clearSecret();
      try {
        const result = baseline
          ? await updateChatProvider({
              path: { providerId: baseline.id },
              query: { revision: baseline.revision },
              body,
              signal,
            })
          : await createChatProvider({ body, signal });
        signal.throwIfAborted();
        setBaseline(result.data);
        form.setFieldValue("credentialAction", "KEEP");
        // The provider exists now, so its chosen models are created one by one; a failure keeps the
        // editor open naming what is left, because the provider itself is already saved.
        const failed: string[] = [];
        for (const model of chosenModels) {
          try {
            await createChatModel({
              path: { providerId: result.data.id },
              body: modelBody(reportedDraft(model, adapter)),
              signal,
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
      } finally {
        delete body.credential?.value;
      }
    },
    { onConflict: () => setConflict(true) },
  );

  const reconciling = useModelMutation(async (signal) => {
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
      // Access and the boundary come from the reconciled baseline; the typed name and endpoint stay.
      form.setFieldValue("adapterType", current.adapterType);
      form.setFieldValue("isPublic", current.isPublic);
      form.setFieldValue("dataBoundary", current.dataBoundary);
      form.setFieldValue("groupIds", current.groupIds);
    }
    form.setFieldValue("credentialAction", "KEEP");
    setConflict(false);
  });

  const pending = saving.pending || reconciling.pending;

  function cancelAll() {
    saving.cancel();
    reconciling.cancel();
  }

  async function save() {
    if (blocked || conflicted || busy) return;
    changed();
    reconciling.cancel();
    try {
      await saving.run();
    } catch {
      /* The mutation keeps only safe action-local feedback. */
    } finally {
      clearSecret();
    }
  }

  async function reconcile() {
    clearSecret();
    setSaved(false);
    cancelAll();
    try {
      await reconciling.run();
    } catch {
      /* No secret or raw SDK error is retained. */
    }
  }

  function close() {
    clearSecret();
    cancelAll();
  }

  return {
    form,
    values,
    baseline,
    adapter,
    keyVersion: secret.version,
    connection,
    saving,
    pending,
    busy,
    conflicted,
    credentialMissing,
    blocked,
    testable,
    saved,
    chosenModels,
    setChosenModels,
    unsavedModels,
    /** One feedback line: starting an operation clears what the other reported. */
    actionError: saving.error ?? reconciling.error,
    draftConnection,
    testConnection,
    clearSecret,
    typeSecret: secret.type,
    reconcile,
    close,
  };
}
