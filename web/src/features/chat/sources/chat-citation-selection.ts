import type { DocumentSelection } from "@/features/documents/document-preview-dialog";
import { matchingProvenance } from "@/features/documents/source-provenance";
import type { ChatSource } from "./chat-evidence";

/**
 * The cited passages of an indexed document or file citation, read with Chat authority. As in Search, the
 * original is given only the passage that matched: the neighbours the model also read stay in the passage list
 * without being drawn. An answer saved before the match was recorded draws its whole cited range, as it did.
 */
export function citationSelection(source: ChatSource): DocumentSelection {
  const matched = source.fileLocation?.ordinal ?? source.matchingOrdinal ?? undefined;
  const first = matched ?? source.startOrdinal;
  return {
    documentId: source.documentId ?? source.fileId!,
    generation: source.generation ?? source.fileLocation!.generation!,
    title: source.title,
    mediaType: source.mediaType,
    sourceTypes: source.sourceTypes,
    providerUrl: source.providerUrl,
    matches: [
      {
        from: Math.max(0, first - 2),
        matchingOrdinal: first,
        matchingEndOrdinal: matched ?? source.endOrdinal,
        provenance:
          matched === undefined
            ? matchingProvenance(source.provenance, first)
            : source.provenance
                .filter((chunk) => chunk.ordinal === matched)
                .map((chunk) => chunk.provenanceJson),
      },
    ],
    activeMatchIndex: 0,
  };
}
