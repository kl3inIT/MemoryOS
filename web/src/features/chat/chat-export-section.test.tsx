import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n";
import type { ChatExport } from "@/lib/hey-api/types.gen";
import { ChatExportSection } from "./chat-export-section";

const listChatExports = vi.hoisted(() => vi.fn());
const requestChatExport = vi.hoisted(() => vi.fn());

vi.mock("@/lib/hey-api/sdk.gen", () => ({
  listChatExports: (...args: unknown[]) => listChatExports(...args),
  requestChatExport: (...args: unknown[]) => requestChatExport(...args),
}));
vi.mock("@/features/identity/application-session-context", () => ({
  useApplicationSession: () => ({ actorId: "actor", authorizationVersion: 1, capabilities: [] }),
}));

const ready: ChatExport = {
  id: "11111111-1111-4111-8111-111111111111",
  status: "READY",
  sessionCount: 3,
  fileCount: 2,
  skipped: ["qua-lon.pdf"],
  sizeBytes: 2048,
  failure: null,
  createdAt: "2026-09-21T09:00:00Z",
  expiresAt: "2026-09-22T09:00:00Z",
};

function show(exports: ChatExport[]) {
  listChatExports.mockResolvedValue({ data: exports });
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <ChatExportSection />
    </QueryClientProvider>,
  );
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  vi.clearAllMocks();
});
afterEach(cleanup);

it("asks for an export and offers the download when it is ready", async () => {
  show([ready]);
  const user = userEvent.setup();
  requestChatExport.mockResolvedValue({ data: { ...ready, status: "PENDING" } });

  expect(
    await screen.findByText("Bản xuất đã sẵn sàng: 3 hội thoại, 2 tệp · 2 KB"),
  ).toBeInTheDocument();
  // What an export left out is named, not hidden.
  expect(screen.getByText(/qua-lon\.pdf/)).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "Tải bản xuất" })).toHaveAttribute(
    "href",
    `/api/chat/exports/${ready.id}/content`,
  );

  await user.click(screen.getByRole("button", { name: "Xuất dữ liệu" }));
  await waitFor(() => expect(requestChatExport).toHaveBeenCalledTimes(1));
});

it("says an export is being packed and offers no download yet", async () => {
  show([{ ...ready, status: "RUNNING", sessionCount: null, fileCount: null, sizeBytes: null }]);

  expect(
    await screen.findByText("Bản xuất đang được đóng gói. Bạn có thể rời trang và quay lại sau."),
  ).toBeInTheDocument();
  expect(screen.queryByRole("link", { name: "Tải bản xuất" })).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Đang chuẩn bị…" })).toBeDisabled();
});

it("names the reason an export failed", async () => {
  show([{ ...ready, status: "FAILED", failure: "The export could not be packed." }]);

  expect(await screen.findByText("The export could not be packed.")).toBeInTheDocument();
});
