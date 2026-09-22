import type { ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n";
import { ChatStoragePage } from "./chat-storage-page";

const loadLibraryUsage = vi.hoisted(() => vi.fn());
const loadTrashWindow = vi.hoisted(() => vi.fn());
const getChatRetention = vi.hoisted(() => vi.fn());
const previewChatRetention = vi.hoisted(() => vi.fn());

vi.mock("./chat-library", () => ({
  chatLibraryKey: ["chat-library"],
  loadLibraryUsage: (...args: unknown[]) => loadLibraryUsage(...args),
  loadTrashWindow: (...args: unknown[]) => loadTrashWindow(...args),
}));
vi.mock("@/lib/hey-api/sdk.gen", () => ({
  getChatRetention: (...args: unknown[]) => getChatRetention(...args),
  previewChatRetention: (...args: unknown[]) => previewChatRetention(...args),
  saveChatRetention: vi.fn(),
}));
vi.mock("@/features/identity/application-session-context", () => ({
  useApplicationSession: () => ({ actorId: "actor", authorizationVersion: 1, capabilities: [] }),
}));
vi.mock("@tanstack/react-router", () => ({
  Link: ({ children, to }: { children: ReactNode; to: string }) => <a href={to}>{children}</a>,
}));

function show() {
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <ChatStoragePage />
    </QueryClientProvider>,
  );
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  loadTrashWindow.mockResolvedValue(30);
  getChatRetention.mockResolvedValue({ data: { days: null } });
  previewChatRetention.mockResolvedValue({ data: { days: null, affected: 0 } });
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

it("shows what is used against the deployment limit, biggest kind first", async () => {
  loadLibraryUsage.mockResolvedValue({
    usedBytes: 20 * 1024 * 1024,
    fileCount: 81,
    limitBytes: 512 * 1024 * 1024,
    byCategory: [
      { category: "DOCUMENT", usedBytes: 802 * 1024 },
      { category: "IMAGE", usedBytes: 19 * 1024 * 1024 },
    ],
  });
  show();

  expect(await screen.findByText("Đã dùng 20 MB / 512 MB")).toBeInTheDocument();
  expect(screen.getByRole("progressbar", { name: "Dung lượng đã dùng" })).toHaveAttribute(
    "aria-valuenow",
    "4",
  );
  expect(screen.getByText("Đã dùng 4% · 81 tệp")).toBeInTheDocument();
  // The kind worth clearing comes first, and each row opens the library on that kind.
  const rows = screen.getAllByRole("listitem");
  expect(rows[0]).toHaveTextContent("Ảnh");
  expect(rows[1]).toHaveTextContent("Tài liệu");
  expect(rows[0]!.querySelector("a")).toHaveAttribute("href", "/library");
});

it("warns when the library is nearly full", async () => {
  loadLibraryUsage.mockResolvedValue({
    usedBytes: 500 * 1024 * 1024,
    fileCount: 12,
    limitBytes: 512 * 1024 * 1024,
    byCategory: [{ category: "IMAGE", usedBytes: 500 * 1024 * 1024 }],
  });
  show();

  expect(await screen.findByText("Gần hết dung lượng · 98% · hãy xoá bớt tệp")).toBeInTheDocument();
});

it("says there is no limit when the deployment sets none, and shows no meter with it", async () => {
  loadLibraryUsage.mockResolvedValue({
    usedBytes: 1024,
    fileCount: 1,
    limitBytes: null,
    byCategory: [],
  });
  show();

  expect(await screen.findByText("Đã dùng 1 KB")).toBeInTheDocument();
  expect(screen.getByText("Triển khai này không đặt giới hạn dung lượng.")).toBeInTheDocument();
  expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
  expect(screen.getByText("Thư viện của bạn chưa có tệp nào.")).toBeInTheDocument();
});

it("holds what the library's own panel holds: the trash window and the retention window", async () => {
  loadLibraryUsage.mockResolvedValue({
    usedBytes: 3072,
    fileCount: 2,
    limitBytes: 10240,
    byCategory: [{ category: "DOCUMENT", usedBytes: 3072 }],
  });
  show();

  expect(
    await screen.findByText("Tệp đã xoá được giữ 30 ngày rồi xoá vĩnh viễn."),
  ).toBeInTheDocument();
  // The one setting on this page belongs to the person, exactly as in the library panel.
  expect(await screen.findByRole("combobox", { name: "Xoá hội thoại sau" })).toBeInTheDocument();
  expect(screen.getByRole("heading", { name: "Tự xoá hội thoại" })).toBeInTheDocument();
});

it("offers a way to try again when the usage cannot be read", async () => {
  loadLibraryUsage.mockRejectedValue(new Error("nope"));
  show();

  expect(await screen.findByText("Không tải được dung lượng đã dùng.")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Thử lại" })).toBeInTheDocument();
});
