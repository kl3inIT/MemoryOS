import { useQuery } from "@tanstack/react-query";
import { getChatVoiceSettingsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";

/** The member's own voice preferences; defaults apply until the member saves one. */
export function useVoiceSettings(enabled = true) {
  return useQuery({
    ...getChatVoiceSettingsOptions(),
    enabled,
    staleTime: 60_000,
    retry: false,
  });
}
