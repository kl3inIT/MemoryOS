import { createContext, useContext } from "react";

/** UI selection only; content and authority remain in the runtime and readers. */
export const ChatPanelContext = createContext<{
  panelId?: string;
  messageId?: string;
  fileId?: string;
  artifactId?: string;
  open: (messageId: string, trigger: HTMLElement, citationId?: number) => void;
  openFile: (file: { id: string; filename: string }, trigger: HTMLElement) => void;
  openArtifact: (messageId: string, artifactId: string, trigger: HTMLElement) => void;
  close: () => void;
}>({ open: () => {}, openFile: () => {}, openArtifact: () => {}, close: () => {} });

export function useChatFilePanel() {
  return useContext(ChatPanelContext);
}
