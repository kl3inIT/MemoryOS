import DOMPurify from "dompurify";
import { fileArtifactUrl } from "./chat-code";

/** A chat file to preview: a file run_python generated or an attachment, each read through its owner route. */
export type PreviewTarget = {
  source: "generated" | "attachment";
  id: string;
  filename: string;
  /** The stored type when known; a link in an answer only knows the name. */
  mediaType?: string;
};

export function downloadUrl(target: PreviewTarget): string {
  return target.source === "generated"
    ? fileArtifactUrl(target.id)
    : `/api/chat/files/${encodeURIComponent(target.id)}/content`;
}

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
  | "pptx"
  | "unsupported";

/** Text-like previews read at most this many bytes. */
export const MAX_TEXT_PREVIEW_BYTES = 1024 * 1024;

/** Code and data files by extension, as Onyx getCodeLanguage/getDataLanguage; the value is the Shiki language. */
const CODE_LANGUAGES: Record<string, string> = {
  py: "python",
  ipynb: "json",
  js: "javascript",
  jsx: "jsx",
  mjs: "javascript",
  ts: "typescript",
  tsx: "tsx",
  java: "java",
  kt: "kotlin",
  go: "go",
  rs: "rust",
  rb: "ruby",
  php: "php",
  c: "c",
  h: "c",
  cpp: "cpp",
  cc: "cpp",
  hpp: "cpp",
  cs: "csharp",
  swift: "swift",
  scala: "scala",
  r: "r",
  sql: "sql",
  sh: "bash",
  bash: "bash",
  ps1: "powershell",
  html: "html",
  css: "css",
  scss: "scss",
  json: "json",
  yaml: "yaml",
  yml: "yaml",
  toml: "toml",
  xml: "xml",
  ini: "ini",
  dockerfile: "dockerfile",
};

function extension(filename: string): string {
  const dot = filename.lastIndexOf(".");
  return dot < 0 ? "" : filename.slice(dot + 1).toLowerCase();
}

export function previewKind(filename: string, mediaType: string): PreviewKind {
  const ext = extension(filename);
  const type = mediaType.split(";")[0]!.trim().toLowerCase();
  if (CODE_LANGUAGES[ext]) return "code";
  if (["image/png", "image/jpeg", "image/webp", "image/gif"].includes(type)) return "image";
  if (type === "application/pdf") return "pdf";
  if (type === "text/csv" || ext === "csv") return "csv";
  if (
    type === "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ||
    ext === "xlsx"
  )
    return "xlsx";
  if (type === "text/markdown" || ext === "md") return "markdown";
  if (ext === "doc" || type === "application/msword") return "doc";
  if (
    type === "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
    ext === "docx"
  )
    return "docx";
  if (type.startsWith("text/") || ["txt", "log", "conf", "tsv"].includes(ext)) return "text";
  if (
    type === "application/vnd.openxmlformats-officedocument.presentationml.presentation" ||
    ext === "pptx"
  )
    return "pptx";
  return "unsupported";
}

/** Shiki language for a code, markdown or text preview. */
export function codeLanguage(filename: string, kind: PreviewKind): string {
  if (kind === "markdown") return "markdown";
  return CODE_LANGUAGES[extension(filename)] ?? "text";
}

/** Onyx variant sizes: documents, tables and images (and a presentation's PDF) take the screen; code and text a large window. */
export function previewSize(kind: PreviewKind): "full" | "large" | "tall" {
  if (kind === "code" || kind === "text") return "large";
  if (kind === "unsupported" || kind === "doc") return "tall";
  return "full";
}

export function lineCount(text: string): number {
  return text ? text.split("\n").length : 0;
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
