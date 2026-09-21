import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from "@tanstack/react-router";
import { act, cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n";
import { ApiError } from "@/lib/api";
import type { ChatLibraryFile } from "@/lib/hey-api/types.gen";
import { ChatLibraryPage } from "./chat-library-page";

const listChatLibrary = vi.hoisted(() => vi.fn());
const deleteChatFile = vi.hoisted(() => vi.fn());
const deleteChatFileArtifact = vi.hoisted(() => vi.fn());
const deleteChatImageArtifact = vi.hoisted(() => vi.fn());
const listChatProjects = vi.hoisted(() => vi.fn());
const getChatProject = vi.hoisted(() => vi.fn());
const updateChatProject = vi.hoisted(() => vi.fn());
const copyChatLibraryFile = vi.hoisted(() => vi.fn());
const getChatFile = vi.hoisted(() => vi.fn());
const changeChatLibraryFile = vi.hoisted(() => vi.fn());
const searchChatLibraryContent = vi.hoisted(() => vi.fn());
const retryChatFile = vi.hoisted(() => vi.fn());
const uploadChatFile = vi.hoisted(() => vi.fn());
const requestChatLibraryArchive = vi.hoisted(() => vi.fn());
const getChatLibraryArchive = vi.hoisted(() => vi.fn());
const getChatLibraryUsage = vi.hoisted(() => vi.fn());
const getChatLibraryTrashWindow = vi.hoisted(() => vi.fn());
const restoreChatLibraryFile = vi.hoisted(() => vi.fn());
const purgeChatLibraryFile = vi.hoisted(() => vi.fn());
const emptyChatLibraryTrash = vi.hoisted(() => vi.fn());

vi.mock("@/lib/hey-api/sdk.gen", () => ({
  listChatLibrary: (...args: unknown[]) => listChatLibrary(...args),
  deleteChatFile: (...args: unknown[]) => deleteChatFile(...args),
  deleteChatFileArtifact: (...args: unknown[]) => deleteChatFileArtifact(...args),
  deleteChatImageArtifact: (...args: unknown[]) => deleteChatImageArtifact(...args),
  listChatProjects: (...args: unknown[]) => listChatProjects(...args),
  getChatProject: (...args: unknown[]) => getChatProject(...args),
  updateChatProject: (...args: unknown[]) => updateChatProject(...args),
  copyChatLibraryFile: (...args: unknown[]) => copyChatLibraryFile(...args),
  getChatFile: (...args: unknown[]) => getChatFile(...args),
  changeChatLibraryFile: (...args: unknown[]) => changeChatLibraryFile(...args),
  searchChatLibraryContent: (...args: unknown[]) => searchChatLibraryContent(...args),
  retryChatFile: (...args: unknown[]) => retryChatFile(...args),
  requestChatLibraryArchive: (...args: unknown[]) => requestChatLibraryArchive(...args),
  getChatLibraryArchive: (...args: unknown[]) => getChatLibraryArchive(...args),
  getChatLibraryUsage: (...args: unknown[]) => getChatLibraryUsage(...args),
  getChatLibraryTrashWindow: (...args: unknown[]) => getChatLibraryTrashWindow(...args),
  restoreChatLibraryFile: (...args: unknown[]) => restoreChatLibraryFile(...args),
  purgeChatLibraryFile: (...args: unknown[]) => purgeChatLibraryFile(...args),
  emptyChatLibraryTrash: (...args: unknown[]) => emptyChatLibraryTrash(...args),
}));

vi.mock("./chat-files", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./chat-files")>()),
  uploadChatFile: (...args: unknown[]) => uploadChatFile(...args),
}));

vi.mock("@/features/identity/application-session-context", () => ({
  useApplicationSession: () => ({ actorId: "actor", authorizationVersion: 1, capabilities: [] }),
}));

