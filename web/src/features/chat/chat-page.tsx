import {
  readChatModelPreference,
  RESEARCH_MINIMUM_CONTEXT,
  useChatModels,
  writeChatModelPreference,
} from "./chat-models";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useAttachOnOpen } from "@/features/chat/composer/use-attach-on-open";
import { useAuiState } from "@assistant-ui/react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useParams } from "@tanstack/react-router";
import { useEffect, useMemo, useState, useSyncExternalStore } from "react";
import { AppShellHeader } from "@/components/app-shell/app-shell-header";
import { Button } from "@/components/ui/button";
import { useApplicationSession } from "@/features/identity/application-session-context";
import {
  editChatMessage,
  regenerateChatMessage,
  selectChatBranch,
  getChatBranches,
  getChatFeedback,
  getChatProject,
  getChatSettings,
  getChatImageAvailability,
  getChatWebAvailability,
  pinChatReasoningEffort,
} from "@/lib/hey-api/sdk.gen";
import type { Accepted, ReasoningSelection } from "@/lib/hey-api/types.gen";
import type { MemoryOsChatTransport } from "@/features/chat/runtime/chat-transport";
import { ChatThread } from "@/features/chat/thread/chat-thread";
import { ChatModelPicker } from "./chat-model-picker";
import { ChatComposerMenu } from "@/features/chat/composer/chat-composer-menu";
import type { WebSearchMode } from "@/features/chat/web-search/chat-web-preference";
import type { ImageMode } from "@/features/chat/image/chat-image";
import { ChatEditingContext } from "@/features/chat/thread/chat-editing-context";
import { ChatImageEditContext } from "@/features/chat/image/chat-image-edit-context";
import {
  ChatSessionSettings,
  ChatStarterPrompts,
} from "@/features/chat/session/chat-session-settings";
import {
  ChatTemporaryBadge,
  ChatTemporaryNotice,
  ChatTemporaryToggle,
} from "@/features/chat/session/chat-temporary";
import { ChatConversationSearch } from "@/features/chat/thread/chat-conversation-search";
import { branchSchema, feedbackSchema, type Feedback } from "@/features/chat/chat-api";
import { loadPersonas } from "@/features/chat/chat-personas-api";
import { projectSchema, type Project } from "@/features/chat/projects/chat-projects-api";
import {
  ProjectContextPanel,
  ProjectConversationList,
} from "@/features/chat/projects/chat-projects-page";
import { useTranslation } from "react-i18next";
import { useProblemMessage } from "@/lib/use-problem-message";
import { useChatThreads } from "@/features/chat/runtime/chat-threads-context";
import type { ChatThreadController } from "@/features/chat/runtime/chat-thread-controller";
import { branchSteps } from "@/features/chat/thread/chat-branch-steps";

