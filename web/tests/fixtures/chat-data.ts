import type { AvailableModel, ChatSource } from "../../src/lib/hey-api/types.gen.ts";
export const fixtureModels: AvailableModel[] = [
  {
    id: "10000000-0000-4000-8000-000000000001",
    providerId: "20000000-0000-4000-8000-000000000001",
    providerName: "OpenAI",
    modelName: "gpt-5-mini",
    displayName: "GPT-5 mini",
    isDefault: true,
    capabilities: { streaming: true, toolCalling: true, reasoning: true },
  },
  {
    id: "10000000-0000-4000-8000-000000000002",
    providerId: "20000000-0000-4000-8000-000000000002",
    providerName: "Office inference",
    modelName: "Qwen3.5-9B",
    displayName: "Qwen3.5 9B",
    capabilities: { streaming: true, toolCalling: true },
  },
];
export const fixtureSource: ChatSource = {
  citationId: 1,
  documentId: "30000000-0000-4000-8000-000000000001",
  generation: "40000000-0000-4000-8000-000000000001",
  title: "Employee handbook",
  startOrdinal: 3,
  endOrdinal: 4,
  provenance: [
    { ordinal: 3, provenanceJson: '{"page":2}' },
    { ordinal: 4, provenanceJson: '{"page":3}' },
  ],
};
