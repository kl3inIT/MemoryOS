import DOMPurify from "dompurify";

/** The first matching preview, in the Onyx PreviewModal variant order; anything else downloads. */
export type PreviewKind =
  | "code"
  | "image"
  | "pdf"
  | "csv"
  | "xlsx"
  | "markdown"
  | "docx"
  | "doc"
  | "text"
  | "unsupported";

/** Text-like previews read at most this many bytes. */
export const MAX_TEXT_PREVIEW_BYTES = 1024 * 1024;

const CODE_LANGUAGES: Record<string, string> = {
  py: "python",
  js: "javascript",
  ts: "typescript",
  sql: "sql",
  sh: "bash",
  html: "html",
  css: "css",
  json: "json",
  yaml: "yaml",
  yml: "yaml",
  xml: "xml",
};

function extension(filename: string): string {
  const dot = filename.lastIndexOf(".");
  return dot < 0 ? "" : filename.slice(dot + 1).toLowerCase();
}

export function previewKind(filename: string, mediaType: string): PreviewKind {
  const ext = extension(filename);
  if (CODE_LANGUAGES[ext]) return "code";
  if (["image/png", "image/jpeg", "image/webp"].includes(mediaType)) return "image";
  if (mediaType === "application/pdf") return "pdf";
  if (mediaType.startsWith("text/csv") || ext === "csv") return "csv";
  if (mediaType === "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    return "xlsx";
  if (mediaType === "text/markdown" || ext === "md") return "markdown";
  if (ext === "doc") return "doc";
  if (
    mediaType === "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
    ext === "docx"
  )
    return "docx";
  if (mediaType.startsWith("text/") || ext === "txt" || ext === "log") return "text";
  return "unsupported";
}

/** Shiki language for a code or data preview. */
export function codeLanguage(filename: string, kind: PreviewKind): string {
  if (kind === "markdown") return "markdown";
  return CODE_LANGUAGES[extension(filename)] ?? "text";
}

/**
 * RFC 4180 rows: quoted fields may hold commas, doubled quotes and line breaks. Onyx splits on commas,
 * which breaks the quoted cells the xlsx preview writes.
 */
export function parseCsv(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = "";
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    const char = text[i]!;
    if (quoted) {
      if (char !== '"') field += char;
      else if (text[i + 1] === '"') {
        field += '"';
        i++;
      } else quoted = false;
    } else if (char === '"') quoted = true;
    else if (char === ",") {
      row.push(field);
      field = "";
    } else if (char === "\n" || char === "\r") {
      if (char === "\r" && text[i + 1] === "\n") i++;
      row.push(field);
      rows.push(row);
      row = [];
      field = "";
    } else field += char;
  }
  if (field || row.length) {
    row.push(field);
    rows.push(row);
  }
  return rows;
}

/**
 * Onyx sanitizeDocxHtml: docx-preview writes document-controlled HTML and hrefs, so its output is sanitized
 * again. Only http(s) and mailto links survive; base64 images stay because DOMPurify allows data: in img.
 */
export function sanitizeDocxHtml(html: string): string {
  return DOMPurify.sanitize(html, { ALLOWED_URI_REGEXP: /^(?:https?|mailto):/i });
}
