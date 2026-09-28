import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ApplicationSession } from "@/features/identity/application-session-context";
import { ApplicationSessionProvider } from "@/features/identity/application-session-provider";
import {
  handleDeleteChatVoiceConnection,
  handleListChatVoiceConnections,
  handleListChatVoiceProviders,
  handleSaveChatVoiceConnection,
  handleSelectChatVoiceProvider,
} from "@/lib/hey-api/msw.gen";
import type {
  VoiceConnectionRequest,
  VoiceConnectionResponse,
  VoiceProviderResponse,
} from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { VoiceAdminPage } from "./voice-admin-page";

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

/** What each change sent: the saved connections, the chosen defaults and the removed rows. */
let sent: {
  saved: { provider: string; body: VoiceConnectionRequest }[];
  selected: unknown[];
  deleted: { provider: string; revision: string | null }[];
};

beforeEach(() => {
  sent = { saved: [], selected: [], deleted: [] };
  server.use(
    handleListChatVoiceProviders({ body: providers }),
    handleSelectChatVoiceProvider(async ({ request }) => {
      sent.selected.push(await request.json());
      return new HttpResponse(null, { status: 204 });
    }),
    handleDeleteChatVoiceConnection(({ params, request }) => {
      sent.deleted.push({
        provider: params.provider,
        revision: new URL(request.url).searchParams.get("revision"),
      });
      return new HttpResponse(null, { status: 204 });
    }),
  );
});

function connections(list: () => VoiceConnectionResponse[]) {
  server.use(handleListChatVoiceConnections(() => HttpResponse.json(list())));
}

describe("Voice administration", () => {
  it("offers a speech-to-text-only provider for recognition but not for reading aloud", async () => {
    connections(() => []);
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
    connections(() => saved);
    server.use(
      handleSaveChatVoiceConnection(async ({ params, request }) => {
        sent.saved.push({ provider: params.provider, body: await request.json() });
        saved = [
          connection({
            provider: "OPENAI",
            sttModel: "whisper-1",
            credentialConfigured: true,
            sttActive: true,
          }),
        ];
        return HttpResponse.json(saved[0]);
      }),
    );
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
    expect(sent.saved).toHaveLength(1);
    expect(sent.saved[0]).toMatchObject({
      provider: "OPENAI",
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
    expect(sent.selected).toEqual([]);
    expect(await row("Speech to text", "OpenAI").findByText("Active")).toBeVisible();
    expect(row("Text to speech", "OpenAI").getByText("Needs setup")).toBeVisible();
  });

  it("switches the default and disconnects only after confirmation", async () => {
    connections(() => [
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
    ]);
    const user = userEvent.setup();
    mount();

    await screen.findByRole("region", { name: "Speech to text" });
    const compatible = row("Speech to text", "OpenAI-compatible");
    expect(
      compatible.getByText("Systran/faster-whisper-small · speaches.internal:8000"),
    ).toBeVisible();
    await user.click(compatible.getByRole("button", { name: "Set as Default" }));
    await waitFor(() =>
      expect(sent.selected).toEqual([{ function: "STT", provider: "OPENAI_COMPATIBLE" }]),
    );

    await user.click(
      row("Speech to text", "OpenAI").getByRole("button", { name: "Disconnect OpenAI" }),
    );
    const confirm = await screen.findByRole("alertdialog", { name: "Disconnect OpenAI?" });
    expect(sent.deleted).toEqual([]);
    await user.click(within(confirm).getByRole("button", { name: "Disconnect" }));
    await waitFor(() => expect(sent.deleted).toEqual([{ provider: "OPENAI", revision: "3" }]));
  });

  it("keeps the dialog open with coded copy when the provider rejects the key", async () => {
    connections(() => []);
    server.use(
      handleSaveChatVoiceConnection(async ({ params, request }) => {
        sent.saved.push({ provider: params.provider, body: await request.json() });
        return HttpResponse.json(
          { status: 503, code: "CHAT_PROVIDER_UNAVAILABLE", detail: "upstream rejected key-123" },
          { status: 503 },
        );
      }),
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
    expect(sent.saved[0]?.body).toMatchObject({
      sttModel: "",
      ttsModel: "tts-1",
      ttsVoice: "alloy",
      activate: "TTS",
    });
  });

  it("does not read voice configuration without model management authority", () => {
    const read = vi.fn(() => HttpResponse.json([]));
    server.use(handleListChatVoiceProviders(read), handleListChatVoiceConnections(read));
    mount({ ...manager, capabilities: [] });
    expect(screen.getByRole("alert")).toBeVisible();
    expect(read).not.toHaveBeenCalled();
  });
});
