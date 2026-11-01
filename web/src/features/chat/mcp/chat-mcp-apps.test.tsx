import { HttpResponse } from "msw";
import { expect, it } from "vitest";
import {
  ActivityChunks,
  APP_PART_SUFFIX,
  appRoute,
  historyParts,
  parseAppRoute,
  toolEventSchema,
} from "@/features/chat/activity/chat-activity";
import { handleHostChatToolApp } from "@/lib/hey-api/msw.gen";
import { server } from "@/test/msw";
import { appFetch } from "./chat-mcp-app-fetch";

const view = {
  toolCallId: "call_1",
  resourceUri: "ui://canva/create-design",
  input: { query: "poster" },
  output: { structuredContent: { candidates: [] } },
};

const step = (status: "COMPLETED" | "FAILED") => ({
  position: 0,
  toolCallId: "call_1",
  toolName: "mcp_canva_create_design",
  status,
  durationMs: 12,
  textOffset: 0,
  queries: [],
  filters: null,
  documents: [],
  citations: [],
  failure: null,
});

const route = appRoute("session-1", "message-1", "call_1", "mcp_canva_create_design");

it("follows a completed step with its view, which names the answer and the call", () => {
  const chunks = new ActivityChunks("message-1", "session-1");
  const event = (stage: string, app: typeof view | null) =>
    toolEventSchema.parse({
      toolCallId: "call_1",
      toolName: "mcp_canva_create_design",
      stage,
      source: null,
      durationMs: stage === "COMPLETED" ? 12 : null,
      app,
    });
  chunks.tool(event("STARTED", null));
  const finished = chunks.tool(event("COMPLETED", view));
  expect(finished).toContainEqual({
    type: "tool-input-available",
    toolCallId: `call_1${APP_PART_SUFFIX}`,
    toolName: "mcp_canva_create_design",
    input: view.input,
    providerExecuted: true,
    dynamic: true,
    providerMetadata: { mcp: { app: { resourceUri: view.resourceUri, serverId: route } } },
  });
  expect(finished).toContainEqual(
    expect.objectContaining({ type: "tool-output-available", output: view.output }),
  );
  expect(parseAppRoute(route)).toEqual({
    sessionId: "session-1",
    messageId: "message-1",
    callId: "call_1",
    toolName: "mcp_canva_create_design",
  });
});

it("shows a saved view after its step, and none after a failed one", () => {
  const apps = { sessionId: "session-1", messageId: "message-1", views: [view] };
  const parts = historyParts("Done", { reasoning: [], steps: [step("COMPLETED")] }, apps);
  expect(parts.map((part) => ("toolCallId" in part ? part.toolCallId : part.type))).toEqual([
    "call_1",
    `call_1${APP_PART_SUFFIX}`,
    "text",
  ]);
  expect(parts[1]).toMatchObject({
    state: "output-available",
    input: view.input,
    output: view.output,
    callProviderMetadata: { mcp: { app: { resourceUri: view.resourceUri, serverId: route } } },
  });
  const failed = historyParts("", { reasoning: [], steps: [step("FAILED")] }, apps);
  expect(
    failed.some((part) => "toolCallId" in part && part.toolCallId.endsWith(APP_PART_SUFFIX)),
  ).toBe(false);
});

const request = (method: string, params: Record<string, unknown>) => ({
  method: "POST",
  body: JSON.stringify({ method, params: { ...params, serverId: route } }),
});

it("sends a view's request to its answer's route without the browser naming a server", async () => {
  const received: unknown[] = [];
  server.use(
    handleHostChatToolApp(async ({ request: posted, params }) => {
      received.push({ path: params, body: await posted.json() });
      return HttpResponse.json({ content: [] });
    }),
  );
  const response = await appFetch(
    request("tools/call", { name: "select", arguments: { id: 1 } }),
    () => {
      throw new Error("no approval expected");
    },
  );
  expect(await response.json()).toEqual({ content: [] });
  expect(received).toMatchObject([
    {
      path: { sessionId: "session-1", messageId: "message-1", callId: "call_1" },
      body: {
        method: "tools/call",
        params: { name: "select", arguments: { id: 1 } },
        approved: false,
      },
    },
  ]);
});

it("runs a tool that asks first only once the person allows it", async () => {
  const approvals: boolean[] = [];
  server.use(
    handleHostChatToolApp(async ({ request: posted }) => {
      const body = (await posted.json()) as { approved: boolean };
      approvals.push(body.approved);
      return body.approved
        ? HttpResponse.json({ content: [{ type: "text", text: "published" }] })
        : HttpResponse.json(
            { type: "about:blank", status: 409, code: "MCP_APP_APPROVAL_REQUIRED" },
            { status: 409 },
          );
    }),
  );
  let asked: { slug: string; tool: string } | undefined;
  const response = await appFetch(request("tools/call", { name: "publish" }), (approval) => {
    asked = { slug: approval.slug, tool: approval.tool };
    void approval.run().then(approval.settle);
  });
  expect(asked).toEqual({ slug: "canva", tool: "publish" });
  expect(approvals).toEqual([false, true]);
  expect(response.ok).toBe(true);

  const declined = await appFetch(request("tools/call", { name: "publish" }), (approval) =>
    approval.settle(new Response(null, { status: 403 })),
  );
  expect(declined.status).toBe(403);
});

it("refuses a request without a route or with another method", async () => {
  expect(
    (await appFetch({ body: JSON.stringify({ method: "tools/call", params: {} }) }, () => {}))
      .status,
  ).toBe(400);
  expect((await appFetch(request("ui/sendMessage", {}), () => {})).status).toBe(400);
});
