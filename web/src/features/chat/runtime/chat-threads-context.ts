import type { AssistantRuntime } from "@assistant-ui/react";
import { createContext, useContext } from "react";
import type { ChatThreadRegistry } from "./chat-thread-controller";

export type ChatThreads = {
  runtime: AssistantRuntime;
  registry: ChatThreadRegistry;
  /** Session route that could not be opened, for example after deletion or revoked access. */
  routeError?: string;
  retryRoute: () => void;
};

export const ChatThreadsContext = createContext<ChatThreads | null>(null);

export function useChatThreads() {
  const threads = useContext(ChatThreadsContext);
  if (!threads) throw new Error("ChatRuntimeProvider is missing");
  return threads;
}

/** Shell components rendered outside the application boundary (isolated tests) have no thread list. */
export function useOptionalChatThreads() {
  return useContext(ChatThreadsContext);
}
