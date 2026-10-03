import { useQuery } from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import type { DocumentSelection } from "@/features/documents/document-preview-dialog";
import { previewKind, type PreviewKind } from "@/features/preview/preview-kind";
import { getSearchDocumentOptions } from "@/lib/hey-api/@tanstack/react-query.gen";

const searchRoute = getRouteApi("/_authenticated/search");

/** Originals the reader can only offer for download; a link carries no citation to switch to the text from. */
const DOWNLOAD_ONLY: ReadonlySet<PreviewKind> = new Set(["doc", "pptx", "unsupported"]);

/**
 * The document a `/search?doc=<id>` link names, read at its current generation with the reader's own access. MemoryOS
 * MCP gives Claude and ChatGPT this link for a document without a provider link; closing it drops `doc` and keeps
 * the rest of the address. It opens on the original, as a Chat citation does, unless the original can only be
 * downloaded, when it opens on the extracted text.
 */
export function useLinkedDocument() {
  const { doc } = searchRoute.useSearch();
  const navigate = searchRoute.useNavigate();
  const document = useQuery({
    ...getSearchDocumentOptions({ path: { documentId: doc ?? "" } }),
    enabled: doc !== undefined,
    retry: false,
  });
  const selection: DocumentSelection | null =
    doc !== undefined && document.data
      ? {
          documentId: document.data.documentId,
          generation: document.data.generation,
          title: document.data.title,
          // The kind of original decides the reader, as a citation's media type does in Chat.
          mediaType: document.data.mediaType,
          matches: [],
          activeMatchIndex: 0,
        }
      : null;
  const kind = selection && previewKind(selection.title, selection.mediaType ?? "");
  return {
    selection,
    view: kind && DOWNLOAD_ONLY.has(kind) ? ("passages" as const) : undefined,
    unavailable: doc !== undefined && document.isError,
    close: () =>
      void navigate({
        search: (current) => ({ ...current, doc: undefined }),
        replace: true,
        resetScroll: false,
      }),
  };
}
