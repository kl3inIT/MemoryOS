import type { VoiceConnectionResponse, VoiceProviderResponse } from "@/lib/hey-api/types.gen";
import { presentProblem, type ErrorMessage } from "@/lib/problem-presentation";

export type VoiceProviderId = VoiceProviderResponse["provider"];
export type VoiceFunction = "STT" | "TTS";

/** Every voice query shares this prefix, so a configuration change refreshes availability everywhere. */
export const voiceQueryKey = ["chat-voice"] as const;

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
