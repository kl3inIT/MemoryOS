import { AssistantRuntimeProvider, useAui } from "@assistant-ui/react";
import { useAISDKChat, useChatRuntime } from "@assistant-ui/ai-sdk";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useParams } from "@tanstack/react-router";
import { useEffect, useImperativeHandle, useRef, useState, type RefObject } from "react";
import { AppShell } from "@/components/app-shell/app-shell";
import { Button } from "@/components/ui/button";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { ApiError } from "@/lib/api";
import { getCurrentIdentityQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import { chatSessionsKey, loadChatHistory, toUiMessages, type ChatHistory } from "./chat-api";
import { MemoryOsChatTransport, type ConnectionState } from "./chat-transport";
import { ChatThread } from "./chat-thread";

export function ExistingChatPage() {
  const { sessionId } = useParams({ from: "/_authenticated/chat/$sessionId" });
  return <ChatPage sessionId={sessionId} />;
}

export function ChatPage({ sessionId }: { sessionId?: string }) {
  const identity = useApplicationSession();
  const authority = JSON.stringify([
    identity.actorId,
    identity.authorizationVersion,
    identity.capabilities,
    identity.scopedCapabilities,
  ]);
  return (
    <AppShell pageTitle="Chat">
      <ChatSessionView key={`${authority}:${sessionId ?? "new"}`} sessionId={sessionId} />
    </AppShell>
  );
}

function ChatSessionView({ sessionId }: { sessionId?: string }) {
  const query = useQuery({
    queryKey: ["chat-history", sessionId],
    queryFn: ({ signal }) => loadChatHistory(sessionId!, signal),
    enabled: !!sessionId,
    staleTime: 0,
    refetchOnWindowFocus: false,
    retry: false,
  });
  if (sessionId && (query.isPending || query.isFetching))
    return (
      <p role="status" className="p-6 text-content-secondary">
        Loading conversation…
      </p>
    );
  if (sessionId && query.isError)
    return (
      <div role="alert" className="space-y-3 p-6">
        <p>This conversation could not be loaded.</p>
        <Button prominence="secondary" onClick={() => void query.refetch()}>
          Try again
        </Button>
      </div>
    );
  return <ChatConversation initial={query.data} />;
}

function ChatConversation({ initial }: { initial?: ChatHistory }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const running = initial?.messages.find((message) => message.status === "RUNNING");
  const [transport] = useState(() => new MemoryOsChatTransport(initial?.session, running));
  const [connection, setConnection] = useState<ConnectionState>(running ? "recovering" : "ready");
  const [error, setError] = useState<string>();
  const [unavailable, setUnavailable] = useState(false);
  const [stopping, setStopping] = useState(false);
  const [checking, setChecking] = useState(false);
  const active = useRef(true);
  const controls = useRef<{ check: () => Promise<void> }>(null);
  const runtime = useChatRuntime({
    transport,
    // Server request IDs are UUIDs. No client-side model/tool continuation.
    generateId: () => crypto.randomUUID(),
    messages: toUiMessages(
      initial?.messages.filter((message) => message.status !== "RUNNING") ?? [],
    ),
    sendAutomaticallyWhen: () => false,
    isSendDisabled: connection !== "ready" || unavailable || checking,
    throttle: 50,
    onError: () => {
      if (active.current)
        setError("The reply could not finish. Check the saved conversation before sending again.");
    },
  });
  useEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
      transport.disconnect();
    };
  }, [transport]);

  function handleError(cause: unknown) {
    if (!active.current) return;
    if (cause instanceof ApiError && [401, 403, 404].includes(cause.status ?? 0)) {
      setUnavailable(true);
      transport.disconnect();
      void queryClient.invalidateQueries({ queryKey: getCurrentIdentityQueryKey() });
    } else setError("Connection interrupted. Check the saved conversation before sending again.");
  }

  async function stop() {
    if (stopping) return;
    setStopping(true);
    try {
      await transport.stop();
    } catch (cause) {
      handleError(cause);
      if (active.current) setStopping(false);
    }
  }

  if (unavailable)
    return (
      <p role="alert" className="p-6">
        This conversation is no longer available.
      </p>
    );
  return (
    <AssistantRuntimeProvider runtime={runtime}>
      <ChatRuntimeBridge
        transport={transport}
        resume={!!running}
        controls={controls}
        onState={(state) => {
          if (active.current) {
            setConnection(state);
            if (state === "sending") setError(undefined);
            if (state === "ready" || state === "uncertain") setStopping(false);
          }
        }}
        onError={handleError}
        onAccepted={(sessionId) => {
          void queryClient.invalidateQueries({ queryKey: chatSessionsKey });
          if (!initial)
            void navigate({ to: "/chat/$sessionId", params: { sessionId }, replace: true });
        }}
      />
      <ChatThread
        connection={connection}
        stopping={stopping}
        onStop={() => void stop()}
        error={error}
        onCheck={async () => {
          setChecking(true);
          setError(undefined);
          try {
            await controls.current?.check();
          } catch (cause) {
            handleError(cause);
          } finally {
            if (active.current) setChecking(false);
          }
        }}
        checking={checking}
      />
    </AssistantRuntimeProvider>
  );
}

function ChatRuntimeBridge({
  transport,
  resume,
  controls,
  onState,
  onError,
  onAccepted,
}: {
  transport: MemoryOsChatTransport;
  resume: boolean;
  controls: RefObject<{ check: () => Promise<void> } | null>;
  onState: (state: ConnectionState) => void;
  onError: (error: unknown) => void;
  onAccepted: (sessionId: string) => void;
}) {
  const chat = useAISDKChat();
  const sdkReady = chat !== undefined;
  const aui = useAui();
  const latest = useRef({ chat, aui, onState, onError, onAccepted });
  const lifetime = useRef<AbortController | null>(null);
  useEffect(() => {
    latest.current = { chat, aui, onState, onError, onAccepted };
  }, [chat, aui, onState, onError, onAccepted]);
  useImperativeHandle(
    controls,
    () => ({
      check: async () => {
        const signal = lifetime.current?.signal;
        if (!signal) return;
        if (!transport.session) {
          latest.current.chat?.setMessages([]);
          latest.current.chat?.clearError();
          latest.current.onState("ready");
          return;
        }
        transport.disconnect();
        await latest.current.chat?.stop();
        const history = await loadChatHistory(transport.session.id, signal);
        signal.throwIfAborted();
        transport.restore(history.session, history.messages);
        latest.current.chat?.setMessages(
          toUiMessages(history.messages.filter((message) => message.status !== "RUNNING")),
        );
        latest.current.chat?.clearError();
        if (history.messages.some((message) => message.status === "RUNNING")) {
          latest.current.onState("recovering");
          await latest.current.chat?.resumeStream();
        } else latest.current.onState("ready");
      },
    }),
    [transport],
  );
  useEffect(() => {
    if (!sdkReady) return;
    const controller = new AbortController();
    lifetime.current = controller;
    const unlisten = transport.listen({
      state: (state) => latest.current.onState(state),
      error: (error) => latest.current.onError(error),
      canceled: () => latest.current.aui.thread.cancelRun(),
      accepted: (session, userId, localId) => {
        latest.current.chat?.setMessages((messages) =>
          messages.map((message) =>
            message.id === localId ? { ...message, id: userId } : message,
          ),
        );
        latest.current.onAccepted(session.id);
      },
    });
    if (resume) void latest.current.chat?.resumeStream();
    return () => {
      controller.abort();
      transport.disconnect();
      void latest.current.chat?.stop();
      unlisten();
    };
  }, [transport, resume, sdkReady]);
  return null;
}
