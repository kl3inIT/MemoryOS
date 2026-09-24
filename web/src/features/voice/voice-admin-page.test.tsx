import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ApplicationSession } from "@/features/identity/application-session-context";
import { ApplicationSessionProvider } from "@/features/identity/application-session-provider";
import { ApiError } from "@/lib/api";
import type * as Sdk from "@/lib/hey-api/sdk.gen";
import type { VoiceConnectionResponse, VoiceProviderResponse } from "@/lib/hey-api/types.gen";
import { VoiceAdminPage } from "./voice-admin-page";

const api = vi.hoisted(() => ({
  listChatVoiceProviders: vi.fn(),
  listChatVoiceConnections: vi.fn(),
  saveChatVoiceConnection: vi.fn(),
  selectChatVoiceProvider: vi.fn(),
  deleteChatVoiceConnection: vi.fn(),
  testChatVoiceConnection: vi.fn(),
}));

vi.mock("@/lib/hey-api/sdk.gen", async (importOriginal) => ({
  ...(await importOriginal<typeof Sdk>()),
  ...api,
}));

const manager: ApplicationSession = {
  actorId: "5d0c6c57-4a3f-4d0e-9d62-0b7d0f8c1f11",
  authorizationVersion: 1,
  uiLanguage: "en",
  tenant: { displayName: "Fixture", role: "MEMBER" },
  capabilities: ["MODELS_MANAGE"],
  scopedCapabilities: [],
};

const providers: VoiceProviderResponse[] = [
  {
    provider: "OPENAI",
    requiresKey: true,
    requiresEndpoint: false,
    defaultEndpoint: "https://api.openai.com/v1",
    speech: true,
    sttModels: ["whisper-1", "gpt-4o-transcribe"],
    ttsModels: ["tts-1", "tts-1-hd"],
    voices: ["alloy", "nova"],
  },
  {
    provider: "OPENAI_COMPATIBLE",
    requiresKey: false,
    requiresEndpoint: true,
    defaultEndpoint: "",
    speech: true,
    sttModels: [],
    ttsModels: [],
    voices: [],
  },
  {
    provider: "SONIOX",
    requiresKey: true,
    requiresEndpoint: false,
    defaultEndpoint: "https://api.soniox.com/v1",
    speech: false,
    sttModels: ["stt-rt-v5"],
    ttsModels: [],
    voices: [],
  },
];

function connection(
  values: Partial<VoiceConnectionResponse> & Pick<VoiceConnectionResponse, "provider">,
): VoiceConnectionResponse {
  return {
    endpoint: "",
    sttModel: "",
    ttsModel: "",
    ttsVoice: "",
    credentialConfigured: false,
    sttActive: false,
    ttsActive: false,
    revision: 1,
    ...values,
  };
}

function mount(session = manager) {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <ApplicationSessionProvider session={session}>
        <VoiceAdminPage />
      </ApplicationSessionProvider>
    </QueryClientProvider>,
  );
}

function row(section: string, provider: string) {
  return within(
    within(screen.getByRole("region", { name: section })).getByRole("listitem", {
      name: provider,
    }),
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  api.listChatVoiceProviders.mockResolvedValue({ data: providers });
  api.selectChatVoiceProvider.mockResolvedValue({ data: undefined });
  api.deleteChatVoiceConnection.mockResolvedValue({ data: undefined });
  api.testChatVoiceConnection.mockResolvedValue({ data: undefined });
});

