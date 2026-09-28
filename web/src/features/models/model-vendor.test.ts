import { describe, expect, it } from "vitest";
import { modelVendor } from "./model-vendor";

describe("model vendor marks", () => {
  it("names the vendor from the model name whichever provider routes it", () => {
    expect(modelVendor("gpt-5.6-luna")).toBe("openai");
    expect(modelVendor("o3-mini")).toBe("openai");
    expect(modelVendor("openai/gpt-oss-120b")).toBe("openai");
    expect(modelVendor("claude-sonnet-5")).toBe("claude");
    expect(modelVendor("models/gemini-2.5-pro")).toBe("gemini");
    expect(modelVendor("google/gemma-3-27b-it")).toBe("gemma");
    expect(modelVendor("ocg/deepseek-v4-flash")).toBe("deepseek");
    expect(modelVendor("qwen/qwen3.8-27b:free")).toBe("qwen");
    expect(modelVendor("ocg/glm-5.1")).toBe("zai");
    expect(modelVendor("moonshotai/kimi-k2")).toBe("kimi");
    expect(modelVendor("ministral-8b")).toBe("mistral");
    expect(modelVendor("meta-llama/llama-4-maverick")).toBe("meta");
    expect(modelVendor("microsoft/phi-4")).toBe("microsoft");
    expect(modelVendor("x-ai/grok-4")).toBe("grok");
    expect(modelVendor("nvidia/nemotron-nano")).toBe("nvidia");
  });

  it("keeps the generic mark for a name no vendor key matches", () => {
    expect(modelVendor("oc")).toBeNull();
    expect(modelVendor("my-fine-tune")).toBeNull();
    expect(modelVendor("photon-7b")).toBeNull();
  });
});
