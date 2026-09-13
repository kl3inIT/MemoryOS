import { useAppTranslation } from "@/i18n/use-app-translation";
// Adapted from assistant-ui EditMessage (MIT), revision 2c22f5d7.
// Server-backed editing retains the previous branch; no discard warning applies.
import { useEffect, useId, useRef, type ReactNode } from "react";
import TextareaAutosize from "react-textarea-autosize";
import { Button } from "@/components/ui/button";

export function EditMessage({
  value,
  onValueChange,
  onSave,
  onCancel,
  pending,
  saveDisabled,
  error,
  children,
  hasAttachments = false,
}: {
  value: string;
  onValueChange: (value: string) => void;
  onSave: () => void;
  onCancel: () => void;
  pending: boolean;
  saveDisabled?: boolean;
  error?: string;
  children?: ReactNode;
  hasAttachments?: boolean;
}) {
  const ui = useAppTranslation();

  const input = useRef<HTMLTextAreaElement>(null);
  const hintId = useId();
  useEffect(() => {
    input.current?.focus();
    input.current?.select();
  }, []);
  return (
    <form
      data-slot="edit-message"
      className="flex w-full min-w-0 flex-col gap-2 rounded-2xl border border-border-default bg-surface-sunken p-3 focus-within:border-border-strong"
      onSubmit={(event) => {
        event.preventDefault();
        if (!pending && !saveDisabled && (value.trim() || hasAttachments)) onSave();
      }}
    >
      <TextareaAutosize
        ref={input}
        aria-label={ui("Nội dung câu hỏi")}
        aria-describedby={hintId}
        value={value}
        required={!hasAttachments}
        maxLength={32000}
        minRows={1}
        maxRows={8}
        disabled={pending}
        onChange={(event) => onValueChange(event.target.value)}
        onKeyDown={(event) => {
          if (event.nativeEvent.isComposing) return;
          if (event.key === "Escape" && !pending) {
            event.preventDefault();
            onCancel();
          }
          if (
            event.key === "Enter" &&
            (event.ctrlKey || event.metaKey) &&
            !pending &&
            !saveDisabled &&
            (value.trim() || hasAttachments)
          ) {
            event.preventDefault();
            onSave();
          }
        }}
        className="w-full resize-none bg-transparent px-1 py-1.5 text-base leading-6 outline-none disabled:opacity-60"
      />
      <p id={hintId} className="sr-only">
        {ui("Câu hỏi và câu trả lời cũ vẫn có thể chọn lại.")}
      </p>
      {error && (
        <p role="alert" className="text-sm">
          {error}
        </p>
      )}
      <div data-slot="edit-message-footer" className="flex items-center justify-between gap-2">
        <div className="flex min-w-0 items-center">{children}</div>
        <div className="flex shrink-0 items-center gap-2">
          <Button
            type="button"
            size="sm"
            prominence="secondary"
            disabled={pending}
            onClick={onCancel}
          >
            {ui("Hủy")}
          </Button>
          <Button
            type="submit"
            size="sm"
            pending={pending}
            disabled={saveDisabled || (!value.trim() && !hasAttachments)}
          >
            {ui("Lưu và gửi")}
          </Button>
        </div>
      </div>
    </form>
  );
}
