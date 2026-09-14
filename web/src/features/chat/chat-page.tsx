import { useAppTranslation } from "@/i18n/use-app-translation";
import { useAuiState } from "@assistant-ui/react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useParams } from "@tanstack/react-router";
import { useEffect, useState, useSyncExternalStore } from "react";
import { AppShell } from "@/components/app-shell/app-shell";
import { Button } from "@/components/ui/button";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  editChatMessage,
  regenerateChatMessage,
  selectChatBranch,
  getChatBranches,
  getChatFeedback,
  getChatProject,
} from "@/lib/hey-api/sdk.gen";
import type { Accepted } from "@/lib/hey-api/types.gen";
import type { MemoryOsChatTransport } from "./chat-transport";
import { ChatThread } from "./chat-thread";
import { ChatModelPicker } from "./chat-model-picker";
import { ChatComposerMenu } from "./chat-composer-menu";
import type { WebSearchMode } from "./chat-web-preference";
import { ChatEditingContext } from "./chat-editing-context";
import { ChatSessionSettings, ChatStarterPrompts } from "./chat-session-settings";
import { ChatConversationSearch } from "./chat-conversation-search";
import {
  branchSchema,
  feedbackSchema,
  projectSchema,
  type Project,
  type Feedback,
} from "./chat-workspace-api";
import { ProjectContextPanel, ProjectConversationList } from "./chat-projects-page";
import { useTranslation } from "react-i18next";
import { useProblemMessage } from "@/lib/use-problem-message";
import { useChatThreads } from "./chat-threads-context";
import type { ChatThreadController } from "./chat-thread-controller";

export function ChatPage() {
  const ui = useAppTranslation();
  const { sessionId, projectId } = useParams({ strict: false });
  const { registry, routeError, retryRoute } = useChatThreads();
  const identity = useApplicationSession();
  const mainId = useAuiState((state) => state.threads.mainThreadId);
  const mainRemoteId = useAuiState(
    (state) =>
      state.threads.threadItems.find((item) => item.id === state.threads.mainThreadId)?.remoteId,
  );
  const controller = useSyncExternalStore(registry.subscribe, () => registry.get(mainId));
  const project = useQuery({
    queryKey: ["chat-project", identity.actorId, identity.authorizationVersion, projectId],
    queryFn: async ({ signal }) =>
      projectSchema.parse(
        (await getChatProject({ path: { projectId: projectId! }, signal, throwOnError: true }))
          .data,
      ),
    enabled: !!projectId,
    retry: false,
  });
  const authority = JSON.stringify([
    identity.actorId,
    identity.authorizationVersion,
    identity.capabilities,
    identity.scopedCapabilities,
  ]);
  if (sessionId && routeError === sessionId)
    return (
      <AppShell pageTitle={ui("Chat")}>
        <div role="alert" className="space-y-3 p-6">
          <p>{ui("Không tải được hội thoại.")}</p>
          <Button prominence="secondary" onClick={retryRoute}>
            {ui("Thử lại")}
          </Button>
        </div>
      </AppShell>
    );
  // The server ID of a conversation created here arrives before the route changes, so promotion
  // never passes through this branch and keeps the composer mounted.
  if ((sessionId && mainRemoteId !== sessionId) || !controller)
    return (
      <AppShell pageTitle={ui("Chat")}>
        <p role="status" className="p-6 text-content-secondary">
          {ui("Đang tải hội thoại…")}
        </p>
      </AppShell>
    );
  if (projectId && !project.data)
    return (
      <AppShell pageTitle={ui("Dự án")}>
        <div className="p-6" role={project.isError ? "alert" : "status"}>
          {project.isError ? (
            <>
              {ui("Dự án không khả dụng.")}{" "}
              <Button onClick={() => void project.refetch()}>{ui("Tải lại")}</Button>
            </>
          ) : (
            ui("Đang tải dự án…")
          )}
        </div>
      </AppShell>
    );
  return (
    <ChatConversation
      key={`${authority}:${mainId}`}
      controller={controller}
      project={projectId ? project.data : undefined}
    />
  );
}

