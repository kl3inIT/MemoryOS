import { z } from "zod";

const aiProviderTabs = ["models", "system-one", "web-search", "voice", "image-generation"] as const;
export type AiProviderTab = (typeof aiProviderTabs)[number];

export const DEFAULT_AI_PROVIDERS_SEARCH = { tab: "models" } as const;

/** The page's tab in its address; Models is the default and is left out. */
export const aiProvidersSearchSchema = z.object({
  tab: z.enum(aiProviderTabs).default("models").catch("models"),
});
