import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n";
import type { McpConnection } from "@/lib/hey-api/types.gen";
import { ActivityChunks, historyParts, toolEventSchema, type ToolProgress } from "./chat-activity";
import { parseMcpToolName } from "./chat-mcp-connections";
import { ChatMcpToolStep } from "./chat-mcp-step";

const listMcpConnections = vi.fn();

vi.mock("@/lib/hey-api/sdk.gen", () => ({
  listMcpConnections: (...args: unknown[]) => listMcpConnections(...args),
  startMcpConnectionAuthorization: vi.fn(),
  saveMcpConnectionApiKey: vi.fn(),
}));

vi.mock("@tanstack/react-router", () => ({
  useParams: () => ({ sessionId: "session-7" }),
}));

const connection = (overrides: Partial<McpConnection>): McpConnection => ({
  id: "11111111-1111-4111-8111-111111111111",
  slug: "drive",
  name: "Google Drive",
  description: null,
  url: "https://drivemcp.googleapis.com/mcp/v1",
  authType: "OAUTH",
  authPerformer: "PER_USER",
  status: "CONNECTED",
  connectionState: "CONNECTED",
  oauthClients: [{ id: "c1", label: "Org A" }],
  enabledToolCount: 8,
  credentialUpdatedAt: null,
  revision: 1,
  ...overrides,
});

const progress = (failure: ToolProgress["failure"]): ToolProgress => ({
  stage: "FAILED",
  queries: [],
  filters: null,
  documents: [],
  citations: [],
  durationMs: 12,
  failure,
});

function show(
  connections: McpConnection[],
  state: "running" | "done" | "failed",
  failure: ToolProgress["failure"] = null,
) {
  listMcpConnections.mockResolvedValue({ data: connections });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <ul>
        <ChatMcpToolStep
          slug="drive"
          tool="search_files"
          progress={progress(failure)}
          state={state}
        />
      </ul>
    </QueryClientProvider>,
  );
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  vi.clearAllMocks();
});

it("splits the model-facing name into server slug and tool", () => {
  expect(parseMcpToolName("mcp_drive_search_files")).toEqual({
    slug: "drive",
    tool: "search_files",
  });
  expect(parseMcpToolName("search_files")).toBeNull();
  expect(parseMcpToolName("mcp_Drive_search")).toBeNull();
});

it("names the tool and the server the person knows", async () => {
  show([connection({})], "done");
  expect(await screen.findByText("Đã dùng search_files trên Google Drive")).toBeInTheDocument();
});

it("falls back to the slug for a server the viewer cannot see", async () => {
  show([], "running");
  expect(await screen.findByText("Đang dùng search_files trên drive…")).toBeInTheDocument();
});

it("offers Connect in place when the server rejected the person's own credential", async () => {
  show([connection({})], "failed", "AUTHORIZATION_REQUIRED");
  expect(
    await screen.findByText("Google Drive từ chối kết nối của bạn. Kết nối lại để tiếp tục."),
  ).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Kết nối" })).toBeInTheDocument();
});

it("sends a shared connection to the administrator without a Connect button", async () => {
  show([connection({ authPerformer: "ADMIN" })], "failed", "AUTHORIZATION_REQUIRED");
  expect(
    await screen.findByText(
      "Google Drive từ chối kết nối dùng chung. Quản trị viên cần kết nối lại.",
    ),
  ).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Kết nối" })).not.toBeInTheDocument();
});

it("explains a timeout without offering to reconnect", async () => {
  show([connection({})], "failed", "TIMEOUT");
  expect(await screen.findByText("Google Drive không phản hồi kịp.")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Kết nối" })).not.toBeInTheDocument();
});

it("restores the failure category from saved history", () => {
  const parts = historyParts("Answer", {
    reasoning: [],
    steps: [
      {
        position: 0,
        toolCallId: "call_1",
        toolName: "mcp_drive_search_files",
        status: "FAILED",
        durationMs: 12,
        textOffset: 0,
        queries: [],
        filters: null,
        documents: [],
        citations: [],
        failure: "AUTHORIZATION_REQUIRED",
      },
    ],
  });
  const step = parts.find((part) => part.type === "dynamic-tool");
  expect(step).toMatchObject({ input: { failure: "AUTHORIZATION_REQUIRED" } });
});

it("carries the streamed failure category into the step input", () => {
  const chunks = new ActivityChunks("run-1");
  const event = (stage: string, failure: string | null) =>
    toolEventSchema.parse({
      toolCallId: "call_1",
      toolName: "mcp_drive_search_files",
      stage,
      source: null,
      durationMs: stage === "FAILED" ? 12 : null,
      failure,
    });
  chunks.tool(event("STARTED", null));
  const finished = chunks.tool(event("FAILED", "AUTHORIZATION_REQUIRED"));
  expect(finished).toContainEqual(
    expect.objectContaining({
      type: "tool-input-available",
      input: expect.objectContaining({ failure: "AUTHORIZATION_REQUIRED" }),
    }),
  );
});
