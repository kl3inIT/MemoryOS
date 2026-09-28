import type { AssistantRuntime } from "@assistant-ui/react";
import { useQueryClient } from "@tanstack/react-query";
import { createContext, use, useCallback } from "react";
import { invalidateChatSessions } from "@/features/chat/chat-api";
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
  const threads = use(ChatThreadsContext);
  if (!threads) throw new Error("ChatRuntimeProvider is missing");
  return threads;
}

/** Shell components rendered outside the application boundary (isolated tests) have no thread list. */
export function useOptionalChatThreads() {
  return use(ChatThreadsContext);
}

/**
 * Refreshes conversations after a change made outside the thread list, such as a move, a bulk archive or a
 * retention purge: the cached conversation queries and the assistant-ui thread list, which is runtime state
 * rather than a query. The returned promise settles once both have been read again.
 */
export function useRefreshChatSessions() {
  const cache = useQueryClient();
  const threads = use(ChatThreadsContext);
  return useCallback(
    (sessionId?: string) =>
      Promise.all([invalidateChatSessions(cache, sessionId), threads?.runtime.threads.reload()]),
    [cache, threads],
  );
}
