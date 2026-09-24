import { z } from "zod";

/** Limits mirror ChatCodeEvent on the server; longer output is truncated before it is streamed. */
export const MAX_CODE_CHARACTERS = 8_000;
export const MAX_OUTPUT_CHARACTERS = 16_000;

/** A file run_python produced, persisted on the assistant message and served by the API. */
export const generatedFileSchema = z.object({
  id: z.string().uuid(),
  filename: z.string().min(1).max(200),
  mediaType: z.string().max(128),
  sizeBytes: z.number().int().nonnegative(),
  /** Chart data captured from the figure behind this PNG is served at /chart. */
  chart: z.boolean().optional(),
  /** Deleted from the library: the answer keeps the card, the content routes no longer serve it. */
  deleted: z.boolean().optional(),
});
export type GeneratedFile = z.infer<typeof generatedFileSchema>;

/** One run_python call as the timeline knows it, keyed by tool call id. */
export type CodeRun = {
  code: string;
  stdout: string;
  stderr: string;
  files: GeneratedFile[];
  status: "running" | "done" | "failed";
};

export function parseGeneratedFiles(value: unknown): GeneratedFile[] {
  return z
    .array(generatedFileSchema)
    .catch([])
    .parse(value ?? []);
}

/** Authorized serving URL for a generated file; the backend enforces ownership. */
export function fileArtifactUrl(id: string): string {
  return `/api/chat/file-artifacts/${id}/content`;
}
