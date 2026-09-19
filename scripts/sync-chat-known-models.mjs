#!/usr/bin/env node
// Regenerates api/src/main/resources/chat/known-models.json from the pinned LiteLLM
// model metadata file. Run with: node scripts/sync-chat-known-models.mjs [commit-sha]
// Bump SOURCE_COMMIT to refresh prices; review the diff before committing it.
import { writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const SOURCE_REPO = "BerriAI/litellm";
const SOURCE_FILE = "model_prices_and_context_window.json";
const SOURCE_COMMIT = process.argv[2] ?? "b1a61f510c90ce7e4533e89247c941fa201ada4f";
const PROVIDERS = new Set(["openai", "anthropic"]);
const OUTPUT = join(
  dirname(fileURLToPath(import.meta.url)),
  "..",
  "api",
  "src",
  "main",
  "resources",
  "chat",
  "known-models.json",
);

const url = `https://raw.githubusercontent.com/${SOURCE_REPO}/${SOURCE_COMMIT}/${SOURCE_FILE}`;
const response = await fetch(url);
if (!response.ok) throw new Error(`${url} responded ${response.status}`);
const source = await response.json();
const retrieved = new Date().toISOString().slice(0, 10);

const models = [];
for (const [modelName, entry] of Object.entries(source)) {
  if (!entry || typeof entry !== "object" || entry.mode !== "chat") continue;
  if (!PROVIDERS.has(entry.litellm_provider) || modelName.includes("/")) continue;
  if (entry.deprecation_date && entry.deprecation_date < retrieved) continue;
  const contextWindow = entry.max_input_tokens;
  const maxOutputTokens = entry.max_output_tokens;
  const input = entry.input_cost_per_token;
  const output = entry.output_cost_per_token;
  // MemoryOS model settings reject these combinations, so an incomplete row is dropped
  // rather than prefilled with a guess.
  if (!Number.isInteger(contextWindow) || !Number.isInteger(maxOutputTokens)) continue;
  if (contextWindow < 256 || contextWindow > 10_000_000) continue;
  if (maxOutputTokens < 1 || maxOutputTokens >= contextWindow) continue;
  if (typeof input !== "number" || typeof output !== "number") continue;
  models.push({
    modelName,
    contextWindow,
    maxOutputTokens,
    toolCalling: entry.supports_function_calling === true,
    vision: entry.supports_vision === true,
    reasoning: entry.supports_reasoning === true,
    inputPerMillion: Number((input * 1e6).toFixed(6)),
    outputPerMillion: Number((output * 1e6).toFixed(6)),
    // Prompt-cache reads are billed at their own rate; absent means the input rate applies.
    ...(typeof entry.cache_read_input_token_cost === "number"
      ? { cachedInputPerMillion: Number((entry.cache_read_input_token_cost * 1e6).toFixed(6)) }
      : {}),
  });
}
models.sort((left, right) => left.modelName.localeCompare(right.modelName, "en"));
if (models.length < 50) throw new Error(`Only ${models.length} models survived filtering`);

const document = {
  source: {
    repository: `https://github.com/${SOURCE_REPO}`,
    file: SOURCE_FILE,
    commit: SOURCE_COMMIT,
    retrieved,
    licence: "MIT (Copyright (c) 2023 Berri AI)",
    providers: [...PROVIDERS].sort(),
  },
  models,
};
writeFileSync(OUTPUT, `${JSON.stringify(document, null, 2)}\n`, "utf8");
process.stdout.write(`${OUTPUT}: ${models.length} models\n`);
