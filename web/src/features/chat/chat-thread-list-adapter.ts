import type { RemoteThreadListAdapter, ThreadHistoryAdapter } from "@assistant-ui/react";
import type { QueryClient } from "@tanstack/react-query";
import { createAssistantStream } from "assistant-stream";
import { sameOriginMutationHeaders } from "@/lib/api";
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
import type { ChatThreadController, ChatThreadRegistry } from "./chat-thread-controller";

const PAGE = 30;

type RemoteThreadMetadata = Awaited<ReturnType<RemoteThreadListAdapter["fetch"]>>;

export type ChatThreadCustom = Pick<
  ChatSession,
  "personaId" | "rootMessageId" | "createdAt" | "updatedAt" | "projectId"
>;

export function threadMetadata(session: ChatSession): RemoteThreadMetadata {
  const custom: ChatThreadCustom = {
    personaId: session.personaId,
    rootMessageId: session.rootMessageId,
    createdAt: session.createdAt,
    updatedAt: session.updatedAt,
    projectId: session.projectId ?? null,
  };
  return {
    status: session.archived ? "archived" : "regular",
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
  status: string;
  custom?: Record<string, unknown>;
}): ChatSession | undefined {
  const custom = item.custom as ChatThreadCustom | undefined;
  if (!item.remoteId || !custom) return undefined;
  return {
    id: item.remoteId,
    title: item.title ?? "",
    archived: item.status === "archived",
    ...custom,
  };
}

/**
 * Maps assistant-ui's remote thread list onto the owner-authorized session API. The server is the
 * transcript authority; nothing here persists messages client-side.
 */
export function createChatThreadListAdapter(
  registry: ChatThreadRegistry,
  queries: QueryClient,
): RemoteThreadListAdapter {
  const refreshLists = (sessionId: string) =>
    Promise.all([
      queries.invalidateQueries({ queryKey: ["chat-project-sessions"] }),
      queries.invalidateQueries({ queryKey: ["chat-session", sessionId] }),
    ]);
  const request = { headers: sameOriginMutationHeaders, throwOnError: true } as const;
  return {
    async list(params) {
      const offset = params?.after ? Number(params.after) : 0;
      const { data } = await listChatSessions({
        query: { status: "ALL", offset, limit: PAGE },
        signal: AbortSignal.timeout(30_000),
        throwOnError: true,
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
        throwOnError: true,
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
        ...request,
        path: { sessionId: remoteId },
        body: { title },
        signal: AbortSignal.timeout(30_000),
      });
      registry.byRemoteId(remoteId)?.updateSession(data);
      await refreshLists(remoteId);
    },
    async archive(remoteId) {
      await archiveChatSession({
        ...request,
        path: { sessionId: remoteId },
        signal: AbortSignal.timeout(30_000),
      });
      await refreshLists(remoteId);
    },
    async unarchive(remoteId) {
      await unarchiveChatSession({
        ...request,
        path: { sessionId: remoteId },
        signal: AbortSignal.timeout(30_000),
      });
      await refreshLists(remoteId);
    },
    async delete(remoteId) {
      await deleteChatSession({
        ...request,
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
          ...request,
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
            parentId: index === 0 ? null : messages[index - 1]!.id,
            message: message as unknown as TMessage,
          })),
        };
      },
      append: async () => {},
      update: async () => {},
    }),
  };
}
