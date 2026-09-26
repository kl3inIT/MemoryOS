import { DocumentPreviewContent } from "@/features/search/document-preview-content";
import { documentOriginalReader } from "@/features/search/document-original-reader";
import { useDocumentReading } from "@/features/search/document-reading";
import type { EvidenceView } from "@/features/search/evidence-order";
import { EvidenceViewSwitch } from "@/features/search/evidence-view-switch";
import { readSourceLocation } from "@/features/search/source-provenance";
import { citationSelection } from "./chat-citation-selection";
import type { ChatSource } from "./chat-evidence";

/**
 * One document citation inside the narrow panel: its passages and its stored original share the reading
 * state, so the original opens on the same match and paints the passages that were actually cited.
 */
export function ChatDocumentEvidence({
  source,
  view,
  onViewChange,
}: {
  source: ChatSource;
  view?: EvidenceView;
  onViewChange: (view: EvidenceView) => void;
}) {
  const selection = citationSelection(source);
  const reading = useDocumentReading(selection, "chat", source.fileId ?? undefined);
  const location = readSourceLocation(selection.matches[0]!.provenance ?? []);
  const original =
    source.documentId && source.generation
      ? {
          reader: documentOriginalReader("chat", source.documentId, source.generation),
          filename: source.title,
          mediaType: source.mediaType,
          pages: location.pages,
          boxes: location.boxes,
          citations: reading.citations,
        }
      : undefined;
  return (
    <EvidenceViewSwitch
      original={original}
      table={location.table}
      view={view}
      onViewChange={onViewChange}
    >
      <DocumentPreviewContent variant="chat" selection={selection} reading={reading} />
    </EvidenceViewSwitch>
  );
}