export function ChatPage() {
  const ui = useAppTranslation();
  useAttachOnOpen();
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
      projectSchema.parse((await getChatProject({ path: { projectId: projectId! }, signal })).data),
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
      <>
        <AppShellHeader title={ui("Chat")} />
        <div role="alert" className="space-y-3 p-6">
          <p>{ui("Không tải được hội thoại.")}</p>
          <Button prominence="secondary" onClick={retryRoute}>
            {ui("Thử lại")}
          </Button>
        </div>
      </>
    );
  // The server ID of a conversation created here arrives before the route changes, so promotion
  // never passes through this branch and keeps the composer mounted.
  if ((sessionId && mainRemoteId !== sessionId) || !controller)
    return (
      <>
        <AppShellHeader title={ui("Chat")} />
        <p role="status" className="p-6 text-content-secondary">
          {ui("Đang tải hội thoại…")}
        </p>
      </>
    );
  if (projectId && !project.data)
    return (
      <>
        <AppShellHeader title={ui("Dự án")} />
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
      </>
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
  /** Decided before the first question: a conversation cannot become temporary once it exists (MEM-153). */
  const [temporary, setTemporary] = useState(transport.temporary);
  const navigate = useNavigate();
  const [mcpServerIds, setMcpServerIds] = useState<string[]>(transport.mcpServerIds);
  const [image, setImage] = useState<ImageMode>(transport.image);
  const [deepResearch, setDeepResearch] = useState(transport.deepResearch);
  // The level pinned on this conversation; a new conversation starts on the member's own default.
  const [effort, setEffort] = useState<string>();
  const pinnedEffort = effort ?? session?.reasoningEffort ?? undefined;
  const pinReasoning = useMutation({
    mutationFn: async (level: string) => {
      setEffort(level);
      if (!session?.id) return;
      await pinChatReasoningEffort({
        path: { sessionId: session.id },
        body: { reasoningEffort: level as ReasoningSelection["reasoningEffort"] },
      });
    },
  });
  const applicationSession = useApplicationSession();
  const chatSettings = useQuery({
    queryKey: [
      "chat-settings",
      applicationSession.actorId,
      applicationSession.authorizationVersion,
    ],
    queryFn: async ({ signal }) => (await getChatSettings({ signal })).data,
    retry: false,
  });
  const personas = useQuery({
    queryKey: [
      "chat-personas",
      applicationSession.actorId,
      applicationSession.authorizationVersion,
    ],
    queryFn: ({ signal }) => loadPersonas(signal),
  });
  const persona = session?.personaId
    ? personas.data?.find((candidate) => candidate.id === session.personaId)
    : personas.data?.find((candidate) => candidate.builtin);
  const webAvailability = useQuery({
    queryKey: [
      "chat-web",
      applicationSession.actorId,
      applicationSession.authorizationVersion,
      session?.id,
    ],
    queryFn: async ({ signal }) =>
      (
        await getChatWebAvailability({
          query: { sessionId: session?.id },
          signal,
        })
      ).data,
    retry: false,
  });
  const imageAvailability = useQuery({
    queryKey: ["chat-image", applicationSession.actorId, applicationSession.authorizationVersion],
    queryFn: async ({ signal }) => (await getChatImageAvailability({ signal })).data,
    retry: false,
  });
  // As Onyx: Deep research is offered outside Projects while the organization setting is on and research agents
  // have internal Search or an external Web search connection (research never uses provider-hosted search).
  const researchAvailable =
    !project &&
    !session?.projectId &&
    chatSettings.data?.deepResearchEnabled === true &&
    (persona?.tools.includes("search") === true ||
      (persona?.tools.includes("web_search") !== false &&
        webAvailability.data?.searchAvailable === true));
  // MEM-130: the server rejects research on a model without tool calling or under 50,000 tokens; say so up front.
  const { catalog: modelCatalog } = useChatModels(session?.id);
  const selectedModel = modelCatalog.data?.find((candidate) => candidate.id === model.choice.id);
  const researchUnsupported =
    selectedModel &&
    (!selectedModel.capabilities.toolCalling ||
      selectedModel.contextWindow < RESEARCH_MINIMUM_CONTEXT)
      ? ui(
          "Mô hình này không chạy được Deep research: cần gọi công cụ và ngữ cảnh từ 50.000 token.",
        )
      : undefined;
  // Until the session agent is known, no tool is sent: a command must never carry a tool the agent forbids.
  const allowedTools = useMemo(
    () =>
      persona
        ? {
            web: persona.tools.includes("web_search"),
            image: persona.tools.includes("image_generation"),
            mcpServerIds: persona.builtin ? null : persona.mcpServers.map((server) => server.id),
          }
        : { web: false, image: false, mcpServerIds: [] },
    [persona],
  );
  // The server rejects tools the agent does not allow; the transport drops them and the composer hides them.
  useEffect(() => transport.restrictTools(allowedTools), [transport, allowedTools]);
  const shownWebSearch = allowedTools?.web === false ? "off" : webSearch;
  const shownImage = allowedTools?.image === false ? "off" : image;
  const shownMcpServerIds = mcpServerIds.filter(
    (id) => !allowedTools?.mcpServerIds || allowedTools.mcpServerIds.includes(id),
  );
  const busy = state.connection !== "ready" || state.checking;
  const imageEditing = useMemo(
    () => ({
      enableImages: () => {
        if (transport.image !== "off") return;
        transport.selectImage("auto");
        setImage("auto");
      },
    }),
    [transport],
  );
  const branches = useQuery({
    queryKey: ["chat-branches", session?.id],
    enabled: !!session,
    queryFn: async ({ signal }) =>
      branchSchema
        .array()
        .parse((await getChatBranches({ path: { sessionId: session!.id }, signal })).data),
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
              })
            ).data,
          ),
        );
      return values;
    },
  });

  /**
   * Scrolls to a message of this conversation (MEM-152 "show in conversation"). A message on another version is
   * brought onto the selected path first, one version choice at a time, as the version arrows would.
   */
  const showMessage = async (messageId: string) => {
    const find = () =>
      document.querySelector<HTMLElement>(`[data-message-id="${CSS.escape(messageId)}"]`);
    let element = find();
    if (!element && session) {
      const current = (await branches.refetch()).data ?? [];
      if (!current.some((branch) => branch.id === messageId)) return false;
      const steps = branchSteps(current, messageId);
      if (steps.length > 0) {
        await controller.mutate(async () => {
          for (const step of steps)
            await selectChatBranch({
              path: { sessionId: session.id },
              body: step,
              signal: AbortSignal.timeout(30000),
            });
        });
        await branches.refetch();
      }
      for (let waited = 0; !element && waited < 5000; waited += 100) {
        await new Promise((resolve) => setTimeout(resolve, 100));
        element = find();
      }
    }
    if (!element) return false;
    element.scrollIntoView({ block: "center", behavior: "smooth" });
    element.setAttribute("data-chat-search-match", "true");
    setTimeout(() => element.removeAttribute("data-chat-search-match"), 2400);
    return true;
  };

  if (state.unavailable)
    return (
      <>
        <AppShellHeader title={ui("Chat")} />
        <p role="alert" className="p-6">
          {ui("Hội thoại không còn khả dụng.")}
        </p>
      </>
    );
  if (state.historyFailed && !session)
    return (
      <>
        <AppShellHeader title={ui("Chat")} />
        <div role="alert" className="space-y-3 p-6">
          <p>{ui("Không tải được hội thoại.")}</p>
          <Button prominence="secondary" onClick={() => void controller.check()}>
            {ui("Thử lại")}
          </Button>
        </div>
      </>
    );
  if (loadingHistory && controller.remoteId)
    return (
      <>
        <AppShellHeader title={ui("Chat")} />
        <p role="status" className="p-6 text-content-secondary">
          {ui("Đang tải hội thoại…")}
        </p>
      </>
    );
  const headerSession = session && { ...session, title: title ?? session.title };
  return (
    <>
      <AppShellHeader
        title={headerSession?.title ?? (project ? ui("Dự án") : ui("Chat"))}
        actions={
          <div className="flex items-center gap-1">
            <ChatTemporaryBadge temporary={headerSession?.temporary ?? temporary} />
            {!session && !project && (
              <ChatTemporaryToggle
                value={temporary}
                disabled={busy}
                onChange={(next) => {
                  transport.selectTemporary(next);
                  setTemporary(next);
                }}
              />
            )}
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
              onShowMessage={showMessage}
            />
          </div>
        }
      />
      <div className="flex h-full min-h-0 flex-col">
        <ChatImageEditContext.Provider value={imageEditing}>
          <ChatEditingContext.Provider
            value={{
              sessionId: session?.id,
              sessionTitle: headerSession?.title,
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
                      webSearch: shownWebSearch,
                      deepResearch: researchAvailable && !researchUnsupported && deepResearch,
                    },
                    signal: AbortSignal.timeout(30000),
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
                      webSearch: shownWebSearch,
                      deepResearch: researchAvailable && !researchUnsupported && deepResearch,
                    },
                    signal: AbortSignal.timeout(30000),
                  });
                  transport.recordModelSelection(data);
                }),
              branch: (messageId, expectedChildId) =>
                controller.mutate(() =>
                  selectChatBranch({
                    path: { sessionId: session!.id },
                    body: { messageId, expectedChildId },
                    signal: AbortSignal.timeout(30000),
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
              sendDisabled={!persona}
              welcome={project && !session ? <ProjectContextPanel project={project} /> : undefined}
              afterComposer={
                (headerSession?.temporary ?? (temporary && !session)) ? (
                  <ChatTemporaryNotice
                    onLeave={() => {
                      transport.selectTemporary(false);
                      setTemporary(false);
                      void navigate({ to: "/" });
                    }}
                  />
                ) : project && !session ? (
                  <ProjectConversationList projectId={project.id} />
                ) : undefined
              }
              starters={<ChatStarterPrompts personaId={session?.personaId} disabled={busy} />}
              composerMenu={
                <ChatComposerMenu
                  disabled={busy}
                  allowed={allowedTools}
                  web={{
                    sessionId: session?.id,
                    modelId: model.choice.id,
                    value: shownWebSearch,
                    onChange: (mode) => {
                      transport.selectWeb(mode);
                      setWebSearch(mode);
                    },
                  }}
                  research={
                    researchAvailable
                      ? {
                          unsupported: researchUnsupported,
                          value: deepResearch,
                          onChange: (enabled) => {
                            transport.selectResearch(enabled);
                            setDeepResearch(enabled);
                          },
                        }
                      : undefined
                  }
                  image={{
                    available: imageAvailability.data?.available === true,
                    pending: imageAvailability.isPending,
                    onRetry: imageAvailability.isError
                      ? () => void imageAvailability.refetch()
                      : undefined,
                    value: shownImage,
                    onChange: (mode) => {
                      transport.selectImage(mode);
                      setImage(mode);
                    },
                  }}
                  mcp={{
                    selected: shownMcpServerIds,
                    sessionId: session?.id,
                    onChange: (ids) => {
                      transport.selectMcpServers(ids);
                      setMcpServerIds(ids);
                    },
                  }}
                />
              }
              modelPicker={
                <ChatModelPicker
                  sessionId={transport.session?.id}
                  value={model.choice.id}
                  onChange={model.select}
                  effort={pinnedEffort}
                  onEffortChange={(level) => pinReasoning.mutate(level)}
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
        </ChatImageEditContext.Provider>
      </div>
    </>
  );
}

type ModelChoice = { id?: string; fallback?: boolean };

function useChatModelChoice(transport: MemoryOsChatTransport) {
  const { actorId, authorizationVersion } = useApplicationSession();
  const queryClient = useQueryClient();
  const [choice, setChoice] = useState<ModelChoice>(() => {
    // Keep the accepted-turn notice through the new-chat route transition,
    // using the in-memory query cache. A page reload retains only the model ID.
    const accepted = queryClient.getQueryData<Accepted>([
      "chat-model-selection",
      actorId,
      authorizationVersion,
      transport.session?.id,
    ]);
    const saved = readChatModelPreference(actorId);
    if (!saved) return {};
    return {
      id: saved,
      fallback: accepted?.modelConfigurationId === saved && !!accepted.fallbackReason,
    };
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
        writeChatModelPreference(actorId, next.id);
        setChoice(next);
      } else {
        setChoice((current) => (current.fallback ? { id: current.id } : current));
      }
    });
  }, [transport, choice.id, actorId, authorizationVersion, queryClient]);
  function select(id?: string) {
    transport.selectModel(id);
    setChoice({ id });
    writeChatModelPreference(actorId, id);
  }
  return { choice, select };
}
