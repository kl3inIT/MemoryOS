import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
  stripSearchParams,
} from "@tanstack/react-router";
import { act, cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { ActionNotifications } from "@/components/ui/action-notifications";
import { i18n } from "@/i18n/index";
import { HttpResponse } from "msw";
import {
  handleChangeChatLibraryFile,
  handleDeleteChatFile,
  handleDeleteChatFileArtifact,
  handleDeleteChatImageArtifact,
  handleEmptyChatLibraryTrash,
  handleGetChatLibraryArchive,
  handleGetChatLibraryTrashWindow,
  handleGetChatLibraryUsage,
  handleListChatLibrary,
  handlePurgeChatLibraryFile,
  handleRequestChatLibraryArchive,
  handleRestoreChatLibraryFile,
  handleRetryChatFile,
  handleSearchChatLibraryContent,
} from "@/lib/hey-api/msw.gen";
import type { ChatLibraryFile } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { LibraryPage } from "./library-page";
import { librarySearchDefaults, librarySearchSchema } from "./library-search";

const uploadChatFile = vi.hoisted(() => vi.fn());

vi.mock("./files", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./files")>()),
  uploadChatFile: (...args: unknown[]) => uploadChatFile(...args),
}));

/** A request's query as one object: a repeated parameter as its list, a single one as its value. */
function queryOf(request: Request) {
  const params = new URL(request.url).searchParams;
  return Object.fromEntries(
    [...new Set(params.keys())].map((key) => {
      const values = params.getAll(key);
      return [key, values.length > 1 ? values : values[0]];
    }),
  );
}

/** Every listing the page asked for, in order. */
let listed: Record<string, unknown>[] = [];
const lastListed = () => listed.at(-1);

/** Answers every listing with these files. */
function listing(items: ChatLibraryFile[], hasMore = false) {
  server.use(
    handleListChatLibrary(({ request }) => {
      listed.push(queryOf(request));
      return HttpResponse.json({
        items,
        totalCount: hasMore ? 60 : items.length,
        totalBytes: items.reduce((total, item) => total + item.sizeBytes, 0),
        hasMore,
      });
    }),
  );
}

const noContent = () => new HttpResponse(null, { status: 204 });

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

async function show(
  items: ChatLibraryFile[] = [file(), upload],
  hasMore = false,
  path = "/library",
) {
  const first = items[0];
  listing(items, hasMore);
  const rootRoute = createRootRoute();
  const authenticatedRoute = createRoute({ getParentRoute: () => rootRoute, id: "_authenticated" });
  const route = createRoute({
    getParentRoute: () => authenticatedRoute,
    path: "library",
    validateSearch: librarySearchSchema,
    search: { middlewares: [stripSearchParams(librarySearchDefaults)] },
    component: () => <LibraryPage />,
  });
  // The original conversation of a file is a link, so the page can navigate to one.
  const chatRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: "/chat/$sessionId",
    component: () => null,
  });
  const router = createRouter({
    routeTree: rootRoute.addChildren([authenticatedRoute.addChildren([route]), chatRoute]),
    history: createMemoryHistory({ initialEntries: [path] }),
  });
  await router.load();
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <ActionNotifications>
        <RouterProvider router={router} />
      </ActionNotifications>
    </QueryClientProvider>,
  );
  if (first) await screen.findByText(first.filename);
  return router;
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  vi.clearAllMocks();
  listed = [];
  server.use(
    handleGetChatLibraryUsage({
      body: { usedBytes: 3072, fileCount: 2, limitBytes: 10240, byCategory: [] },
    }),
    handleGetChatLibraryTrashWindow({ body: { days: 30 } }),
  );
});
afterEach(cleanup);

/** A file's row: the redesign renders each file as a list item rather than a table row. */
const row = (name: string) => screen.getByText(name).closest("li") as HTMLElement;

it("lists every source with its size, total and originating conversation", async () => {
  await show();

  expect(screen.queryByText("2 tệp · 3 KB")).not.toBeInTheDocument();
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
  await user.click(screen.getByRole("combobox", { name: "Sắp xếp" }));
  await user.click(await screen.findByRole("option", { name: "Dung lượng giảm dần" }));

  await waitFor(() =>
    expect(lastListed()).toMatchObject({
      query: "doanh",
      sources: "IMAGE",
      sort: "LARGEST",
      offset: "0",
    }),
  );
});

