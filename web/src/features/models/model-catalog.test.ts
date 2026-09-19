import { QueryClient } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";
import {
  changeModelDraft,
  modelBody,
  modelDraft,
  modelDraftError,
  refreshModelCatalog,
  tenantCandidate,
  type InstalledAdapter,
  type ManagedModel,
  type ManagedProvider,
} from "./model-catalog";

const adapter: InstalledAdapter = {
  type: "openai",
  credentialRequirement: "REQUIRED",
  tokenizerProfiles: [{ id: "openai-o200k-v1", displayName: "OpenAI" }],
  nativeWebSearch: true,
  knownModels: [
    {
      modelName: "gpt-5",
      contextWindow: 272_000,
      maxOutputTokens: 128_000,
      capabilities: { streaming: true, toolCalling: true, vision: true, reasoning: true },
      pricing: { inputPerMillion: 1.25, outputPerMillion: 10 },
    },
  ],
};
const provider: ManagedProvider = {
  id: "00000000-0000-0000-0000-000000000001",
  name: "Hosted",
  adapterType: "openai",
  baseUrl: "https://example.test/v1",
  enabled: true,
  isPublic: true,
  groupIds: ["00000000-0000-0000-0000-000000000002"],
  personaIds: [],
  credentialConfigured: true,
  revision: 3,
  dataBoundary: "EXTERNAL",
};
const model: ManagedModel = {
  id: "00000000-0000-0000-0000-000000000003",
  tenantId: "00000000-0000-0000-0000-000000000004",
  providerId: provider.id,
  modelName: "hosted",
  displayName: "Hosted model",
  visible: true,
  revision: 7,
  settings: {
    contextWindow: 4096,
    maxOutputTokens: 256,
    tokenizerProfile: "openai-o200k-v1",
    capabilities: { streaming: true, toolCalling: true, vision: true, reasoning: true },
    options: {
      temperature: 0.5,
      topP: 0.9,
      frequencyPenalty: -1,
      presencePenalty: 1,
      reasoningEffort: "high",
    },
    pricing: null,
  },
};

describe("model configuration transitions", () => {
  it("removes incompatible sampling and reasoning keys without treating the output limit as a boolean", () => {
    let draft = changeModelDraft(modelDraft(model), "completionTokens", true);
    expect(modelBody(draft).settings?.options).toEqual({
      maxCompletionTokens: true,
      reasoningEffort: "high",
    });
    expect(modelBody(draft).settings?.maxOutputTokens).toBe(256);
    draft = changeModelDraft(draft, "reasoning", false);
    draft = changeModelDraft(draft, "completionTokens", false);
    expect(modelBody(draft).settings?.options).toEqual({ maxCompletionTokens: false });
  });

  it("sends no output limit when it is left blank, so the provider default applies", () => {
    const draft = { ...modelDraft(model, adapter), maxOutputTokens: "" };
    expect(modelDraftError(draft, adapter)).toBeNull();
    expect(modelBody(draft).settings?.maxOutputTokens).toBeNull();
    expect(modelDraftError({ ...draft, maxOutputTokens: "0" }, adapter)).not.toBeNull();
  });

  it("adopts the installed token estimator and rejects a configuration whose estimator is gone", () => {
    expect(modelDraft(undefined, adapter).tokenizerProfile).toBe("openai-o200k-v1");
    expect(modelDraftError(modelDraft(model, adapter), adapter)).toBeNull();
    const retired = { ...modelDraft(model, adapter), tokenizerProfile: "retired-profile" };
    expect(modelDraftError(retired, adapter)).not.toBeNull();
    expect(modelBody(retired).settings?.tokenizerProfile).toBe("retired-profile");
  });

  it("keeps unknown pricing distinct from an explicit zero pair and rejects partial prices and output at context", () => {
    const draft = modelDraft(model);
    expect(modelBody(draft).settings?.pricing).toBeNull();
    expect(modelDraftError({ ...draft, inputPrice: "0" }, adapter)).not.toBeNull();
    expect(modelBody({ ...draft, inputPrice: "0", outputPrice: "0" }).settings?.pricing).toEqual({
      inputPerMillion: 0,
      outputPerMillion: 0,
    });
    expect(
      modelDraftError({ ...draft, contextWindow: "256", maxOutputTokens: "256" }, adapter),
    ).not.toBeNull();
    expect(
      modelDraftError({ ...draft, contextWindow: "256", maxOutputTokens: "255" }, adapter),
    ).toBeNull();
  });

  it("prefills installed metadata for a known model name and leaves an unknown name untouched", () => {
    const known = changeModelDraft(modelDraft(), "modelName", "gpt-5", adapter);
    expect(modelBody(known).settings).toMatchObject({
      contextWindow: 272_000,
      maxOutputTokens: 128_000,
      capabilities: { streaming: true, toolCalling: true, vision: true, reasoning: true },
      pricing: { inputPerMillion: 1.25, outputPerMillion: 10 },
    });
    expect(modelBody(known).settings?.options).toEqual({ maxCompletionTokens: true });
    expect(known.displayName).toBe("gpt-5");
    const custom = changeModelDraft(modelDraft(), "modelName", "llama-3.1-8b", adapter);
    expect(custom.contextWindow).toBe(modelDraft().contextWindow);
    expect(custom.inputPrice).toBe("");
    expect(custom.displayName).toBe("");
    const switched = changeModelDraft(known, "modelName", "llama-3.1-8b", adapter);
    expect(switched.contextWindow).toBe("");
    expect(switched.inputPrice).toBe("");
    expect(switched.reasoning).toBe(false);
    expect(switched.displayName).toBe("");
  });
});

describe("default eligibility and deletion dependencies", () => {
  it("allows public providers with Group associations but excludes manager-only Tenant defaults", () => {
    expect(tenantCandidate(model, provider, [adapter])).toBe(true);
    expect(tenantCandidate(model, { ...provider, isPublic: false }, [adapter])).toBe(false);
    expect(tenantCandidate(model, { ...provider, credentialConfigured: false }, [adapter])).toBe(
      false,
    );
    expect(tenantCandidate(model, provider, [])).toBe(false);
  });

  it("retires saved defaults and workspace catalogs after mutation without invalidating transcript", async () => {
    const client = new QueryClient();
    const first = [{ _id: "getPersonaModel", path: { personaId: "first" } }];
    const second = [{ _id: "getPersonaModel", path: { personaId: "second" } }];
    const transcript = ["chat-history", "chat"];
    const workspaceCatalogs = [
      ["chat-models", "actor", 1, "chat"],
      ["chat-persona-models", "actor", 1, "first"],
      ["chat-personas", "actor", 1],
    ];
    client.setQueryData(first, { modelConfigurationId: model.id, revision: 1 });
    client.setQueryData(second, { modelConfigurationId: model.id, revision: 9 });
    client.setQueryData(transcript, { messages: ["retained"] });
    for (const key of workspaceCatalogs) client.setQueryData(key, []);
    await refreshModelCatalog(client);
    expect(client.getQueryState(first)?.isInvalidated).toBe(true);
    expect(client.getQueryState(second)?.isInvalidated).toBe(true);
    for (const key of workspaceCatalogs)
      expect(client.getQueryState(key)?.isInvalidated).toBe(true);
    expect(client.getQueryState(transcript)?.isInvalidated).toBe(false);
    expect(client.getQueryData(transcript)).toEqual({ messages: ["retained"] });
    client.clear();
  });
});
