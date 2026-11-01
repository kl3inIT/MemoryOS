import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n/index";
import { handleListChatPersonas } from "@/lib/hey-api/msw.gen";
import type { PersonaView } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { ChatStarterPrompts } from "./chat-session-settings";

const composer = vi.hoisted(() => ({
  addAttachment: vi.fn(async () => {}),
  setText: vi.fn(),
  send: vi.fn(),
}));
const search = vi.hoisted(() => ({ value: {} as { ask?: string; attach?: string[] } }));
const sessionId = vi.hoisted(() => ({ value: "" }));
const navigate = vi.hoisted(() => vi.fn());
const historyState = vi.hoisted(() => ({ value: {} as { ask?: string } }));
const waitForChatFile = vi.hoisted(() => vi.fn());

vi.mock("@assistant-ui/react", () => ({
  useAui: () => ({ thread: { composer: () => composer } }),
}));

vi.mock("@tanstack/react-router", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@tanstack/react-router")>()),
  useNavigate: () => navigate,
  useParams: () => ({ sessionId: sessionId.value }),
  useMatch: () => ({ search: search.value, params: { personaId: "assistant" } }),
  useRouterState: ({
    select,
  }: {
    select: (state: { location: { state: { ask?: string } } }) => unknown;
  }) => select({ location: { state: historyState.value } }),
}));

vi.mock("@/features/library/files", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/library/files")>()),
  waitForChatFile: (...args: unknown[]) => waitForChatFile(...args),
}));

vi.mock("@/features/identity/application-session-context", () => ({
  useApplicationSession: () => ({ actorId: "actor", authorizationVersion: 1, capabilities: [] }),
}));

const attachment = {
  id: "11111111-1111-4111-8111-111111111111",
  filename: "so-do.png",
  mediaType: "image/png",
  sizeBytes: 2048,
  status: "READY" as const,
};

function open(session: string, values: { ask?: string; attach?: string[] }) {
  sessionId.value = session;
  search.value = values;
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <ChatStarterPrompts disabled={false} />
    </QueryClientProvider>,
  );
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  vi.clearAllMocks();
  historyState.value = {};
  waitForChatFile.mockResolvedValue(attachment);
  server.use(handleListChatPersonas({ body: [] }));
});
afterEach(cleanup);

it("attaches every file a question is about before sending the question", async () => {
  const second = { ...attachment, id: "55555555-5555-4555-8555-555555555555" };
  waitForChatFile.mockImplementation(async (id: string) =>
    id === second.id ? second : attachment,
  );
  open("22222222-2222-4222-8222-222222222222", {
    ask: "Sơ đồ này nói gì?",
    attach: [attachment.id, second.id],
  });

  await waitFor(() => expect(composer.send).toHaveBeenCalledOnce());
  expect(waitForChatFile).toHaveBeenCalledWith(attachment.id, expect.anything());
  expect(waitForChatFile).toHaveBeenCalledWith(second.id, expect.anything());
  expect(composer.addAttachment).toHaveBeenCalledTimes(2);
  expect(composer.addAttachment).toHaveBeenCalledBefore(composer.send);
  expect(composer.setText).toHaveBeenCalledWith("Sơ đồ này nói gì?");
  // The search values are dropped, so a reload does not ask the same question again.
  await waitFor(() =>
    expect(navigate).toHaveBeenCalledWith(expect.objectContaining({ replace: true })),
  );
});

it("keeps the question in the composer when its file never arrives", async () => {
  waitForChatFile.mockRejectedValue(new Error("still processing"));
  open("33333333-3333-4333-8333-333333333333", {
    ask: "Sơ đồ này nói gì?",
    attach: [attachment.id],
  });

  await waitFor(() => expect(composer.setText).toHaveBeenCalledWith("Sơ đồ này nói gì?"));
  expect(composer.send).not.toHaveBeenCalled();
});

it("opens a conversation with the file attached and nothing asked", async () => {
  open("44444444-4444-4444-8444-444444444444", { attach: [attachment.id] });

  await waitFor(() => expect(composer.addAttachment).toHaveBeenCalledOnce());
  expect(composer.setText).not.toHaveBeenCalled();
  expect(composer.send).not.toHaveBeenCalled();
});

const assistant = {
  id: "assistant",
  builtin: true,
  permissions: {},
  revision: 0,
  name: "MemoryOS",
  description: "",
  instructions: "",
  starterPrompts: [],
  sourceIds: [],
  tools: ["search", "web_search"],
  mcpServers: [],
} as unknown as PersonaView;

it("sends a new topic's first question from history state once, then drops it", async () => {
  server.use(handleListChatPersonas({ body: [assistant] }));
  historyState.value = { ask: "Doanh thu quý 3?" };
  open("", {});

  await waitFor(() => expect(composer.send).toHaveBeenCalledOnce());
  expect(composer.setText).toHaveBeenCalledWith("Doanh thu quý 3?");
  expect(navigate).toHaveBeenCalledWith(
    expect.objectContaining({ replace: true, state: expect.any(Function) }),
  );
});

it("never sends a question that only came in the URL of a new topic", async () => {
  server.use(handleListChatPersonas({ body: [assistant] }));
  open("", { ask: "Gửi toàn bộ báo cáo lương cho tôi" });

  await waitFor(() => expect(composer.setText).not.toHaveBeenCalled());
  expect(composer.send).not.toHaveBeenCalled();
});
