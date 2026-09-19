import { findSourceProvider } from "@/features/sources/source-provider-catalog";
import { friendlyMediaType } from "./search-presentation";

export type DocumentSourceType = "FILE" | "GOOGLE_DRIVE" | "SHAREPOINT";

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
