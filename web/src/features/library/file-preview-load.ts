import { queryOptions } from "@tanstack/react-query";
import { readPreviewContent, type PreviewContent } from "@/features/preview/preview-content";
import { previewKind, sheetsSchema, type Sheets } from "@/features/preview/preview-kind";
import {
  downloadChatFile,
  getChatFileArtifact,
  getChatFileArtifactPdfPreview,
  getChatImageArtifact,
  previewChatFileArtifactSpreadsheet,
  previewChatFileSpreadsheet,
} from "@/lib/hey-api/sdk.gen";
import type { PreviewTarget } from "./file-preview";

async function readBlob(target: PreviewTarget, signal: AbortSignal): Promise<Blob> {
  const { data } =
    target.source === "generated"
      ? await getChatFileArtifact({ path: { artifactId: target.id }, parseAs: "blob", signal })
      : target.source === "image"
        ? await getChatImageArtifact({ path: { artifactId: target.id }, parseAs: "blob", signal })
        : await downloadChatFile({ path: { fileId: target.id }, parseAs: "blob", signal });
  if (!(data instanceof Blob)) throw new Error("Invalid file content");
  return data;
}

async function readSheets(target: PreviewTarget, signal: AbortSignal): Promise<Sheets> {
  const { data } =
    target.source === "generated"
      ? await previewChatFileArtifactSpreadsheet({ path: { artifactId: target.id }, signal })
      : await previewChatFileSpreadsheet({ path: { fileId: target.id }, signal });
  return sheetsSchema.parse(data).sheets;
}

/** A deck's PDF rendering is shown in place of the deck, and the header says so. */
export type Loaded = PreviewContent & { converted?: boolean };

/**
 * Onyx PreviewModal fetch: resolve the variant by name, then by the stored type the response reports, so a
 * workbook is read through the parsed spreadsheet route and never as bytes.
 */
async function load(target: PreviewTarget, signal: AbortSignal): Promise<Loaded> {
  const known = previewKind(target.filename, target.mediaType ?? "application/octet-stream");
  if (known === "xlsx") return { kind: "xlsx", sheets: await readSheets(target, signal) };
  if (known === "pptx") {
    // Onyx Craft converts decks with LibreOffice in its sandbox; the API converts in the interpreter executor
    // and caches the PDF, which the pdf.js reader shows. Attachments are not converted.
    if (target.source !== "generated") return { kind: "unsupported" };
    const { data } = await getChatFileArtifactPdfPreview({
      path: { artifactId: target.id },
      parseAs: "blob",
      signal,
    });
    if (!(data instanceof Blob)) throw new Error("Invalid preview");
    return { kind: "pdf", file: data.slice(0, data.size, "application/pdf"), converted: true };
  }
  if (known === "doc" || (known === "unsupported" && target.mediaType)) return { kind: known };
  const blob = await readBlob(target, signal);
  const stored =
    target.mediaType ?? (blob.type && blob.type !== "application/octet-stream" ? blob.type : "");
  const kind = previewKind(target.filename, stored || "application/octet-stream");
  if (kind === "xlsx") return { kind, sheets: await readSheets(target, signal) };
  if (kind === "pptx") return { kind: "unsupported" };
  // The attachment route serves octet-stream under nosniff; give the viewer the stored type.
  return readPreviewContent(
    stored && blob.type !== stored ? blob.slice(0, blob.size, stored) : blob,
    kind,
  );
}

/**
 * What the preview shows of one file. The read picks one of several routes by the file's source and kind, so
 * it has no single generated query; its key names the file, and it is read afresh on every opening because
 * the bytes are held only while the preview is.
 */
export function filePreviewOptions(target: PreviewTarget) {
  return queryOptions({
    queryKey: ["chat-file-preview", target.source, target.id],
    queryFn: ({ signal }) => load(target, signal),
    gcTime: 0,
    staleTime: 0,
    retry: false,
  });
}