function ChatConversation({
  controller,
  project,
}: {
  controller: ChatThreadController;
  project?: Project;
}) {
  const ui = useAppTranslation();
  const { t } = useTranslation("chatStatus");
  const problemMessage = useProblemMessage();
  const { runtime } = useChatThreads();
  const state = useSyncExternalStore(controller.subscribe, controller.getState);
  const { transport } = controller;
  const session = state.session;
  const title = useAuiState(
    (s) => s.threads.threadItems.find((item) => item.id === controller.id)?.title,
  );
  const loadingHistory = useAuiState((s) => s.thread.isLoading);
  useEffect(() => controller.setProject(project?.id), [controller, project?.id]);
  const model = useChatModelChoice(transport);
  const [webSearch, setWebSearch] = useState<WebSearchMode>(transport.webSearch);
  const busy = state.connection !== "ready" || state.checking;
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

  if (state.unavailable)
    return (
      <AppShell pageTitle={ui("Chat")}>
        <p role="alert" className="p-6">
          {ui("Hội thoại không còn khả dụng.")}
        </p>
      </AppShell>
    );
  if (state.historyFailed && !session)
    return (
      <AppShell pageTitle={ui("Chat")}>
        <div role="alert" className="space-y-3 p-6">
          <p>{ui("Không tải được hội thoại.")}</p>
          <Button prominence="secondary" onClick={() => void controller.check()}>
            {ui("Thử lại")}
          </Button>
        </div>
      </AppShell>
    );
  if (loadingHistory && controller.remoteId)
    return (
      <AppShell pageTitle={ui("Chat")}>
        <p role="status" className="p-6 text-content-secondary">
          {ui("Đang tải hội thoại…")}
        </p>
      </AppShell>
    );
  const headerSession = session && { ...session, title: title ?? session.title };
  return (
    <AppShell
      pageTitle={headerSession?.title ?? (project ? ui("Dự án") : ui("Chat"))}
      headerActions={
        <div className="flex items-center gap-1">
          <ChatConversationSearch key={headerSession?.id ?? "new"} />
          <ChatSessionSettings
            session={headerSession}
            busy={busy}
            onChange={async () => {
              model.select(undefined);
              await controller.check();
            }}
            deleteSession={() => runtime.threads.getItemById(controller.id).delete()}
            onDelete={() => controller.markUnavailable()}
          />
        </div>
      }
    >
      <div className="flex h-full min-h-0 flex-col">
        <ChatEditingContext.Provider
          value={{
            sessionId: session?.id,
            busy,
            branches: branches.data ?? [],
            feedback: feedback.data ?? [],
            edit: (userMessageId, text, clientRequestId, fileIds) =>
              controller.mutate(async () => {
                const { data } = await editChatMessage({
                  path: { sessionId: session!.id, userMessageId },
                  body: {
                    text,
                    clientRequestId,
                    modelConfigurationId: model.choice.id,
                    fileIds,
                    webSearch,
                  },
                  headers: sameOriginMutationHeaders,
                  signal: AbortSignal.timeout(30000),
                  throwOnError: true,
                });
                transport.recordModelSelection(data);
              }),
            regenerate: (userMessageId, clientRequestId, modelConfigurationId) =>
              controller.mutate(async () => {
                const { data } = await regenerateChatMessage({
                  path: { sessionId: session!.id, userMessageId },
                  body: {
                    clientRequestId,
                    modelConfigurationId: modelConfigurationId ?? model.choice.id,
                    webSearch,
                  },
                  headers: sameOriginMutationHeaders,
                  signal: AbortSignal.timeout(30000),
                  throwOnError: true,
                });
                transport.recordModelSelection(data);
              }),
            branch: (messageId, expectedChildId) =>
              controller.mutate(() =>
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
          {(branches.isError || feedback.isError) && (
            <p role="alert" className="px-4 text-sm">
              {ui("Không tải được phiên bản hoặc đánh giá.")}{" "}
              <Button
                prominence="internal"
                size="sm"
                onClick={() => {
                  void branches.refetch();
                  void feedback.refetch();
                }}
              >
                {ui("Tải lại")}
              </Button>
            </p>
          )}
          {state.attachmentError && (
            <p role="alert" className="text-sm">
              {problemMessage(state.attachmentError)}
              <Button
                type="button"
                size="sm"
                prominence="internal"
                onClick={() => controller.setAttachmentError(undefined)}
              >
                {ui("Đóng")}
              </Button>
            </p>
          )}
          <ChatThread
            welcome={project && !session ? <ProjectContextPanel project={project} /> : undefined}
            afterComposer={
              project && !session ? <ProjectConversationList projectId={project.id} /> : undefined
            }
            starters={<ChatStarterPrompts personaId={session?.personaId} disabled={busy} />}
            composerMenu={
              <ChatComposerMenu
                disabled={busy}
                web={{
                  sessionId: session?.id,
                  modelId: model.choice.id,
                  value: webSearch,
                  onChange: (mode) => {
                    transport.selectWeb(mode);
                    setWebSearch(mode);
                  },
                }}
              />
            }
            modelPicker={
              <ChatModelPicker
                sessionId={transport.session?.id}
                value={model.choice.id}
                onChange={model.select}
                disabled={busy}
              />
            }
            modelNotice={
              model.choice.fallback
                ? ui(
                    "Mô hình đã chọn không khả dụng. Câu trả lời đang dùng mô hình mặc định mà bạn được phép sử dụng.",
                  )
                : undefined
            }
            connection={state.connection}
            stopping={state.stopping}
            onStop={() => void controller.stop()}
            error={
              state.error
                ? typeof state.error === "string"
                  ? t(state.error)
                  : problemMessage(state.error)
                : undefined
            }
            onCheck={() => controller.check()}
            checking={state.checking}
          />
        </ChatEditingContext.Provider>
      </div>
    </AppShell>
  );
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
