import type { QueryClient } from "@tanstack/react-query";
import {
  getChatVoiceAvailabilityQueryKey,
  listChatVoiceConnectionsQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { VoiceConnectionResponse, VoiceProviderResponse } from "@/lib/hey-api/types.gen";
import { presentProblem, type ErrorMessage } from "@/lib/problem-presentation";

export type VoiceProviderId = VoiceProviderResponse["provider"];
export type VoiceFunction = "STT" | "TTS";

/** Refreshes what a voice configuration change affects: the connections and the availability Chat and Search read. */
export function invalidateVoice(cache: QueryClient) {
  return Promise.all([
    cache.invalidateQueries({ queryKey: listChatVoiceConnectionsQueryKey() }),
    cache.invalidateQueries({ queryKey: getChatVoiceAvailabilityQueryKey() }),
  ]);
}

export const voiceProblem = (error: unknown): ErrorMessage =>
  presentProblem(error, "mutation", {
    CHAT_PROVIDER_UNAVAILABLE: { key: "voiceProviderUnavailable" },
    CHAT_CAPACITY_EXCEEDED: { key: "throttled" },
  }).message;

export function isDefault(connection: VoiceConnectionResponse | undefined, fn: VoiceFunction) {
  return fn === "STT" ? !!connection?.sttActive : !!connection?.ttsActive;
}

/** The server rule for a connection that may become the default for a function. */
export function canServe(
  provider: VoiceProviderResponse,
  fn: VoiceFunction,
  values: { credential: boolean; sttModel: string; ttsModel: string; ttsVoice: string },
) {
  if (provider.requiresKey && !values.credential) return false;
  if (fn === "TTS" && !provider.speech) return false;
  return fn === "STT" ? values.sttModel !== "" : values.ttsModel !== "" && values.ttsVoice !== "";
}

export function connectionServes(
  provider: VoiceProviderResponse,
  fn: VoiceFunction,
  connection: VoiceConnectionResponse | undefined,
) {
  return (
    !!connection &&
    canServe(provider, fn, { credential: !!connection.credentialConfigured, ...connection })
  );
}

export function endpointHost(endpoint: string) {
  try {
    return new URL(endpoint).host;
  } catch {
    return endpoint;
  }
}
