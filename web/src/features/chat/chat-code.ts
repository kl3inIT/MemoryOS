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

/** Human file size for a download chip: "12,4 KB" in the active locale. */
export function fileSize(bytes: number, locale: string): string {
  const units = ["B", "KB", "MB"];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  const formatted = new Intl.NumberFormat(locale, {
    maximumFractionDigits: unit === 0 ? 0 : 1,
  }).format(value);
  return `${formatted} ${units[unit]}`;
}
