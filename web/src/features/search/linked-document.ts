import { useQuery } from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import type { DocumentSelection } from "@/features/documents/document-preview-dialog";
import { getSearchDocumentOptions } from "@/lib/hey-api/@tanstack/react-query.gen";

const searchRoute = getRouteApi("/_authenticated/search");

/**
 * The document a `/search?doc=<id>` link names, read at its current generation with the reader's own access. MemoryOS
 * MCP gives Claude and ChatGPT this link for a document without a provider link; closing it drops `doc` and keeps
 * the rest of the address.
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
          matches: [],
          activeMatchIndex: 0,
        }
      : null;
  return {
    selection,
    unavailable: doc !== undefined && document.isError,
    close: () =>
      void navigate({
        search: (current) => ({ ...current, doc: undefined }),
        replace: true,
        resetScroll: false,
      }),
  };
}
