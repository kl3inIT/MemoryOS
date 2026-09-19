import type { UIMessage } from "ai";
import { i18n } from "@/i18n";
import { sourcesSchema, type ChatSource } from "./chat-evidence";
import { activitySchema, historyParts } from "./chat-activity";
import { sameOriginMutationHeaders } from "@/lib/api";
import { createChatSession, getChatHistory, getChatSession } from "@/lib/hey-api/sdk.gen";
import type { ChatMessage, ChatSession } from "@/lib/hey-api/types.gen";
import { fileReference } from "./chat-files";
import { artifactsSchema, type ChatArtifact } from "./chat-artifacts";
import { parseGeneratedImages, type GeneratedImage } from "./chat-image";
import { parseGeneratedFiles, type GeneratedFile } from "./chat-code";
import { historyResearch, type ResearchState } from "./chat-research";

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
export const chatSessionsKey = ["chat-sessions"] as const;

export async function loadChatHistory(
  sessionId: string,
  signal: AbortSignal,
): Promise<ChatHistory> {
  signal = AbortSignal.any([signal, AbortSignal.timeout(30_000)]);
  const { data: session } = await getChatSession({
    path: { sessionId },
    signal,
    throwOnError: true,
  });
  const messages: ChatMessage[] = [];
  let characters = 0;
  // The server bounds a selected branch to 9999 nodes. Never truncate silently.
  for (let page = 0; page < 100; page++) {
    const { data } = await getChatHistory({
      path: { sessionId },
      query: { limit: 100, after: messages.at(-1)?.id },
      signal,
      throwOnError: true,
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
) {
  const { data } = await createChatSession({
    body: { title: initialChatTitle(text), personaId, projectId },
    headers: sameOriginMutationHeaders,
    signal: AbortSignal.any([signal, AbortSignal.timeout(30_000)]),
    throwOnError: true,
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
      // historyParts builds text, reasoning and tool parts only; its data-part type stays the AI SDK default.
      ...((message.role === "ASSISTANT"
        ? historyParts(message.content, activitySchema.parse(message.activity))
        : [{ type: "text" as const, text: message.content }]) as ChatUiMessage["parts"]),
      ...(message.files ?? []).map((file) => ({
        type: "file" as const,
        filename: file.filename,
        mediaType: file.mediaType ?? "application/octet-stream",
        url: fileReference(file.id!),
      })),
    ],
    metadata: {
      serverStatus: message.status,
      failureCode: message.failureCode ?? undefined,
      createdAt: message.createdAt,
      sources: sourcesSchema.parse(message.sources),
      artifacts: artifactsSchema.parse(message.artifacts),
      images: parseGeneratedImages((message as { images?: unknown }).images),
      generatedFiles: parseGeneratedFiles(message.generatedFiles),
    },
  }));
}
