import { MAX_TEXT_PREVIEW_BYTES, type PreviewKind, type Sheets } from "./preview-kind";

type TextContent = {
  text: string;
  truncated: boolean;
  /** The whole file's size, which the text itself does not tell once it is cut. */
  bytes: number;
};

/**
 * A file read far enough for its view: a PDF is handed to pdf.js as a same-origin URL it reads by range, or as
 * the file itself; a workbook arrives already parsed per sheet; text is read up to the preview ceiling.
 */
export type PreviewContent =
  | { kind: "pdf"; file: string | Blob }
  | { kind: "xlsx"; sheets: Sheets }
  | ({ kind: "csv" | "markdown" } & TextContent)
  | ({ kind: "code" | "text" } & TextContent)
  | { kind: "image" | "docx"; blob: Blob }
  | { kind: "doc" | "pptx" | "unsupported" };

/**
 * The content a downloaded file gives its view. A workbook is not among them: the app ships no workbook
 * parser, so a surface asks its sheet route for a workbook instead of downloading it.
 */
export async function readPreviewContent(
  blob: Blob,
  kind: Exclude<PreviewKind, "xlsx">,
): Promise<PreviewContent> {
  switch (kind) {
    case "pdf":
      return { kind, file: blob };
    case "image":
    case "docx":
      return { kind, blob };
    case "csv":
    case "markdown":
    case "code":
    case "text":
      return {
        kind,
        text: await blob.slice(0, MAX_TEXT_PREVIEW_BYTES).text(),
        truncated: blob.size > MAX_TEXT_PREVIEW_BYTES,
        bytes: blob.size,
      };
    default:
      return { kind };
  }
}
