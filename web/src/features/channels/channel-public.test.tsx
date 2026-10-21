import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vitest";
import { i18n } from "@/i18n/index";
import {
  handleGetChatPersona,
  handleListChannelGroups,
  handleListChannels,
  handleListChatPersonas,
  handleUpdateChannelPublic,
} from "@/lib/hey-api/msw.gen";
import type { ChannelResponse, PersonaView } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { useChannelAdmin } from "./channel-admin";
import { ChannelPublicSwitch } from "./channel-public";

const CARE = "70000000-0000-4000-8000-000000000021";
const PRICES = "70000000-0000-4000-8000-000000000022";
const bot = (publicAccess: boolean): ChannelResponse => ({
  id: "5d1f6c2e-8a1b-4c3d-9e4f-0a1b2c3d4e5f",
  provider: "ZALO_BOT",
  name: "CSKH Tasco",
  appId: "1234567890",
  noticeLanguage: "vi",
  enabled: true,
  publicAccess,
  serviceAccountId: "9e8d7c6b-5a49-4382-9a1b-c0d1e2f3a4b5",
  missingScopes: [],
  status: "CONNECTED",
  assistant: { id: CARE, name: "Chăm sóc khách hàng" },
  revision: 4,
  createdAt: "2026-10-13T09:00:00Z",
  updatedAt: "2026-10-13T09:10:00Z",
});
const view = (id: string, name: string, given: Partial<PersonaView> = {}): PersonaView => ({
  id,
  revision: 1,
  name,
  permissions: { edit: true },
  ...given,
});

function Harness({ channel }: { channel: ChannelResponse }) {
  const admin = useChannelAdmin();
  return (
    <>
      <ChannelPublicSwitch channel={channel} admin={admin} />
      {admin.changeError ? <p role="alert">{admin.changeError}</p> : null}
    </>
  );
}

function renderSwitch(channel: ChannelResponse) {
  const saved: { body: unknown; revision: string | null }[] = [];
  server.use(
    handleListChannels({ body: [channel] }),
    handleListChatPersonas({ body: [view("70000000-0000-4000-8000-000000000000", "MemoryOS")] }),
    handleListChannelGroups({
      body: [
        {
          id: "zg_1234567890",
          label: "Khách Đại Phát",
          firstMessageAt: "2026-10-10T02:00:00Z",
          permission: "WAITING",
          assistant: { id: PRICES, name: "Bảng giá Đại Phát" },
        },
      ],
    }),
    handleGetChatPersona(({ params }) =>
      HttpResponse.json(
        params.personaId === CARE
          ? view(CARE, "Chăm sóc khách hàng", {
              sources: [{ id: "s1", name: "Câu hỏi thường gặp" }],
              documentSets: [{ id: "d1", name: "Chính sách đổi trả" }],
              fileIds: ["f1", "f2"],
            })
          : view(PRICES, "Bảng giá Đại Phát"),
      ),
    ),
    handleUpdateChannelPublic(async ({ request }) => {
      saved.push({ body: await request.json(), revision: request.headers.get("If-Match") });
      return HttpResponse.json(channel);
    }),
  );
  render(
    <QueryClientProvider client={new QueryClient()}>
      <Harness channel={channel} />
    </QueryClientProvider>,
  );
  return saved;
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
});

describe("ChannelPublicSwitch", () => {
  it("asks first, naming each assistant the bot answers with and what it is given", async () => {
    const user = userEvent.setup();
    const saved = renderSwitch(bot(false));

    await user.click(screen.getByRole("switch", { name: "Cho mọi người hỏi CSKH Tasco" }));
    const dialog = await screen.findByRole("alertdialog", {
      name: "Cho mọi người hỏi CSKH Tasco?",
    });
    expect(
      await within(dialog).findByText("Câu hỏi thường gặp, Chính sách đổi trả, 2 tệp"),
    ).toBeVisible();
    expect(within(dialog).getByText(/Chăm sóc khách hàng/)).toHaveTextContent(
      "Chăm sóc khách hàng · mặc định",
    );
    expect(within(dialog).getByText(/Bảng giá Đại Phát/)).toHaveTextContent(
      "Bảng giá Đại Phát · Khách Đại Phát",
    );
    expect(await within(dialog).findByText("Không có tài liệu")).toBeVisible();

    await user.click(within(dialog).getByRole("button", { name: "Hủy" }));
    expect(saved).toEqual([]);

    await user.click(screen.getByRole("switch", { name: "Cho mọi người hỏi CSKH Tasco" }));
    await user.click(
      within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Công khai" }),
    );
    await waitFor(() => expect(saved).toEqual([{ body: { publicAccess: true }, revision: '"4"' }]));
  });

  it("closes at once, without asking", async () => {
    const user = userEvent.setup();
    const saved = renderSwitch(bot(true));

    await user.click(screen.getByRole("switch", { name: "Cho mọi người hỏi CSKH Tasco" }));
    await waitFor(() =>
      expect(saved).toEqual([{ body: { publicAccess: false }, revision: '"4"' }]),
    );
    expect(screen.queryByRole("alertdialog")).toBeNull();
  });
});
