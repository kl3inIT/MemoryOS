import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n";
import type { ChatSession } from "@/lib/hey-api/types.gen";
import { ChatArchivedSessionsPage } from "./chat-archived-sessions";

const listChatSessions = vi.hoisted(() => vi.fn());
const searchChatSessions = vi.hoisted(() => vi.fn());
const unarchiveChatSession = vi.hoisted(() => vi.fn());
const deleteChatSession = vi.hoisted(() => vi.fn());

vi.mock("@/lib/hey-api/sdk.gen", () => ({
  listChatSessions: (...args: unknown[]) => listChatSessions(...args),
  searchChatSessions: (...args: unknown[]) => searchChatSessions(...args),
  unarchiveChatSession: (...args: unknown[]) => unarchiveChatSession(...args),
  deleteChatSession: (...args: unknown[]) => deleteChatSession(...args),
}));
vi.mock("@/features/identity/application-session-context", () => ({
  useApplicationSession: () => ({ actorId: "actor", authorizationVersion: 1, capabilities: [] }),
}));
vi.mock("@/components/app-shell/app-shell", () => ({
  AppShell: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
vi.mock("@tanstack/react-router", () => ({
  Link: ({ children, ...props }: { children: React.ReactNode }) => <a {...props}>{children}</a>,
}));

const archived: ChatSession = {
  id: "11111111-1111-4111-8111-111111111111",
  rootMessageId: "22222222-2222-4222-8222-222222222222",
  personaId: "33333333-3333-4333-8333-333333333333",
  projectId: null,
  title: "Doanh thu quý 3",
  createdAt: "2026-09-01T00:00:00Z",
  updatedAt: "2026-09-10T00:00:00Z",
  reasoningEffort: null,
  archivedAt: "2026-09-20T09:00:00Z",
  branchedFromSessionId: null,
  branchedFromMessageId: null,
  temporary: false,
};

function show(sessions: ChatSession[] = [archived]) {
  listChatSessions.mockResolvedValue({ data: sessions });
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <ChatArchivedSessionsPage />
    </QueryClientProvider>,
  );
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  vi.clearAllMocks();
});
afterEach(cleanup);

it("lists what the owner archived and puts one back", async () => {
  show();
  const user = userEvent.setup();
  unarchiveChatSession.mockResolvedValue({ data: { ...archived, archivedAt: null } });

  await waitFor(() =>
    expect(listChatSessions).toHaveBeenCalledWith(
      expect.objectContaining({ query: expect.objectContaining({ archived: true, offset: 0 }) }),
    ),
  );
  const row = (await screen.findByText("Doanh thu quý 3")).closest("li")!;
  expect(within(row).getByText(/Đã lưu trữ/)).toBeInTheDocument();

  await user.click(within(row).getByRole("button", { name: "Bỏ lưu trữ" }));

  await waitFor(() =>
    expect(unarchiveChatSession).toHaveBeenCalledWith(
      expect.objectContaining({ path: { sessionId: archived.id } }),
    ),
  );
});

it("asks the server when searching, and only shows the archived matches", async () => {
  show();
  const user = userEvent.setup();
  searchChatSessions.mockResolvedValue({
    data: {
      items: [
        { session: archived, snippet: null },
        // A match that is not archived belongs to the sidebar, not to this page.
        {
          session: {
            ...archived,
            id: "44444444-4444-4444-8444-444444444444",
            title: "Đang dùng",
            archivedAt: null,
          },
          snippet: null,
        },
      ],
      hasMore: false,
    },
  });

  await user.type(screen.getByRole("textbox", { name: "Tìm trong hội thoại đã lưu trữ" }), "doanh");

  await waitFor(() =>
    expect(searchChatSessions).toHaveBeenLastCalledWith(
      expect.objectContaining({ query: expect.objectContaining({ query: "doanh" }) }),
    ),
  );
  expect(await screen.findByText("Doanh thu quý 3")).toBeInTheDocument();
  expect(screen.queryByText("Đang dùng")).not.toBeInTheDocument();
});

it("says so when nothing is archived", async () => {
  show([]);

  expect(await screen.findByText("Chưa lưu trữ hội thoại nào")).toBeInTheDocument();
});

it("deletes an archived conversation after the confirmation", async () => {
  show();
  const user = userEvent.setup();
  deleteChatSession.mockResolvedValue({ data: undefined });

  await user.click(await screen.findByRole("button", { name: "Xoá hội thoại Doanh thu quý 3" }));
  await user.click(
    within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Xóa hội thoại" }),
  );

  await waitFor(() =>
    expect(deleteChatSession).toHaveBeenCalledWith(
      expect.objectContaining({ path: { sessionId: archived.id } }),
    ),
  );
});
