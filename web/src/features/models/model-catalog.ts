import type { QueryClient } from "@tanstack/react-query";
import { ApiError } from "@/lib/api";
import type {
  CreateChatModelData,
  CreateChatProviderData,
  ListChatProvidersResponse,
  ListConfiguredChatModelsResponse,
  ListChatProviderAdaptersResponse,
} from "@/lib/hey-api/types.gen";

export type ManagedProvider = ListChatProvidersResponse[number];
export type ManagedModel = ListConfiguredChatModelsResponse[number];
export type InstalledAdapter = ListChatProviderAdaptersResponse[number];
export type ProviderBody = CreateChatProviderData["body"];
export type ModelBody = CreateChatModelData["body"];
export type CredentialAction = NonNullable<NonNullable<ProviderBody["credential"]>["action"]>;

export function modelActionError(error: unknown): string {
  if (!(error instanceof ApiError))
    return "The request could not be completed. Check your connection and refresh before trying again.";
  switch (error.status) {
    case 400:
      return "The configuration is not accepted. Check the endpoint, profile, capabilities, limits, options and Persona restrictions. Choose another Tenant default before hiding or deleting its model, disabling its provider or removing its required key.";
    case 401:
      return "Your session has ended. Sign in again.";
    case 403:
      return "Your access has changed. Refreshing your session.";
    case 404:
      return "This configuration is no longer available. Refresh the catalog.";
    case 409:
      return "The catalog changed or this operation conflicts with an existing configuration. Reconcile the saved state before trying again.";
    case 429:
      return "Validation capacity is busy. Try again later; no retry was made.";
    case 503:
      return "The provider could not be acquired or validation is unavailable. Check service and credential configuration; this does not identify a specific credential failure.";
    default:
      return "The request failed. Refresh the saved state before trying again.";
  }
}

export function isCatalogConflict(error: unknown) {
  return error instanceof ApiError && error.status === 409;
}

const affectedOperations = [
  "listChatProviders",
  "listConfiguredChatModels",
  "listAvailableChatModels",
  "getChatModelDefault",
  "getPersonaModel",
];

export async function refreshModelCatalog(client: QueryClient) {
  // The partial generated key also matches inactive Persona selections and every provider.
  await Promise.all([
    ...affectedOperations.map((id) =>
      client.invalidateQueries({ queryKey: [{ _id: id }] }, { throwOnError: true }),
    ),
    // Workspace catalogs use authority-scoped handwritten keys rather than SDK keys.
    ...["chat-models", "chat-persona-models", "chat-personas"].map((key) =>
      client.invalidateQueries({ queryKey: [key] }, { throwOnError: true }),
    ),
  ]);
}

export function providerUsable(provider: ManagedProvider, adapters: InstalledAdapter[]) {
  const adapter = adapters.find((entry) => entry.type === provider.adapterType);
  return Boolean(
    adapter &&
    provider.enabled &&
    (adapter.credentialRequirement !== "REQUIRED" || provider.credentialConfigured),
  );
}

export function tenantCandidate(
  model: ManagedModel,
  provider: ManagedProvider,
  adapters: InstalledAdapter[],
) {
  return (
    model.visible &&
    providerUsable(provider, adapters) &&
    provider.isPublic &&
    provider.personaIds.length === 0
  );
}

export function personaCandidate(
  model: ManagedModel,
  provider: ManagedProvider,
  adapters: InstalledAdapter[],
  personaId: string,
) {
  return (
    model.visible &&
    providerUsable(provider, adapters) &&
    (provider.personaIds.length === 0 || provider.personaIds.includes(personaId))
  );
}

export function modelLabel(model: ManagedModel, provider: ManagedProvider) {
  return `${model.displayName} (${model.modelName}) — ${provider.name} · ${provider.id}`;
}

export type ModelDraft = {
  modelName: string;
  displayName: string;
  visible: boolean;
  tokenizerProfile: string;
  contextWindow: string;
  maxOutputTokens: string;
  toolCalling: boolean;
  vision: boolean;
  reasoning: boolean;
  completionTokens: boolean;
  temperature: string;
  topP: string;
  frequencyPenalty: string;
  presencePenalty: string;
  reasoningEffort: string;
  inputPrice: string;
  outputPrice: string;
};

const optionText = (value: unknown) =>
  typeof value === "number" || typeof value === "string" ? String(value) : "";

