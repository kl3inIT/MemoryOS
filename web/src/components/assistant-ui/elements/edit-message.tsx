import { useAppTranslation } from "@/i18n/use-app-translation";
// Adapted from assistant-ui EditMessage (MIT), revision 2c22f5d7.
// Server-backed editing retains the previous branch; no discard warning applies.
import { useEffect, useRef, type ReactNode } from "react";
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
  useEffect(() => {
    input.current?.focus();
    input.current?.select();
  }, []);
  return (
    <form
      data-slot="edit-message"
      className="flex w-full flex-col gap-3 rounded-2xl border border-border-default bg-surface-raised p-4"
      onSubmit={(event) => {
        event.preventDefault();
        if (!pending && !saveDisabled && (value.trim() || hasAttachments)) onSave();
      }}
    >
      <textarea
        ref={input}
        aria-label={ui("Nội dung câu hỏi")}
        value={value}
        required={!hasAttachments}
        maxLength={32000}
        rows={3}
        disabled={pending}
        onChange={(event) => onValueChange(event.target.value)}
        onKeyDown={(event) => {
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
        className="max-h-80 min-h-24 w-full resize-y bg-transparent text-base leading-relaxed outline-none disabled:opacity-60"
      />
      {children}
      <p className="text-xs text-content-muted">
        {ui("Câu hỏi và câu trả lời cũ vẫn có thể chọn lại.")}
      </p>
      {error && (
        <p role="alert" className="text-sm">
          {error}
        </p>
      )}
      <div className="flex justify-end gap-2">
        <Button type="button" prominence="secondary" disabled={pending} onClick={onCancel}>
          {ui("Hủy")}
        </Button>
        <Button
          type="submit"
          pending={pending}
          disabled={saveDisabled || (!value.trim() && !hasAttachments)}
        >
          {ui("Lưu và gửi")}
        </Button>
      </div>
    </form>
  );
}