vi.mock("@/components/app-shell/app-shell", () => ({
  AppShell: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

const file = (overrides: Partial<ChatLibraryFile> = {}): ChatLibraryFile => ({
  source: "GENERATED",
  id: "11111111-1111-4111-8111-111111111111",
  filename: "doanh-thu.xlsx",
  mediaType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  sizeBytes: 2048,
  createdAt: new Date().toISOString(),
  category: "SPREADSHEET",
  sessionId: "22222222-2222-4222-8222-222222222222",
  sessionTitle: "Báo cáo",
  messageId: "55555555-5555-4555-8555-555555555555",
  favorite: false,
  status: "READY",
  errorCode: null,
  deletedAt: null,
  purgeAfter: null,
  usedBy: [],
  deletable: true,
  ...overrides,
});

const upload = file({
  source: "UPLOAD",
  id: "33333333-3333-4333-8333-333333333333",
  filename: "ghi-chú.pdf",
  mediaType: "application/pdf",
  sizeBytes: 1024,
  category: "DOCUMENT",
  sessionId: null,
  sessionTitle: null,
  usedBy: [{ kind: "PROJECT", id: "44444444-4444-4444-8444-444444444444", name: "Kế hoạch" }],
  deletable: false,
});

async function show(items: ChatLibraryFile[] = [file(), upload], hasMore = false) {
  const first = items[0];
  listChatLibrary.mockResolvedValue({
    data: {
      items,
      totalCount: hasMore ? 60 : items.length,
      totalBytes: items.reduce((total, item) => total + item.sizeBytes, 0),
      hasMore,
    },
  });
  const rootRoute = createRootRoute();
  const route = createRoute({
    getParentRoute: () => rootRoute,
    path: "/library",
    component: () => <ChatLibraryPage />,
  });
  const router = createRouter({
    routeTree: rootRoute.addChildren([route]),
    history: createMemoryHistory({ initialEntries: ["/library"] }),
  });
  await router.load();
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  if (first) await screen.findByText(first.filename);
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  vi.clearAllMocks();
  getChatLibraryUsage.mockResolvedValue({
    data: { usedBytes: 3072, fileCount: 2, limitBytes: 10240, byCategory: [] },
  });
  getChatLibraryTrashWindow.mockResolvedValue({ data: { days: 30 } });
});
afterEach(cleanup);

/** A file's row: the redesign renders each file as a list item rather than a table row. */
const row = (name: string) => screen.getByText(name).closest("li") as HTMLElement;

it("lists every source with its size, total and originating conversation", async () => {
  await show();

  expect(screen.getByText("2 tệp · 3 KB")).toBeInTheDocument();
  const user = userEvent.setup();
  const generated = row("doanh-thu.xlsx");
  expect(within(generated).getByText("Do mã tạo")).toBeInTheDocument();
  await user.click(within(generated).getByRole("button", { name: "Thao tác với doanh-thu.xlsx" }));
  expect(await screen.findByRole("menuitem", { name: "Mở hội thoại gốc" })).toHaveAttribute(
    "href",
    "/chat/22222222-2222-4222-8222-222222222222",
  );
  await user.keyboard("{Escape}");

  // An upload belongs to its owner rather than one conversation, so it offers no conversation link, and a
  // Project holding it blocks the deletion with the holder named in its place.
  const uploaded = row("ghi-chú.pdf");
  expect(within(uploaded).getByText("Đang dùng trong Kế hoạch")).toBeInTheDocument();
  await user.click(within(uploaded).getByRole("button", { name: "Thao tác với ghi-chú.pdf" }));
  const menu = await screen.findByRole("menu");
  expect(
    within(menu).queryByRole("menuitem", { name: "Mở hội thoại gốc" }),
  ).not.toBeInTheDocument();
  expect(within(menu).getByRole("menuitem", { name: "Đang dùng trong Kế hoạch" })).toHaveAttribute(
    "aria-disabled",
    "true",
  );
});

it("sends the filters, the search and the sort to the server", async () => {
  await show();
  const user = userEvent.setup();

  await user.click(screen.getByRole("button", { name: "Bộ lọc" }));
  await user.click(await screen.findByRole("button", { name: "Ảnh AI" }));
  await user.keyboard("{Escape}");
  await user.type(screen.getByRole("textbox", { name: "Tìm theo tên tệp" }), "doanh");
  await user.selectOptions(screen.getByRole("combobox", { name: "Sắp xếp" }), "LARGEST");

  await waitFor(() =>
    expect(listChatLibrary).toHaveBeenLastCalledWith(
      expect.objectContaining({
        query: expect.objectContaining({
          query: "doanh",
          sources: ["IMAGE"],
          sort: "LARGEST",
          offset: 0,
        }),
      }),
    ),
  );
});

it("deletes each selected file through its own route and reports a refusal", async () => {
  const image = file({
    source: "IMAGE",
    id: "55555555-5555-4555-8555-555555555555",
    filename: "image-20260920-101500-abcdef12.png",
    mediaType: "image/png",
    category: "IMAGE",
  });
  await show([file(), image]);
  const user = userEvent.setup();
  deleteChatFileArtifact.mockRejectedValue(new Error("refused"));
  deleteChatImageArtifact.mockResolvedValue({ data: undefined });

  await user.click(screen.getByRole("checkbox", { name: "Chọn tất cả" }));
  await user.click(screen.getByRole("button", { name: "Xoá" }));
  // The dialog's own confirm button, not the toolbar one that opened it.
  await user.click(
    within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Xoá" }),
  );

  await waitFor(() => expect(deleteChatImageArtifact).toHaveBeenCalledTimes(1));
  expect(deleteChatFileArtifact).toHaveBeenCalledWith(
    expect.objectContaining({ path: { artifactId: file().id } }),
  );
  // The refused file is named; the image beside it was still deleted.
  expect(await screen.findByText(/doanh-thu\.xlsx:/)).toBeInTheDocument();
});

it("names the project holding an upload when the server refuses the deletion", async () => {
  const held = { ...upload, deletable: true, usedBy: [] };
  await show([held]);
  const user = userEvent.setup();
  deleteChatFile.mockRejectedValue(
    new ApiError(409, {
      code: "CHAT_FILE_IN_USE",
      usedBy: [{ kind: "PROJECT", id: "p", name: "Kế hoạch" }],
    }),
  );

  await user.click(screen.getByRole("button", { name: "Thao tác với ghi-chú.pdf" }));
  await user.click(await screen.findByRole("menuitem", { name: "Xoá" }));
  await user.click(
    within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Xoá" }),
  );

  expect(await screen.findByText("ghi-chú.pdf: Đang dùng trong Kế hoạch")).toBeInTheDocument();
});

it("drops a selection made on another page, so paging cannot delete nothing silently", async () => {
  await show([file()], true);
  const user = userEvent.setup();

  await user.click(screen.getByRole("checkbox", { name: "Chọn tất cả" }));
  expect(screen.getByText("Đã chọn 1 tệp")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Trang sau" }));

  expect(screen.queryByText("Đã chọn 1 tệp")).not.toBeInTheDocument();
  await waitFor(() =>
    expect(listChatLibrary).toHaveBeenLastCalledWith(
      expect.objectContaining({ query: expect.objectContaining({ offset: 50 }) }),
    ),
  );
});

const PROJECT = {
  id: "44444444-4444-4444-8444-444444444444",
  name: "Kế hoạch",
  description: "",
  instructions: "",
  revision: 3,
  updatedAt: new Date().toISOString(),
  fileIds: ["66666666-6666-4666-8666-666666666666"],
};

it("adds a generated file to a project by copying it into an upload first", async () => {
  await show();
  const user = userEvent.setup();
  const copy = "77777777-7777-4777-8777-777777777777";
  listChatProjects.mockResolvedValue({ data: [PROJECT] });
  getChatProject.mockResolvedValue({ data: PROJECT });
  updateChatProject.mockResolvedValue({ data: PROJECT });
  copyChatLibraryFile.mockResolvedValue({
    data: {
      id: copy,
      filename: "doanh-thu.xlsx",
      mediaType: "text/csv",
      sizeBytes: 2048,
      status: "READY",
    },
  });

  await user.click(screen.getByRole("button", { name: "Thao tác với doanh-thu.xlsx" }));
  await user.click(await screen.findByRole("menuitem", { name: "Thêm vào dự án" }));
  const dialog = await screen.findByRole("dialog");
  await within(dialog).findByRole("option", { name: "Kế hoạch (1/20)" });
  await user.click(within(dialog).getByRole("button", { name: "Thêm vào dự án" }));

  await waitFor(() =>
    expect(updateChatProject).toHaveBeenCalledWith(
      expect.objectContaining({
        path: { projectId: PROJECT.id },
        query: { revision: 3 },
        body: expect.objectContaining({ fileIds: [...PROJECT.fileIds, copy] }),
      }),
    ),
  );
  expect(copyChatLibraryFile).toHaveBeenCalledWith(
    expect.objectContaining({ path: { source: "GENERATED", id: file().id } }),
  );
  expect(await screen.findByText("Đã thêm vào dự án Kế hoạch.")).toBeInTheDocument();
});

it("takes an upload out of a project from its usage label without deleting it", async () => {
  await show();
  const user = userEvent.setup();
  getChatProject.mockResolvedValue({
    data: { ...PROJECT, fileIds: [upload.id, ...PROJECT.fileIds] },
  });
  updateChatProject.mockResolvedValue({ data: PROJECT });

  await user.click(screen.getByRole("button", { name: "Gỡ khỏi Kế hoạch" }));

  await waitFor(() =>
    expect(updateChatProject).toHaveBeenCalledWith(
      expect.objectContaining({ body: expect.objectContaining({ fileIds: PROJECT.fileIds }) }),
    ),
  );
  expect(deleteChatFile).not.toHaveBeenCalled();
  expect(await screen.findByText("Đã gỡ khỏi dự án Kế hoạch.")).toBeInTheDocument();
});

it("takes files straight into the library, showing each upload's progress", async () => {
  await show();
  const user = userEvent.setup();
  let report: ((value: number) => void) | undefined;
  uploadChatFile.mockImplementation(
    (_file, _id, signal: AbortSignal, progress: (value: number) => void) => {
      report = progress;
      return new Promise((_resolve, reject) =>
        signal.addEventListener("abort", () => reject(signal.reason), { once: true }),
      );
    },
  );

  await user.upload(
    screen.getByLabelText("Tải tệp lên thư viện"),
    new File(["nội dung"], "hop-dong.pdf", { type: "application/pdf" }),
  );

  const tray = await screen.findByRole("region", { name: "Tiến trình tải lên" });
  expect(within(tray).getByText("hop-dong.pdf")).toBeInTheDocument();
  expect(uploadChatFile).toHaveBeenCalledOnce();
  act(() => report?.(40));
  expect(await within(tray).findByText("40%")).toBeInTheDocument();
  // Cancelling one upload stops it and says so, without touching the others.
  await user.click(within(tray).getByRole("button", { name: "Huỷ tải hop-dong.pdf" }));
  expect(await within(tray).findByText("Đã huỷ")).toBeInTheDocument();
});

it("lists uploads still being processed separately, with retry", async () => {
  await show();
  const user = userEvent.setup();
  const failed = file({
    source: "UPLOAD",
    id: "88888888-8888-4888-8888-888888888888",
    filename: "bao-cao.pdf",
    category: "DOCUMENT",
    sessionId: null,
    sessionTitle: null,
    status: "FAILED",
    errorCode: "EXTRACTION_FAILED",
  });
  listChatLibrary.mockResolvedValue({
    data: { items: [failed], totalCount: 1, totalBytes: failed.sizeBytes, hasMore: false },
  });
  retryChatFile.mockResolvedValue({ data: undefined });

  await user.click(screen.getByRole("button", { name: "Đang xử lý" }));

  await waitFor(() =>
    expect(listChatLibrary).toHaveBeenLastCalledWith(
      expect.objectContaining({ query: expect.objectContaining({ status: "PENDING" }) }),
    ),
  );
  expect(await screen.findByText("Xử lý lỗi")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Thử lại" }));
  await waitFor(() =>
    expect(retryChatFile).toHaveBeenCalledWith(
      expect.objectContaining({ path: { fileId: failed.id } }),
    ),
  );
});

it("renames a file, keeping its extension, and stars it", async () => {
  await show();
  const user = userEvent.setup();
  changeChatLibraryFile.mockResolvedValue({
    data: { ...file(), filename: "Doanh thu quý 3.xlsx" },
  });

  await user.click(screen.getByRole("button", { name: "Đánh dấu yêu thích doanh-thu.xlsx" }));
  await waitFor(() =>
    expect(changeChatLibraryFile).toHaveBeenCalledWith(
      expect.objectContaining({
        path: { source: "GENERATED", id: file().id },
        body: { favorite: true },
      }),
    ),
  );

  await user.click(screen.getByRole("button", { name: "Thao tác với doanh-thu.xlsx" }));
  await user.click(await screen.findByRole("menuitem", { name: "Đổi tên" }));
  const dialog = await screen.findByRole("dialog");
  const input = within(dialog).getByRole("textbox", { name: "Tên tệp" });
  await user.clear(input);
  await user.type(input, "Doanh thu quý 3");
  await user.click(within(dialog).getByRole("button", { name: "Đổi tên" }));

  await waitFor(() =>
    expect(changeChatLibraryFile).toHaveBeenLastCalledWith(
      expect.objectContaining({ body: { filename: "Doanh thu quý 3" } }),
    ),
  );
});

it("searches inside files and shows the matching passages", async () => {
  await show();
  const user = userEvent.setup();
  searchChatLibraryContent.mockResolvedValue({
    data: [
      {
        file: file({ filename: "hop-dong.pdf", source: "UPLOAD", category: "DOCUMENT" }),
        passages: [{ text: "Điều khoản thanh toán trong 30 ngày", ordinal: 4 }],
      },
    ],
  });

  await user.click(screen.getByRole("radio", { name: "Nội dung" }));
  await user.type(screen.getByRole("textbox", { name: "Tìm trong nội dung tệp" }), "thanh toán");

  await waitFor(() =>
    expect(searchChatLibraryContent).toHaveBeenLastCalledWith(
      expect.objectContaining({ query: { query: "thanh toán" } }),
    ),
  );
  expect(await screen.findByText("hop-dong.pdf")).toBeInTheDocument();
  // The matched words are marked inside the passage.
  const marked = await screen.findByText("thanh toán", { selector: "mark" });
  expect(marked).toBeInTheDocument();
  // The name listing is not asked again while the content mode is open.
  expect(listChatLibrary).not.toHaveBeenCalledWith(
    expect.objectContaining({ query: expect.objectContaining({ query: "thanh toán" }) }),
  );
});

it("packs a selection into a ZIP, then downloads it and names what was skipped", async () => {
  await show();
  const user = userEvent.setup();
  const archiveId = "99999999-9999-4999-8999-999999999999";
  requestChatLibraryArchive.mockResolvedValue({
    data: {
      id: archiveId,
      status: "PENDING",
      fileCount: 2,
      sizeBytes: null,
      skipped: [],
      failure: null,
      createdAt: new Date().toISOString(),
      expiresAt: null,
    },
  });
  getChatLibraryArchive.mockResolvedValue({
    data: {
      id: archiveId,
      status: "READY",
      fileCount: 2,
      sizeBytes: 4096,
      skipped: ["ghi-chú.pdf"],
      failure: null,
      createdAt: new Date().toISOString(),
      expiresAt: new Date(Date.now() + 3600_000).toISOString(),
    },
  });
  const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});

  await user.click(screen.getByRole("checkbox", { name: "Chọn tất cả" }));
  await user.click(screen.getByRole("button", { name: "Tải về ZIP" }));

  expect(await screen.findByText(/Đang đóng gói 2 tệp/)).toBeInTheDocument();
  await waitFor(() =>
    expect(requestChatLibraryArchive).toHaveBeenCalledWith(
      expect.objectContaining({
        body: {
          files: [
            { source: "GENERATED", id: file().id },
            { source: "UPLOAD", id: upload.id },
          ],
        },
      }),
    ),
  );
  // The browser polls the archive every 1.5s, so this waits past one poll.
  expect(await screen.findByText(/ZIP đã sẵn sàng/, {}, { timeout: 5000 })).toBeInTheDocument();
  // The skipped file is named in the notice, not only in the list it came from.
  expect(screen.getByText(/Bỏ qua 1 tệp không còn khả dụng: ghi-chú\.pdf/)).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "Tải lại ZIP" })).toHaveAttribute(
    "href",
    `/api/chat/library/archives/${archiveId}/content`,
  );
  expect(click).toHaveBeenCalled();
  click.mockRestore();
});

