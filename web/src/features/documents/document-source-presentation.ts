import { z } from "zod";
import { findSourceProvider } from "@/features/sources/shared/source-provider-catalog";

export type DocumentSourceType = "FILE" | "GOOGLE_DRIVE" | "SHAREPOINT";

/**
 * Presentation only, so a provider a newer server knows is dropped instead of failing the parse: a rejected search
 * step used to end the reply stream, leaving a finished answer unshown (staging, SharePoint, 2026-09-20).
 */
export const documentSourceTypesSchema = z
  .array(z.string().max(64))
  .max(16)
  .transform((types) =>
    types.filter((type): type is DocumentSourceType =>
      (["FILE", "GOOGLE_DRIVE", "SHAREPOINT"] as string[]).includes(type),
    ),
  );

export type DocumentKind =
  | "pdf"
  | "document"
  | "spreadsheet"
  | "presentation"
  | "text"
  | "code"
  | "image"
  | "generic";

const KINDS: Record<string, DocumentKind> = {
  "application/pdf": "pdf",
  "application/msword": "document",
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document": "document",
  "application/vnd.oasis.opendocument.text": "document",
  "application/vnd.google-apps.document": "document",
  "application/rtf": "document",
  "application/vnd.ms-excel": "spreadsheet",
  "application/vnd.ms-excel.sheet.macroenabled.12": "spreadsheet",
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": "spreadsheet",
  "application/vnd.google-apps.spreadsheet": "spreadsheet",
  "text/csv": "spreadsheet",
  "text/tab-separated-values": "spreadsheet",
  "application/vnd.ms-powerpoint": "presentation",
  "application/vnd.openxmlformats-officedocument.presentationml.presentation": "presentation",
  "application/vnd.google-apps.presentation": "presentation",
  "text/markdown": "text",
  "text/x-markdown": "text",
  "text/plain": "text",
  "text/html": "code",
  "application/json": "code",
  "application/xml": "code",
};

const EXTENSIONS: Record<string, DocumentKind> = {
  pdf: "pdf",
  doc: "document",
  docx: "document",
  odt: "document",
  rtf: "document",
  xls: "spreadsheet",
  xlsx: "spreadsheet",
  xlsm: "spreadsheet",
  ods: "spreadsheet",
  csv: "spreadsheet",
  tsv: "spreadsheet",
  ppt: "presentation",
  pptx: "presentation",
  odp: "presentation",
  md: "text",
  txt: "text",
  html: "code",
  json: "code",
  xml: "code",
  py: "code",
  js: "code",
  ts: "code",
  sql: "code",
  sh: "code",
  yaml: "code",
  yml: "code",
  png: "image",
  jpg: "image",
  jpeg: "image",
  gif: "image",
  webp: "image",
  svg: "image",
};

/**
 * One file-type classification for every surface (search, sources, chat files, attachments). The media type decides;
 * a file sent as octet-stream, text/plain or an unknown type falls back to its name's extension, as Onyx getFileIcon does.
 */
export function documentKind(
  mediaType: string | null | undefined,
  filename?: string | null,
): DocumentKind {
  const normalized = mediaType?.split(";", 1)[0]?.trim().toLowerCase() ?? "";
  const byType = KINDS[normalized] ?? (normalized.startsWith("image/") ? "image" : undefined);
  const extension = filename?.toLowerCase().match(/\.([a-z0-9]+)$/)?.[1];
  const byName = extension ? EXTENSIONS[extension] : undefined;
  // text/plain is what servers send for many source files; a more specific name (phan_tich.py) wins over it.
  if (byType && !(byType === "text" && normalized === "text/plain" && byName)) return byType;
  return byName ?? "generic";
}

/** Untranslated labels: callers pass them through `ui()`. */
export function documentSourceLabels(
  mediaType: string | null | undefined,
  sourceTypes: readonly DocumentSourceType[] | undefined,
) {
  return {
    type: mediaType ? friendlyMediaType(mediaType) : undefined,
    providers: [...new Set(sourceTypes ?? [])].map((type) =>
      type === "FILE" ? "Tệp tải lên" : (findSourceProvider(type)?.name ?? type),
    ),
  };
}

const FRIENDLY_MEDIA_TYPES: Record<string, string> = {
  "application/json": "JSON",
  "application/msword": "Word document",
  "application/pdf": "PDF",
  "application/vnd.ms-excel": "Excel spreadsheet",
  "application/vnd.ms-powerpoint": "PowerPoint presentation",
  "application/vnd.openxmlformats-officedocument.presentationml.presentation":
    "PowerPoint presentation",
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": "Excel spreadsheet",
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document": "Word document",
  "application/vnd.google-apps.document": "Google Docs",
  "application/vnd.google-apps.presentation": "Google Slides",
  "application/vnd.google-apps.spreadsheet": "Google Sheets",
  "text/csv": "CSV",
  "text/markdown": "Markdown",
  "text/x-markdown": "Markdown",
  "text/plain": "Text document",
};

export function friendlyMediaType(mediaType: string): string {
  const normalized = mediaType.split(";", 1)[0]?.trim().toLowerCase() ?? "";
  const known = FRIENDLY_MEDIA_TYPES[normalized];
  if (known) return known;
  if (normalized.startsWith("image/")) return "Image";
  if (normalized.startsWith("audio/")) return "Audio";
  if (normalized.startsWith("video/")) return "Video";
  return "Document";
}
