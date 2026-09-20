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

vi.mock("@/lib/hey-api/sdk.gen", () => ({
  listChatLibrary: (...args: unknown[]) => listChatLibrary(...args),
  deleteChatFile: (...args: unknown[]) => deleteChatFile(...args),
  deleteChatFileArtifact: (...args: unknown[]) => deleteChatFileArtifact(...args),
  deleteChatImageArtifact: (...args: unknown[]) => deleteChatImageArtifact(...args),
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
