import { z } from "zod";

/** Image-generation intent for a turn; required forces the tool, unlike Web search. */
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
  /** Deleted from the library: the answer keeps the card, the content route no longer serves it. */
  deleted: z.boolean().optional(),
});
export type GeneratedImage = z.infer<typeof generatedImageSchema>;

export function parseGeneratedImages(value: unknown): GeneratedImage[] {
  return z
    .array(generatedImageSchema)
    .catch([])
    .parse(value ?? []);
}

/** Which rendering of a generated image to read; the library shows thumbnails, a preview the original. */
export type ImageVariant = "original" | "thumbnail";

/** Authorized serving URL for a generated image; the backend enforces ownership. */
export function imageArtifactUrl(id: string, variant: ImageVariant = "original"): string {
  const url = `/api/chat/image-artifacts/${id}/content`;
  return variant === "thumbnail" ? `${url}?variant=THUMBNAIL` : url;
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
