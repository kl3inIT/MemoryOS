import type { ChatReportedModel } from "@/lib/hey-api/types.gen";
import type { QueryClient } from "@tanstack/react-query";
import { appText, type AppCopy } from "@/i18n/app-text";
import { ApiError, problemCode } from "@/lib/api";
import { invalidateAgents } from "@/features/chat/chat-personas-api";
import {
  getChatModelDefaultQueryKey,
  getPersonaModelQueryKey,
  listAvailableChatModelsQueryKey,
  listChatModelFlowsQueryKey,
  listChatPersonaModelsQueryKey,
  listChatProvidersQueryKey,
  listConfiguredChatModelsQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";
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
  switch (problemCode(error)) {
    case "CHAT_PROVIDER_CREDENTIAL_REJECTED":
      return "The provider rejected the API key";
    case "CHAT_PROVIDER_UNREACHABLE":
      return "Could not reach the provider before the timeout";
    case "CHAT_PROVIDER_INCOMPATIBLE":
      return "The provider response was not OpenAI-compatible";
  }
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

/**
 * The error a catalog mutation settles with: its safe message, and for an API failure only the status and problem code,
 * so the global 401/403 handling and conflict detection still apply. The SDK cause, which can carry the problem body
 * or the request, is never retained. `describe` turns the failure into the page's own static copy.
 */
export function sanitizeModelActionError(
  error: unknown,
  describe: (cause: unknown) => string = modelActionError,
): Error {
  if (!(error instanceof ApiError)) return new Error(describe(error));
  const code = problemCode(error);
  const safe = new ApiError(error.status, code === undefined ? undefined : { code });
  safe.message = describe(error);
  return safe;
}

export function isCatalogConflict(error: unknown) {
  return error instanceof ApiError && error.status === 409;
}

/** Matches every cached call of a generated operation, whatever its path, query or base URL. */
function everyCall([{ _id }]: readonly [{ _id: string }]) {
  return [{ _id }];
}

/**
 * Refreshes every read a catalog change can alter: the management catalog, the Tenant and task defaults, every
 * Persona's model selection and the workspace catalogs Chat and the agent editor read. Transcripts are kept.
 */
export async function refreshModelCatalog(client: QueryClient) {
  const refresh = (queryKey: readonly unknown[]) =>
    client.invalidateQueries({ queryKey }, { throwOnError: true });
  await Promise.all([
    refresh(listChatProvidersQueryKey()),
    refresh(everyCall(listConfiguredChatModelsQueryKey({ path: { providerId: "" } }))),
    refresh(listAvailableChatModelsQueryKey()),
    refresh(getChatModelDefaultQueryKey()),
    refresh(listChatModelFlowsQueryKey()),
    refresh(everyCall(getPersonaModelQueryKey({ path: { personaId: "" } }))),
    refresh(everyCall(listChatPersonaModelsQueryKey({ path: { personaId: "" } }))),
    invalidateAgents(client),
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

export function modelLabel(model: ManagedModel, provider: ManagedProvider) {
  const name =
    model.displayName === model.modelName
      ? model.displayName
      : `${model.displayName} (${model.modelName})`;
  return `${name} — ${provider.name}`;
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
  /** Blank means cached input is billed at the input price. */
  cachedInputPrice: string;
};

const optionText = (value: unknown) =>
  typeof value === "number" || typeof value === "string" ? String(value) : "";

export function modelDraft(model?: ManagedModel, adapter?: InstalledAdapter): ModelDraft {
  const settings = model?.settings;
  return {
    modelName: model?.modelName ?? "",
    displayName: model?.displayName ?? "",
    visible: model?.visible ?? true,
    tokenizerProfile: settings?.tokenizerProfile ?? adapter?.tokenizerProfiles[0]?.id ?? "",
    contextWindow: settings ? String(settings.contextWindow) : "1024",
    // Blank means the provider publishes no output limit: no cap is sent and its default applies (Onyx).
    maxOutputTokens: settings
      ? settings.maxOutputTokens == null
        ? ""
        : String(settings.maxOutputTokens)
      : "",
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
    cachedInputPrice:
      settings?.pricing?.cachedInputPerMillion == null
        ? ""
        : String(settings.pricing.cachedInputPerMillion),
  };
}

export type KnownModel = InstalledAdapter["knownModels"][number];
export type ReportedModel = ChatReportedModel;

/**
 * A draft carrying what the provider endpoint or installed catalog published for a reported model (MEM-130), as
 * Onyx's provider fetchers prefill the form. Anything unpublished stays empty for the administrator to type.
 */
export function reportedDraft(reported: ReportedModel, adapter?: InstalledAdapter): ModelDraft {
  const draft = modelDraft(undefined, adapter);
  const capabilities = reported.capabilities;
  const reasoning = capabilities.reasoning;
  return {
    ...draft,
    modelName: reported.modelName,
    displayName: reported.modelName,
    contextWindow: String(reported.contextWindow),
    maxOutputTokens: reported.maxOutputTokens == null ? "" : String(reported.maxOutputTokens),
    toolCalling: capabilities.toolCalling,
    vision: capabilities.vision,
    reasoning,
    // OpenAI-compatible reasoning models reject max_tokens, so the option family follows the capability.
    completionTokens: reasoning,
    inputPrice: reported.pricing == null ? "" : String(reported.pricing.inputPerMillion),
    outputPrice: reported.pricing == null ? "" : String(reported.pricing.outputPerMillion),
  };
}

export function findKnownModel(adapter: InstalledAdapter | undefined, modelName: string) {
  const name = modelName.trim();
  return name ? adapter?.knownModels.find((known) => known.modelName === name) : undefined;
}

/** Declared specs are facts about the model, so both the list and the editor render them alike. */
export const compactTokens = (value: number) =>
  value >= 1_000_000
    ? `${Math.round(value / 100_000) / 10}M`
    : value >= 1000
      ? `${Math.round(value / 1000)}K`
      : String(value);
export const millionTokenPrice = (value: number | undefined) =>
  value === undefined ? null : `$${value}`;

/** True when the draft still carries exactly what the installed catalog declares for this name. */
export function matchesKnownModel(draft: ModelDraft, known: KnownModel | undefined) {
  return (
    known !== undefined &&
    draft.contextWindow === String(known.contextWindow) &&
    draft.maxOutputTokens === String(known.maxOutputTokens) &&
    draft.toolCalling === known.capabilities.toolCalling &&
    draft.vision === known.capabilities.vision &&
    draft.reasoning === known.capabilities.reasoning &&
    draft.inputPrice === String(known.pricing.inputPerMillion) &&
    draft.outputPrice === String(known.pricing.outputPerMillion) &&
    draft.cachedInputPrice ===
      (known.pricing.cachedInputPerMillion == null
        ? ""
        : String(known.pricing.cachedInputPerMillion))
  );
}

export function changeModelDraft<K extends keyof ModelDraft>(
  draft: ModelDraft,
  key: K,
  value: ModelDraft[K],
  adapter?: InstalledAdapter,
): ModelDraft {
  const next = { ...draft, [key]: value };
  if (key === "completionTokens" && value) {
    next.temperature = "";
    next.topP = "";
    next.frequencyPenalty = "";
    next.presencePenalty = "";
  }
  // A reasoning model rejects a sampling temperature, so enabling reasoning drops it.
  if (key === "reasoning") next[value ? "temperature" : "reasoningEffort"] = "";
  if (key === "modelName") {
    const previous = findKnownModel(adapter, draft.modelName);
    const known = findKnownModel(adapter, next.modelName);
    if (known) {
      next.displayName =
        draft.displayName.trim() && draft.displayName !== previous?.modelName
          ? draft.displayName
          : known.modelName;
      next.contextWindow = String(known.contextWindow);
      next.maxOutputTokens = String(known.maxOutputTokens);
      next.toolCalling = known.capabilities.toolCalling;
      next.vision = known.capabilities.vision;
      next.reasoning = known.capabilities.reasoning;
      // OpenAI rejects max_tokens on a reasoning model, so the option family follows the capability.
      next.completionTokens = known.capabilities.reasoning;
      if (next.reasoning) {
        next.temperature = "";
        next.topP = "";
        next.frequencyPenalty = "";
        next.presencePenalty = "";
      } else next.reasoningEffort = "";
      next.inputPrice = String(known.pricing.inputPerMillion);
      next.outputPrice = String(known.pricing.outputPerMillion);
      next.cachedInputPrice =
        known.pricing.cachedInputPerMillion == null
          ? ""
          : String(known.pricing.cachedInputPerMillion);
    } else if (matchesKnownModel(draft, previous)) {
      // Limits, capabilities and prices describe the model named before, never the one typed now.
      next.displayName = draft.displayName === previous?.modelName ? "" : draft.displayName;
      next.contextWindow = "";
      next.maxOutputTokens = "";
      next.toolCalling = false;
      next.vision = false;
      next.reasoning = false;
      next.reasoningEffort = "";
      next.inputPrice = "";
      next.outputPrice = "";
      next.cachedInputPrice = "";
    }
  }
  return next;
}

export function modelDraftError(
  draft: ModelDraft,
  adapter: InstalledAdapter | undefined,
): AppCopy | null {
  if (!draft.modelName.trim() || !draft.displayName.trim())
    return "Enter an API model name and display name.";
  if (!adapter?.tokenizerProfiles.some((profile) => profile.id === draft.tokenizerProfile))
    return "This adapter has no installed token estimator. Refresh the catalog.";
  const context = Number(draft.contextWindow),
    output = Number(draft.maxOutputTokens);
  if (!/^\d+$/.test(draft.contextWindow) || context < 256 || context > 10_000_000)
    return "Context window must be a whole number from 256 to 10000000.";
  if (
    draft.maxOutputTokens.trim() !== "" &&
    (!/^\d+$/.test(draft.maxOutputTokens) || output < 1 || output >= context)
  )
    return "Maximum output must be at least 1 and strictly below the context window.";
  if ((draft.inputPrice.trim() === "") !== (draft.outputPrice.trim() === ""))
    return "Enter both prices or leave both blank for Unknown pricing.";
  if (draft.cachedInputPrice.trim() && draft.inputPrice.trim() === "")
    return "Enter input and output prices before a cache-read price.";
  for (const value of [draft.inputPrice, draft.outputPrice, draft.cachedInputPrice]) {
    if (value.trim() && (!Number.isFinite(Number(value)) || Number(value) < 0))
      return "Prices must be finite, nonnegative USD per million tokens.";
  }
  if (!draft.completionTokens) {
    for (const [key, label, min, max] of [
      ["temperature", "Temperature", 0, 2],
      ["topP", "Top P", 0, 1],
      ["frequencyPenalty", "Frequency penalty", -2, 2],
      ["presencePenalty", "Presence penalty", -2, 2],
    ] as const) {
      const value = draft[key];
      if (
        value.trim() &&
        (!Number.isFinite(Number(value)) || Number(value) < min || Number(value) > max)
      )
        return appText("{{option}} must be between {{min}} and {{max}}, or blank.", {
          option: appText(label),
          min,
          max,
        });
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
      maxOutputTokens: draft.maxOutputTokens.trim() === "" ? null : Number(draft.maxOutputTokens),
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
              cachedInputPerMillion:
                draft.cachedInputPrice.trim() === "" ? null : Number(draft.cachedInputPrice),
            },
    },
  };
}
