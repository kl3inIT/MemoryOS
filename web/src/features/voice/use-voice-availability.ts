import { useQuery } from "@tanstack/react-query";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { getChatVoiceAvailability } from "@/lib/hey-api/sdk.gen";
import { voiceQueryKey } from "./voice-providers";

/** Whether the Tenant has a default speech-to-text or text-to-speech provider; never exposes configuration. */
export function useVoiceAvailability() {
  const { actorId, authorizationVersion } = useApplicationSession();
  return useQuery({
    queryKey: [...voiceQueryKey, "availability", actorId, authorizationVersion],
    queryFn: async ({ signal }) => (await getChatVoiceAvailability({ signal })).data,
    staleTime: 60_000,
    retry: false,
  });
}
