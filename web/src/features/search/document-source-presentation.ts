import { findSourceProvider } from "@/features/sources/source-provider-catalog";
import { friendlyMediaType } from "./search-presentation";

export type DocumentSourceType = "FILE" | "GOOGLE_DRIVE";

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

export function documentKind(mediaType: string | null | undefined): DocumentKind {
  const normalized = mediaType?.split(";", 1)[0]?.trim().toLowerCase() ?? "";
  if (!normalized) return "generic";
  return KINDS[normalized] ?? (normalized.startsWith("image/") ? "image" : "generic");
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
