import { createContext, useContext } from "react";
import type { GeneratedFile } from "./chat-code";

/** UI selection only; content and authority remain in the runtime and readers. */
export const ChatPanelContext = createContext<{
  panelId?: string;
  messageId?: string;
  citationId?: number;
  fileId?: string;
  artifactId?: string;
  open: (messageId: string, trigger: HTMLElement, citationId?: number) => void;
  /** A file run_python generated carries its metadata so the panel previews it from the artifact routes. */
  openFile: (
    file: { id: string; filename: string; generated?: GeneratedFile },
    trigger: HTMLElement,
  ) => void;
  openArtifact: (messageId: string, artifactId: string, trigger: HTMLElement) => void;
  close: () => void;
}>({ open: () => {}, openFile: () => {}, openArtifact: () => {}, close: () => {} });

export function useChatFilePanel() {
  return useContext(ChatPanelContext);
}
