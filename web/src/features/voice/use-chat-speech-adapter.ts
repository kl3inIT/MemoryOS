import { useState } from "react";
import type { SpeechSynthesisAdapter } from "@assistant-ui/react";
import { useQueryClient, type QueryClient } from "@tanstack/react-query";
import { getChatVoiceSettingsQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { VoiceSettingsResponse } from "@/lib/hey-api/types.gen";
import { createMemoryosSpeechAdapter } from "./memoryos-speech-adapter";
import { useVoiceAvailability } from "./use-voice-availability";
import { useVoiceSettings } from "./use-voice-settings";
import { ReadAloudError, synthesizeSpeech } from "./voice-failure";
import { chatDictationSession } from "./voice-session-store";

/** Read-aloud for answers, offered only while the Tenant has a default text-to-speech provider. */
export function useChatSpeechAdapter(): SpeechSynthesisAdapter | undefined {
  const queries = useQueryClient();
  const available = useVoiceAvailability().data?.ttsAvailable === true;
  useVoiceSettings(available);
  const [adapter] = useState(() =>
    createMemoryosSpeechAdapter({
      speed: () => playbackSpeed(queries),
      synthesize: synthesizeSpeech,
      onError: (error) =>
        chatDictationSession.fail(
          error instanceof ReadAloudError ? error : new ReadAloudError("playback"),
        ),
    }),
  );
  return available ? adapter : undefined;
}

/** The saved speed at the moment reading starts; 1.0 until the member's settings have loaded. */
function playbackSpeed(queries: QueryClient) {
  return (
    queries.getQueryData<VoiceSettingsResponse>(getChatVoiceSettingsQueryKey())?.playbackSpeed ?? 1
  );
}
