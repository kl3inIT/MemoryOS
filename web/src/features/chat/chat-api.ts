import type { QueryClient } from "@tanstack/react-query";
import type { UIMessage } from "ai";
import { i18n } from "@/i18n";
import { sourcesSchema, type ChatSource } from "@/features/chat/sources/chat-evidence";
import { activitySchema, historyParts } from "@/features/chat/activity/chat-activity";
import { createChatSession, getChatHistory, getChatSession } from "@/lib/hey-api/sdk.gen";
import {
  getChatBranchesQueryKey,
  getChatFeedbackQueryKey,
  getChatSessionQueryKey,
  listChatSessionsQueryKey,
  listProjectChatSessionsQueryKey,
  searchChatSessionsQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type {
  ChatBranch,
  ChatMessage,
  ChatSession,
  Feedback as FeedbackView,
} from "@/lib/hey-api/types.gen";
import { fileReference } from "@/features/library/files";
import { artifactsSchema, type ChatArtifact } from "@/features/chat/thread/chat-artifacts";
import { parseGeneratedImages, type GeneratedImage } from "@/features/chat/image/chat-image";
import { parseGeneratedFiles, type GeneratedFile } from "@/features/chat/interpreter/chat-code";
import { historyResearch, type ResearchState } from "@/features/chat/research/chat-research";

export type ChatUiMessage = UIMessage<
  {
    serverStatus?: ChatMessage["status"];
    /** Why a FAILED reply ended (for example CHAT_MODEL_OUTPUT_LIMIT). */
    failureCode?: string;
    createdAt?: string;
    /** Set by assistant-ui on a live question sent with a composer quote. */
    custom?: { quote?: { text: string; messageId: string } };
    sources?: ChatSource[];
    artifacts?: ChatArtifact[];
    /** Live citations per tool call, including late hosted-search citations. */
    toolCitations?: Record<string, number[]>;
    images?: GeneratedImage[];
    imageGenerating?: boolean;
    /** Files run_python generated, saved on the assistant message. */
    generatedFiles?: GeneratedFile[];
  },
  { research: ResearchState }
>;
export type ChatHistory = { session: ChatSession; messages: ChatMessage[] };

/** Matches every cached call of a generated operation, whatever its path, query or base URL. */
function everyCall([{ _id }]: readonly [{ _id: string }]) {
  return [{ _id }];
}

/**
 * Refreshes every cached read of conversations after one changes: the session lists (open and archived),
 * conversation search, every Project's conversation list and, when given, the changed conversation. The
 * assistant-ui thread list is not a query; `useRefreshChatSessions` reloads it beside this.
 */
export function invalidateChatSessions(cache: QueryClient, sessionId?: string) {
  return Promise.all([
    cache.invalidateQueries({ queryKey: listChatSessionsQueryKey() }),
    cache.invalidateQueries({ queryKey: searchChatSessionsQueryKey() }),
    cache.invalidateQueries({
      queryKey: everyCall(listProjectChatSessionsQueryKey({ path: { projectId: "" } })),
    }),
    sessionId === undefined
      ? undefined
      : cache.invalidateQueries({ queryKey: getChatSessionQueryKey({ path: { sessionId } }) }),
  ]);
}

/** Refreshes the version relationships of a conversation and the feedback on each of its versions. */
export function invalidateChatVersions(cache: QueryClient, sessionId: string) {
  const path = { sessionId };
  return Promise.all([
    cache.invalidateQueries({ queryKey: getChatBranchesQueryKey({ path }) }),
    // Feedback is read in chunks of message ids; an empty id list matches every chunk.
    cache.invalidateQueries({
      queryKey: getChatFeedbackQueryKey({ path, query: { messageIds: [] } }),
    }),
  ]);
}

/** A version relationship as the API sends it; the published contract marks its fields optional. */
export function branchOf({ id, parentMessageId = null, latestChildMessageId = null }: ChatBranch) {
  if (id === undefined) throw new TypeError("A conversation branch carries its message id.");
  return { id, parentMessageId, latestChildMessageId };
}
export type Branch = ReturnType<typeof branchOf>;

/** Feedback on an answer as the API sends it; the published contract marks its fields optional. */
export function feedbackOf({
  assistantMessageId,
  positive = null,
  comment = "",
  reason = "",
}: FeedbackView) {
  if (assistantMessageId === undefined)
    throw new TypeError("Answer feedback carries its message id.");
  return { assistantMessageId, positive, comment, reason };
}
export type Feedback = ReturnType<typeof feedbackOf>;

export async function loadChatHistory(
  sessionId: string,
  signal: AbortSignal,
): Promise<ChatHistory> {
  signal = AbortSignal.any([signal, AbortSignal.timeout(30_000)]);
  const { data: session } = await getChatSession({
    path: { sessionId },
    signal,
  });
  const messages: ChatMessage[] = [];
  let characters = 0;
  // The server bounds a selected branch to 9999 nodes. Never truncate silently.
  for (let page = 0; page < 100; page++) {
    const { data } = await getChatHistory({
      path: { sessionId },
      query: { limit: 100, after: messages.at(-1)?.id },
      signal,
    });
    if (data.length === 0) return { session, messages };
    characters += data.reduce(
      (total, message) =>
        total +
        message.content.length +
        (message.artifacts ?? []).reduce(
          (size, artifact) => size + (artifact.spec?.length ?? 0),
          0,
        ),
      0,
    );
    if (characters > 8_000_000) throw new Error("Conversation history exceeds the browser limit");
    if (data.at(-1)?.id === messages.at(-1)?.id) throw new Error("History cursor did not advance");
    messages.push(...data);
  }
  throw new Error("Conversation history exceeds the supported limit");
}

export async function newChatSession(
  text: string,
  signal: AbortSignal,
  personaId?: string,
  projectId?: string,
  /** A temporary conversation (MEM-153): listed nowhere, deleted with its uploads after its window. */
  temporary?: boolean,
) {
  const { data } = await createChatSession({
    body: { title: initialChatTitle(text), personaId, projectId, temporary },
    signal: AbortSignal.any([signal, AbortSignal.timeout(30_000)]),
  });
  return data;
}

export function initialChatTitle(text: string) {
  const segments = new Intl.Segmenter(undefined, { granularity: "grapheme" }).segment(
    text.trim().replace(/\s+/g, " "),
  );
  let title = "";
  let count = 0;
  for (const { segment } of segments) {
    // Keep whole visible characters and reserve room for the ellipsis inside the API's 200 UTF-16 limit.
    if (count === 40 || title.length + segment.length > 199) return `${title.trimEnd()}…`;
    title += segment;
    count++;
  }
  return title || i18n.t("app:Hội thoại mới", { keySeparator: false });
}

/** Saved deep research renders before the answer, as it streamed. */
function researchParts(message: ChatMessage): ChatUiMessage["parts"] {
  const research = message.role === "ASSISTANT" ? historyResearch(message.research) : undefined;
  return research ? [{ type: "data-research", id: `${message.id}:research`, data: research }] : [];
}

export function toUiMessages(messages: ChatMessage[]): ChatUiMessage[] {
  return messages.map((message) => ({
    id: message.id,
    role: message.role === "USER" ? "user" : "assistant",
    parts: [
      ...researchParts(message),
      // Attached files come first, above the question, as they are shown while composing.
      ...(message.files ?? []).map((file) => ({
        type: "file" as const,
        filename: file.filename,
        mediaType: file.mediaType ?? "application/octet-stream",
        url: fileReference(file.id!),
      })),
      // historyParts builds text, reasoning and tool parts only; its data-part type stays the AI SDK default.
      ...((message.role === "ASSISTANT"
        ? historyParts(message.content, activitySchema.parse(message.activity))
        : [{ type: "text" as const, text: message.content }]) as ChatUiMessage["parts"]),
    ],
    metadata: {
      serverStatus: message.status,
      failureCode: message.failureCode ?? undefined,
      createdAt: message.createdAt,
      sources: sourcesSchema.parse(message.sources),
      artifacts: artifactsSchema.parse(message.artifacts),
      images: parseGeneratedImages(message.images),
      generatedFiles: parseGeneratedFiles(message.generatedFiles),
    },
  }));
}
