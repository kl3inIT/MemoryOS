import { z } from "zod";

const agentEditorTabs = ["config", "share"] as const;
export type AgentEditorTab = (typeof agentEditorTabs)[number];

export const DEFAULT_AGENT_EDITOR_SEARCH = { tab: "config" } as const;

/** The editor's tab in its address; Cấu hình is the default and is left out. */
export const agentEditorSearchSchema = z.object({
  tab: z.enum(agentEditorTabs).default("config").catch("config"),
});
