import { useChat } from "@ai-sdk/react";
import { useAISDKRuntime } from "@assistant-ui/ai-sdk";
import {
  AssistantRuntimeProvider,
  useAuiState,
  useRemoteThreadListRuntime,
} from "@assistant-ui/react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useRouterState } from "@tanstack/react-router";
import { useEffect, useMemo, useRef, useState, useSyncExternalStore, type ReactNode } from "react";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { useChatDictationAdapter } from "@/features/voice/use-chat-dictation-adapter";
import { useChatSpeechAdapter } from "@/features/voice/use-chat-speech-adapter";
import { chatSessionsKey, type ChatUiMessage } from "./chat-api";
import { createChatAttachmentAdapter } from "./chat-files";
import { ChatThreadRegistry } from "./chat-thread-controller";
import { chatHistoryAdapter, createChatThreadListAdapter } from "./chat-thread-list-adapter";
import { ChatThreadsContext } from "./chat-threads-context";

const isChatRoute = (pathname: string) =>
  pathname === "/" || /^\/chat\/[^/]+$/.test(pathname) || /^\/projects\/[^/]+$/.test(pathname);

/**
 * One assistant-ui remote thread list for the authenticated application, so the sidebar and the
 * conversation share list state. Only the visible thread is mounted: the AI SDK integration does
 * not expose background threads for custom adapters.
 */
export function ChatRuntimeProvider({ children }: { children: ReactNode }) {
  const queries = useQueryClient();
  const { actorId, authorizationVersion } = useApplicationSession();
  const navigate = useNavigate();
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  // The layout route above the chat routes does not receive their params.
  const sessionId = /^\/chat\/([^/]+)$/.exec(pathname)?.[1];
  const [registry] = useState(() => new ChatThreadRegistry(queries, actorId));
  const [adapter] = useState(() => createChatThreadListAdapter(registry, queries));
  const [runtimeHook] = useState(
    () =>
      function useMemoryOsThreadRuntime() {
        return useChatThreadRuntime(registry);
      },
  );
  const path = useRef(pathname);
  useEffect(() => {
    path.current = pathname;
  }, [pathname]);
  const runtime = useRemoteThreadListRuntime({
    adapter,
    runtimeHook,
    onThreadIdChange: (id) => {
      const current = path.current;
      if (id && isChatRoute(current) && current !== `/chat/${id}`)
        void navigate({ to: "/chat/$sessionId", params: { sessionId: id }, replace: true });
      // Deleting the open conversation moves the list to a new thread.
      else if (!id && current.startsWith("/chat/")) void navigate({ to: "/" });
    },
  });

  const [failure, setFailure] = useState<{ sessionId: string; attempt: number }>();
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    if (!isChatRoute(pathname)) return;
    const threads = runtime.threads;
    if (sessionId)
      threads.switchToThread(sessionId).catch(() => setFailure({ sessionId, attempt }));
    else if (threads.mainItem.getState().status !== "new") void threads.switchToNewThread();
  }, [pathname, sessionId, runtime, attempt]);
  // A retry or another route clears the failure without resetting state inside the effect.
  const routeError =
    failure && failure.sessionId === sessionId && failure.attempt === attempt
      ? failure.sessionId
      : undefined;

  // Existing mutations invalidate this key; observing it turns those invalidations into list reloads.
  useQuery({
    queryKey: chatSessionsKey,
    queryFn: async () => {
      await runtime.threads.reload();
      return Date.now();
    },
    initialData: 0,
    staleTime: Infinity,
    gcTime: Infinity,
    refetchOnWindowFocus: false,
    retry: false,
  });
  const authorization = useRef(authorizationVersion);
  useEffect(() => {
    if (authorization.current === authorizationVersion) return;
    authorization.current = authorizationVersion;
    void queries.invalidateQueries({ queryKey: chatSessionsKey });
  }, [authorizationVersion, queries]);

  const value = useMemo(
    () => ({ runtime, registry, routeError, retryRoute: () => setAttempt((n) => n + 1) }),
    [runtime, registry, routeError],
  );
  return (
    <ChatThreadsContext.Provider value={value}>
      <AssistantRuntimeProvider runtime={runtime}>{children}</AssistantRuntimeProvider>
    </ChatThreadsContext.Provider>
  );
}

function useChatThreadRuntime(registry: ChatThreadRegistry) {
  const id = useAuiState((state) => state.threadListItem.id);
  const remoteId = useAuiState((state) => state.threadListItem.remoteId);
  const controller = registry.obtain(id, remoteId);
  const state = useSyncExternalStore(controller.subscribe, controller.getState);
  const [attachments] = useState(() => createChatAttachmentAdapter(controller.setAttachmentError));
  const [history] = useState(() => chatHistoryAdapter(controller));
  const dictation = useChatDictationAdapter();
  const speech = useChatSpeechAdapter();
  const chat = useChat<ChatUiMessage>({
    id,
    transport: controller.transport,
    // Server request IDs are UUIDs. No client-side model/tool continuation.
    generateId: () => crypto.randomUUID(),
    sendAutomaticallyWhen: () => false,
    throttle: 50,
    onError: () => controller.markUnfinished(),
  });
  const runtime = useAISDKRuntime(chat, {
    adapters: { attachments, history, dictation, speech },
    isSendDisabled: state.connection !== "ready" || state.unavailable || state.checking,
  });
  useEffect(() => {
    controller.connect(chat, () => runtime.thread.cancelRun());
  });
  useEffect(() => {
    const release = registry.retain(controller);
    const unlisten = controller.listen();
    return () => {
      unlisten();
      release();
    };
  }, [registry, controller]);
  useEffect(() => () => attachments.cancelPending(), [attachments]);
  const isMain = useAuiState((state) => state.threads.mainThreadId === state.threadListItem.id);
  // Sidebar links to non-chat pages (Search documents, Assistants, the Projects list) do not switch
  // the main thread; the reader must close there too, while the server run continues. A project
  // conversation page (/projects/$projectId) is a chat route that switches to its own thread.
  const onChatRoute = useRouterState({ select: (state) => isChatRoute(state.location.pathname) });
  useEffect(() => controller.setVisible(isMain && onChatRoute), [controller, isMain, onChatRoute]);
  const loading = useSyncExternalStore(
    runtime.thread.subscribe,
    () => runtime.thread.getState().isLoading,
  );
  useEffect(() => {
    if (!loading && state.resume) controller.resumePending();
  }, [loading, state.resume, controller]);
  return runtime;
}
