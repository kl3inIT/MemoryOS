import { useEffect, useRef, type ComponentProps } from "react";
import { ComposerPrimitive, useAui, useAuiState } from "@assistant-ui/react";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { fileIdFromReference } from "./chat-files";

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

/** Business readiness layered on the native composer; no duplicate draft state. */
function useFilesBlocked() {
  return useAuiState(
    (state) =>
      state.composer.attachments.length > 20 ||
      state.composer.attachments.some(
        (file) =>
          file.status.type === "running" ||
          file.status.type === "incomplete" ||
          !file.content?.some(
            (part) =>
              part.type === "file" &&
              typeof part.data === "string" &&
              fileIdFromReference(part.data),
          ),
      ),
  );
}

export function ChatComposerRoot({
  onSubmit,
  ...props
}: ComponentProps<typeof ComposerPrimitive.Root>) {
  const blocked = useFilesBlocked();
  return (
    <ComposerPrimitive.Root
      {...props}
      onSubmit={(event) => {
        if (blocked) event.preventDefault();
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
  return <ComposerPrimitive.Send {...props} disabled={disabled || blocked} />;
}
