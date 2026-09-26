import { createContext } from "react";
import type { ChatSource } from "./chat-evidence";

export const emptySources: ChatSource[] = [];

/** The answer whose sources and citations are being rendered. */
export const EvidenceContext = createContext<{ messageId: string; sources: ChatSource[] }>({
  messageId: "",
  sources: emptySources,
});
