import { z } from "zod";

const idsSchema = z.array(z.string().uuid()).max(8);

/**
 * The MCP servers the person chose for this conversation, or undefined when they chose nothing and the default
 * applies. Browser preference only; the API independently authorizes every command.
 */
export function readMcpPreference(
  owner: string | undefined,
  sessionId: string | undefined,
): string[] | undefined {
  if (!owner || !sessionId) return undefined;
  try {
    const stored = localStorage.getItem(`memoryos:mcp:${owner}:${sessionId}`);
    if (stored === null) return undefined;
    const parsed = idsSchema.safeParse(JSON.parse(stored));
    return parsed.success ? parsed.data : undefined;
  } catch {
    return undefined;
  }
}

export function writeMcpPreference(
  owner: string | undefined,
  sessionId: string | undefined,
  ids: string[] | undefined,
) {
  if (!owner || !sessionId || !ids) return;
  try {
    localStorage.setItem(`memoryos:mcp:${owner}:${sessionId}`, JSON.stringify(ids));
  } catch {
    // Disabled/full browser storage must not prevent a chat turn.
  }
}
