import type { DocumentSelection } from "@/features/search/document-preview-dialog";
import { matchingProvenance } from "@/features/search/source-provenance";
import type { ChatSource } from "./chat-evidence";

/** The cited passages of an indexed document or file citation, read with Chat authority. */
export function citationSelection(source: ChatSource): DocumentSelection {
  const ordinal = source.fileLocation?.ordinal;
  return {
    documentId: source.documentId ?? source.fileId!,
    generation: source.generation ?? source.fileLocation!.generation!,
    title: source.title,
    mediaType: source.mediaType,
    sourceTypes: source.sourceTypes,
    providerUrl: source.providerUrl,
    matches: [
      {
        from: Math.max(0, (ordinal ?? source.startOrdinal) - 2),
        matchingOrdinal: ordinal ?? source.startOrdinal,
        matchingEndOrdinal: ordinal ?? source.endOrdinal,
        provenance: matchingProvenance(source.provenance, ordinal ?? source.startOrdinal),
      },
    ],
    activeMatchIndex: 0,
  };
}
