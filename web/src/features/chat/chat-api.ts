import type { UIMessage } from "ai";
import { sourcesSchema, type ChatSource, type SearchProgress } from "./chat-evidence";
import { sameOriginMutationHeaders } from "@/lib/api";
import { createChatSession, getChatHistory, getChatSession } from "@/lib/hey-api/sdk.gen";
import type { ChatMessage, ChatSession } from "@/lib/hey-api/types.gen";
import { fileReference } from "./chat-files";

export type ChatUiMessage = UIMessage<{
  serverStatus?: ChatMessage["status"];
  createdAt?: string;
  sources?: ChatSource[];
  searchProgress?: SearchProgress;
}>;
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
    characters += data.reduce((total, message) => total + message.content.length, 0);
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
    body: { title: text.trim().slice(0, 200) || "Hội thoại mới", personaId, projectId },
    headers: sameOriginMutationHeaders,
    signal: AbortSignal.any([signal, AbortSignal.timeout(30_000)]),
    throwOnError: true,
  });
  return data;
}

export function toUiMessages(messages: ChatMessage[]): ChatUiMessage[] {
  return messages.map((message) => ({
    id: message.id,
    role: message.role === "USER" ? "user" : "assistant",
    parts: [
      { type: "text", text: message.content },
      ...(message.files ?? []).map((file) => ({
        type: "file" as const,
        filename: file.filename,
        mediaType: file.mediaType ?? "application/octet-stream",
        url: fileReference(file.id!),
      })),
    ],
    metadata: {
      serverStatus: message.status,
      createdAt: message.createdAt,
      sources: sourcesSchema.parse(message.sources),
    },
  }));
}
