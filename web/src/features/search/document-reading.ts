import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { useApplicationSession } from "@/features/identity/application-session-context";
import {
  getSearchDocument,
  readChatDocumentPassages,
  readChatFilePassages,
} from "@/lib/hey-api/sdk.gen";
import type { DocumentSelection } from "./document-preview-dialog";

export type PassageReader = {
  /** Chat citations read passages with Chat authority; the Search page keeps Search authority. */
  variant: "search" | "chat";
  documentId: string;
  generation: string;
  /** Owner-private Chat file whose indexed passages are cited, read with its own authority. */
  fileId?: string;
};

/** One authorized window of a document's passages. Each reader has its own cache entry, never a shared one. */
export function useDocumentPassages(
  { variant, documentId, generation, fileId }: PassageReader,
  from: number,
) {
  const { actorId, authorizationVersion } = useApplicationSession();
  return useQuery({
    queryFn: async ({ signal }) =>
      (fileId
        ? await readChatFilePassages({
            path: { fileId },
            query: { generation, from },
            signal,
            throwOnError: true,
          })
        : await (variant === "chat" ? readChatDocumentPassages : getSearchDocument)({
            path: { documentId },
            query: { generation, from },
            signal,
            throwOnError: true,
          })
      ).data,
    queryKey: [
      "document-preview",
      actorId,
      authorizationVersion,
      // Each reader has its own authority, so they never share cache entries or in-flight requests.
      fileId ? "chat-file" : variant,
      fileId ?? documentId,
      generation,
      from,
    ],
    retry: false,
    // Each opening reads the requested generation from the authorized reader.
    staleTime: 0,
    gcTime: 0,
  });
}

/**
 * What both views of one opened document share: which match is being read, which window of passages is shown,
 * and the cited text itself, which the original view locates and paints. The cited text is read at the match's
 * own window, so paging through the passages never moves the highlight off the citation.
 */
export function useDocumentReading(
  selection: DocumentSelection,
  variant: "search" | "chat",
  fileId?: string,
) {
  const reader: PassageReader = {
    variant,
    documentId: selection.documentId,
    generation: selection.generation,
    fileId,
  };
  const [activeMatchIndex, setActiveMatchIndex] = useState(selection.activeMatchIndex);
  const activeMatch = selection.matches[activeMatchIndex] ?? selection.matches[0];
  const [from, setFrom] = useState(activeMatch?.from ?? 0);
  const detail = useDocumentPassages(reader, from);
  const cited = useDocumentPassages(reader, activeMatch?.from ?? 0);
  const first = activeMatch?.matchingOrdinal;
  const last = activeMatch?.matchingEndOrdinal ?? first;
  // Keyed on the ordinals rather than the match object: a caller that rebuilds its selection each render
  // must not make the highlight repaint on every render.
  const citations = useMemo(
    () =>
      first === undefined || !cited.data
        ? []
        : cited.data.passages
            .filter((passage) => passage.ordinal >= first && passage.ordinal <= last!)
            .map((passage) => passage.content),
    [first, last, cited.data],
  );
  return {
    activeMatchIndex,
    activeMatch,
    from,
    detail,
    citations,
    select: (index: number) => {
      setActiveMatchIndex(index);
      setFrom(selection.matches[index]?.from ?? 0);
    },
    page: setFrom,
  };
}

export type DocumentReading = ReturnType<typeof useDocumentReading>;
