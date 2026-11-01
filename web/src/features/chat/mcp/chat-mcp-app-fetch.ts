import { parseAppRoute } from "@/features/chat/activity/chat-activity";
import { parseMcpToolName } from "@/features/mcp/mcp-connections";
import { ApiError, problemCode } from "@/lib/api";
import { hostChatToolApp } from "@/lib/hey-api/sdk.gen";

/** A view's call of a tool that asks first, waiting for the person (MEM-275). */
export type Approval = {
  slug: string;
  tool: string;
  arguments: unknown;
  run: () => Promise<Response>;
  settle: (response: Response) => void;
};

export const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

/**
 * One request of a view: the renderer's `{ method, params }` with the part's server ID, which names the answer and the
 * call. A tool that asks first comes back as `MCP_APP_APPROVAL_REQUIRED` and runs again once the person allows it.
 */
export async function appFetch(
  init: RequestInit | undefined,
  ask: (approval: Approval) => void,
): Promise<Response> {
  let request: { method?: unknown; params?: Record<string, unknown> };
  try {
    request = JSON.parse(String(init?.body ?? "{}")) as typeof request;
  } catch {
    return json({ error: "invalid request" }, 400);
  }
  const { serverId, ...params } = request.params ?? {};
  const route = parseAppRoute(serverId);
  const method = request.method;
  if (
    !route ||
    (method !== "mcp-apps/read-resource" &&
      method !== "tools/call" &&
      method !== "resources/read" &&
      method !== "resources/list")
  )
    return json({ error: "invalid request" }, 400);
  const send = async (approved: boolean) => {
    const { data } = await hostChatToolApp({
      path: { sessionId: route.sessionId, messageId: route.messageId, callId: route.callId },
      body: { method, params, approved },
    });
    return json(data);
  };
  try {
    return await send(false);
  } catch (failure) {
    if (!(failure instanceof ApiError)) return json({ error: "unavailable" }, 503);
    const code = problemCode(failure);
    if (code !== "MCP_APP_APPROVAL_REQUIRED")
      return json({ error: code ?? "failed" }, failure.status ?? 500);
    return new Promise<Response>((settle) =>
      ask({
        slug: parseMcpToolName(route.toolName)?.slug ?? "",
        tool: typeof params.name === "string" ? params.name : "",
        arguments: params.arguments,
        run: () => send(true),
        settle,
      }),
    );
  }
}
