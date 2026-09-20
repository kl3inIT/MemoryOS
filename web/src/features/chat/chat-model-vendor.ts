/** Vendor marks under `public/model-logos` (LobeHub icons, MIT); monochrome ones follow the theme. */
export const vendorFiles = {
  deepseek: { file: "deepseek.svg", monochrome: false },
  qwen: { file: "qwen.svg", monochrome: false },
  zai: { file: "zai.svg", monochrome: true },
  kimi: { file: "kimi.svg", monochrome: true },
  minimax: { file: "minimax.svg", monochrome: false },
  mistral: { file: "mistral.svg", monochrome: false },
  meta: { file: "meta.svg", monochrome: false },
  microsoft: { file: "microsoft.svg", monochrome: false },
  grok: { file: "grok.svg", monochrome: true },
  gemma: { file: "gemma.svg", monochrome: true },
  cohere: { file: "cohere.svg", monochrome: false },
  nvidia: { file: "nvidia.svg", monochrome: false },
  doubao: { file: "doubao.svg", monochrome: false },
  hunyuan: { file: "hunyuan.svg", monochrome: false },
  baichuan: { file: "baichuan.svg", monochrome: false },
  stepfun: { file: "stepfun.svg", monochrome: false },
} as const;

export type ModelVendor = "openai" | "claude" | "gemini" | keyof typeof vendorFiles;

/**
 * Onyx `getModelIcon` keys, checked in order against the whole model name, so a routed name such as
 * `ocg/deepseek-v4-flash` or `qwen/qwen3.8-27b` shows its vendor whichever provider serves it. Gemma is checked
 * before Gemini's family and GLM maps to Z.ai, which publishes it.
 */
const vendorKeys: [RegExp, ModelVendor][] = [
  [/claude/, "claude"],
  [/gemma/, "gemma"],
  [/gemini/, "gemini"],
  [/(^|\/)(gpt-|o\d(-|$)|chatgpt|codex)|openai/, "openai"],
  [/deepseek/, "deepseek"],
  [/qwen|qwq/, "qwen"],
  [/glm|zhipu|(^|\/)z-?ai\b/, "zai"],
  [/kimi|moonshot/, "kimi"],
  [/minimax|abab/, "minimax"],
  [/mistral|ministral|mixtral|codestral|magistral|devstral|pixtral/, "mistral"],
  [/llama/, "meta"],
  [/(^|\/)phi-/, "microsoft"],
  [/grok/, "grok"],
  [/(^|\/)command|cohere/, "cohere"],
  [/nemotron|nvidia/, "nvidia"],
  [/doubao/, "doubao"],
  [/hunyuan/, "hunyuan"],
  [/baichuan/, "baichuan"],
  [/(^|\/)step-/, "stepfun"],
];

export function modelVendor(modelName: string): ModelVendor | null {
  const name = modelName.toLowerCase();
  return vendorKeys.find(([key]) => key.test(name))?.[1] ?? null;
}
