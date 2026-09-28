import type { RemoteThreadListAdapter, ThreadHistoryAdapter } from "@assistant-ui/react";
import type { QueryClient } from "@tanstack/react-query";
import { createAssistantStream } from "assistant-stream";
import {
  archiveChatSession,
  deleteChatSession,
  generateChatTitle,
  getChatSession,
  listChatSessions,
  renameChatSession,
  unarchiveChatSession,
} from "@/lib/hey-api/sdk.gen";
import type { ChatSession } from "@/lib/hey-api/types.gen";
import { invalidateChatSessions } from "@/features/chat/chat-api";
import type { ChatThreadController, ChatThreadRegistry } from "./chat-thread-controller";

const PAGE = 30;

type RemoteThreadMetadata = Awaited<ReturnType<RemoteThreadListAdapter["fetch"]>>;

type ChatThreadCustom = Pick<
  ChatSession,
  | "personaId"
  | "rootMessageId"
  | "createdAt"
  | "updatedAt"
  | "projectId"
  | "reasoningEffort"
  | "archivedAt"
  | "branchedFromSessionId"
  | "branchedFromMessageId"
  | "temporary"
>;

function threadMetadata(session: ChatSession): RemoteThreadMetadata {
  const custom: ChatThreadCustom = {
    personaId: session.personaId,
    rootMessageId: session.rootMessageId,
    createdAt: session.createdAt,
    updatedAt: session.updatedAt,
    projectId: session.projectId ?? null,
    reasoningEffort: session.reasoningEffort ?? null,
    archivedAt: session.archivedAt ?? null,
    branchedFromSessionId: session.branchedFromSessionId ?? null,
    branchedFromMessageId: session.branchedFromMessageId ?? null,
    temporary: session.temporary,
  };
  return {
    // An archived conversation is kept, so the thread list holds it as archived rather than dropping it.
    status: session.archivedAt ? "archived" : "regular",
    remoteId: session.id,
    title: session.title,
    lastMessageAt: new Date(session.updatedAt),
    custom,
  };
}

/** Rebuilds the shared row shape from list state; `custom` is always written by `threadMetadata`. */
export function sessionFromThread(item: {
  remoteId?: string;
  title?: string;
  custom?: Record<string, unknown>;
}): ChatSession | undefined {
  const custom = item.custom as ChatThreadCustom | undefined;
  if (!item.remoteId || !custom) return undefined;
  return { id: item.remoteId, title: item.title ?? "", ...custom };
}

/**
 * Maps assistant-ui's remote thread list onto the owner-authorized session API. The server is the
 * transcript authority; nothing here persists messages client-side.
 */
export function createChatThreadListAdapter(
  registry: ChatThreadRegistry,
  queries: QueryClient,
): RemoteThreadListAdapter {
  // assistant-ui updates its own list after these calls; the cached conversation queries follow here.
  const refreshLists = (sessionId: string) => invalidateChatSessions(queries, sessionId);
  return {
    async list(params) {
      const offset = params?.after ? Number(params.after) : 0;
      const { data } = await listChatSessions({
        query: { offset, limit: PAGE, archived: false },
        signal: AbortSignal.timeout(30_000),
      });
      return {
        threads: data.map(threadMetadata),
        nextCursor:
          data.length === PAGE && offset + PAGE <= 10_000 ? String(offset + PAGE) : undefined,
      };
    },
    async fetch(remoteId) {
      const { data } = await getChatSession({
        path: { sessionId: remoteId },
        signal: AbortSignal.timeout(30_000),
      });
      return threadMetadata(data);
    },
    async initialize(localId) {
      const controller = registry.get(localId);
      if (!controller) throw new Error("Conversation is not mounted");
      const session = await controller.sessionCreated();
      return { remoteId: session.id, externalId: undefined };
    },
    async rename(remoteId, title) {
      const { data } = await renameChatSession({
        path: { sessionId: remoteId },
        body: { title },
        signal: AbortSignal.timeout(30_000),
      });
      registry.byRemoteId(remoteId)?.updateSession(data);
      await refreshLists(remoteId);
    },
    async archive(remoteId) {
      const { data } = await archiveChatSession({
        path: { sessionId: remoteId },
        signal: AbortSignal.timeout(30_000),
      });
      registry.byRemoteId(remoteId)?.updateSession(data);
      await refreshLists(remoteId);
    },
    async unarchive(remoteId) {
      const { data } = await unarchiveChatSession({
        path: { sessionId: remoteId },
        signal: AbortSignal.timeout(30_000),
      });
      registry.byRemoteId(remoteId)?.updateSession(data);
      await refreshLists(remoteId);
    },
    async delete(remoteId) {
      await deleteChatSession({
        path: { sessionId: remoteId },
        signal: AbortSignal.timeout(30_000),
      });
      await refreshLists(remoteId);
    },
    async updateCustom() {
      /* Custom values mirror server state already written by the caller. */
    },
    async generateTitle(remoteId) {
      const controller = registry.byRemoteId(remoteId);
      await controller?.firstAnswer().catch(() => undefined);
      let title = controller?.getState().session?.title;
      try {
        const { data } = await generateChatTitle({
          path: { sessionId: remoteId },
          signal: AbortSignal.timeout(15_000),
        });
        title = data.title;
        controller?.updateSession(data);
        await refreshLists(remoteId);
      } catch {
        /* Best-effort naming never changes answer or error state. */
      }
      return createAssistantStream((stream) => {
        if (title) stream.appendText(title);
      });
    },
  };
}

/** Loads the selected branch once per mounted thread; appends are ignored because the server saves turns. */
export function chatHistoryAdapter(controller: ChatThreadController): ThreadHistoryAdapter {
  return {
    load: async () => ({ messages: [] }),
    append: async () => {},
    withFormat: <TMessage>() => ({
      load: async () => {
        const messages = await controller.loadHistory();
        return {
          messages: messages.map((message, index) => ({
            parentId: messages[index - 1]?.id ?? null,
            message: message as unknown as TMessage,
          })),
        };
      },
      append: async () => {},
      update: async () => {},
    }),
  };
}
