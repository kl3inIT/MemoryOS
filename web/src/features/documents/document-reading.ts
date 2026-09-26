import { useQueries, useQuery, type UseQueryOptions } from "@tanstack/react-query";
import { useState } from "react";
import {
  getSearchDocumentOptions,
  readChatDocumentPassagesOptions,
  readChatFilePassagesOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SearchDocument } from "@/lib/hey-api/types.gen";
import type { DocumentSelection } from "./document-preview-dialog";
import { passageBody, passageSection } from "./passage";

export type PassageReader = {
  /** Chat citations read passages with Chat authority; the Search page keeps Search authority. */
  variant: "search" | "chat";
  documentId: string;
  generation: string;
  /** Owner-private Chat file whose indexed passages are cited, read with its own authority. */
  fileId?: string;
};

type Match = DocumentSelection["matches"][number];

/**
 * One authorized window of a document's passages. Each reader is its own generated operation, so a Chat file,
 * a Chat citation and a Search result never share a cache entry or an in-flight request.
 */
function passageWindow(
  { variant, documentId, generation, fileId }: PassageReader,
  from: number,
): UseQueryOptions<SearchDocument, Error, SearchDocument, readonly unknown[]> {
  const query = { generation, from };
  const options = fileId
    ? readChatFilePassagesOptions({ path: { fileId }, query })
    : variant === "chat"
      ? readChatDocumentPassagesOptions({ path: { documentId }, query })
      : getSearchDocumentOptions({ path: { documentId }, query });
  return {
    ...(options as UseQueryOptions<SearchDocument, Error, SearchDocument, readonly unknown[]>),
    retry: false,
    // Each opening reads the requested generation from the authorized reader.
    staleTime: 0,
    gcTime: 0,
  };
}

/**
 * The passages a match cites, without the chunk header and joined; the locator collapses whitespace, so the
 * join is one citation.
 */
function citedText(data: SearchDocument | undefined, match: Match) {
  if (!data) return "";
  const last = match.matchingEndOrdinal ?? match.matchingOrdinal;
  return data.passages
    .filter((passage) => passage.ordinal >= match.matchingOrdinal && passage.ordinal <= last)
    .map((passage) => passageBody(passage.content))
    .join("\n");
}

/** The heading trail the first cited passage records, which is the context the citation is read in. */
function citedSection(data: SearchDocument | undefined, match: Match) {
  const first = data?.passages.find((passage) => passage.ordinal === match.matchingOrdinal);
  return first ? passageSection(first.content) : undefined;
}

/**
 * What both views of one opened document share: which citation is being read, which window of passages is
 * shown, and the cited text itself, which the original view locates and paints. Every match's text is read
 * at its own window, so paging through the passages never moves a highlight off its citation.
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
  const matches = selection.matches;
  const [activeMatchIndex, setActiveMatchIndex] = useState(selection.activeMatchIndex);
  const activeMatch = matches[activeMatchIndex] ?? matches[0];
  const [from, setFrom] = useState(activeMatch?.from ?? 0);
  const detail = useQuery(passageWindow(reader, from));
  // Each match reads its own window; several matches usually share one and so read it once. The combined
  // list is structurally shared by react-query, which is what keeps the original from repainting and
  // scrolling on every render.
  const windows = [...new Set(matches.map((match) => match.from))];
  const cited = useQueries({
    queries: windows.map((window) => passageWindow(reader, window)),
    combine: (results) => ({
      citations: matches.map((match) =>
        citedText(results[windows.indexOf(match.from)]?.data, match),
      ),
      sections: matches.map((match) =>
        citedSection(results[windows.indexOf(match.from)]?.data, match),
      ),
    }),
  });

  return {
    activeMatchIndex,
    activeMatch,
    from,
    detail,
    /** One entry per match, in the order the citation rail lists them. */
    citations: cited.citations,
    /** The heading trail each citation sits under, shown beside it rather than searched for. */
    sections: cited.sections,
    select: (index: number) => {
      setActiveMatchIndex(index);
      setFrom(matches[index]?.from ?? 0);
    },
    page: setFrom,
  };
}

export type DocumentReading = ReturnType<typeof useDocumentReading>;
