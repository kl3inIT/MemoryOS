import type { ComponentProps } from "react";
import { ComposerPrimitive, useAuiState } from "@assistant-ui/react";
import { fileIdFromReference } from "./chat-files";

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
