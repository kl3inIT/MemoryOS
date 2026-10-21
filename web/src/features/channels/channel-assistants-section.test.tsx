import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vitest";
import { i18n } from "@/i18n/index";
import {
  handleAllowChannelGroup,
  handleForgetChannelGroup,
  handleLabelChannelGroup,
  handleListChannelGroups,
  handleListChatPersonas,
} from "@/lib/hey-api/msw.gen";
import type { ChannelGroupResponse, ChannelResponse, PersonaView } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { ChannelAssistantsSection } from "./channel-assistants-section";

const TAX = "70000000-0000-4000-8000-000000000011";
const channel = (provider: ChannelResponse["provider"]): ChannelResponse => ({
  id: "5d1f6c2e-8a1b-4c3d-9e4f-0a1b2c3d4e5f",
  provider,
  name: "Hỏi đáp Tài chính",
  appId: provider === "LARK" ? "cli_b7c3e9f1a2d4c6e8" : "1234567890",
  noticeLanguage: "vi",
  enabled: true,
  publicAccess: false,
  serviceAccountId: "9e8d7c6b-5a49-4382-9a1b-c0d1e2f3a4b5",
  missingScopes: [],
  status: "CONNECTED",
  revision: 2,
  createdAt: "2026-10-13T09:00:00Z",
  updatedAt: "2026-10-13T09:10:00Z",
});
const persona = (id: string, name: string, builtin = false): PersonaView => ({
  id,
  revision: 1,
  builtin,
  name,
  permissions: { edit: !builtin },
});
const group = (overrides: Partial<ChannelGroupResponse>): ChannelGroupResponse => ({
  id: "oc_a1b2c3d4e5f60718293a4b5c6d7e8f90",
  firstMessageAt: "2026-10-10T02:00:00Z",
  lastMessageAt: new Date(Date.now() - 10 * 60_000).toISOString(),
  permission: "NOT_NEEDED",
  ...overrides,
});

function renderSection(provider: ChannelResponse["provider"], groups: ChannelGroupResponse[]) {
  server.use(
    handleListChatPersonas({
      body: [
        persona("70000000-0000-4000-8000-000000000000", "MemoryOS", true),
        persona(TAX, "Kế toán thuế"),
      ],
    }),
    handleListChannelGroups({ body: groups }),
  );
  render(
    <QueryClientProvider client={new QueryClient()}>
      <ChannelAssistantsSection channel={channel(provider)} />
    </QueryClientProvider>,
  );
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
});

describe("ChannelAssistantsSection", () => {
  it("shows the bot's default and each group chat with the assistant it answers with", async () => {
    renderSection("LARK", [
      group({ name: "Phòng Kế toán", assistant: { id: TAX, name: "Kế toán thuế" } }),
      group({ id: "oc_0f9e8d7c6b5a49382716a5b4c3d2e1f0", name: "Ban Giám đốc" }),
    ]);
    const fallback = await screen.findByRole("combobox", { name: "Trợ lý mặc định" });
    await waitFor(() => expect(fallback).toHaveTextContent("MemoryOS"));
    expect(
      await screen.findByRole("combobox", { name: "Trợ lý của Phòng Kế toán" }),
    ).toHaveTextContent("Kế toán thuế");
    expect(screen.getByRole("combobox", { name: "Trợ lý của Ban Giám đốc" })).toHaveTextContent(
      "Theo mặc định",
    );
    expect(screen.getAllByText("Nhóm Lark · hỏi 10 phút trước")).toHaveLength(2);
  });

  it("shows a Lark group nobody has asked in yet, which only leaving the group removes", async () => {
    renderSection("LARK", [group({ name: "Phòng Nhân sự", lastMessageAt: undefined })]);
    expect(await screen.findByText("Nhóm Lark · chưa có ai hỏi")).toBeVisible();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Thao tác với Phòng Nhân sự" }));
    expect(screen.getByRole("menuitem", { name: "Đổi tên" })).toBeVisible();
    expect(screen.queryByRole("menuitem", { name: "Quên nhóm" })).toBeNull();
  });

  it("lets an administrator allow a Zalo group waiting for permission, and a Lark group has no switch", async () => {
    const sent: unknown[] = [];
    server.use(
      handleAllowChannelGroup(async ({ request }) => {
        sent.push(await request.json());
        return HttpResponse.json(group({ id: "2468013579", permission: "ALLOWED" }));
      }),
    );
    renderSection("ZALO_BOT", [
      group({ id: "2468013579", lastMessageAt: undefined, permission: "WAITING" }),
    ]);
    expect(await screen.findByText("Nhóm Zalo · chờ cho phép")).toBeVisible();
    const allowed = screen.getByRole("switch", { name: "Cho phép trả lời trong Nhóm Zalo 3579" });
    expect(allowed).not.toBeChecked();
    await userEvent.setup().click(allowed);
    await expect.poll(() => sent).toEqual([{ allowed: true }]);
  });

  it("shows no permission switch for a group that needs none", async () => {
    renderSection("LARK", [group({ name: "Phòng Nhân sự" })]);
    expect(await screen.findByText("Phòng Nhân sự")).toBeVisible();
    expect(screen.queryByRole("switch")).toBeNull();
  });

  it("says so when the bot is in no group chat yet", async () => {
    renderSection("ZALO_BOT", []);
    expect(await screen.findByRole("status")).toHaveTextContent("Chưa có nhóm chat nào");
  });

  it("names a Zalo group without a label by its platform and the end of its id", async () => {
    renderSection("ZALO_BOT", [group({ id: "2468013579" })]);
    expect(await screen.findByText("Nhóm Zalo 3579")).toBeVisible();
  });

  it("renames a group, a blank name clearing the label", async () => {
    const sent: unknown[] = [];
    server.use(
      handleLabelChannelGroup(async ({ request }) => {
        sent.push(await request.json());
        return HttpResponse.json(group({ name: "Phòng Kế toán" }));
      }),
    );
    renderSection("LARK", [group({ name: "Phòng Kế toán", label: "Kế toán" })]);
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "Thao tác với Kế toán" }));
    await user.click(screen.getByRole("menuitem", { name: "Đổi tên" }));
    const dialog = screen.getByRole("dialog", { name: "Đổi tên nhóm chat" });
    await user.clear(within(dialog).getByLabelText("Tên nhóm"));
    await user.click(within(dialog).getByRole("button", { name: /Lưu/ }));
    await expect.poll(() => sent).toEqual([{ label: null }]);
  });

  it("keeps the confirmation open with the reason when forgetting is refused", async () => {
    server.use(
      handleForgetChannelGroup(() =>
        HttpResponse.json(
          { status: 403, code: "CHANNEL_ASSISTANT_NOT_EDITABLE", title: "Forbidden" },
          { status: 403, headers: { "Content-Type": "application/problem+json" } },
        ),
      ),
    );
    renderSection("ZALO_BOT", [
      group({ label: "Phòng Kế toán", assistant: { id: TAX, name: "Kế toán thuế" } }),
    ]);
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "Thao tác với Phòng Kế toán" }));
    await user.click(screen.getByRole("menuitem", { name: "Quên nhóm" }));
    const confirm = screen.getByRole("alertdialog", { name: "Quên nhóm Phòng Kế toán?" });
    await user.click(within(confirm).getByRole("button", { name: "Quên nhóm" }));
    expect(
      await within(confirm).findByText(
        "Bạn cần quyền sửa cả trợ lý đang dùng và trợ lý muốn chuyển sang thì mới đổi được.",
      ),
    ).toBeVisible();
  });
});
