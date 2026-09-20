import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createMemoryOsQueryClient } from "@/lib/query-client";
import type { ApplicationSession } from "@/features/identity/application-session-context";
import { ApplicationSessionProvider } from "@/features/identity/application-session-provider";
import { ChatPreferencesSections } from "./chat-preferences-sections";
import { ProfileSection } from "./profile-section";

afterEach(() => vi.unstubAllGlobals());

const saved = {
  workRole: "Kế toán trưởng",
  personalPreferences: "",
  defaultModelId: null,
  autoScroll: true,
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
  const puts: unknown[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (request: Request) => {
      if (request.method === "PUT") {
        const body = await request.clone().json();
        puts.push(body);
        return Response.json({ ...saved, ...body, defaultModelId: body.defaultModelId ?? null });
      }
      if (request.url.includes("/api/chat/preferences")) return Response.json(saved);
      return Response.json([
        model("luna", "OpenAI", "GPT-5.6 Luna"),
        model("qwen", "vLLM nội bộ", "qwen3-32b"),
      ]);
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
