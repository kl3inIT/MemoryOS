import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse } from "msw";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n/index";
import {
  handleClearChatMemory,
  handleGetChatMemory,
  handleSaveChatMemory,
  handleSetChatMemoryEnabled,
} from "@/lib/hey-api/msw.gen";
import { server } from "@/test/msw";
import { ChatMemorySection } from "./chat-memory-section";

type Memory = { content: string; enabled: boolean; revision: number; maxLength: number };

let stored: Memory;
const saveChatMemory = vi.fn<(body: { content: string; revision: number }) => void>();
const setEnabled = vi.fn<(body: { enabled: boolean }) => void>();
const clearChatMemory = vi.fn();

function show(conflict = false) {
  server.use(
    handleGetChatMemory(() => HttpResponse.json(stored)),
    handleSaveChatMemory(async ({ request }) => {
      const body = (await request.json()) as { content: string; revision: number };
      saveChatMemory(body);
      if (conflict) {
        stored = { ...stored, content: "Chat đã ghi điều mới.", revision: stored.revision + 1 };
        return HttpResponse.json({ status: 409, code: "CHAT_CONFLICT" }, { status: 409 });
      }
      stored = { ...stored, content: body.content, revision: stored.revision + 1 };
      return HttpResponse.json(stored);
    }),
    handleSetChatMemoryEnabled(async ({ request }) => {
      const body = (await request.json()) as { enabled: boolean };
      setEnabled(body);
      stored = { ...stored, enabled: body.enabled };
      return HttpResponse.json(stored);
    }),
    handleClearChatMemory(() => {
      clearChatMemory();
      stored = { ...stored, content: "", revision: stored.revision + 1 };
      return HttpResponse.json(stored);
    }),
  );
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <ChatMemorySection />
    </QueryClientProvider>,
  );
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  stored = { content: "Kế toán trưởng.", enabled: true, revision: 3, maxLength: 4000 };
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

it("saves the edited memory from the revision it was read at when the field loses focus", async () => {
  show();
  const user = userEvent.setup();

  const field = await screen.findByRole("textbox", { name: "Bộ nhớ" });
  expect(field).toHaveValue("Kế toán trưởng.");
  await user.clear(field);
  await user.type(field, "Kế toán trưởng, thích bảng.");
  await user.tab();

  await waitFor(() =>
    expect(saveChatMemory).toHaveBeenCalledWith({
      content: "Kế toán trưởng, thích bảng.",
      revision: 3,
    }),
  );
});

it("shows what Chat stored when the memory changed after the page read it", async () => {
  show(true);
  const user = userEvent.setup();

  const field = await screen.findByRole("textbox", { name: "Bộ nhớ" });
  await user.type(field, " Thêm.");
  await user.tab();

  expect(
    await screen.findByText("Bộ nhớ vừa được Chat cập nhật. Đã tải lại nội dung mới nhất."),
  ).toBeInTheDocument();
  await waitFor(() => expect(field).toHaveValue("Chat đã ghi điều mới."));
});

it("switches the memory off at once and clears it only after confirmation", async () => {
  show();
  const user = userEvent.setup();

  await user.click(await screen.findByRole("switch", { name: "Ghi nhớ về bạn" }));
  await waitFor(() => expect(setEnabled).toHaveBeenCalledWith({ enabled: false }));

  await user.click(screen.getByRole("button", { name: "Xóa bộ nhớ" }));
  expect(clearChatMemory).not.toHaveBeenCalled();
  await user.click(
    within(screen.getByRole("alertdialog")).getByRole("button", { name: "Xóa bộ nhớ" }),
  );
  await waitFor(() => expect(clearChatMemory).toHaveBeenCalledOnce());
  await waitFor(() => expect(screen.getByRole("textbox", { name: "Bộ nhớ" })).toHaveValue(""));
});
