import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n";
import { ChatRetentionSection } from "./chat-retention-section";

const getChatRetention = vi.hoisted(() => vi.fn());
const previewChatRetention = vi.hoisted(() => vi.fn());
const saveChatRetention = vi.hoisted(() => vi.fn());

vi.mock("@/lib/hey-api/sdk.gen", () => ({
  getChatRetention: (...args: unknown[]) => getChatRetention(...args),
  previewChatRetention: (...args: unknown[]) => previewChatRetention(...args),
  saveChatRetention: (...args: unknown[]) => saveChatRetention(...args),
}));
vi.mock("@/features/identity/application-session-context", () => ({
  useApplicationSession: () => ({ actorId: "actor", authorizationVersion: 1, capabilities: [] }),
}));

function show() {
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <ChatRetentionSection />
    </QueryClientProvider>,
  );
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  getChatRetention.mockResolvedValue({ data: { days: null } });
  previewChatRetention.mockResolvedValue({ data: { days: 90, affected: 0 } });
  saveChatRetention.mockResolvedValue({ data: { days: 90 } });
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

it("starts from no policy and saves the window the person picked", async () => {
  previewChatRetention.mockResolvedValue({ data: { days: 90, affected: 2 } });
  show();
  const user = userEvent.setup();

  const control = await screen.findByRole("combobox", { name: "Xoá hội thoại sau" });
  expect(control).toHaveTextContent("Không tự xoá");
  // Nothing to save while nothing has changed.
  expect(screen.getByRole("button", { name: "Lưu thiết lập" })).toBeDisabled();

  await user.click(control);
  await user.click(await screen.findByRole("option", { name: "90 ngày" }));

  // What the number would delete is said before it can be saved, because deleting is not undoable.
  expect(await screen.findByText("2 hội thoại sẽ bị xoá khi lưu.")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Lưu thiết lập" }));
  expect(
    await screen.findByText("2 hội thoại đã quá hạn sẽ bị xoá ngay khi lưu. Không thể hoàn tác."),
  ).toBeInTheDocument();
  await user.click(
    within(screen.getByRole("alertdialog")).getByRole("button", { name: "Lưu thiết lập" }),
  );

  await waitFor(() =>
    expect(saveChatRetention).toHaveBeenCalledWith(expect.objectContaining({ body: { days: 90 } })),
  );
  expect(await screen.findByText("Đã lưu thiết lập tự xoá.")).toBeInTheDocument();
});

it("takes a number of days nobody offered as a preset", async () => {
  getChatRetention.mockResolvedValue({ data: { days: 45 } });
  previewChatRetention.mockResolvedValue({ data: { days: 7, affected: 0 } });
  show();
  const user = userEvent.setup();

  // A saved number that is not a preset opens the custom field on that number.
  const control = await screen.findByRole("combobox", { name: "Xoá hội thoại sau" });
  expect(control).toHaveTextContent("Số ngày khác…");
  expect(screen.getByLabelText("Số ngày không hoạt động")).toHaveValue(45);

  const days = screen.getByLabelText("Số ngày không hoạt động");
  await user.clear(days);
  await user.type(days, "0");
  expect(screen.getByText("Số ngày phải từ 1 đến 3650.")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Lưu thiết lập" })).toBeDisabled();

  await user.clear(days);
  await user.type(days, "7");
  expect(await screen.findByText("Không có hội thoại nào bị xoá ngay.")).toBeInTheDocument();
});

it("clears the policy by leaving the number out", async () => {
  getChatRetention.mockResolvedValue({ data: { days: 30 } });
  saveChatRetention.mockResolvedValue({ data: { days: null } });
  show();
  const user = userEvent.setup();

  const control = await screen.findByRole("combobox", { name: "Xoá hội thoại sau" });
  expect(control).toHaveTextContent("30 ngày");
  await user.click(control);
  await user.click(await screen.findByRole("option", { name: "Không tự xoá" }));

  await user.click(screen.getByRole("button", { name: "Lưu thiết lập" }));
  expect(await screen.findByText("Tắt tự xoá hội thoại?")).toBeInTheDocument();
  await user.click(
    within(screen.getByRole("alertdialog")).getByRole("button", { name: "Lưu thiết lập" }),
  );

  await waitFor(() =>
    expect(saveChatRetention).toHaveBeenCalledWith(expect.objectContaining({ body: {} })),
  );
  // Nothing is previewed for "no policy": there is nothing to count.
  expect(previewChatRetention).not.toHaveBeenCalledWith(
    expect.objectContaining({ query: { days: undefined } }),
  );
});

it("says so when the policy cannot be read", async () => {
  getChatRetention.mockRejectedValue(new Error("nope"));
  show();

  expect(await screen.findByText("Không tải được thiết lập tự xoá.")).toBeInTheDocument();
});