it("shows what the library holds against its limit and links to the largest files", async () => {
  await show();
  const user = userEvent.setup();

  const bar = await screen.findByRole("region", { name: "Dung lượng đã dùng" });
  expect(within(bar).getByText(/3 KB/)).toBeInTheDocument();
  expect(within(bar).getByText(/10 KB/)).toBeInTheDocument();

  await user.click(within(bar).getByRole("button", { name: "Xem tệp lớn nhất" }));

  await waitFor(() =>
    expect(listChatLibrary).toHaveBeenLastCalledWith(
      expect.objectContaining({ query: expect.objectContaining({ sort: "LARGEST" }) }),
    ),
  );
});

it("says a deleted file goes to the trash, and restores or ends it from there", async () => {
  await show();
  const user = userEvent.setup();
  const trashed = file({
    filename: "da-xoa.xlsx",
    deletedAt: new Date().toISOString(),
    purgeAfter: new Date(Date.now() + 86_400_000).toISOString(),
  });
  restoreChatLibraryFile.mockResolvedValue({ data: undefined });
  purgeChatLibraryFile.mockResolvedValue({ data: undefined });
  emptyChatLibraryTrash.mockResolvedValue({ data: { purged: 3 } });

  // The confirmation says where the file goes, not that it is gone.
  await user.click(screen.getByRole("button", { name: "Thao tác với doanh-thu.xlsx" }));
  await user.click(await screen.findByRole("menuitem", { name: "Xoá" }));
  expect(await screen.findByText(/nằm trong thùng rác 30 ngày/)).toBeInTheDocument();
  await user.keyboard("{Escape}");

  listChatLibrary.mockResolvedValue({
    data: { items: [trashed], totalCount: 1, totalBytes: trashed.sizeBytes, hasMore: false },
  });
  await user.click(screen.getByRole("button", { name: "Thùng rác" }));

  await waitFor(() =>
    expect(listChatLibrary).toHaveBeenLastCalledWith(
      expect.objectContaining({
        query: expect.objectContaining({ status: "TRASH", sort: "DELETED" }),
      }),
    ),
  );
  expect(await screen.findByText(/Tệp đã xoá được giữ 30 ngày/)).toBeInTheDocument();

  await user.click(await screen.findByRole("button", { name: "Khôi phục" }));
  await waitFor(() =>
    expect(restoreChatLibraryFile).toHaveBeenCalledWith(
      expect.objectContaining({ path: { source: "GENERATED", id: trashed.id } }),
    ),
  );
  expect(await screen.findByText("Đã khôi phục da-xoa.xlsx.")).toBeInTheDocument();

  await user.click(screen.getByRole("button", { name: "Xoá vĩnh viễn da-xoa.xlsx" }));
  await user.click(
    within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Xoá vĩnh viễn" }),
  );
  await waitFor(() =>
    expect(purgeChatLibraryFile).toHaveBeenCalledWith(
      expect.objectContaining({ path: { source: "GENERATED", id: trashed.id } }),
    ),
  );

  await user.click(screen.getByRole("button", { name: "Dọn sạch thùng rác" }));
  await user.click(
    within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Dọn sạch" }),
  );
  await waitFor(() => expect(emptyChatLibraryTrash).toHaveBeenCalled());
  expect(await screen.findByText("Đã xoá vĩnh viễn 3 tệp.")).toBeInTheDocument();
});

