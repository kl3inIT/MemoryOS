import { useQuery } from "@tanstack/react-query";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { getChatVoiceSettings } from "@/lib/hey-api/sdk.gen";
import { voiceQueryKey } from "./voice-providers";

export function useVoiceSettingsKey() {
  const { actorId, authorizationVersion } = useApplicationSession();
  return [...voiceQueryKey, "settings", actorId, authorizationVersion] as const;
}

/** The member's own voice preferences; defaults apply until the member saves one. */
export function useVoiceSettings(enabled = true) {
  const queryKey = useVoiceSettingsKey();
  return useQuery({
    queryKey,
    enabled,
    queryFn: async ({ signal }) => (await getChatVoiceSettings({ signal })).data,
    staleTime: 60_000,
    retry: false,
  });
}
