import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  createMemoryHistory,
  createRootRoute,
  createRouter,
  RouterProvider,
} from "@tanstack/react-router";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { ActionNotifications } from "@/components/ui/action-notifications";
import { i18n } from "@/i18n/index";
import type { ChatSession } from "@/lib/hey-api/types.gen";
import { ChatSessionRow } from "./chat-session-row";

const session: ChatSession = {
  id: "22222222-2222-4222-8222-222222222222",
  personaId: "33333333-3333-4333-8333-333333333333",
  rootMessageId: "66666666-6666-4666-8666-666666666666",
  title: "Báo cáo quý",
  createdAt: "2026-10-01T08:00:00Z",
  updatedAt: "2026-10-01T08:00:00Z",
  projectId: null,
  reasoningEffort: null,
  archivedAt: null,
  branchedFromSessionId: null,
  branchedFromMessageId: null,
  temporary: false,
};

async function startRenaming(rename: (title: string) => Promise<void>) {
  const rootRoute = createRootRoute({
    component: () => (
      <>
        <ChatSessionRow session={session} rename={rename} />
        <button type="button">Elsewhere</button>
      </>
    ),
  });
  const router = createRouter({
    routeTree: rootRoute,
    history: createMemoryHistory({ initialEntries: ["/"] }),
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
  const user = userEvent.setup();
  await user.click(await screen.findByRole("button", { name: "Thao tác hội thoại Báo cáo quý" }));
  await user.click(await screen.findByRole("menuitem", { name: "Đổi tên" }));
  return { user, field: await screen.findByRole("textbox", { name: "Tên hội thoại" }) };
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
});
afterEach(cleanup);

it("is a single name field without save or cancel buttons", async () => {
  const { field } = await startRenaming(vi.fn(async () => {}));
  expect(field).not.toBeRequired();
  expect(screen.queryByRole("button", { name: /Lưu|Hủy/ })).toBeNull();
});

it("saves the trimmed name once when Enter is followed by the blur of the closing field", async () => {
  let finish = () => {};
  const rename = vi.fn(() => new Promise<void>((resolve) => (finish = resolve)));
  const { user, field } = await startRenaming(rename);
  await user.clear(field);
  await user.type(field, "  Đã đổi tên  {Enter}");
  expect(field).toHaveAttribute("readonly");
  expect(field).toHaveAttribute("aria-busy", "true");
  await user.click(screen.getByRole("button", { name: "Elsewhere" }));
  finish();
  await waitFor(() => expect(screen.queryByRole("textbox")).toBeNull());
  expect(rename).toHaveBeenCalledTimes(1);
  expect(rename).toHaveBeenCalledWith("Đã đổi tên");
});

it("saves on blur and leaves focus where the person moved it", async () => {
  const rename = vi.fn(async () => {});
  const { user, field } = await startRenaming(rename);
  await user.type(field, " mới");
  await user.click(screen.getByRole("button", { name: "Elsewhere" }));
  await waitFor(() => expect(screen.queryByRole("textbox")).toBeNull());
  expect(rename).toHaveBeenCalledExactlyOnceWith("Báo cáo quý mới");
  expect(screen.getByRole("button", { name: "Elsewhere" })).toHaveFocus();
});

it("returns focus to the row when Enter saves from the field", async () => {
  const { user, field } = await startRenaming(vi.fn(async () => {}));
  await user.type(field, "!{Enter}");
  await waitFor(() => expect(screen.getByRole("link")).toHaveFocus());
});

it("closes without a request for an empty or unchanged name", async () => {
  const rename = vi.fn(async () => {});
  const { user, field } = await startRenaming(rename);
  await user.clear(field);
  await user.type(field, "   {Enter}");
  await waitFor(() => expect(screen.queryByRole("textbox")).toBeNull());
  await user.click(screen.getByRole("button", { name: "Thao tác hội thoại Báo cáo quý" }));
  await user.click(await screen.findByRole("menuitem", { name: "Đổi tên" }));
  await user.click(await screen.findByRole("textbox", { name: "Tên hội thoại" }));
  await user.click(screen.getByRole("button", { name: "Elsewhere" }));
  await waitFor(() => expect(screen.queryByRole("textbox")).toBeNull());
  expect(rename).not.toHaveBeenCalled();
});

it("discards the draft on Escape and returns focus to the row", async () => {
  const rename = vi.fn(async () => {});
  const { user, field } = await startRenaming(rename);
  await user.type(field, " bỏ");
  await user.keyboard("{Escape}");
  await waitFor(() => expect(screen.getByRole("link")).toHaveFocus());
  expect(screen.getByRole("link")).toHaveTextContent("Báo cáo quý");
  expect(rename).not.toHaveBeenCalled();
});

it("ignores Enter that confirms an IME candidate", async () => {
  const { field } = await startRenaming(vi.fn(async () => {}));
  expect(fireEvent.keyDown(field, { key: "Enter", isComposing: true })).toBe(false);
});

it("keeps the draft and the error after a failed save so the person can retry", async () => {
  const rename = vi.fn<(title: string) => Promise<void>>().mockRejectedValueOnce(new Error("down"));
  rename.mockResolvedValue();
  const { user, field } = await startRenaming(rename);
  await user.type(field, " lại{Enter}");
  expect(await screen.findByRole("alert")).toBeVisible();
  expect(field).toHaveValue("Báo cáo quý lại");
  expect(field).not.toHaveAttribute("readonly");
  await user.type(field, "{Enter}");
  await waitFor(() => expect(screen.queryByRole("textbox")).toBeNull());
  expect(rename).toHaveBeenCalledTimes(2);
});
