import type { WebAvailabilityResponse } from "@/lib/hey-api/types.gen";

export type WebSearchMode = "off" | "auto";

/**
 * Whether Web search can run on a model: its provider hosts search, or the model calls tools and the organization
 * has a search connection. Deep research never uses provider-hosted search, so it passes `hosted` false.
 */
export function webUsableOn(
  availability: WebAvailabilityResponse | undefined,
  modelId: string | undefined,
  hosted = true,
) {
  if (!availability || !modelId) return false;
  return (
    (hosted && availability.nativeModelIds?.includes(modelId) === true) ||
    (availability.searchAvailable === true &&
      availability.automaticModelIds?.includes(modelId) === true)
  );
}

/**
 * What the person chose for this conversation, or undefined when they chose nothing and the default applies. Browser
 * preference only; the API independently authorizes every command. No messages or keys.
 */
export function readWebPreference(
  owner: string | undefined,
  sessionId: string | undefined,
): WebSearchMode | undefined {
  if (!owner || !sessionId) return undefined;
  try {
    const value = localStorage.getItem(`memoryos:web:${owner}:${sessionId}`);
    return value === "auto" || value === "off" ? value : undefined;
  } catch {
    return undefined;
  }
}

export function writeWebPreference(
  owner: string | undefined,
  sessionId: string | undefined,
  mode: WebSearchMode | undefined,
) {
  if (!owner || !sessionId || !mode) return;
  try {
    localStorage.setItem(`memoryos:web:${owner}:${sessionId}`, mode);
  } catch {
    // Disabled/full browser storage must not prevent a chat turn.
  }
}
