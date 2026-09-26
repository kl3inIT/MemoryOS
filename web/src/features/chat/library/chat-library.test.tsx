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
import { HttpResponse } from "msw";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { ActionNotifications } from "@/components/ui/action-notifications";
import { i18n } from "@/i18n/index";
import {
  handleCopyChatLibraryFile,
  handleCreateChatSession,
  handleDownloadChatFile,
  handleGetChatVoiceAvailability,
  handleGetChatLibraryTrashWindow,
  handleGetChatLibraryUsage,
  handleGetChatProject,
  handleGetChatRetention,
  handleListChatLibrary,
  handleListAvailableChatModels,
  handleListChatProjects,
  handlePreviewChatRetention,
  handleUpdateChatProject,
} from "@/lib/hey-api/msw.gen";
import type { ChatLibraryFile, ProjectInput } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { ChatLibraryPage, ChatStoragePage } from "./chat-library";

// What Chat adds to the library, exercised through the pages as the application composes them.

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
  server.use(
    handleListChatLibrary({
      body: {
        items,
        totalCount: hasMore ? 60 : items.length,
        totalBytes: items.reduce((total, item) => total + item.sizeBytes, 0),
        hasMore,
      },
    }),
  );
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
  server.use(
    handleGetChatLibraryUsage({
      body: { usedBytes: 3072, fileCount: 2, limitBytes: 10240, byCategory: [] },
    }),
    handleGetChatLibraryTrashWindow({ body: { days: 30 } }),
    handleGetChatRetention({ body: { days: null } }),
    handlePreviewChatRetention({ body: { days: null, affected: 0 } }),
  );
});

/** Records each Project update and answers with the Project. */
function recordProjectUpdates() {
  const updates: { projectId: string; revision: string | null; body: ProjectInput }[] = [];
  server.use(
    handleUpdateChatProject(async ({ params, request }) => {
      updates.push({
        projectId: params.projectId,
        revision: new URL(request.url).searchParams.get("revision"),
        body: await request.json(),
      });
      return HttpResponse.json(PROJECT);
    }),
  );
  return updates;
}
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
  const copied: Record<string, string>[] = [];
  server.use(
    handleListChatProjects({ body: [PROJECT] }),
    handleGetChatProject({ body: PROJECT }),
    handleCopyChatLibraryFile(({ params }) => {
      copied.push({ ...params });
      return HttpResponse.json({
        id: copy,
        filename: "doanh-thu.xlsx",
        mediaType: "text/csv",
        sizeBytes: 2048,
        status: "READY",
      });
    }),
  );
  const updates = recordProjectUpdates();

  await user.click(screen.getByRole("button", { name: "Thao tác với doanh-thu.xlsx" }));
  await user.click(await screen.findByRole("menuitem", { name: "Thêm vào dự án" }));
  const dialog = await screen.findByRole("dialog");
  await within(dialog).findByRole("option", { name: "Kế hoạch (1/20)" });
  await user.click(within(dialog).getByRole("button", { name: "Thêm vào dự án" }));

  await waitFor(() =>
    expect(updates).toEqual([
      expect.objectContaining({
        projectId: PROJECT.id,
        revision: "3",
        body: expect.objectContaining({ fileIds: [...PROJECT.fileIds, copy] }),
      }),
    ]),
  );
  expect(copied).toEqual([expect.objectContaining({ id: file().id })]);
  expect(await screen.findByText("Đã thêm vào dự án Kế hoạch.")).toBeInTheDocument();
});

it("takes an upload out of a project from its usage label without deleting it", async () => {
  await show();
  const user = userEvent.setup();
  server.use(
    handleGetChatProject({ body: { ...PROJECT, fileIds: [upload.id, ...PROJECT.fileIds] } }),
  );
  const updates = recordProjectUpdates();

  await user.click(screen.getByRole("button", { name: "Gỡ khỏi Kế hoạch" }));

  // Only the link goes: no delete is handled, so a delete request would fail the test.
  await waitFor(() =>
    expect(updates).toEqual([
      expect.objectContaining({ body: expect.objectContaining({ fileIds: PROJECT.fileIds }) }),
    ]),
  );
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
  globalThis.URL.createObjectURL = vi.fn(() => "blob:preview");
  globalThis.URL.revokeObjectURL = vi.fn();
  const created: unknown[] = [];
  server.use(
    // A jsdom Blob would reach MSW as text, so the bytes are given as a string.
    handleDownloadChatFile(
      () => new HttpResponse("png", { headers: { "Content-Type": "image/png" } }),
    ),
    handleListAvailableChatModels({ body: [] }),
    handleGetChatVoiceAvailability({ body: { sttAvailable: false, ttsAvailable: false } }),
    handleCreateChatSession(async ({ request }) => {
      created.push(await request.json());
      return HttpResponse.json(
        { id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", title: "Sơ đồ này nói gì?" },
        { status: 201 },
      );
    }),
  );

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
  // An upload is already the file Chat attaches, so nothing is copied (no copy is handled).
  expect(created).toEqual([expect.objectContaining({ title: "Sơ đồ này nói gì?" })]);
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
