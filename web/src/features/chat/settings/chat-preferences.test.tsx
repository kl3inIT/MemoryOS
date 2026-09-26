import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import userEvent from "@testing-library/user-event";
import { HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import {
  handleGetChatPreferences,
  handleListAvailableChatModels,
  handleSaveChatPreferences,
} from "@/lib/hey-api/msw.gen";
import { server } from "@/test/msw";
import { createMemoryOsQueryClient } from "@/lib/query-client";
import type { ApplicationSession } from "@/features/identity/application-session-context";
import { ApplicationSessionProvider } from "@/features/identity/application-session-provider";
import { ChatPreferencesSections } from "./chat-preferences-sections";
import { ProfileSection } from "@/features/identity/profile-section";

const saved = {
  workRole: "Kế toán trưởng",
  personalPreferences: "",
  defaultModelId: null,
  autoScroll: true,
  temperatureDefault: null,
  reasoningEffortDefault: "MEDIUM" as const,
  displayName: "Trần Thu Hà",
  email: "ha.tt@tasco.vn",
};
const model = (id: string, providerName: string, displayName: string) => ({
  id,
  providerId: providerName,
  providerName,
  modelName: id,
  displayName,
  capabilities: { streaming: true, toolCalling: true, vision: false, reasoning: false },
  contextWindow: 128000,
  maxOutputTokens: null,
  pricing: null,
  isDefault: false,
});

const session: ApplicationSession = {
  actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
  authorizationVersion: 1,
  uiLanguage: "en",
  tenant: { displayName: "Tasco", role: "MEMBER" },
  capabilities: ["CHAT_READ", "CHAT_WRITE"],
  scopedCapabilities: [],
};

function mount(ui: React.ReactNode) {
  const puts: Record<string, unknown>[] = [];
  server.use(
    handleGetChatPreferences({ body: saved }),
    handleSaveChatPreferences(async ({ request }) => {
      const body = (await request.json()) as Record<string, unknown>;
      puts.push(body);
      return HttpResponse.json({
        ...saved,
        ...body,
        defaultModelId: (body.defaultModelId as string | undefined) ?? null,
      });
    }),
    handleListAvailableChatModels({
      body: [model("luna", "OpenAI", "GPT-5.6 Luna"), model("qwen", "vLLM nội bộ", "qwen3-32b")],
    }),
  );
  render(
    <QueryClientProvider client={createMemoryOsQueryClient()}>
      <ApplicationSessionProvider session={session}>{ui}</ApplicationSessionProvider>
    </QueryClientProvider>,
  );
  return puts;
}

describe("personal Chat preferences", () => {
  it("saves each change with the whole record and lists models under their provider", async () => {
    const puts = mount(<ChatPreferencesSections />);
    const picker = await screen.findByLabelText("Default Model");
    await waitFor(() => expect(picker).toBeEnabled());
    await userEvent.click(picker);
    // Models are listed under their provider, with the organization default first.
    expect(await screen.findByText("OpenAI")).toBeVisible();
    expect(screen.getByText("vLLM nội bộ")).toBeVisible();
    await userEvent.click(screen.getByText("qwen3-32b"));
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]).toMatchObject({
      defaultModelId: "qwen",
      workRole: "Kế toán trưởng",
      autoScroll: true,
    });
    await waitFor(() =>
      expect(screen.getByRole("switch", { name: "Chat Auto-scroll" })).toBeEnabled(),
    );
    fireEvent.click(screen.getByRole("switch", { name: "Chat Auto-scroll" }));
    await waitFor(() => expect(puts).toHaveLength(2));
    expect(puts[1]).toMatchObject({ autoScroll: false, defaultModelId: "qwen" });
  });

  it("shows the sign-in profile read-only and saves the work role on leaving the field", async () => {
    const puts = mount(<ProfileSection />);
    expect(await screen.findByText("Trần Thu Hà")).toBeVisible();
    expect(screen.getByText("ha.tt@tasco.vn")).toBeVisible();
    const role = screen.getByLabelText("Work Role");
    await waitFor(() => expect(role).toHaveValue("Kế toán trưởng"));
    fireEvent.change(role, { target: { value: "  Giám đốc tài chính " } });
    fireEvent.blur(role);
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]).toMatchObject({ workRole: "Giám đốc tài chính" });
  });
});
