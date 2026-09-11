import { QueryClient } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";
import {
  changeModelDraft,
  modelBody,
  modelDraft,
  modelDraftError,
  personaCandidate,
  refreshModelCatalog,
  tenantCandidate,
  type InstalledAdapter,
  type ManagedModel,
  type ManagedProvider,
} from "./model-catalog";

const adapter: InstalledAdapter = {
  type: "openai",
  credentialRequirement: "REQUIRED",
  tokenizerProfiles: [
    { id: "openai-o200k-v1", displayName: "OpenAI" },
    { id: "smollm2-135m-12fd25f-v1", displayName: "SmolLM2" },
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

  it("requires explicit capability changes for the text-only profile and retains capable hosted configuration", () => {
    const hosted = modelDraft(model);
    const local = changeModelDraft(hosted, "tokenizerProfile", "smollm2-135m-12fd25f-v1");
    expect(modelDraftError(local, adapter)).not.toBeNull();
    expect(modelBody(local).settings?.capabilities).toEqual(model.settings.capabilities);
    expect(modelDraftError(hosted, adapter)).toBeNull();
    const compatible = {
      ...local,
      toolCalling: false,
      vision: false,
      reasoning: false,
      reasoningEffort: "",
    };
    expect(modelDraftError(compatible, adapter)).toBeNull();
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

  it("does not bypass Persona allowlists for a manager and does not offer hidden models", () => {
    const restricted = { ...provider, isPublic: false, personaIds: ["allowed"] };
    expect(personaCandidate(model, restricted, [adapter], "allowed")).toBe(true);
    expect(personaCandidate(model, restricted, [adapter], "other")).toBe(false);
    expect(personaCandidate({ ...model, visible: false }, restricted, [adapter], "allowed")).toBe(
      false,
    );
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
