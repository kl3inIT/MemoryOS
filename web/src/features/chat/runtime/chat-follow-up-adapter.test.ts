// @vitest-environment jsdom
import type { ThreadMessage } from "@assistant-ui/react";
import { QueryClient } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import { getChatSettingsQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { ChatSettingsResponse } from "@/lib/hey-api/types.gen";
import { createChatFollowUpAdapter } from "./chat-follow-up-adapter";
import { ChatThreadRegistry } from "./chat-thread-controller";

const sessionId = "5230ab53-dab0-4441-acbf-840636b52953";
const answerId = "8b0f6c1e-0d55-4d0e-9a51-2d6f1b0f3c11";
const settings = (followUpSuggestions: boolean): ChatSettingsResponse => ({
  deepResearchEnabled: true,
  chatHistoryVisibility: "NORMAL",
  groundedAnswers: false,
  groundedAllowWeb: false,
  followUpSuggestions,
  revision: 1,
});
const answer = (serverStatus: string, role: "assistant" | "user" = "assistant") =>
  ({ id: answerId, role, metadata: { custom: { serverStatus } } }) as unknown as ThreadMessage;

function stubFetch(handler: () => Response) {
  const requests: string[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(input, init);
      requests.push(`${request.method} ${new URL(request.url).pathname}`);
      return handler();
    }),
  );
  return requests;
}

function setup(enabled = true) {
  const queries = new QueryClient();
  queries.setQueryData(getChatSettingsQueryKey(), settings(enabled));
  const controller = new ChatThreadRegistry(queries, "actor").obtain("local", sessionId);
  return { controller, adapter: createChatFollowUpAdapter(controller, queries) };
}

afterEach(() => vi.unstubAllGlobals());

describe("follow-up suggestions adapter (MEM-266)", () => {
  it("asks the API for the completed answer and marks it while the request runs", async () => {
    const { controller, adapter } = setup();
    let pending: string | undefined;
    const requests = stubFetch(() => {
      pending = controller.getState().followUpsFor;
      return Response.json({ suggestions: ["Mẫu đơn ở đâu?", "Ai duyệt?"] });
    });

    const suggestions = await adapter.generate({ messages: [answer("COMPLETED")] });

    expect(requests).toEqual([
      `POST /api/chat/sessions/${sessionId}/messages/${answerId}/follow-ups`,
    ]);
    expect(suggestions).toEqual([{ prompt: "Mẫu đơn ở đâu?" }, { prompt: "Ai duyệt?" }]);
    expect(pending).toBe(answerId);
    expect(controller.getState().followUpsFor).toBeUndefined();
  });

  it("asks nothing while the Tenant's switch is off", async () => {
    const { adapter } = setup(false);
    const requests = stubFetch(() => Response.json({ suggestions: ["x"] }));
    expect(await adapter.generate({ messages: [answer("COMPLETED")] })).toEqual([]);
    expect(requests).toEqual([]);
  });

  it.each(["FAILED", "CANCELED", "RUNNING"])("asks nothing for a %s answer", async (status) => {
    const { adapter } = setup();
    const requests = stubFetch(() => Response.json({ suggestions: ["x"] }));
    expect(await adapter.generate({ messages: [answer(status)] })).toEqual([]);
    expect(requests).toEqual([]);
  });

  it("asks nothing in a temporary conversation", async () => {
    const { controller, adapter } = setup();
    controller.transport.selectTemporary(true);
    const requests = stubFetch(() => Response.json({ suggestions: ["x"] }));
    expect(await adapter.generate({ messages: [answer("COMPLETED")] })).toEqual([]);
    expect(requests).toEqual([]);
  });

  it("shows nothing when the request fails", async () => {
    const { controller, adapter } = setup();
    stubFetch(() => Response.json({ status: 500, code: "INTERNAL" }, { status: 500 }));
    expect(await adapter.generate({ messages: [answer("COMPLETED")] })).toEqual([]);
    expect(controller.getState().followUpsFor).toBeUndefined();
  });
});
