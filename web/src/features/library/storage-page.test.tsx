import type { ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen } from "@testing-library/react";
import { HttpResponse } from "msw";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n/index";
import { handleGetChatLibraryTrashWindow, handleGetChatLibraryUsage } from "@/lib/hey-api/msw.gen";
import type { ChatLibraryUsage } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { StoragePage } from "./storage-page";

const usage = (body: ChatLibraryUsage) => server.use(handleGetChatLibraryUsage({ body }));

vi.mock("@tanstack/react-router", () => ({
  Link: ({ children, to }: { children: ReactNode; to: string }) => <a href={to}>{children}</a>,
}));

function show(retention?: ReactNode) {
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <StoragePage retention={retention} />
    </QueryClientProvider>,
  );
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  server.use(handleGetChatLibraryTrashWindow({ body: { days: 30 } }));
});
afterEach(() => {
  cleanup();
});

it("shows what is used against the deployment limit, biggest kind first", async () => {
  usage({
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
  // The bar is one slice per kind rather than a single progress track, and names what each slice holds.
  expect(screen.getByRole("img", { name: "Ảnh: 19 MB, Tài liệu: 802 KB" })).toBeInTheDocument();
  expect(screen.getByText("Đã dùng 4% · 81 tệp")).toBeInTheDocument();
  // The kind worth clearing comes first, and each row opens the library on that kind.
  const rows = screen.getAllByRole("listitem");
  expect(rows[0]).toHaveTextContent("Ảnh");
  expect(rows[1]).toHaveTextContent("Tài liệu");
  expect(rows[0]!.querySelector("a")).toHaveAttribute("href", "/library");
});

it("warns when the library is nearly full", async () => {
  usage({
    usedBytes: 500 * 1024 * 1024,
    fileCount: 12,
    limitBytes: 512 * 1024 * 1024,
    byCategory: [{ category: "IMAGE", usedBytes: 500 * 1024 * 1024 }],
  });
  show();

  expect(await screen.findByText("Gần hết dung lượng · 98% · hãy xoá bớt tệp")).toBeInTheDocument();
});

it("says there is no limit when the deployment sets none, and shows no meter with it", async () => {
  usage({
    usedBytes: 1024,
    fileCount: 1,
    limitBytes: null,
    byCategory: [],
  });
  show();

  expect(await screen.findByText("Đã dùng 1 KB")).toBeInTheDocument();
  expect(
    screen.getByText("Triển khai này không đặt giới hạn dung lượng · 1 tệp"),
  ).toBeInTheDocument();
  expect(screen.getByText("Thư viện của bạn chưa có tệp nào.")).toBeInTheDocument();
});

it("holds what the library's own panel holds: the trash window and the retention Chat supplies", async () => {
  usage({
    usedBytes: 3072,
    fileCount: 2,
    limitBytes: 10240,
    byCategory: [{ category: "DOCUMENT", usedBytes: 3072 }],
  });
  show(<section aria-label="Tự xoá hội thoại" />);

  expect(
    await screen.findByText("Tệp đã xoá được giữ 30 ngày rồi xoá vĩnh viễn."),
  ).toBeInTheDocument();
  expect(screen.getByRole("region", { name: "Tự xoá hội thoại" })).toBeInTheDocument();
});

it("offers a way to try again when the usage cannot be read", async () => {
  server.use(
    handleGetChatLibraryUsage(() => HttpResponse.json({ code: "UNAVAILABLE" }, { status: 503 })),
  );
  show();

  expect(await screen.findByText("Không tải được dung lượng đã dùng.")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Thử lại" })).toBeInTheDocument();
});
