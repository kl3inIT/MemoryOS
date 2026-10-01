import type {
  AvailableModel,
  ImageAvailabilityResponse,
  McpConnection,
  WebAvailabilityResponse,
} from "@/lib/hey-api/types.gen";
import type { ToolDefaults } from "@/features/chat/runtime/chat-transport";
import { webUsableOn } from "@/features/chat/web-search/chat-web-preference";
import { usableInTurn } from "@/features/mcp/mcp-status";

/** The API accepts at most this many MCP servers on one turn. */
const MAX_MCP_SERVERS = 8;

/**
 * The model a turn runs on, resolved as the server does: the chosen one, else the agent's, else the person's own
 * default, and the organization's default when that one is no longer offered.
 */
export function turnModelOf(
  catalog: AvailableModel[] | undefined,
  chosen: (string | null | undefined)[],
) {
  const id = chosen.find((candidate) => candidate);
  return (
    catalog?.find((candidate) => candidate.id === id) ??
    catalog?.find((candidate) => candidate.isDefault)
  );
}

/**
 * As Onyx, an agent's tools are on until the person turns one off. A default is on only where the tool can be used
 * now, because the server refuses a turn that asks for a tool it cannot offer; so every default is off until the
 * turn's model and the tool's availability are known. A conversation that answers from documents only keeps every
 * tool off until the person turns one on.
 */
export function chatToolDefaults(input: {
  grounded: boolean;
  allowed: { web: boolean; image: boolean; mcpServerIds: string[] | null };
  /** Undefined until the model the turn runs on is known. */
  model: AvailableModel | undefined;
  web: WebAvailabilityResponse | undefined;
  image: ImageAvailabilityResponse | undefined;
  mayGenerateImages: boolean;
  connections: McpConnection[] | undefined;
  deepResearch: boolean;
}): ToolDefaults {
  const { allowed, model } = input;
  if (input.grounded || !model?.id || model.capabilities?.toolCalling !== true)
    return { web: "off", image: "off", mcpServerIds: [] };
  return {
    // Deep research never uses provider-hosted search.
    web: allowed.web && webUsableOn(input.web, model.id, !input.deepResearch) ? "auto" : "off",
    image:
      allowed.image && input.mayGenerateImages && input.image?.available === true ? "auto" : "off",
    mcpServerIds: (input.connections ?? [])
      .filter(usableInTurn)
      .filter((connection) => !allowed.mcpServerIds || allowed.mcpServerIds.includes(connection.id))
      .slice(0, MAX_MCP_SERVERS)
      .map((connection) => connection.id),
  };
}
