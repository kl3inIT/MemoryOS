import { z } from "zod";

/** Image-generation intent for a turn; required forces the tool, unlike Web search. */
export type ImageMode = "off" | "auto" | "required";

/** A generated image reference persisted on an assistant message and served by the API. */
const generatedImageSchema = z.object({
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

/**
 * What the person chose for this conversation, or undefined when they chose nothing and the default applies. Browser
 * preference only; the API independently authorizes every command. No messages or keys.
 */
export function readImagePreference(
  owner: string | undefined,
  sessionId: string | undefined,
): ImageMode | undefined {
  if (!owner || !sessionId) return undefined;
  try {
    const value = localStorage.getItem(`memoryos:image:${owner}:${sessionId}`);
    return value === "auto" || value === "required" || value === "off" ? value : undefined;
  } catch {
    return undefined;
  }
}

export function writeImagePreference(
  owner: string | undefined,
  sessionId: string | undefined,
  mode: ImageMode | undefined,
) {
  if (!owner || !sessionId || !mode) return;
  try {
    localStorage.setItem(`memoryos:image:${owner}:${sessionId}`, mode);
  } catch {
    // Disabled/full browser storage must not prevent a chat turn.
  }
}
