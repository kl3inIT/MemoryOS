import { useState } from "react";
import type { DictationAdapter } from "@assistant-ui/react";
import { uiLanguage } from "@/i18n";
import { createMemoryosDictationAdapter } from "./memoryos-dictation-adapter";
import { useVoiceAvailability } from "./use-voice-availability";
import { requestVoiceTicket } from "./voice-failure";
import { chatDictationSession } from "./voice-session-store";

/** The Chat composer's dictation, offered only while the Tenant has a default speech-to-text provider. */
export function useChatDictationAdapter(): DictationAdapter | undefined {
  const available = useVoiceAvailability().data?.sttAvailable === true;
  const [adapter] = useState(() =>
    createMemoryosDictationAdapter({
      // The document language follows the account's display language when each dictation starts.
      language: () => uiLanguage(document.documentElement.lang),
      requestTicket: requestVoiceTicket,
      onStarting: chatDictationSession.starting,
      onStarted: chatDictationSession.started,
      onLevel: chatDictationSession.level,
      onStopping: chatDictationSession.stopping,
      onEnded: chatDictationSession.ended,
    }),
  );
  return available ? adapter : undefined;
}
