import { previewKind } from "@/features/preview/preview-kind";

export type EvidenceView = "passages" | "original";

/** Kinds whose layout carries meaning the extracted text drops: pages, tables, columns, the picture itself. */
const LAID_OUT = new Set(["pdf", "docx", "xlsx", "image"]);

/**
 * Which view a citation opens on. A laid-out original is the evidence, so it opens first; for text whose
 * original adds nothing, and for anything this app cannot show, the passages open first.
 */
export function firstEvidenceView(filename: string, mediaType: string): EvidenceView {
  return LAID_OUT.has(previewKind(filename, mediaType)) ? "original" : "passages";
}
