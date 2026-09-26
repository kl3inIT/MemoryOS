import { useAppTranslation } from "@/i18n/use-app-translation";
import { useAuiState } from "@assistant-ui/react";
import { useMutation, useQueries, useQuery } from "@tanstack/react-query";
import { useEffect, useMemo, useState, useSyncExternalStore } from "react";
import {
  editChatMessageMutation,
  getChatBranchesOptions,
  getChatFeedbackOptions,
  getChatImageAvailabilityOptions,
  getChatSettingsOptions,
  getChatWebAvailabilityOptions,
  pinChatReasoningEffortMutation,
  regenerateChatMessageMutation,
  selectChatBranchMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { Feedback as FeedbackView, ReasoningSelection } from "@/lib/hey-api/types.gen";
import { branchOf, feedbackOf } from "@/features/chat/chat-api";
import { personasOptions } from "@/features/chat/chat-personas-api";
import type { Project } from "@/features/chat/projects/chat-projects-api";
import type { ChatThreadController } from "@/features/chat/runtime/chat-thread-controller";
import type { ImageMode } from "@/features/chat/image/chat-image";
import type { WebSearchMode } from "@/features/chat/web-search/chat-web-preference";
import { branchSteps } from "@/features/chat/thread/chat-branch-steps";
import { RESEARCH_MINIMUM_CONTEXT, useChatModels } from "./chat-models";
import { useChatModelChoice } from "./use-chat-model-choice";

/** Feedback is read for at most this many answers per request. */
const FEEDBACK_CHUNK = 100;
/** Edits, regenerations and version switches give up after this long. */
const MUTATION_TIMEOUT = 30_000;

/** The version relationships of a conversation and the feedback on each version. */
function useChatVersions(sessionId: string | undefined) {
  const branches = useQuery({
    ...getChatBranchesOptions({ path: { sessionId: sessionId ?? "" } }),
    enabled: sessionId !== undefined,
    select: (views) => views.map(branchOf),
  });
  const ids = branches.data?.map((branch) => branch.id) ?? [];
  const chunks = Array.from({ length: Math.ceil(ids.length / FEEDBACK_CHUNK) }, (_, index) =>
    ids.slice(index * FEEDBACK_CHUNK, (index + 1) * FEEDBACK_CHUNK),
  );
  const feedback = useQueries({
    queries: chunks.map((messageIds) => ({
      ...getChatFeedbackOptions({ path: { sessionId: sessionId ?? "" }, query: { messageIds } }),
      enabled: sessionId !== undefined,
      select: (views: FeedbackView[]) => views.map(feedbackOf),
    })),
    combine: (results) => ({
      data: results.flatMap((result) => result.data ?? []),
      isError: results.some((result) => result.isError),
      refetch: () => Promise.all(results.map((result) => result.refetch())),
    }),
  });
  return { branches, feedback };
}

/**
 * The per-turn tool choices of the composer. Each choice is written to the transport, which sends it with
 * the next turn, and mirrored here so the composer shows it.
 */
function useToolChoices(controller: ChatThreadController) {
  const { transport } = controller;
  const [webSearch, setWebSearch] = useState<WebSearchMode>(transport.webSearch);
  /** Decided before the first question: a conversation cannot become temporary once it exists (MEM-153). */
  const [temporary, setTemporary] = useState(transport.temporary);
  const [mcpServerIds, setMcpServerIds] = useState<string[]>(transport.mcpServerIds);
  const [image, setImage] = useState<ImageMode>(transport.image);
  const [deepResearch, setDeepResearch] = useState(transport.deepResearch);
  // Editing an image turns image mode on, so the edit is sent as an image request.
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
  return {
    imageEditing,
    webSearch,
    selectWeb: (mode: WebSearchMode) => {
      transport.selectWeb(mode);
      setWebSearch(mode);
    },
    temporary,
    selectTemporary: (next: boolean) => {
      transport.selectTemporary(next);
      setTemporary(next);
    },
    mcpServerIds,
    selectMcpServers: (ids: string[]) => {
      transport.selectMcpServers(ids);
      setMcpServerIds(ids);
    },
    image,
    selectImage: (mode: ImageMode) => {
      transport.selectImage(mode);
      setImage(mode);
    },
    deepResearch,
    selectResearch: (enabled: boolean) => {
      transport.selectResearch(enabled);
      setDeepResearch(enabled);
    },
  };
}

/** Everything one conversation page reads and changes, apart from the thread body assistant-ui renders. */
export function useChatConversation(controller: ChatThreadController, project?: Project) {
  const ui = useAppTranslation();
  const state = useSyncExternalStore(controller.subscribe, controller.getState);
  const { transport } = controller;
  const session = state.session;
  const title = useAuiState(
    (s) => s.threads.threadItems.find((item) => item.id === controller.id)?.title,
  );
  // The controller outlives this page; it learns which Project a new conversation is created in.
  useEffect(() => controller.setProject(project?.id), [controller, project?.id]);
  const model = useChatModelChoice(transport);
  const tools = useToolChoices(controller);

  // The level pinned on this conversation; a new conversation starts on the member's own default.
  const [effort, setEffort] = useState<string>();
  const pinnedEffort = effort ?? session?.reasoningEffort ?? undefined;
  const pinReasoning = useMutation(pinChatReasoningEffortMutation());
  const pinEffort = (level: string) => {
    setEffort(level);
    if (session)
      pinReasoning.mutate({
        path: { sessionId: session.id },
        body: { reasoningEffort: level as ReasoningSelection["reasoningEffort"] },
      });
  };

  const chatSettings = useQuery({ ...getChatSettingsOptions(), retry: false });
  const personas = useQuery(personasOptions());
  const persona = session?.personaId
    ? personas.data?.find((candidate) => candidate.id === session.personaId)
    : personas.data?.find((candidate) => candidate.builtin);
  const webAvailability = useQuery({
    ...getChatWebAvailabilityOptions({ query: { sessionId: session?.id } }),
    retry: false,
  });
  const imageAvailability = useQuery({ ...getChatImageAvailabilityOptions(), retry: false });

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
  const deepResearch = researchAvailable && !researchUnsupported && tools.deepResearch;

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
  const shownWebSearch = allowedTools.web ? tools.webSearch : "off";
  const shownImage = allowedTools.image ? tools.image : "off";
  const shownMcpServerIds = tools.mcpServerIds.filter(
    (id) => !allowedTools.mcpServerIds || allowedTools.mcpServerIds.includes(id),
  );
  const busy = state.connection !== "ready" || state.checking;

  const { branches, feedback } = useChatVersions(session?.id);
  const editMessage = useMutation(editChatMessageMutation());
  const regenerateMessage = useMutation(regenerateChatMessageMutation());
  const selectBranch = useMutation(selectChatBranchMutation());
  const sessionId = session?.id;
  const sessionTitle = title ?? session?.title;
  const editing = {
    sessionId,
    sessionTitle,
    busy,
    branches: branches.data ?? [],
    feedback: feedback.data,
    edit: (userMessageId: string, text: string, clientRequestId: string, fileIds?: string[]) =>
      controller.mutate(async () => {
        const accepted = await editMessage.mutateAsync({
          path: { sessionId: sessionId!, userMessageId },
          body: {
            text,
            clientRequestId,
            modelConfigurationId: model.choice.id,
            fileIds,
            webSearch: shownWebSearch,
            deepResearch,
          },
          signal: AbortSignal.timeout(MUTATION_TIMEOUT),
        });
        transport.recordModelSelection(accepted);
      }),
    regenerate: (userMessageId: string, clientRequestId: string, modelConfigurationId?: string) =>
      controller.mutate(async () => {
        const accepted = await regenerateMessage.mutateAsync({
          path: { sessionId: sessionId!, userMessageId },
          body: {
            clientRequestId,
            modelConfigurationId: modelConfigurationId ?? model.choice.id,
            webSearch: shownWebSearch,
            deepResearch,
          },
          signal: AbortSignal.timeout(MUTATION_TIMEOUT),
        });
        transport.recordModelSelection(accepted);
      }),
    branch: (messageId: string, expectedChildId: string) =>
      controller.mutate(async () => {
        await selectBranch.mutateAsync({
          path: { sessionId: sessionId! },
          body: { messageId, expectedChildId },
          signal: AbortSignal.timeout(MUTATION_TIMEOUT),
        });
      }),
  };

  /**
   * Scrolls to a message of this conversation (MEM-152 "show in conversation"). A message on another version is
   * brought onto the selected path first, one version choice at a time, as the version arrows would.
   */
  const showMessage = async (messageId: string) => {
    const find = () =>
      document.querySelector<HTMLElement>(`[data-message-id="${CSS.escape(messageId)}"]`);
    let element = find();
    if (!element && sessionId) {
      const current = (await branches.refetch()).data ?? [];
      if (!current.some((branch) => branch.id === messageId)) return false;
      const steps = branchSteps(current, messageId);
      if (steps.length > 0) {
        await controller.mutate(async () => {
          for (const step of steps)
            await selectBranch.mutateAsync({
              path: { sessionId },
              body: step,
              signal: AbortSignal.timeout(MUTATION_TIMEOUT),
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

  return {
    state,
    session,
    /** The conversation as the header shows it, with the thread list's live title. */
    headerSession: session && { ...session, title: sessionTitle ?? session.title },
    busy,
    persona,
    model,
    pinnedEffort,
    pinEffort,
    tools,
    allowedTools,
    shownWebSearch,
    shownImage,
    shownMcpServerIds,
    research: researchAvailable ? { unsupported: researchUnsupported } : undefined,
    imageAvailability,
    versionsFailed: branches.isError || feedback.isError,
    reloadVersions: () => {
      void branches.refetch();
      void feedback.refetch();
    },
    editing,
    showMessage,
  };
}