export function modelDraft(model?: ManagedModel): ModelDraft {
  const settings = model?.settings;
  return {
    modelName: model?.modelName ?? "",
    displayName: model?.displayName ?? "",
    visible: model?.visible ?? true,
    tokenizerProfile: settings?.tokenizerProfile ?? "",
    contextWindow: settings ? String(settings.contextWindow) : "1024",
    maxOutputTokens: settings ? String(settings.maxOutputTokens) : "128",
    toolCalling: settings?.capabilities.toolCalling ?? false,
    vision: settings?.capabilities.vision ?? false,
    reasoning: settings?.capabilities.reasoning ?? false,
    completionTokens: settings?.options.maxCompletionTokens === true,
    temperature: optionText(settings?.options.temperature),
    topP: optionText(settings?.options.topP),
    frequencyPenalty: optionText(settings?.options.frequencyPenalty),
    presencePenalty: optionText(settings?.options.presencePenalty),
    reasoningEffort: optionText(settings?.options.reasoningEffort),
    inputPrice: settings?.pricing == null ? "" : String(settings.pricing.inputPerMillion),
    outputPrice: settings?.pricing == null ? "" : String(settings.pricing.outputPerMillion),
  };
}

export function changeModelDraft<K extends keyof ModelDraft>(
  draft: ModelDraft,
  key: K,
  value: ModelDraft[K],
): ModelDraft {
  const next = { ...draft, [key]: value };
  if (key === "completionTokens" && value) {
    next.temperature = "";
    next.topP = "";
    next.frequencyPenalty = "";
    next.presencePenalty = "";
  }
  if (key === "reasoning" && !value) next.reasoningEffort = "";
  return next;
}

export function modelDraftError(
  draft: ModelDraft,
  adapter: InstalledAdapter | undefined,
): string | null {
  if (!draft.modelName.trim() || !draft.displayName.trim())
    return "Enter an API model name and display name.";
  if (!adapter?.tokenizerProfiles.some((profile) => profile.id === draft.tokenizerProfile))
    return "Choose an installed tokenizer profile supported by this adapter.";
  const context = Number(draft.contextWindow),
    output = Number(draft.maxOutputTokens);
  if (!/^\d+$/.test(draft.contextWindow) || context < 256 || context > 10_000_000)
    return "Context window must be a whole number from 256 to 10000000.";
  if (!/^\d+$/.test(draft.maxOutputTokens) || output < 1 || output >= context)
    return "Maximum output must be at least 1 and strictly below the context window.";
  if (
    draft.tokenizerProfile === "smollm2-135m-12fd25f-v1" &&
    (draft.toolCalling || draft.vision || draft.reasoning)
  )
    return "The SmolLM2 profile is text-only. Turn off tools, vision and reasoning explicitly, or choose the correct capable profile.";
  if ((draft.inputPrice.trim() === "") !== (draft.outputPrice.trim() === ""))
    return "Enter both prices or leave both blank for Unknown pricing.";
  for (const value of [draft.inputPrice, draft.outputPrice]) {
    if (value.trim() && (!Number.isFinite(Number(value)) || Number(value) < 0))
      return "Prices must be finite, nonnegative USD per million tokens.";
  }
  if (!draft.completionTokens) {
    for (const [key, min, max] of [
      ["temperature", 0, 2],
      ["topP", 0, 1],
      ["frequencyPenalty", -2, 2],
      ["presencePenalty", -2, 2],
    ] as const) {
      const value = draft[key];
      if (
        value.trim() &&
        (!Number.isFinite(Number(value)) || Number(value) < min || Number(value) > max)
      )
        return `${key} must be between ${min} and ${max}, or blank.`;
    }
  }
  if (
    draft.reasoning &&
    draft.reasoningEffort &&
    !["minimal", "low", "medium", "high"].includes(draft.reasoningEffort)
  )
    return "Choose a supported reasoning effort.";
  return null;
}

export function modelBody(draft: ModelDraft): ModelBody {
  const options: ManagedModel["settings"]["options"] = {
    maxCompletionTokens: draft.completionTokens,
  };
  if (!draft.completionTokens)
    for (const key of ["temperature", "topP", "frequencyPenalty", "presencePenalty"] as const) {
      if (draft[key].trim()) options[key] = Number(draft[key]);
    }
  if (draft.reasoning && draft.reasoningEffort) options.reasoningEffort = draft.reasoningEffort;
  return {
    modelName: draft.modelName.trim(),
    displayName: draft.displayName.trim(),
    visible: draft.visible,
    settings: {
      tokenizerProfile: draft.tokenizerProfile,
      contextWindow: Number(draft.contextWindow),
      maxOutputTokens: Number(draft.maxOutputTokens),
      capabilities: {
        streaming: true,
        toolCalling: draft.toolCalling,
        vision: draft.vision,
        reasoning: draft.reasoning,
      },
      options,
      pricing:
        draft.inputPrice.trim() === ""
          ? null
          : {
              inputPerMillion: Number(draft.inputPrice),
              outputPerMillion: Number(draft.outputPrice),
            },
    },
  };
}
