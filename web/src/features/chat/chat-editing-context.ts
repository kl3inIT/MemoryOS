import { createContext } from "react";
import type { Branch, Feedback } from "./chat-workspace-api";
export const ChatEditingContext = createContext<{
  sessionId?: string;
  busy: boolean;
  branches: Branch[];
  feedback: Feedback[];
  edit: (id: string, text: string, requestId: string, fileIds?: string[]) => Promise<void>;
  /** Without a model, regeneration uses the composer's current model choice. */
  regenerate: (userId: string, requestId: string, modelConfigurationId?: string) => Promise<void>;
  branch: (targetId: string, expectedChildId: string) => Promise<void>;
} | null>(null);
