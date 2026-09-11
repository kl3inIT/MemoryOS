// Adapted from assistant-ui EditMessage (MIT), revision 2c22f5d7.
// Server-backed editing retains the previous branch; no discard warning applies.
import { useEffect, useRef } from "react";
import { Button } from "@/components/ui/button";

export function EditMessage({
  value,
  onValueChange,
  onSave,
  onCancel,
  pending,
  saveDisabled,
  error,
}: {
  value: string;
  onValueChange: (value: string) => void;
  onSave: () => void;
  onCancel: () => void;
  pending: boolean;
  saveDisabled?: boolean;
  error?: string;
}) {
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
        if (!pending && !saveDisabled && value.trim()) onSave();
      }}
    >
      <textarea
        ref={input}
        aria-label="Nội dung câu hỏi"
        value={value}
        required
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
            value.trim()
          ) {
            event.preventDefault();
            onSave();
          }
        }}
        className="max-h-80 min-h-24 w-full resize-y bg-transparent text-base leading-relaxed outline-none disabled:opacity-60"
      />
      <p className="text-xs text-content-muted">Câu hỏi và câu trả lời cũ vẫn có thể chọn lại.</p>
      {error && (
        <p role="alert" className="text-sm">
          {error}
        </p>
      )}
      <div className="flex justify-end gap-2">
        <Button type="button" prominence="secondary" disabled={pending} onClick={onCancel}>
          Hủy
        </Button>
        <Button type="submit" pending={pending} disabled={saveDisabled || !value.trim()}>
          Lưu và gửi
        </Button>
      </div>
    </form>
  );
}