it("opens the list the address names and keeps a changed order in it, leaving defaults out", async () => {
  const router = await show([file(), upload], false, "/library?category=IMAGE&view=ready");
  const user = userEvent.setup();
  expect(lastListed()).toMatchObject({ categories: "IMAGE", offset: "0" });

  await user.click(screen.getByRole("combobox", { name: "Sắp xếp" }));
  await user.click(await screen.findByRole("option", { name: "Dung lượng giảm dần" }));

  await waitFor(() =>
    expect(router.state.location.search).toEqual({ category: ["IMAGE"], sort: "LARGEST" }),
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
  const deletedArtifacts: string[] = [];
  const deletedImages: string[] = [];
  server.use(
    handleDeleteChatFileArtifact(({ params }) => {
      deletedArtifacts.push(params.artifactId);
      return HttpResponse.json({ code: "REFUSED" }, { status: 500 });
    }),
    handleDeleteChatImageArtifact(({ params }) => {
      deletedImages.push(params.artifactId);
      return noContent();
    }),
  );

  await user.click(screen.getByRole("checkbox", { name: "Chọn tất cả trên trang này" }));
  await user.click(screen.getByRole("button", { name: "Xoá" }));
  // The dialog's own confirm button, not the toolbar one that opened it.
  await user.click(
    within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Xoá" }),
  );

  await waitFor(() => expect(deletedImages).toEqual([image.id]));
  expect(deletedArtifacts).toEqual([file().id]);
  // The refused file is named; the image beside it was still deleted.
  expect(await screen.findByText(/doanh-thu\.xlsx:/)).toBeInTheDocument();
});

it("names the project holding an upload when the server refuses the deletion", async () => {
  const held = { ...upload, deletable: true, usedBy: [] };
  await show([held]);
  const user = userEvent.setup();
  server.use(
    handleDeleteChatFile(() =>
      HttpResponse.json(
        { code: "CHAT_FILE_IN_USE", usedBy: [{ kind: "PROJECT", id: "p", name: "Kế hoạch" }] },
        { status: 409 },
      ),
    ),
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

  await user.click(screen.getByRole("checkbox", { name: "Chọn tất cả trên trang này" }));
  expect(screen.getByText("Đã chọn 1 tệp")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Trang sau" }));

  // The bar plays its exit before it goes, so the selection is dropped a frame before the DOM says so.
  await waitFor(() => expect(screen.queryByText("Đã chọn 1 tệp")).not.toBeInTheDocument());
  // The page after the one shown is prefetched, so the request to assert is the page itself, not the last.
  await waitFor(() => expect(listed).toContainEqual(expect.objectContaining({ offset: "50" })));
});

it("chooses one day at its heading and leaves the other days alone", async () => {
  const yesterday = new Date();
  yesterday.setDate(yesterday.getDate() - 1);
  const older = file({
    id: "66666666-6666-4666-8666-666666666666",
    filename: "bảng-lương.xlsx",
    createdAt: yesterday.toISOString(),
  });
  await show([file(), upload, older]);
  const user = userEvent.setup();

  await user.click(screen.getByRole("checkbox", { name: "Chọn tất cả Hôm nay" }));

  // Today holds the two files the server returned first; yesterday's file is untouched.
  expect(screen.getByText("Đã chọn 2 tệp")).toBeInTheDocument();
  expect(screen.getByRole("checkbox", { name: "Chọn tất cả Hôm qua" })).toHaveAttribute(
    "data-state",
    "unchecked",
  );
  // The page's own box says part of the page, not all of it.
  expect(screen.getByRole("checkbox", { name: "Chọn tất cả trên trang này" })).toHaveAttribute(
    "data-state",
    "indeterminate",
  );

  await user.click(screen.getByRole("checkbox", { name: "Chọn tất cả Hôm nay" }));
  await waitFor(() => expect(screen.queryByText("Đã chọn 2 tệp")).not.toBeInTheDocument());
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
  listing([failed]);
  const retried: string[] = [];
  server.use(
    handleRetryChatFile(({ params }) => {
      retried.push(params.fileId);
      return noContent();
    }),
  );

  await user.click(screen.getByRole("button", { name: "Đang xử lý" }));

  await waitFor(() => expect(lastListed()).toMatchObject({ status: "PENDING" }));
  expect(await screen.findByText("Xử lý lỗi")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Thử lại" }));
  await waitFor(() => expect(retried).toEqual([failed.id]));
});

it("renames a file, keeping its extension, and stars it", async () => {
  await show();
  const user = userEvent.setup();
  const changes: { path: unknown; body: unknown }[] = [];
  server.use(
    handleChangeChatLibraryFile(async ({ params, request }) => {
      const body = await request.json();
      changes.push({ path: { source: params.source, id: params.id }, body });
      return HttpResponse.json({ ...file(), ...body });
    }),
  );

  await user.click(screen.getByRole("button", { name: "Đánh dấu yêu thích doanh-thu.xlsx" }));
  await waitFor(() =>
    expect(changes).toEqual([
      { path: { source: "GENERATED", id: file().id }, body: { favorite: true } },
    ]),
  );

  await user.click(screen.getByRole("button", { name: "Thao tác với doanh-thu.xlsx" }));
  await user.click(await screen.findByRole("menuitem", { name: "Đổi tên" }));
  const dialog = await screen.findByRole("dialog");
  const input = within(dialog).getByRole("textbox", { name: "Tên tệp" });
  await user.clear(input);
  await user.type(input, "Doanh thu quý 3");
  await user.click(within(dialog).getByRole("button", { name: "Đổi tên" }));

  await waitFor(() =>
    expect(changes.at(-1)).toMatchObject({ body: { filename: "Doanh thu quý 3" } }),
  );
});

it("says so when starring a file fails", async () => {
  await show();
  const user = userEvent.setup();
  server.use(handleChangeChatLibraryFile(() => HttpResponse.error()));

  await user.click(screen.getByRole("button", { name: "Đánh dấu yêu thích doanh-thu.xlsx" }));

  expect(
    await screen.findByText("Chưa xác nhận được kết quả. Tải lại để kiểm tra trước khi thử lại."),
  ).toBeInTheDocument();
});

it("searches inside files and shows the matching passages", async () => {
  await show();
  const user = userEvent.setup();
  const searched: unknown[] = [];
  server.use(
    handleSearchChatLibraryContent(({ request }) => {
      searched.push(queryOf(request));
      return HttpResponse.json([
        {
          file: file({ filename: "hop-dong.pdf", source: "UPLOAD", category: "DOCUMENT" }),
          passages: [{ text: "Điều khoản thanh toán trong 30 ngày", ordinal: 4 }],
        },
      ]);
    }),
  );

  await user.click(screen.getByRole("radio", { name: "Nội dung" }));
  await user.type(screen.getByRole("textbox", { name: "Tìm trong nội dung tệp" }), "thanh toán");

  await waitFor(() => expect(searched.at(-1)).toEqual({ query: "thanh toán" }));
  expect(await screen.findByText("hop-dong.pdf")).toBeInTheDocument();
  // The matched words are marked inside the passage.
  const marked = await screen.findByText("thanh toán", { selector: "mark" });
  expect(marked).toBeInTheDocument();
  // The name listing is not asked again while the content mode is open.
  expect(listed).not.toContainEqual(expect.objectContaining({ query: "thanh toán" }));
});

it("packs a selection into a ZIP, then downloads it and names what was skipped", async () => {
  await show();
  const user = userEvent.setup();
  const archiveId = "99999999-9999-4999-8999-999999999999";
  const requested: unknown[] = [];
  server.use(
    handleRequestChatLibraryArchive(async ({ request }) => {
      requested.push(await request.json());
      return HttpResponse.json({
        id: archiveId,
        status: "PENDING",
        fileCount: 2,
        sizeBytes: null,
        skipped: [],
        failure: null,
        createdAt: new Date().toISOString(),
        expiresAt: null,
      });
    }),
    handleGetChatLibraryArchive({
      body: {
        id: archiveId,
        status: "READY",
        fileCount: 2,
        sizeBytes: 4096,
        skipped: ["ghi-chú.pdf"],
        failure: null,
        createdAt: new Date().toISOString(),
        expiresAt: new Date(Date.now() + 3600_000).toISOString(),
      },
    }),
  );
  const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});

  await user.click(screen.getByRole("checkbox", { name: "Chọn tất cả trên trang này" }));
  await user.click(screen.getByRole("button", { name: "Tải về ZIP" }));

  expect(await screen.findByText(/Đang đóng gói 2 tệp/)).toBeInTheDocument();
  await waitFor(() =>
    expect(requested).toEqual([
      {
        files: [
          { source: "GENERATED", id: file().id },
          { source: "UPLOAD", id: upload.id },
        ],
      },
    ]),
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

  await waitFor(() => expect(lastListed()).toMatchObject({ sort: "LARGEST" }));
});

it("says a deleted file goes to the trash, and restores or ends it from there", async () => {
  await show();
  const user = userEvent.setup();
  const trashed = file({
    filename: "da-xoa.xlsx",
    deletedAt: new Date().toISOString(),
    purgeAfter: new Date(Date.now() + 86_400_000).toISOString(),
  });
  const restored: unknown[] = [];
  const purged: unknown[] = [];
  let emptied = false;
  server.use(
    handleRestoreChatLibraryFile(({ params }) => {
      restored.push({ source: params.source, id: params.id });
      return noContent();
    }),
    handlePurgeChatLibraryFile(({ params }) => {
      purged.push({ source: params.source, id: params.id });
      return noContent();
    }),
    handleEmptyChatLibraryTrash(() => {
      emptied = true;
      return HttpResponse.json({ purged: 3 });
    }),
  );

  // The confirmation says where the file goes, not that it is gone.
  await user.click(screen.getByRole("button", { name: "Thao tác với doanh-thu.xlsx" }));
  await user.click(await screen.findByRole("menuitem", { name: "Xoá" }));
  expect(await screen.findByText(/nằm trong thùng rác 30 ngày/)).toBeInTheDocument();
  await user.keyboard("{Escape}");

  listing([trashed]);
  await user.click(screen.getByRole("button", { name: "Thùng rác" }));

  await waitFor(() => expect(lastListed()).toMatchObject({ status: "TRASH", sort: "DELETED" }));
  expect(await screen.findByText(/Tệp đã xoá được giữ 30 ngày/)).toBeInTheDocument();

  await user.click(await screen.findByRole("button", { name: "Khôi phục" }));
  await waitFor(() => expect(restored).toEqual([{ source: "GENERATED", id: trashed.id }]));
  expect(await screen.findByText("Đã khôi phục da-xoa.xlsx.")).toBeInTheDocument();

  await user.click(screen.getByRole("button", { name: "Xoá vĩnh viễn da-xoa.xlsx" }));
  await user.click(
    within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Xoá vĩnh viễn" }),
  );
  await waitFor(() => expect(purged).toEqual([{ source: "GENERATED", id: trashed.id }]));

  await user.click(screen.getByRole("button", { name: "Dọn sạch thùng rác" }));
  await user.click(
    within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Dọn sạch" }),
  );
  await waitFor(() => expect(emptied).toBe(true));
  expect(await screen.findByText("Đã xoá vĩnh viễn 3 tệp.")).toBeInTheDocument();
});

it("asks for the favourites when the rail switches to them", async () => {
  await show();
  const user = userEvent.setup();

  await user.click(screen.getByRole("button", { name: "Yêu thích" }));

  await waitFor(() => expect(lastListed()).toMatchObject({ favorite: "true", offset: "0" }));
  // Favourites are a view, so the same thing is not also offered as a filter.
  await user.click(screen.getByRole("button", { name: "Bộ lọc" }));
  expect(
    within(await screen.findByRole("dialog")).queryByText("Chỉ tệp yêu thích"),
  ).not.toBeInTheDocument();
});

it("says what an empty view means and offers the way out of a filter", async () => {
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

it("keeps the library's own settings on the library: what is stored and how long it stays in the trash", async () => {
  server.use(
    handleGetChatLibraryUsage({
      body: {
        usedBytes: 3072,
        fileCount: 2,
        limitBytes: 10240,
        byCategory: [
          { category: "IMAGE", usedBytes: 2048 },
          { category: "DOCUMENT", usedBytes: 1024 },
        ],
      },
    }),
  );
  await show();
  const user = userEvent.setup();

  await user.click(screen.getByRole("button", { name: "Cài đặt thư viện" }));
  const panel = await screen.findByRole("dialog", { name: "Cài đặt thư viện" });
  // The meter, what the deployment allows, and the trash window are read here rather than in an admin page.
  expect(within(panel).getByText(/3 KB \/ 10 KB/)).toBeInTheDocument();
  expect(
    within(panel).getByText("Tệp đã xoá được giữ 30 ngày rồi xoá vĩnh viễn."),
  ).toBeInTheDocument();
  // Without Chat the panel has no conversation retention to offer.
  expect(within(panel).queryByRole("combobox", { name: "Xoá hội thoại sau" })).toBeNull();

  // A kind of file narrows the list already behind the panel instead of navigating anywhere.
  await user.click(within(panel).getByRole("button", { name: /Ảnh/ }));

  await waitFor(() => expect(lastListed()).toMatchObject({ categories: "IMAGE", offset: "0" }));
});

it("cuts the list into pages of the chosen size, from its first page", async () => {
  await show([file()], true);
  const user = userEvent.setup();

  await user.click(screen.getByRole("button", { name: "Trang sau" }));
  await waitFor(() =>
    expect(listed).toContainEqual(expect.objectContaining({ offset: "50", limit: "50" })),
  );

  await user.click(screen.getByRole("combobox", { name: "Số tệp mỗi trang" }));
  await user.click(await screen.findByRole("option", { name: "12" }));

  await waitFor(() =>
    expect(listed).toContainEqual(expect.objectContaining({ offset: "0", limit: "12" })),
  );
  expect(screen.getByText("Hiển thị 1–12 trên 60 tệp")).toBeInTheDocument();
});
