import { z } from "zod";

/** Image-generation intent for a turn; mirrors WebSearchMode. */
export type ImageMode = "off" | "auto" | "required";

/** A generated image reference persisted on an assistant message and served by the API. */
export const generatedImageSchema = z.object({
  id: z.string().uuid(),
  mediaType: z.string().max(128),
  revisedPrompt: z
    .string()
    .max(4000)
    .nullish()
    .transform((value) => value ?? null),
});
export type GeneratedImage = z.infer<typeof generatedImageSchema>;

export function parseGeneratedImages(value: unknown): GeneratedImage[] {
  return z
    .array(generatedImageSchema)
    .catch([])
    .parse(value ?? []);
}

/** Authorized serving URL for a generated image; the backend enforces ownership. */
export function imageArtifactUrl(id: string): string {
  return `/api/chat/image-artifacts/${id}/content`;
}

/** Browser preference only; the API independently authorizes every command. No messages or keys. */
export function readImagePreference(
  owner: string | undefined,
  sessionId: string | undefined,
): ImageMode {
  if (!owner || !sessionId) return "off";
  try {
    const value = localStorage.getItem(`memoryos:image:${owner}:${sessionId}`);
    return value === "auto" || value === "required" ? value : "off";
  } catch {
    return "off";
  }
}

export function writeImagePreference(
  owner: string | undefined,
  sessionId: string | undefined,
  mode: ImageMode,
) {
  if (!owner || !sessionId) return;
  try {
    const key = `memoryos:image:${owner}:${sessionId}`;
    if (mode === "off") localStorage.removeItem(key);
    else localStorage.setItem(key, mode);
  } catch {
    // Disabled/full browser storage must not prevent a chat turn.
  }
}
