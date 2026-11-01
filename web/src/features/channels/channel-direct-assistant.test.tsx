import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vitest";
import { i18n } from "@/i18n/index";
import {
  handleGetChatPreferences,
  handleListChannelLinks,
  handleListChatPersonas,
  handleSaveChatPreferences,
} from "@/lib/hey-api/msw.gen";
import type { ChannelLinkResponse, ChatPreferences } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { ChannelDirectAssistantSection } from "./channel-direct-assistant";

const MINE = "70000000-0000-4000-8000-000000000031";
const preferences: ChatPreferences = {
  workRole: "Kế toán trưởng",
  personalPreferences: "",
  defaultModelId: null,
  temperatureDefault: null,
  reasoningEffortDefault: "OFF",
  autoScroll: true,
  displayName: "Lê Thu Hà",
  email: "ha.le@minhlong.vn",
};

function renderSection(bots: ChannelLinkResponse[]) {
  const saved: unknown[] = [];
  server.use(
    handleListChannelLinks({ body: bots }),
    handleGetChatPreferences({ body: preferences }),
    handleListChatPersonas({
      body: [
        {
          id: "70000000-0000-4000-8000-000000000000",
          revision: 1,
          name: "MemoryOS",
          builtin: true,
        },
        { id: MINE, revision: 1, name: "Thư ký của tôi" },
      ],
    }),
    handleSaveChatPreferences(async ({ request }) => {
      const body = (await request.json()) as Record<string, unknown>;
      saved.push(body);
      return HttpResponse.json({ ...preferences, ...body });
    }),
  );
  render(
    <QueryClientProvider client={new QueryClient()}>
      <ChannelDirectAssistantSection />
    </QueryClientProvider>,
  );
  return saved;
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
});

describe("ChannelDirectAssistantSection", () => {
  it("saves the assistant of the member's direct messages on change, keeping the rest", async () => {
    const user = userEvent.setup();
    const saved = renderSection([
      {
        connectionId: "c1",
        botName: "Tasco Hỗ trợ",
        provider: "ZALO_BOT",
        linkedAt: "2026-10-20T02:00:00Z",
      },
    ]);
    const picker = await screen.findByRole("combobox", { name: "Trợ lý trả lời" });
    await waitFor(() => expect(picker).toBeEnabled());
    await screen.findByRole("option", { name: "Thư ký của tôi" });
    await user.selectOptions(picker, "Thư ký của tôi");
    await waitFor(() =>
      expect(saved).toEqual([
        expect.objectContaining({ channelPersonaId: MINE, workRole: "Kế toán trưởng" }),
      ]),
    );
    await waitFor(() => expect(picker).toHaveValue(MINE));
  });

  it("is not shown when the organisation has no bot", async () => {
    renderSection([]);
    await waitFor(() => expect(screen.queryByRole("combobox")).not.toBeInTheDocument());
    expect(screen.queryByText("Nhắn riêng bot")).not.toBeInTheDocument();
  });
});
