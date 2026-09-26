import { useAppTranslation } from "@/i18n/use-app-translation";
import { ChatPromptShortcutPopover } from "@/features/agents/prompt-shortcuts";
import { AuiIf, ComposerPrimitive, ThreadPrimitive, useAuiState } from "@assistant-ui/react";
import { ArrowDown, ArrowUp, Square } from "lucide-react";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { ErrorState } from "@/components/assistant-ui/elements/error-state";
import { ConnectionState as ConnectionNotice } from "@/components/assistant-ui/elements/connection-state";
import { IconButton } from "@/components/ui/icon-button";
import { cn } from "@/lib/utils";
import type { ConnectionState } from "@/features/chat/runtime/chat-transport";
import {
  ChatComposerAutoSend,
  ChatComposerDraft,
  ChatComposerRoot,
  ChatComposerSend,
} from "@/features/chat/composer/chat-composer";
import { ChatComposerAttachments } from "@/features/chat/composer/chat-attachment-staging";
import {
  ChatDictationButton,
  ChatDictationStrip,
  ChatVoiceFailure,
} from "@/features/voice/chat-dictation-controls";
import {
  ChatAutoListen,
  ChatAutoPlayback,
  ChatSpeakingIndicator,
} from "@/features/voice/chat-auto-playback";
import { useAutoPlayback } from "@/features/voice/use-chat-auto-playback";
import { ChatComposerQuote } from "./chat-quote";

/** A message admits at most this many attachments. */
const ATTACHMENT_LIMIT = 20;

/**
 * The composer at the foot of the thread: connection and model notices above it, then the question field with its
 * attachments, quote, dictation and the toolbar of tools, model and Send or Stop.
 */
export function ChatThreadComposer({
  composerMenu,
  modelPicker,
  modelNotice,
  connection,
  stopping,
  onStop,
  error,
  onCheck,
  checking,
  sendDisabled,
  isEmpty,
}: {
  composerMenu?: ReactNode;
  modelPicker: ReactNode;
  modelNotice?: string;
  connection: ConnectionState;
  stopping: boolean;
  onStop: () => void;
  error?: string;
  onCheck: () => Promise<void>;
  checking: boolean;
  sendDisabled: boolean;
  /** An empty thread centres the composer; a conversation keeps it at the bottom. */
  isEmpty: boolean;
}) {
  const ui = useAppTranslation();
  const { t } = useTranslation("chatStatus");
  const dictating = useAuiState((state) => state.composer.dictation != null);
  const reading = useAutoPlayback().phase !== "idle";
  return (
    <ThreadPrimitive.ViewportFooter
      className={cn(
        "relative mx-auto flex w-full max-w-3xl flex-col bg-surface-base pb-[max(1rem,env(safe-area-inset-bottom))] pt-3",
        !isEmpty && "sticky bottom-0 mt-auto rounded-t-2xl",
      )}
    >
      <div className="absolute -top-11 left-1/2 -translate-x-1/2">
        <ThreadPrimitive.ScrollToBottom asChild>
          <IconButton
            aria-label={ui("Đến tin nhắn mới nhất")}
            prominence="secondary"
            className="disabled:hidden"
          >
            <ArrowDown />
          </IconButton>
        </ThreadPrimitive.ScrollToBottom>
      </div>
      {modelNotice && (
        <p role="status" className="mb-2 text-sm text-content-secondary">
          {modelNotice}
        </p>
      )}
      {connection === "recovering" && !error && (
        <ConnectionNotice className="mb-2" label={t("reconnecting")} />
      )}
      {connection === "uncertain" || error ? (
        <ErrorState
          className="mb-3"
          title={t("unconfirmed")}
          detail={error ?? t("checkBeforeSending")}
          action={{ label: t("check"), pending: checking, onClick: () => void onCheck() }}
        />
      ) : null}
      <ChatVoiceFailure />
      <ChatSpeakingIndicator />
      <ChatAutoPlayback />
      <ComposerPrimitive.Unstable_TriggerPopoverRoot>
        <ComposerPrimitive.AttachmentDropzone className="rounded-2xl data-[dragging]:ring-2">
          <ChatComposerRoot className="flex w-full flex-col gap-2 rounded-2xl border border-border-default bg-surface-raised p-2.5 shadow-sm transition-colors focus-within:border-border-strong">
            <ChatComposerDraft />
            <ChatComposerAutoSend />
            <ChatAutoListen />
            <ChatComposerQuote />
            <ChatComposerAttachments />
            <ComposerPrimitive.Input
              aria-label={ui("Câu hỏi")}
              placeholder={
                dictating
                  ? ui("Đang nghe…")
                  : reading
                    ? ui("MemoryOS đang đọc…")
                    : ui("Nhập câu hỏi…")
              }
              rows={1}
              maxLength={32000}
              className="max-h-48 min-h-12 w-full resize-none bg-transparent px-2.5 py-1.5 text-base leading-6 outline-none placeholder:text-content-muted"
            />
            <ChatPromptShortcutPopover />
            <ChatDictationStrip />
            <AuiIf condition={(state) => state.composer.attachments.length > ATTACHMENT_LIMIT}>
              <p role="alert" className="text-sm">
                {ui("Mỗi tin nhắn có tối đa 20 tệp. Hãy gỡ bớt trước khi gửi.")}
              </p>
            </AuiIf>
            <div
              data-testid="chat-composer-actions"
              className="flex flex-nowrap items-center justify-between gap-2"
            >
              {composerMenu ?? <span />}
              <div className="flex min-w-0 items-center gap-1">
                {modelPicker}
                <ChatDictationButton />
                <AuiIf condition={(state) => !state.thread.isRunning}>
                  <ChatComposerSend asChild disabled={sendDisabled}>
                    <IconButton aria-label={ui("Gửi câu hỏi")} prominence="primary">
                      <ArrowUp />
                    </IconButton>
                  </ChatComposerSend>
                </AuiIf>
                <AuiIf condition={(state) => state.thread.isRunning}>
                  <IconButton
                    aria-label={stopping ? ui("Đang yêu cầu dừng") : ui("Dừng trả lời")}
                    prominence="secondary"
                    disabled={stopping}
                    onClick={onStop}
                  >
                    <Square />
                  </IconButton>
                </AuiIf>
              </div>
            </div>
          </ChatComposerRoot>
        </ComposerPrimitive.AttachmentDropzone>
      </ComposerPrimitive.Unstable_TriggerPopoverRoot>
    </ThreadPrimitive.ViewportFooter>
  );
}
