import { createContext, useContext } from "react";
import type { PreviewTarget } from "@/features/library/file-preview-modal";

/** UI selection only; content and authority remain in the runtime and readers. */
export const ChatPanelContext = createContext<{
  panelId?: string;
  messageId?: string;
  citationId?: number;
  artifactId?: string;
  open: (messageId: string, trigger: HTMLElement, citationId?: number) => void;
  /** Opens a chat file in the centered preview modal, as Onyx PreviewModal. */
  previewFile: (target: PreviewTarget, trigger: HTMLElement) => void;
  openArtifact: (messageId: string, artifactId: string, trigger: HTMLElement) => void;
  close: () => void;
}>({ open: () => {}, previewFile: () => {}, openArtifact: () => {}, close: () => {} });

export function useChatFilePanel() {
  return useContext(ChatPanelContext);
}