describe("Voice administration", () => {
  it("offers a speech-to-text-only provider for recognition but not for reading aloud", async () => {
    api.listChatVoiceConnections.mockResolvedValue({ data: [] });
    mount();

    await screen.findByRole("region", { name: "Speech to text" });
    expect(
      await row("Speech to text", "Soniox").findByText(
        "Real-time Vietnamese recognition with speaker separation",
      ),
    ).toBeVisible();
    const speech = within(screen.getByRole("region", { name: "Text to speech" }));
    expect(speech.queryByRole("listitem", { name: "Soniox" })).not.toBeInTheDocument();
    expect(speech.getByRole("listitem", { name: "OpenAI" })).toBeVisible();
  });

  it("connects the first provider as the default only through the verifying save", async () => {
    let saved: VoiceConnectionResponse[] = [];
    api.listChatVoiceConnections.mockImplementation(async () => ({ data: saved }));
    api.saveChatVoiceConnection.mockImplementation(async () => {
      saved = [
        connection({
          provider: "OPENAI",
          sttModel: "whisper-1",
          credentialConfigured: true,
          sttActive: true,
        }),
      ];
      return { data: saved[0] };
    });
    const user = userEvent.setup();
    mount();

    expect(
      await screen.findByText(
        "No default provider is selected, so the microphone in Chat and Search is off.",
      ),
    ).toBeVisible();
    await user.click(row("Speech to text", "OpenAI").getByRole("button", { name: "Connect" }));
    const dialog = await screen.findByRole("dialog", { name: "Connect OpenAI" });
    expect(within(dialog).getByLabelText("Transcription model")).toHaveValue("whisper-1");
    await user.type(within(dialog).getByLabelText("API key"), "synthetic-key");
    await user.click(within(dialog).getByRole("button", { name: "Connect" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(api.saveChatVoiceConnection).toHaveBeenCalledOnce();
    expect(api.saveChatVoiceConnection.mock.calls[0][0]).toMatchObject({
      path: { provider: "OPENAI" },
      body: {
        endpoint: "",
        sttModel: "whisper-1",
        ttsModel: "",
        ttsVoice: "",
        credentialAction: "REPLACE",
        credentialValue: "synthetic-key",
        activate: "STT",
        revision: 0,
      },
    });
    expect(api.selectChatVoiceProvider).not.toHaveBeenCalled();
    expect(await row("Speech to text", "OpenAI").findByText("Active")).toBeVisible();
    expect(row("Text to speech", "OpenAI").getByText("Needs setup")).toBeVisible();
  });

  it("switches the default and disconnects only after confirmation", async () => {
    api.listChatVoiceConnections.mockResolvedValue({
      data: [
        connection({
          provider: "OPENAI",
          sttModel: "whisper-1",
          credentialConfigured: true,
          sttActive: true,
          revision: 3,
        }),
        connection({
          provider: "OPENAI_COMPATIBLE",
          endpoint: "http://speaches.internal:8000/v1",
          sttModel: "Systran/faster-whisper-small",
        }),
      ],
    });
    const user = userEvent.setup();
    mount();

    await screen.findByRole("region", { name: "Speech to text" });
    const compatible = row("Speech to text", "OpenAI-compatible");
    expect(
      compatible.getByText("Systran/faster-whisper-small · speaches.internal:8000"),
    ).toBeVisible();
    await user.click(compatible.getByRole("button", { name: "Set as Default" }));
    await waitFor(() =>
      expect(api.selectChatVoiceProvider).toHaveBeenCalledWith(
        expect.objectContaining({ body: { function: "STT", provider: "OPENAI_COMPATIBLE" } }),
      ),
    );

    await user.click(
      row("Speech to text", "OpenAI").getByRole("button", { name: "Disconnect OpenAI" }),
    );
    const confirm = await screen.findByRole("alertdialog", { name: "Disconnect OpenAI?" });
    expect(api.deleteChatVoiceConnection).not.toHaveBeenCalled();
    await user.click(within(confirm).getByRole("button", { name: "Disconnect" }));
    await waitFor(() =>
      expect(api.deleteChatVoiceConnection).toHaveBeenCalledWith(
        expect.objectContaining({ path: { provider: "OPENAI" }, query: { revision: 3 } }),
      ),
    );
  });

  it("keeps the dialog open with coded copy when the provider rejects the key", async () => {
    api.listChatVoiceConnections.mockResolvedValue({ data: [] });
    api.saveChatVoiceConnection.mockRejectedValue(
      new ApiError(503, { code: "CHAT_PROVIDER_UNAVAILABLE", detail: "upstream rejected key-123" }),
    );
    const user = userEvent.setup();
    mount();

    await screen.findByRole("region", { name: "Text to speech" });
    await user.click(row("Text to speech", "OpenAI").getByRole("button", { name: "Connect" }));
    const dialog = await screen.findByRole("dialog", { name: "Connect OpenAI" });
    expect(within(dialog).getByLabelText("Speech model")).toHaveValue("tts-1");
    expect(within(dialog).getByLabelText("Voice")).toHaveValue("alloy");
    await user.type(within(dialog).getByLabelText("API key"), "synthetic-key");
    await user.click(within(dialog).getByRole("button", { name: "Connect" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(
      "The voice provider could not be reached or rejected the key. Check the address, key and model, then try again.",
    );
    expect(dialog).not.toHaveTextContent("key-123");
    expect(api.saveChatVoiceConnection.mock.calls[0][0].body).toMatchObject({
      sttModel: "",
      ttsModel: "tts-1",
      ttsVoice: "alloy",
      activate: "TTS",
    });
  });

  it("does not read voice configuration without model management authority", () => {
    mount({ ...manager, capabilities: [] });
    expect(screen.getByRole("alert")).toBeVisible();
    expect(api.listChatVoiceProviders).not.toHaveBeenCalled();
    expect(api.listChatVoiceConnections).not.toHaveBeenCalled();
  });
});
