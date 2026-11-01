import { createContext, type ComponentType } from "react";

/**
 * What a thread's host draws under each answer, beside the cards every answer has: the assistant editor's Agent
 * Builder draws the changes a reply made to its form (MEM-280). Absent everywhere else.
 */
export const ChatReplyAddon = createContext<ComponentType | null>(null);
