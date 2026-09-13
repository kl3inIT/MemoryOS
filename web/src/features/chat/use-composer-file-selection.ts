import { useAui, useAuiState } from "@assistant-ui/react";
import { fileIdFromReference, fileReference, type ChatFile } from "./chat-files";

/** Composer attachments as server file identities, updated from the recent-file list. */
export function useComposerFileSelection() {
  const aui = useAui();
  const attachments = useAuiState((state) => state.composer.attachments);
  const identities = attachments.map((attachment) => {
    const part = attachment.content?.find((part) => part.type === "file");
    return part?.type === "file" && typeof part.data === "string"
      ? fileIdFromReference(part.data)
      : undefined;
  });
  return {
    full: attachments.length >= 20,
    selected: identities.filter((id): id is string => !!id),
    select(ids: string[], files: ChatFile[]) {
      attachments.forEach((attachment, index) => {
        if (identities[index] && !ids.includes(identities[index]))
          aui.composer.attachment({ id: attachment.id }).remove();
      });
      for (const file of files)
        if (!identities.includes(file.id))
          void aui.composer.addAttachment({
            id: file.id,
            name: file.filename,
            type: "file",
            contentType: file.mediaType,
            content: [
              {
                type: "file",
                filename: file.filename,
                mimeType: file.mediaType,
                data: fileReference(file.id),
                providerMetadata: { memoryos: { sizeBytes: file.sizeBytes } },
              },
            ],
          });
    },
  };
}
