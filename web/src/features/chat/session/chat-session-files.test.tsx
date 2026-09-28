import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n/index";
import { HttpResponse } from "msw";
import {
  handleCopyChatLibraryFile,
  handleDeleteChatFileArtifact,
  handleGetChatFile,
  handleListChatLibrary,
} from "@/lib/hey-api/msw.gen";
import type { ChatLibraryFile } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { ChatSessionFiles } from "./chat-session-files";

const composer = vi.hoisted(() => ({
  attachments: [] as unknown[],
  getState() {
    return { attachments: this.attachments };
  },
  addAttachment: vi.fn(),
}));

vi.mock("@assistant-ui/react", () => ({
  useAui: () => ({ thread: { composer: () => composer } }),
  useAuiState: (select: (state: unknown) => unknown) => select({ thread: { isRunning: false } }),
}));

vi.mock("@/features/identity/application-session-context", () => ({
  useApplicationSession: () => ({ actorId: "actor", authorizationVersion: 1, capabilities: [] }),
}));

const SESSION = "22222222-2222-4222-8222-222222222222";
const ANSWER = "33333333-3333-4333-8333-333333333333";

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
  messageId: ANSWER,
  favorite: false,
  status: "READY",
  errorCode: null,
  deletedAt: null,
  purgeAfter: null,
  usedBy: [],
  deletable: true,
};

/** The library listings the panel asked for, as their query parameters. */
let listings: URLSearchParams[] = [];

function show(items: ChatLibraryFile[] = [generated], onShowMessage = vi.fn()) {
  server.use(
    handleListChatLibrary(({ request }) => {
      listings.push(new URL(request.url).searchParams);
      return HttpResponse.json({
        items,
        totalCount: items.length,
        totalBytes: items.reduce((total, item) => total + item.sizeBytes, 0),
        hasMore: false,
      });
    }),
  );
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <ChatSessionFiles sessionId={SESSION} onShowMessage={onShowMessage} />
    </QueryClientProvider>,
  );
  return onShowMessage;
}

async function openPanel() {
  const user = userEvent.setup();
  await user.click(await screen.findByRole("button", { name: "Tệp trong hội thoại (1)" }));
  return { user, panel: await screen.findByRole("dialog") };
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  vi.clearAllMocks();
  composer.attachments = [];
  listings = [];
});
afterEach(cleanup);

it("counts this conversation's files on the button and lists them in the panel", async () => {
  show();

  await waitFor(() =>
    expect(listings.some((params) => params.get("sessionId") === SESSION)).toBe(true),
  );
  const { panel } = await openPanel();
  expect(within(panel).getByText("doanh-thu.xlsx")).toBeInTheDocument();
  expect(within(panel).getByText("1 tệp · 2 KB")).toBeInTheDocument();
});

it("says so when the conversation has no files", async () => {
  show([]);
  const user = userEvent.setup();

  await user.click(await screen.findByRole("button", { name: "Tệp trong hội thoại" }));

  expect(await screen.findByText("Hội thoại này chưa có tệp nào")).toBeInTheDocument();
});

it("searches and filters within the conversation on the server", async () => {
  show();
  const { user, panel } = await openPanel();

  await user.type(within(panel).getByRole("textbox", { name: "Tìm theo tên tệp" }), "doanh");
  await user.click(within(panel).getByRole("button", { name: "Loại tệp" }));
  await user.click(await screen.findByRole("button", { name: "Bảng tính" }));

  await waitFor(() =>
    expect(
      listings.some(
        (params) =>
          params.get("sessionId") === SESSION &&
          params.get("query") === "doanh" &&
          params.getAll("categories").join() === "SPREADSHEET",
      ),
    ).toBe(true),
  );
});

it("attaches a generated file to the next question through its server copy", async () => {
  show();
  const copy = "44444444-4444-4444-8444-444444444444";
  const copied: string[] = [];
  server.use(
    handleCopyChatLibraryFile(({ params }) => {
      copied.push(`${params.source}:${params.id}`);
      return HttpResponse.json({
        id: copy,
        filename: "doanh-thu.xlsx",
        mediaType: "text/csv",
        sizeBytes: 2048,
        status: "PROCESSING",
      });
    }),
    handleGetChatFile({
      body: {
        id: copy,
        filename: "doanh-thu.xlsx",
        mediaType: "text/csv",
        sizeBytes: 2048,
        status: "READY",
      },
    }),
  );
  const { user, panel } = await openPanel();

  await user.click(
    within(panel).getByRole("button", { name: "Đính kèm doanh-thu.xlsx vào câu hỏi" }),
  );

  await waitFor(() =>
    expect(composer.addAttachment).toHaveBeenCalledWith(
      expect.objectContaining({
        id: copy,
        content: [expect.objectContaining({ data: `memoryos-file:${copy}` })],
      }),
    ),
  );
  expect(copied).toEqual([`GENERATED:${generated.id}`]);
  expect(
    await within(panel).findByText("Đã đính kèm doanh-thu.xlsx vào câu hỏi tiếp theo."),
  ).toBeInTheDocument();
});

it("jumps to the message a file belongs to", async () => {
  const onShowMessage = show([generated], vi.fn().mockResolvedValue(true));
  const { user, panel } = await openPanel();

  await user.click(within(panel).getByRole("button", { name: "Thao tác với doanh-thu.xlsx" }));
  await user.click(await screen.findByRole("menuitem", { name: "Xem trong hội thoại" }));

  await waitFor(() => expect(onShowMessage).toHaveBeenCalledWith(ANSWER));
});

it("deletes a file after the confirmation", async () => {
  show();
  const deleted: string[] = [];
  server.use(
    handleDeleteChatFileArtifact(({ params }) => {
      deleted.push(params.artifactId);
      return new HttpResponse(null, { status: 204 });
    }),
  );
  const { user, panel } = await openPanel();

  await user.click(within(panel).getByRole("button", { name: "Thao tác với doanh-thu.xlsx" }));
  await user.click(await screen.findByRole("menuitem", { name: "Xoá" }));
  await user.click(
    within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Xoá" }),
  );

  await waitFor(() => expect(deleted).toEqual([generated.id]));
});
