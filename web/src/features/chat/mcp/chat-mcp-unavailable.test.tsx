import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AssistantRuntimeProvider, ThreadPrimitive, useLocalRuntime } from "@assistant-ui/react";
import { HttpResponse } from "msw";
import { beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n/index";
import {
  handleListMcpConnections,
  handleStartMcpConnectionAuthorization,
} from "@/lib/hey-api/msw.gen";
import type { McpConnection } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import type { UnavailableApp } from "@/features/chat/activity/chat-activity";
import { ChatEditingContext } from "@/features/chat/thread/chat-editing-context";
import { ChatMcpUnavailable } from "./chat-mcp-unavailable";

const LINEAR = "11111111-1111-4111-8111-111111111111";
const JIRA = "22222222-2222-4222-8222-222222222222";
const DRIVE = "33333333-3333-4333-8333-333333333333";
const authorized = vi.fn();
const listed = vi.fn();

const connection = (overrides: Partial<McpConnection>): McpConnection => ({
  id: LINEAR,
  slug: "linear",
  name: "Linear",
  description: null,
  url: "https://mcp.linear.app/mcp",
  authType: "OAUTH",
  authPerformer: "PER_USER",
  status: "CONNECTED",
  connectionState: "NOT_CONNECTED",
  oauthClients: [{ id: "44444444-4444-4444-8444-444444444444", label: "Linear" }],
  enabledToolCount: 3,
  credentialUpdatedAt: null,
  revision: 1,
  ...overrides,
});

function show(apps: UnavailableApp[], connections: McpConnection[]) {
  server.use(
    handleListMcpConnections(() => {
      listed();
      return HttpResponse.json(connections);
    }),
    handleStartMcpConnectionAuthorization(async ({ request }) => {
      authorized(await request.json());
      return HttpResponse.json({});
    }),
  );
  function Thread() {
    const runtime = useLocalRuntime(
      { run: async () => ({ content: [] }) },
      {
        initialMessages: [
          {
            id: "answer",
            role: "assistant",
            content: [{ type: "text", text: "Linear is not connected yet." }],
            metadata: { custom: { unavailableApps: apps } },
          },
        ],
      },
    );
    return (
      <AssistantRuntimeProvider runtime={runtime}>
        <ThreadPrimitive.Root>
          <ThreadPrimitive.Messages
            components={{ AssistantMessage: ChatMcpUnavailable, UserMessage: () => null }}
          />
        </ThreadPrimitive.Root>
      </AssistantRuntimeProvider>
    );
  }
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <ChatEditingContext value={{ sessionId: "session-7" } as never}>
        <Thread />
      </ChatEditingContext>
    </QueryClientProvider>,
  );
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  vi.clearAllMocks();
});

it("offers Connect for an app the person has not connected and Kết nối lại for an expired one", async () => {
  show(
    [
      { serverId: LINEAR, serverName: "Linear", reason: "NOT_CONNECTED" },
      { serverId: JIRA, serverName: "Jira", reason: "NOT_CONNECTED" },
    ],
    [
      connection({}),
      // Its sign-in expired since the reply: the line follows the state now.
      connection({ id: JIRA, slug: "jira", name: "Jira", connectionState: "REAUTH_REQUIRED" }),
    ],
  );
  expect(await screen.findByText("Linear chưa kết nối")).toBeVisible();
  expect(screen.getByText("Jira đã hết hạn đăng nhập")).toBeVisible();
  expect(screen.getByRole("button", { name: "Kết nối lại" })).toBeVisible();
  await userEvent.click(screen.getByRole("button", { name: "Kết nối" }));
  // The OAuth callback returns to this conversation.
  await vi.waitFor(() =>
    expect(authorized).toHaveBeenCalledWith({
      oauthClientId: "44444444-4444-4444-8444-444444444444",
      returnPath: "/chat/session-7",
    }),
  );
});

it("shows nothing once the app is connected or no longer offered", async () => {
  show(
    [
      { serverId: LINEAR, serverName: "Linear", reason: "NOT_CONNECTED" },
      { serverId: DRIVE, serverName: "Drive", reason: "REAUTH_REQUIRED" },
    ],
    [connection({ connectionState: "CONNECTED" })],
  );
  await vi.waitFor(() => expect(listed).toHaveBeenCalled());
  await new Promise((resolve) => setTimeout(resolve, 50));
  expect(screen.queryByText(/chưa kết nối|hết hạn/)).toBeNull();
  expect(screen.queryByRole("button")).toBeNull();
});
