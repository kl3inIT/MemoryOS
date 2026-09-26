import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ApplicationSession } from "@/features/identity/application-session-context";
import { ApplicationSessionProvider } from "@/features/identity/application-session-provider";
import {
  handleGetChatVoiceAvailability,
  handleGetChatVoiceSettings,
  handleUpdateChatVoiceSettings,
} from "@/lib/hey-api/msw.gen";
import type { VoiceSettingsRequest } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { VoiceSettingsSection } from "./voice-settings-section";

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
  server.use(handleGetChatVoiceAvailability({ body: { sttAvailable, ttsAvailable } }));
}

/** The changes the section saved, and whether it read the member's settings at all. */
let updates: VoiceSettingsRequest[];
const readSettings = vi.fn(() =>
  HttpResponse.json({ autoSend: false, autoPlayback: false, playbackSpeed: 1 }),
);

beforeEach(() => {
  updates = [];
  readSettings.mockClear();
  vi.stubGlobal(
    "ResizeObserver",
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  );
  server.use(
    handleGetChatVoiceSettings(readSettings),
    handleUpdateChatVoiceSettings(async ({ request }) => {
      const body = await request.json();
      updates.push(body);
      return HttpResponse.json({ autoSend: false, autoPlayback: false, playbackSpeed: 1, ...body });
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
    await waitFor(() => expect(updates).toEqual([{ playbackSpeed: 1.1 }]));
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
    await waitFor(() => expect(updates).toEqual([{ autoSend: true }]));
  });

  it("stays hidden and reads no settings while the Tenant has no voice provider", async () => {
    const availability = vi.fn(() =>
      HttpResponse.json({ sttAvailable: false, ttsAvailable: false }),
    );
    server.use(handleGetChatVoiceAvailability(availability));
    mount();
    await waitFor(() => expect(availability).toHaveBeenCalled());
    expect(screen.queryByRole("heading", { name: "Voice" })).not.toBeInTheDocument();
    expect(readSettings).not.toHaveBeenCalled();
  });
});
