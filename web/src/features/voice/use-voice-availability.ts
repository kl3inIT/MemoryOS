import { useQuery } from "@tanstack/react-query";
import { getChatVoiceAvailabilityOptions } from "@/lib/hey-api/@tanstack/react-query.gen";

/** Whether the Tenant has a default speech-to-text or text-to-speech provider; never exposes configuration. */
export function useVoiceAvailability() {
  return useQuery({ ...getChatVoiceAvailabilityOptions(), staleTime: 60_000, retry: false });
}
