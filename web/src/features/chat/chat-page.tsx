import { AssistantRuntimeProvider, useAui } from "@assistant-ui/react";
import { useAISDKChat, useChatRuntime } from "@assistant-ui/ai-sdk";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useParams } from "@tanstack/react-router";
import { useEffect, useImperativeHandle, useRef, useState, type RefObject } from "react";
import { AppShell } from "@/components/app-shell/app-shell";
import { Button } from "@/components/ui/button";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { ApiError, sameOriginMutationHeaders } from "@/lib/api";
import {
  editChatMessage,
  regenerateChatMessage,
  selectChatBranch,
  getChatBranches,
  getChatFeedback,
  getChatProject,
  getChatSession,
} from "@/lib/hey-api/sdk.gen";
import { getCurrentIdentityQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { Accepted, ChatSession } from "@/lib/hey-api/types.gen";
import { chatSessionsKey, loadChatHistory, toUiMessages, type ChatHistory } from "./chat-api";
import { MemoryOsChatTransport, type ConnectionState } from "./chat-transport";
import { ChatThread } from "./chat-thread";
import { ChatModelPicker } from "./chat-model-picker";
import { ChatEditingContext } from "./chat-editing-context";
import { ChatSessionSettings, ChatStarterPrompts } from "./chat-session-settings";
import {
  branchSchema,
  feedbackSchema,
  projectSchema,
  type Project,
  type Feedback,
} from "./chat-workspace-api";
import { ProjectContextPanel, ProjectConversationList } from "./chat-projects-page";
import { chatActionError } from "./chat-action-utils";

export function ChatPage() {
  const { sessionId, projectId } = useParams({ strict: false });
  const routeKey = sessionId ?? (projectId ? `project:${projectId}` : "new");
  const identity = useApplicationSession();
  const [view, setView] = useState({
    routeKey,
    promotedSessionId: undefined as string | undefined,
    key: 0,
  });
  if (view.routeKey !== routeKey) {
    // Receiving the new session's server ID does not switch conversations.
    const promoted = sessionId !== undefined && sessionId === view.promotedSessionId;
    setView({ routeKey, promotedSessionId: undefined, key: view.key + (promoted ? 0 : 1) });
  }
  const authority = JSON.stringify([
    identity.actorId,
    identity.authorizationVersion,
    identity.capabilities,
    identity.scopedCapabilities,
  ]);
  return (
    <ChatSessionView
      key={`${authority}:${view.key}`}
      sessionId={sessionId}
      projectId={projectId}
      onSessionCreated={(id) => {
        setView((current) => ({ ...current, promotedSessionId: id }));
      }}
    />
  );
}

function ChatSessionView({
  sessionId,
  projectId,
  onSessionCreated,
}: {
  sessionId?: string;
  projectId?: string;
  onSessionCreated: (id: string) => void;
}) {
  // History initializes this runtime once. URL promotion keeps the live stream.
  const [initialSessionId] = useState(sessionId);
  const [initialProjectId] = useState(projectId);
  const { actorId, authorizationVersion } = useApplicationSession();
  const project = useQuery({
    queryKey: ["chat-project", actorId, authorizationVersion, initialProjectId],
    queryFn: async ({ signal }) =>
      projectSchema.parse(
        (
          await getChatProject({
            path: { projectId: initialProjectId! },
            signal,
            throwOnError: true,
          })
        ).data,
      ),
    enabled: !!initialProjectId,
    retry: false,
  });
  const query = useQuery({
    queryKey: ["chat-history", initialSessionId],
    queryFn: ({ signal }) => loadChatHistory(initialSessionId!, signal),
    enabled: !!initialSessionId,
    staleTime: Infinity,
    gcTime: 0,
    refetchOnWindowFocus: false,
    retry: false,
  });
  if (initialSessionId && query.isPending)
    return (
      <AppShell pageTitle="Chat">
        <p role="status" className="p-6 text-content-secondary">
          Đang tải hội thoại…
        </p>
      </AppShell>
    );
  if (initialSessionId && query.isError)
    return (
      <AppShell pageTitle="Chat">
        <div role="alert" className="space-y-3 p-6">
          <p>Không tải được hội thoại.</p>
          <Button prominence="secondary" onClick={() => void query.refetch()}>
            Thử lại
          </Button>
        </div>
      </AppShell>
    );
  if (initialProjectId && !project.data)
    return (
      <AppShell pageTitle="Dự án">
        <div className="p-6" role={project.isError ? "alert" : "status"}>
          {project.isError ? (
            <>
              Dự án không khả dụng. <Button onClick={() => void project.refetch()}>Tải lại</Button>
            </>
          ) : (
            "Đang tải dự án…"
          )}
        </div>
      </AppShell>
    );
  return (
    <ChatConversation
      initial={query.data}
      project={project.data}
      onSessionCreated={onSessionCreated}
    />
  );
}

function ChatConversation({
  initial,
  project,
  onSessionCreated,
}: {
  initial?: ChatHistory;
  project?: Project;
  onSessionCreated: (id: string) => void;
}) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const running = initial?.messages.find((message) => message.status === "RUNNING");
  const [transport] = useState(
    () => new MemoryOsChatTransport(initial?.session, running, project?.id),
  );
  const [session, setSession] = useState(initial?.session);
  const metadata = useQuery({
    queryKey: ["chat-session", session?.id],
    queryFn: async ({ signal }) =>
      (await getChatSession({ path: { sessionId: session!.id }, signal, throwOnError: true })).data,
    enabled: !!session,
    initialData: session,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    retry: false,
  });
  const headerSession = metadata.data ?? session;
  useEffect(() => {
    if (initial?.session)
      queryClient.setQueryData(["chat-session", initial.session.id], initial.session);
  }, [initial?.session, queryClient]);
  const model = useChatModelChoice(transport);
  const [connection, setConnection] = useState<ConnectionState>(running ? "recovering" : "ready");
  const [error, setError] = useState<string>();
  const [unavailable, setUnavailable] = useState(false);
  const [stopping, setStopping] = useState(false);
  const [checking, setChecking] = useState(false);
  const active = useRef(true);
  const mutationInFlight = useRef(false);
  const controls = useRef<{ check: () => Promise<void> }>(null);
  const branches = useQuery({
    queryKey: ["chat-branches", session?.id],
    enabled: !!session,
    queryFn: async ({ signal }) =>
      branchSchema
        .array()
        .parse(
          (await getChatBranches({ path: { sessionId: session!.id }, signal, throwOnError: true }))
            .data,
        ),
  });
  const feedback = useQuery({
    queryKey: ["chat-feedback", session?.id, branches.data?.map((b) => b.id)],
    enabled: !!session && !!branches.data,
    queryFn: async ({ signal }) => {
      const values: Feedback[] = [];
      const ids = branches.data!.map((b) => b.id);
      for (let i = 0; i < ids.length; i += 100)
        values.push(
          ...feedbackSchema.array().parse(
            (
              await getChatFeedback({
                path: { sessionId: session!.id },
                query: { messageIds: ids.slice(i, i + 100) },
                signal,
                throwOnError: true,
              })
            ).data,
          ),
        );
      return values;
    },
  });
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
        setError("Câu trả lời chưa hoàn tất. Kiểm tra hội thoại đã lưu trước khi gửi tiếp.");
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
    } else setError("Kết nối bị gián đoạn. Kiểm tra hội thoại đã lưu trước khi gửi tiếp.");
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

  async function refresh() {
    await controls.current?.check();
    setSession(transport.session);
    if (transport.session)
      queryClient.setQueryData(["chat-session", transport.session.id], transport.session);
    await queryClient.invalidateQueries({ queryKey: ["chat-branches", transport.session?.id] });
    await queryClient.invalidateQueries({ queryKey: ["chat-feedback", transport.session?.id] });
  }
  async function mutate(command: () => Promise<unknown>) {
    if (mutationInFlight.current || connection !== "ready" || checking)
      throw new Error("Conversation is busy");
    mutationInFlight.current = true;
    setChecking(true);
    setError(undefined);
    try {
      await command();
      await refresh();
    } catch (cause) {
      if (active.current) {
        setError(chatActionError(cause));
        setConnection("uncertain");
      }
      throw cause;
    } finally {
      mutationInFlight.current = false;
      if (active.current) setChecking(false);
    }
  }

  if (unavailable)
    return (
      <AppShell pageTitle="Chat">
        <p role="alert" className="p-6">
          Hội thoại không còn khả dụng.
        </p>
      </AppShell>
    );
  return (
    <AppShell
      pageTitle={headerSession?.title ?? (project ? "Dự án" : "Chat")}
      headerActions={
        <ChatSessionSettings
          session={headerSession}
          busy={connection !== "ready" || checking}
          onChange={async () => {
            model.select(undefined);
            await refresh();
          }}
          onDelete={() => {
            transport.disconnect();
            setUnavailable(true);
          }}
        />
      }
    >
      <AssistantRuntimeProvider runtime={runtime}>
        <div className="flex h-full min-h-0 flex-col">
          <ChatEditingContext.Provider
            value={{
              sessionId: session?.id,
              busy: connection !== "ready" || checking,
              branches: branches.data ?? [],
              feedback: feedback.data ?? [],
              edit: (userMessageId, text, clientRequestId) =>
                mutate(async () => {
                  const { data } = await editChatMessage({
                    path: { sessionId: session!.id, userMessageId },
                    body: { text, clientRequestId, modelConfigurationId: model.choice.id },
                    headers: sameOriginMutationHeaders,
                    signal: AbortSignal.timeout(30000),
                    throwOnError: true,
                  });
                  transport.recordModelSelection(data);
                }),
              regenerate: (userMessageId, clientRequestId) =>
                mutate(async () => {
                  const { data } = await regenerateChatMessage({
                    path: { sessionId: session!.id, userMessageId },
                    body: { clientRequestId, modelConfigurationId: model.choice.id },
                    headers: sameOriginMutationHeaders,
                    signal: AbortSignal.timeout(30000),
                    throwOnError: true,
                  });
                  transport.recordModelSelection(data);
                }),
              branch: (messageId, expectedChildId) =>
                mutate(() =>
                  selectChatBranch({
                    path: { sessionId: session!.id },
                    body: { messageId, expectedChildId },
                    headers: sameOriginMutationHeaders,
                    signal: AbortSignal.timeout(30000),
                    throwOnError: true,
                  }),
                ),
            }}
          >
            <ChatRuntimeBridge
              transport={transport}
              resume={!!running}
              controls={controls}
              onState={(state) => {
                if (active.current) {
                  setConnection(state);
                  if (state === "sending") setError(undefined);
                  if (state === "ready" || state === "uncertain") setStopping(false);
                  if (state === "ready") {
                    void queryClient.invalidateQueries({
                      queryKey: ["chat-branches", transport.session?.id],
                    });
                    void queryClient.invalidateQueries({
                      queryKey: ["chat-feedback", transport.session?.id],
                    });
                  }
                }
              }}
              onError={handleError}
              onAccepted={(sessionId) => {
                if (transport.session)
                  queryClient.setQueryData(
                    ["chat-session", sessionId],
                    (current: ChatSession | undefined) => current ?? transport.session,
                  );
                setSession(transport.session);
                void queryClient.invalidateQueries({ queryKey: chatSessionsKey });
                void queryClient.invalidateQueries({ queryKey: ["chat-project-sessions"] });
                if (!initial) {
                  onSessionCreated(sessionId);
                  void navigate({ to: "/chat/$sessionId", params: { sessionId }, replace: true });
                }
              }}
            />
            {(branches.isError || feedback.isError) && (
              <p role="alert" className="px-4 text-sm">
                Không tải được phiên bản hoặc đánh giá.{" "}
                <Button
                  prominence="internal"
                  size="sm"
                  onClick={() => {
                    void branches.refetch();
                    void feedback.refetch();
                  }}
                >
                  Tải lại
                </Button>
              </p>
            )}
            <ChatThread
              welcome={project && !session ? <ProjectContextPanel project={project} /> : undefined}
              afterComposer={
                project && !session ? <ProjectConversationList projectId={project.id} /> : undefined
              }
              starters={
                <ChatStarterPrompts
                  personaId={session?.personaId}
                  disabled={connection !== "ready" || checking}
                />
              }
              modelPicker={
                <ChatModelPicker
                  sessionId={transport.session?.id}
                  value={model.choice.id}
                  onChange={model.select}
                  disabled={connection !== "ready" || checking}
                />
              }
              modelNotice={
                model.choice.fallback
                  ? "Mô hình đã chọn không khả dụng. Câu trả lời đang dùng mô hình mặc định mà bạn được phép sử dụng."
                  : undefined
              }
              connection={connection}
              stopping={stopping}
              onStop={() => void stop()}
              error={error}
              onCheck={async () => {
                setChecking(true);
                setError(undefined);
                try {
                  await refresh();
                } catch (cause) {
                  handleError(cause);
                } finally {
                  if (active.current) setChecking(false);
                }
              }}
              checking={checking}
            />
          </ChatEditingContext.Provider>
        </div>
      </AssistantRuntimeProvider>
    </AppShell>
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
          void latest.current.chat
            ?.resumeStream()
            .catch((cause: unknown) => latest.current.onError(cause));
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

type ModelChoice = { id?: string; fallback?: boolean };

function useChatModelChoice(transport: MemoryOsChatTransport) {
  const { actorId, authorizationVersion } = useApplicationSession();
  const queryClient = useQueryClient();
  const key = `memoryos.chat.model:${actorId}`;
  const [choice, setChoice] = useState<ModelChoice>(() => {
    // Keep the accepted-turn notice through the new-chat route transition,
    // using the in-memory query cache. A page reload retains only the model ID.
    const accepted = queryClient.getQueryData<Accepted>([
      "chat-model-selection",
      actorId,
      authorizationVersion,
      transport.session?.id,
    ]);
    try {
      const saved: unknown = JSON.parse(sessionStorage.getItem(key) ?? "{}");
      if (
        saved &&
        typeof saved === "object" &&
        "id" in saved &&
        typeof saved.id === "string" &&
        /^[0-9a-f-]{36}$/i.test(saved.id)
      )
        return {
          id: saved.id,
          fallback: accepted?.modelConfigurationId === saved.id && !!accepted.fallbackReason,
        };
    } catch {
      /* Preference storage is optional. */
    }
    return {};
  });
  useEffect(() => {
    transport.selectModel(choice.id);
    return transport.listenModelSelection((accepted) => {
      queryClient.setQueryData(
        ["chat-model-selection", actorId, authorizationVersion, transport.session?.id],
        accepted,
      );
      if (accepted.fallbackReason) {
        const next = { id: accepted.modelConfigurationId, fallback: true };
        transport.selectModel(next.id);
        try {
          if (next.id) sessionStorage.setItem(key, JSON.stringify({ id: next.id }));
          else sessionStorage.removeItem(key);
        } catch {
          /* Optional preference. */
        }
        setChoice(next);
      } else {
        setChoice((current) => (current.fallback ? { id: current.id } : current));
      }
    });
  }, [transport, key, choice.id, actorId, authorizationVersion, queryClient]);
  function select(id?: string) {
    transport.selectModel(id);
    setChoice({ id });
    try {
      sessionStorage.setItem(key, JSON.stringify({ id }));
    } catch {
      /* Optional preference. */
    }
  }
  return { choice, select };
}
