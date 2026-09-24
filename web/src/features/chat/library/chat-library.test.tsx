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
import { ActionNotifications } from "@/components/ui/action-notifications";
import { i18n } from "@/i18n/index";
import type { ChatLibraryFile } from "@/lib/hey-api/types.gen";
import { ChatLibraryPage, ChatStoragePage } from "./chat-library";

// What Chat adds to the library, exercised through the pages as the application composes them.

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
const getChatRetention = vi.hoisted(() => vi.fn());
const previewChatRetention = vi.hoisted(() => vi.fn());
const createChatSession = vi.hoisted(() => vi.fn());
const getChatImageArtifact = vi.hoisted(() => vi.fn());

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
  getChatRetention: (...args: unknown[]) => getChatRetention(...args),
  previewChatRetention: (...args: unknown[]) => previewChatRetention(...args),
  createChatSession: (...args: unknown[]) => createChatSession(...args),
  getChatImageArtifact: (...args: unknown[]) => getChatImageArtifact(...args),
  getChatFileArtifact: vi.fn(),
  downloadChatFile: vi.fn(),
  getChatFileArtifactPdfPreview: vi.fn(),
  previewChatFileArtifactSpreadsheet: vi.fn(),
  previewChatFileSpreadsheet: vi.fn(),
  getChatSession: vi.fn(),
  getChatHistory: vi.fn(),
}));

vi.mock("@/features/library/files", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/library/files")>()),
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
  // A question about a file opens a conversation, so the page can navigate to one.
  const chatRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: "/chat/$sessionId",
    component: () => null,
  });
  const router = createRouter({
    routeTree: rootRoute.addChildren([route, chatRoute]),
    history: createMemoryHistory({ initialEntries: ["/library"] }),
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
  getChatLibraryUsage.mockResolvedValue({
    data: { usedBytes: 3072, fileCount: 2, limitBytes: 10240, byCategory: [] },
  });
  getChatLibraryTrashWindow.mockResolvedValue({ data: { days: 30 } });
  getChatRetention.mockResolvedValue({ data: { days: null } });
  previewChatRetention.mockResolvedValue({ data: { days: null, affected: 0 } });
});
afterEach(cleanup);

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

it("asks Chat about the file being previewed, with the file attached to the question", async () => {
  const picture = file({
    source: "UPLOAD",
    id: "99999999-9999-4999-8999-999999999999",
    filename: "so-do.png",
    mediaType: "image/png",
    category: "IMAGE",
    sessionId: null,
    sessionTitle: null,
  });
  const router = await show([picture]);
  const user = userEvent.setup();
  getChatImageArtifact.mockResolvedValue({ data: new Blob(["png"], { type: "image/png" }) });
  globalThis.URL.createObjectURL = vi.fn(() => "blob:preview");
  globalThis.URL.revokeObjectURL = vi.fn();
  createChatSession.mockResolvedValue({
    data: { id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", title: "Sơ đồ này nói gì?" },
  });

  await user.click(screen.getByRole("button", { name: "so-do.png" }));
  await user.type(
    await screen.findByRole("textbox", { name: "Hỏi về so-do.png" }),
    "Sơ đồ này nói gì?",
  );
  await user.click(screen.getByRole("button", { name: "Hỏi trong Chat" }));

  await waitFor(() =>
    expect(router.state.location.pathname).toBe("/chat/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
  );
  expect(router.state.location.search).toEqual({
    ask: "Sơ đồ này nói gì?",
    attach: [picture.id],
  });
  expect(createChatSession).toHaveBeenCalledWith(
    expect.objectContaining({ body: expect.objectContaining({ title: "Sơ đồ này nói gì?" }) }),
  );
  // An upload is already the file Chat attaches, so nothing is copied for it.
  expect(copyChatLibraryFile).not.toHaveBeenCalled();
});

it("offers the person's conversation retention in the library panel", async () => {
  await show();
  const user = userEvent.setup();

  await user.click(screen.getByRole("button", { name: "Cài đặt thư viện" }));
  const panel = await screen.findByRole("dialog", { name: "Cài đặt thư viện" });
  // The one setting the panel offers belongs to the person, not to an administrator.
  expect(
    await within(panel).findByRole("combobox", { name: "Xoá hội thoại sau" }),
  ).toBeInTheDocument();
});

it("offers the same retention on the storage page", async () => {
  const rootRoute = createRootRoute();
  const route = createRoute({
    getParentRoute: () => rootRoute,
    path: "/settings/storage",
    component: () => <ChatStoragePage />,
  });
  const router = createRouter({
    routeTree: rootRoute.addChildren([route]),
    history: createMemoryHistory({ initialEntries: ["/settings/storage"] }),
  });
  await router.load();
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );

  // The storage page holds what the library panel holds, exactly as there.
  expect(await screen.findByRole("combobox", { name: "Xoá hội thoại sau" })).toBeInTheDocument();
  expect(screen.getByRole("heading", { name: "Tự xoá hội thoại" })).toBeInTheDocument();
});
