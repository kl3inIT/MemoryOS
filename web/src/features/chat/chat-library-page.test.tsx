import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from "@tanstack/react-router";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
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
});
afterEach(cleanup);

it("lists every source with its size, total and originating conversation", async () => {
  await show();

  expect(screen.getByText("2 tệp · 3 KB")).toBeInTheDocument();
  const generated = screen.getByRole("row", { name: /doanh-thu\.xlsx/ });
  expect(within(generated).getByText("Do mã tạo")).toBeInTheDocument();
  expect(within(generated).getByRole("link", { name: "Mở hội thoại gốc" })).toHaveAttribute(
    "href",
    "/chat/22222222-2222-4222-8222-222222222222",
  );
  // An upload belongs to its owner rather than one conversation, so it offers no conversation link.
  const uploaded = screen.getByRole("row", { name: /ghi-chú\.pdf/ });
  expect(
    within(uploaded).queryByRole("link", { name: "Mở hội thoại gốc" }),
  ).not.toBeInTheDocument();
  expect(within(uploaded).getByText("Đang dùng trong Kế hoạch")).toBeInTheDocument();
  expect(within(uploaded).getByRole("button", { name: "Xoá ghi-chú.pdf" })).toBeDisabled();
});

it("sends the filters, the search and the sort to the server", async () => {
  await show();
  const user = userEvent.setup();

  await user.click(screen.getByRole("button", { name: "Ảnh AI" }));
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

  await user.click(screen.getByRole("button", { name: "Xoá ghi-chú.pdf" }));
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

  await user.click(screen.getByRole("button", { name: "Thêm doanh-thu.xlsx vào dự án" }));
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
