import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ApplicationSession } from "@/features/identity/application-session-context";
import { ApplicationSessionProvider } from "@/features/identity/application-session-provider";
import type * as Sdk from "@/lib/hey-api/sdk.gen";
import type { VoiceSettingsRequest } from "@/lib/hey-api/types.gen";
import { VoiceSettingsSection } from "./voice-settings-section";

const api = vi.hoisted(() => ({
  getChatVoiceAvailability: vi.fn(),
  getChatVoiceSettings: vi.fn(),
  updateChatVoiceSettings: vi.fn(),
}));

vi.mock("@/lib/hey-api/sdk.gen", async (importOriginal) => ({
  ...(await importOriginal<typeof Sdk>()),
  ...api,
}));

const member: ApplicationSession = {
  actorId: "3b8c5f2e-6a8d-4b1f-9a51-2f1c8e0d7a64",
  authorizationVersion: 1,
  uiLanguage: "en",
  tenant: { displayName: "Fixture", role: "MEMBER" },
  capabilities: ["CHAT_WRITE"],
  scopedCapabilities: [],
};

function mount() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <ApplicationSessionProvider session={member}>
        <VoiceSettingsSection />
      </ApplicationSessionProvider>
    </QueryClientProvider>,
  );
}

function available(sttAvailable: boolean, ttsAvailable: boolean) {
  api.getChatVoiceAvailability.mockResolvedValue({ data: { sttAvailable, ttsAvailable } });
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.stubGlobal(
    "ResizeObserver",
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  );
  api.getChatVoiceSettings.mockResolvedValue({
    data: { autoSend: false, autoPlayback: false, playbackSpeed: 1 },
  });
  api.updateChatVoiceSettings.mockImplementation(
    async ({ body }: { body: VoiceSettingsRequest }) => ({
      data: { autoSend: false, autoPlayback: false, playbackSpeed: 1, ...body },
    }),
  );
});

describe("voice settings", () => {
  it("offers reading speed with text to speech and saves the committed value only", async () => {
    available(false, true);
    const user = userEvent.setup();
    mount();

    const speed = await screen.findByRole("slider", { name: "Reading speed" });
    expect(
      screen.queryByRole("switch", { name: "Auto-send when recording stops" }),
    ).not.toBeInTheDocument();
    expect(screen.getByText("1.0×")).toBeVisible();
    await waitFor(() => expect(speed).not.toHaveAttribute("data-disabled"));

    speed.focus();
    await user.keyboard("{ArrowRight}");
    await waitFor(() => expect(api.updateChatVoiceSettings).toHaveBeenCalledOnce());
    expect(api.updateChatVoiceSettings.mock.calls[0][0].body).toEqual({ playbackSpeed: 1.1 });
    expect(await screen.findByText("1.1×")).toBeVisible();
  });

  it("offers Auto-Send with speech to text and sends only that change", async () => {
    available(true, false);
    const user = userEvent.setup();
    mount();

    const autoSend = await screen.findByRole("switch", { name: "Auto-send when recording stops" });
    expect(screen.queryByRole("slider", { name: "Reading speed" })).not.toBeInTheDocument();
    await waitFor(() => expect(autoSend).toBeEnabled());
    await user.click(autoSend);
    await waitFor(() => expect(api.updateChatVoiceSettings).toHaveBeenCalledOnce());
    expect(api.updateChatVoiceSettings.mock.calls[0][0].body).toEqual({ autoSend: true });
  });

  it("stays hidden and reads no settings while the Tenant has no voice provider", async () => {
    available(false, false);
    mount();
    await waitFor(() => expect(api.getChatVoiceAvailability).toHaveBeenCalled());
    expect(screen.queryByRole("heading", { name: "Voice" })).not.toBeInTheDocument();
    expect(api.getChatVoiceSettings).not.toHaveBeenCalled();
  });
});
