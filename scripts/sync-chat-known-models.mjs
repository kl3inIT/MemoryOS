#!/usr/bin/env node
// Regenerates core/src/main/resources/chat/known-models.json from the pinned LiteLLM
// model metadata file. Run with: node scripts/sync-chat-known-models.mjs [commit-sha]
// Bump SOURCE_COMMIT to refresh prices; review the diff before committing it.
import { writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const SOURCE_REPO = "BerriAI/litellm";
const SOURCE_FILE = "model_prices_and_context_window.json";
const SOURCE_COMMIT = process.argv[2] ?? "7370650d91bea7ecdd04dd7350a6cf4229441048";
// Vendors whose OpenAI-compatible /models endpoint names models without their limits (MEM-130). LiteLLM keys the
// non-OpenAI ones as "<provider>/<model>"; the prefix is dropped because the endpoints report the bare name.
const PROVIDERS = new Set(["openai", "anthropic", "gemini", "xai", "deepseek", "mistral"]);
const OUTPUT = join(
  dirname(fileURLToPath(import.meta.url)),
  "..",
  "core",
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
  if (!PROVIDERS.has(entry.litellm_provider)) continue;
  const prefix = `${entry.litellm_provider}/`;
  const bareName = modelName.startsWith(prefix) ? modelName.slice(prefix.length) : modelName;
  if (bareName.includes("/")) continue;
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
  if (models.some((model) => model.modelName === bareName)) continue;
  models.push({
    modelName: bareName,
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
