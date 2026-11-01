import type { ErrorMessage } from "@/lib/problem-presentation";

/** MCP conflicts that name what clashed; the generic conflict would ask to reload a page that has not changed. */
export const mcpMessages = {
  MCP_OAUTH_CLIENT_LABEL_TAKEN: { key: "mcpOAuthClientLabelTaken" },
  MCP_SLUG_TAKEN: { key: "mcpSlugTaken" },
} satisfies Record<string, ErrorMessage>;
