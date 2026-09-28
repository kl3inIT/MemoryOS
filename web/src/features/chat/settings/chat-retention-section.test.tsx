import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse } from "msw";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n/index";
import {
  handleGetChatRetention,
  handlePreviewChatRetention,
  handleSaveChatRetention,
} from "@/lib/hey-api/msw.gen";
import { server } from "@/test/msw";
import { ChatRetentionSection } from "./chat-retention-section";

/** What the server answers, and the requests it received. */
const answers = {
  policy: { days: null } as { days: number | null } | "failure",
  affected: 0,
};
const previewChatRetention = vi.fn<(call: { query: { days?: number } }) => void>();
const saveChatRetention = vi.fn<(call: { body: { days?: number } }) => void>();

function show() {
  server.use(
    handleGetChatRetention(() =>
      answers.policy === "failure"
        ? HttpResponse.json({ title: "nope" }, { status: 500 })
        : HttpResponse.json(answers.policy),
    ),
    handlePreviewChatRetention(({ request }) => {
      const days = new URL(request.url).searchParams.get("days");
      previewChatRetention({ query: { days: days === null ? undefined : Number(days) } });
      return HttpResponse.json({ days: Number(days), affected: answers.affected });
    }),
    handleSaveChatRetention(async ({ request }) => {
      const body = (await request.json()) as { days?: number };
      saveChatRetention({ body });
      return HttpResponse.json({ days: body.days ?? null });
    }),
  );
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
  answers.policy = { days: null };
  answers.affected = 0;
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

it("starts from no policy and saves the window the person picked", async () => {
  answers.affected = 2;
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

  await waitFor(() => expect(saveChatRetention).toHaveBeenCalledWith({ body: { days: 90 } }));
  expect(await screen.findByText("Đã lưu thiết lập tự xoá.")).toBeInTheDocument();
});

it("takes a number of days nobody offered as a preset", async () => {
  answers.policy = { days: 45 };
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
  answers.policy = { days: 30 };
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

  await waitFor(() => expect(saveChatRetention).toHaveBeenCalledWith({ body: {} }));
  // Nothing is previewed for "no policy": there is nothing to count.
  expect(previewChatRetention).not.toHaveBeenCalledWith({ query: { days: undefined } });
});

it("says so when the policy cannot be read", async () => {
  answers.policy = "failure";
  show();

  expect(await screen.findByText("Không tải được thiết lập tự xoá.")).toBeInTheDocument();
});
