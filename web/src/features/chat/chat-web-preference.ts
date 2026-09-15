export type WebSearchMode = "off" | "auto";

/** Browser preference only; the API independently authorizes every command. No messages or keys. */
export function readWebPreference(
  owner: string | undefined,
  sessionId: string | undefined,
): WebSearchMode {
  if (!owner || !sessionId) return "off";
  try {
    const value = localStorage.getItem(`memoryos:web:${owner}:${sessionId}`);
    return value === "auto" ? value : "off";
  } catch {
    return "off";
  }
}

export function writeWebPreference(
  owner: string | undefined,
  sessionId: string | undefined,
  mode: WebSearchMode,
) {
  if (!owner || !sessionId) return;
  try {
    const key = `memoryos:web:${owner}:${sessionId}`;
    if (mode === "off") localStorage.removeItem(key);
    else localStorage.setItem(key, mode);
  } catch {
    // Disabled/full browser storage must not prevent a chat turn.
  }
}
