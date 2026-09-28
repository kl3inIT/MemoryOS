import { useAuiState } from "@assistant-ui/react";
import { fileIdFromReference } from "@/features/library/files";

/** Business readiness layered on the native composer; no duplicate draft state. */
export function useFilesBlocked() {
  return useAuiState(
    (state) =>
      state.composer.attachments.length > 20 ||
      state.composer.attachments.some(
        (file) =>
          file.status.type === "running" ||
          file.status.type === "incomplete" ||
          !file.content?.some(
            (part) =>
              part.type === "file" &&
              typeof part.data === "string" &&
              fileIdFromReference(part.data),
          ),
      ),
  );
}
