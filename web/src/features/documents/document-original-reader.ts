import type { OriginalReader } from "@/features/preview/original-view";
import { sheetsSchema } from "@/features/preview/preview-kind";
import { client } from "@/lib/hey-api/client.gen";
import {
  readChatDocumentOriginal,
  readChatDocumentSpreadsheet,
  readSearchDocumentOriginal,
  readSearchDocumentSpreadsheet,
} from "@/lib/hey-api/sdk.gen";

/**
 * Chat citations read originals with Chat authority; the Search page keeps Search authority. The two routes
 * differ only in which capability they require, so the reader hides which one a surface holds.
 */
export function documentOriginalReader(
  variant: "search" | "chat",
  documentId: string,
  generation: string,
): OriginalReader {
  const path = { documentId };
  const query = { generation };
  const url = client.buildUrl({
    url:
      variant === "chat"
        ? "/api/chat/documents/{documentId}/original"
        : "/api/search/documents/{documentId}/original",
    path,
    query,
  });
  return {
    url,
    bytes: async (signal) => {
      const { data } = await (
        variant === "chat" ? readChatDocumentOriginal : readSearchDocumentOriginal
      )({ path, query, parseAs: "blob", signal });
      if (!(data instanceof Blob)) throw new Error("Invalid original content");
      return data;
    },
    sheets: async (signal) => {
      const { data } = await (
        variant === "chat" ? readChatDocumentSpreadsheet : readSearchDocumentSpreadsheet
      )({ path, query, signal });
      return sheetsSchema.parse(data).sheets;
    },
  };
}
