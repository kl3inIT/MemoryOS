import { useEffect, useRef, type ComponentProps } from "react";
import { ComposerPrimitive, useAui, useAuiState } from "@assistant-ui/react";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { ChatDictationAutoSend } from "@/features/voice/chat-dictation-controls";
import { useFilesBlocked } from "./use-files-blocked";
import { usePasteTiles } from "./chat-paste-tiles-context";
import { Slot } from "radix-ui";

/**
 * Restores unsent composer text after a reload of the same tab. The draft is keyed by actor and
 * conversation and lives only in sessionStorage, so it never outlives the tab.
 */
export function ChatComposerDraft() {
  const aui = useAui();
  const { actorId } = useApplicationSession();
  const remoteId = useAuiState((state) => state.threadListItem.remoteId);
  const text = useAuiState((state) => state.composer.text);
  const key = `memoryos.chat.draft:${actorId}:${remoteId ?? "new"}`;
  const restored = useRef<string>(undefined);
  useEffect(() => {
    if (restored.current === key) return;
    restored.current = key;
    try {
      const saved = sessionStorage.getItem(key);
      if (saved) aui.composer.setText(saved);
    } catch {
      /* Draft storage is optional. */
    }
  }, [aui, key]);
  useEffect(() => {
    if (restored.current !== key) return;
    try {
      if (text) sessionStorage.setItem(key, text);
      else sessionStorage.removeItem(key);
    } catch {
      /* Draft storage is optional. */
    }
  }, [key, text]);
  return null;
}

export function ChatComposerRoot({
  onSubmit,
  ...props
}: ComponentProps<typeof ComposerPrimitive.Root>) {
  const blocked = useFilesBlocked();
  // The draft is incomplete until dictation writes its final transcript.
  const dictating = useAuiState((state) => state.composer.dictation != null);
  const pastes = usePasteTiles();
  return (
    <ComposerPrimitive.Root
      {...props}
      onSubmit={(event) => {
        if (blocked || dictating) event.preventDefault();
        // With paste tiles the message is the typed text and the tiles, so the send is ours (Enter submits here).
        else if (pastes && pastes.tiles.length > 0) {
          event.preventDefault();
          pastes.send();
        }
        onSubmit?.(event);
      }}
    />
  );
}

export function ChatComposerSend({
  disabled,
  ...props
}: ComponentProps<typeof ComposerPrimitive.Send>) {
  const blocked = useFilesBlocked();
  const dictating = useAuiState((state) => state.composer.dictation != null);
  const pastes = usePasteTiles();
  if (pastes && pastes.tiles.length > 0) {
    // assistant-ui keeps its Send disabled while the field is empty, though the tiles alone are a message.
    const { asChild, children, onClick, ...rest } = props;
    const Button = asChild ? Slot.Root : "button";
    return (
      <Button
        type="button"
        {...rest}
        disabled={disabled || blocked || dictating || pastes.overLimit}
        onClick={(event) => {
          onClick?.(event);
          if (!event.defaultPrevented) pastes.send();
        }}
      >
        {children}
      </Button>
    );
  }
  return <ComposerPrimitive.Send {...props} disabled={disabled || blocked || dictating} />;
}

/**
 * Voice Auto-Send, held back while an attachment is still uploading or unreadable, as Send is, and while paste
 * tiles are on the draft, so a spoken question is never sent without the text it refers to.
 */
export function ChatComposerAutoSend() {
  const blocked = useFilesBlocked();
  const pasted = (usePasteTiles()?.tiles.length ?? 0) > 0;
  return <ChatDictationAutoSend filesBlocked={blocked || pasted} />;
}
