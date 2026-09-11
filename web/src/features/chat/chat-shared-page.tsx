import {
  AssistantRuntimeProvider,
  useExternalStoreRuntime,
  type ThreadMessageLike,
} from "@assistant-ui/react";
import { useQuery } from "@tanstack/react-query";
import { z } from "zod";
import { AppShell } from "@/components/app-shell/app-shell";
import { Button } from "@/components/ui/button";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { getSharedChatHistory, getSharedChatSession } from "@/lib/hey-api/sdk.gen";
import type { ChatMessage } from "@/lib/hey-api/types.gen";
import { ChatThread } from "./chat-thread";
import { sourcesSchema } from "./chat-evidence";

const sharedSchema = z.object({
  id: z.string().uuid(),
  title: z.string(),
  rootMessageId: z.string().uuid(),
});
async function loadShared(sessionId: string, signal: AbortSignal) {
  signal = AbortSignal.any([signal, AbortSignal.timeout(30000)]);
  const session = sharedSchema.parse(
    (await getSharedChatSession({ path: { sessionId }, signal, throwOnError: true })).data,
  );
  const messages: ChatMessage[] = [];
  let characters = 0;
  for (let page = 0; page < 100; page++) {
    const { data } = await getSharedChatHistory({
      path: { sessionId },
      query: { after: messages.at(-1)?.id, limit: 100 },
      signal,
      throwOnError: true,
    });
    if (data.length === 0) return { session, messages };
    characters += data.reduce((count, message) => count + message.content.length, 0);
    if (characters > 8000000 || data.at(-1)?.id === messages.at(-1)?.id)
      throw new Error("Shared history exceeds the browser limit");
    messages.push(...data);
  }
  throw new Error("Shared history exceeds the browser limit");
}

export function ChatSharedPage({ sessionId }: { sessionId: string }) {
  const { actorId, authorizationVersion } = useApplicationSession();
  const shared = useQuery({
    queryKey: ["chat-shared", actorId, authorizationVersion, sessionId],
    queryFn: ({ signal }) => loadShared(sessionId, signal),
    retry: false,
    gcTime: 0,
    refetchInterval: 30000,
  });
  return (
    <AppShell pageTitle="Hội thoại được chia sẻ">
      <div className="flex h-full min-h-0 flex-col">
        {shared.isPending ? (
          <p role="status" className="p-6">
            Đang tải hội thoại…
          </p>
        ) : shared.isError ? (
          <div role="alert" className="space-y-3 p-6">
            <p>
              Hội thoại không khả dụng. Liên kết có thể đã bị thu hồi hoặc bạn không thuộc Tenant
              được chia sẻ.
            </p>
            <Button prominence="secondary" onClick={() => void shared.refetch()}>
              Tải lại
            </Button>
          </div>
        ) : (
          <>
            <div className="border-b border-border-subtle px-6 py-3">
              <h1 className="font-medium">{shared.data.session.title}</h1>
              <p className="mt-1 text-xs text-content-muted">
                Chỉ đọc · Nhánh hiện đang được chủ hội thoại chia sẻ
              </p>
            </div>
            <SharedTranscript messages={shared.data.messages} />
          </>
        )}
      </div>
    </AppShell>
  );
}

function convertMessage(message: ChatMessage): ThreadMessageLike {
  return {
    id: message.id,
    role: message.role === "USER" ? "user" : "assistant",
    content: [{ type: "text", text: message.content }],
    metadata: {
      custom: { serverStatus: message.status, sources: sourcesSchema.parse(message.sources) },
    },
  };
}
function SharedTranscript({ messages }: { messages: ChatMessage[] }) {
  const runtime = useExternalStoreRuntime({
    messages,
    convertMessage,
    isDisabled: true,
    onNew: async () => {
      throw new Error("Shared conversations are read-only");
    },
  });
  if (messages.length === 0)
    return <p className="p-6 text-content-secondary">Chưa có tin nhắn đã lưu để hiển thị.</p>;
  return (
    <AssistantRuntimeProvider runtime={runtime}>
      <ChatThread
        readOnly
        modelPicker={null}
        connection="ready"
        stopping={false}
        checking={false}
        onStop={() => {}}
        onCheck={async () => {}}
      />
    </AssistantRuntimeProvider>
  );
}