it("asks for the favourites when the rail switches to them", async () => {
  await show();
  const user = userEvent.setup();

  await user.click(screen.getByRole("button", { name: "Yêu thích" }));

  await waitFor(() =>
    expect(listChatLibrary).toHaveBeenLastCalledWith(
      expect.objectContaining({ query: expect.objectContaining({ favorite: true, offset: 0 }) }),
    ),
  );
  // Favourites are a view, so the same thing is not also offered as a filter.
  await user.click(screen.getByRole("button", { name: "Bộ lọc" }));
  expect(
    within(await screen.findByRole("dialog")).queryByText("Chỉ tệp yêu thích"),
  ).not.toBeInTheDocument();
});

it("says what an empty view means and offers the way out of a filter", async () => {
  listChatLibrary.mockResolvedValue({
    data: { items: [], totalCount: 0, totalBytes: 0, hasMore: false },
  });
  await show([]);
  const user = userEvent.setup();

  expect(await screen.findByText("Thư viện đang trống")).toBeInTheDocument();

  await user.type(screen.getByRole("textbox", { name: "Tìm theo tên tệp" }), "khong-co");
  expect(await screen.findByText("Không có tệp nào khớp")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Xoá bộ lọc" }));
  expect(await screen.findByText("Thư viện đang trống")).toBeInTheDocument();

  await user.click(screen.getByRole("button", { name: "Thùng rác" }));
  expect(await screen.findByText("Thùng rác trống")).toBeInTheDocument();
});
