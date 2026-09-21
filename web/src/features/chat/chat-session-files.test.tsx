import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n";
import type { ChatLibraryFile } from "@/lib/hey-api/types.gen";
import { ChatSessionFiles } from "./chat-session-files";

const listChatLibrary = vi.hoisted(() => vi.fn());
const deleteChatFileArtifact = vi.hoisted(() => vi.fn());

vi.mock("@/lib/hey-api/sdk.gen", () => ({
  listChatLibrary: (...args: unknown[]) => listChatLibrary(...args),
  deleteChatFile: vi.fn(),
  deleteChatFileArtifact: (...args: unknown[]) => deleteChatFileArtifact(...args),
  deleteChatImageArtifact: vi.fn(),
}));

vi.mock("@/features/identity/application-session-context", () => ({
  useApplicationSession: () => ({ actorId: "actor", authorizationVersion: 1, capabilities: [] }),
}));

const SESSION = "22222222-2222-4222-8222-222222222222";

const generated: ChatLibraryFile = {
  source: "GENERATED",
  id: "11111111-1111-4111-8111-111111111111",
  filename: "doanh-thu.xlsx",
  mediaType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  sizeBytes: 2048,
  createdAt: new Date().toISOString(),
  category: "SPREADSHEET",
  sessionId: SESSION,
  sessionTitle: "Báo cáo",
  usedBy: [],
  deletable: true,
};

function show(items: ChatLibraryFile[] = [generated]) {
  listChatLibrary.mockResolvedValue({
    data: {
      items,
      totalCount: items.length,
      totalBytes: items.reduce((total, item) => total + item.sizeBytes, 0),
      hasMore: false,
    },
  });
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <ChatSessionFiles sessionId={SESSION} />
    </QueryClientProvider>,
  );
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  vi.clearAllMocks();
});
afterEach(cleanup);

it("asks only for this conversation's files, and only once opened", async () => {
  show();
  const user = userEvent.setup();

  // A closed panel costs nothing.
  expect(listChatLibrary).not.toHaveBeenCalled();
  await user.click(screen.getByRole("button", { name: "Tệp trong hội thoại" }));

  await waitFor(() =>
    expect(listChatLibrary).toHaveBeenCalledWith(
      expect.objectContaining({ query: expect.objectContaining({ sessionId: SESSION }) }),
    ),
  );
  const panel = await screen.findByRole("dialog");
  expect(within(panel).getByText("doanh-thu.xlsx")).toBeInTheDocument();
  expect(within(panel).getByText("1 tệp · 2 KB")).toBeInTheDocument();
  expect(within(panel).getByRole("link", { name: "Tải về" })).toHaveAttribute(
    "href",
    `/api/chat/file-artifacts/${generated.id}/content`,
  );
});

it("says so when the conversation has no files", async () => {
  show([]);
  const user = userEvent.setup();

  await user.click(screen.getByRole("button", { name: "Tệp trong hội thoại" }));

  expect(await screen.findByText("Hội thoại này chưa có tệp nào.")).toBeInTheDocument();
});

it("deletes a file after the confirmation", async () => {
  show();
  const user = userEvent.setup();
  deleteChatFileArtifact.mockResolvedValue({ data: undefined });

  await user.click(screen.getByRole("button", { name: "Tệp trong hội thoại" }));
  await user.click(await screen.findByRole("button", { name: "Xoá doanh-thu.xlsx" }));
  await user.click(
    within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Xoá" }),
  );

  await waitFor(() =>
    expect(deleteChatFileArtifact).toHaveBeenCalledWith(
      expect.objectContaining({ path: { artifactId: generated.id } }),
    ),
  );
});
