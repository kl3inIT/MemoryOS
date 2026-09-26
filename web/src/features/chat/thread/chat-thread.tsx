import { useAppTranslation } from "@/i18n/use-app-translation";
import { AuiIf, ThreadPrimitive, useAuiState } from "@assistant-ui/react";
import type { ReactNode } from "react";
import { ChatSourcesWorkspace } from "@/features/chat/sources/chat-sources";
import { cn } from "@/lib/utils";
import { useChatPreferences } from "@/features/identity/chat-preferences";
import type { ConnectionState } from "@/features/chat/runtime/chat-transport";
import { ChatAttachmentStaging } from "@/features/chat/composer/chat-attachment-staging";
import { ChatSelectionToolbar } from "./chat-quote";
import { ChatThreadComposer } from "./chat-thread-composer";
import { AssistantMessage, UserMessage } from "./chat-thread-messages";

export function ChatThread({
  composerMenu,
  modelPicker,
  modelNotice,
  connection,
  stopping,
  onStop,
  error,
  onCheck,
  checking,
  starters,
  welcome,
  afterComposer,
  readOnly = false,
  sendDisabled = false,
}: {
  /** Left of the composer toolbar: the `+` menu for files and tools. */
  composerMenu?: ReactNode;
  /** Right of the composer toolbar, beside Send. */
  modelPicker: ReactNode;
  modelNotice?: string;
  connection: ConnectionState;
  stopping: boolean;
  onStop: () => void;
  error?: string;
  onCheck: () => Promise<void>;
  checking: boolean;
  starters?: ReactNode;
  welcome?: ReactNode;
  afterComposer?: ReactNode;
  readOnly?: boolean;
  /** Blocks Send while the conversation's agent and its tool policy are still unknown. */
  sendDisabled?: boolean;
}) {
  const ui = useAppTranslation();

  const isEmpty = useAuiState((state) => state.thread.isEmpty);
  // Onyx "Chat Auto-scroll": follow the answer while it is written unless the member turned it off.
  const autoScroll = useChatPreferences().data?.autoScroll ?? true;
  return (
    <ChatAttachmentStaging>
      <ChatSourcesWorkspace>
        <ThreadPrimitive.Root className="aui-root flex h-full min-h-0 min-w-0 flex-1 flex-col [&_[data-chat-search-match]]:rounded-xl [&_[data-chat-search-match]]:bg-highlight-match/40">
          <ThreadPrimitive.Viewport
            data-testid="chat-viewport"
            autoScroll={autoScroll}
            className={cn(
              "relative flex min-h-0 flex-1 flex-col overflow-x-hidden overflow-y-auto px-4 pt-6 scrollbar-gutter-stable sm:px-8",
              isEmpty && !welcome && "justify-center",
            )}
          >
            <AuiIf condition={(state) => state.thread.isEmpty}>
              <div className="mx-auto mb-8 w-full max-w-3xl text-center">
                {welcome ?? (
                  <h1 className="text-2xl font-medium tracking-tight sm:text-3xl">
                    {ui("Bạn muốn tìm hiểu điều gì?")}
                  </h1>
                )}
                {!welcome && starters}
              </div>
            </AuiIf>
            <div className="mx-auto flex w-full max-w-3xl flex-col gap-7 pb-8 font-main-content-body empty:hidden">
              <ThreadPrimitive.Messages>
                {({ message }) =>
                  message.role === "user" ? (
                    <UserMessage readOnly={readOnly} />
                  ) : (
                    <AssistantMessage readOnly={readOnly} />
                  )
                }
              </ThreadPrimitive.Messages>
            </div>
            {!readOnly && (
              <ChatThreadComposer
                composerMenu={composerMenu}
                modelPicker={modelPicker}
                modelNotice={modelNotice}
                connection={connection}
                stopping={stopping}
                onStop={onStop}
                error={error}
                onCheck={onCheck}
                checking={checking}
                sendDisabled={sendDisabled}
                isEmpty={isEmpty}
              />
            )}
            {isEmpty && afterComposer && (
              <div className="mx-auto w-full max-w-3xl pb-8">{afterComposer}</div>
            )}
          </ThreadPrimitive.Viewport>
          {!readOnly && <ChatSelectionToolbar />}
        </ThreadPrimitive.Root>
      </ChatSourcesWorkspace>
    </ChatAttachmentStaging>
  );
}
