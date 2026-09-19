import { useSyncExternalStore } from "react";
import { AutoPlayback } from "./auto-playback";
import { openSpeechSocket } from "./synthesize-socket";
import { readAloudFailure, requestVoiceTicket } from "./voice-failure";
import { chatDictationSession } from "./voice-session-store";

/** One answer is read aloud automatically at a time in a tab. */
export const chatAutoPlayback = new AutoPlayback({
  connect: async (speed) =>
    openSpeechSocket({ ticket: await requestVoiceTicket("SYNTHESIZE"), speed }),
  onError: (error) => chatDictationSession.fail(readAloudFailure(error)),
});

export function useAutoPlayback() {
  return useSyncExternalStore(chatAutoPlayback.subscribe, chatAutoPlayback.getSnapshot);
}
