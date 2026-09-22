import { createContext, useContext } from "react";
import type { ComposerPendingAttachment } from "@/components/assistant-ui/elements/attachment.aui";
import type { LibraryFile } from "./chat-library";

export type AttachmentStaging = {
  /** Files being copied into uploads, plus the ones whose copy failed, as composer tiles. */
  pending: ComposerPendingAttachment[];
  /** How many files are still on their way to the draft; they count against the message's file limit. */
  preparing: number;
  /** Puts library files on the draft: the copy runs here, so the composer shows the wait, not a dialog. */
  attach: (files: readonly LibraryFile[]) => void;
};

export const AttachmentStagingContext = createContext<AttachmentStaging | undefined>(undefined);

export function useChatAttachmentStaging(): AttachmentStaging {
  const staging = useContext(AttachmentStagingContext);
  if (!staging) throw new Error("Attachment staging requires ChatAttachmentStaging");
  return staging;
}
