import { useState } from "react";
import { Clock, EyeOff, FolderX, MessageSquareOff } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { FormDialog } from "@/components/composites/form-dialog";
import { useAppTranslation } from "@/i18n/use-app-translation";

/**
 * Temporary chat (MEM-153), after ChatGPT's own: turning it on is explained once, in the terms that matter —
 * it is in no list, it is deleted with its files, and the conversation cannot be shared or filed. The toggle
 * only exists before the first question, because a conversation cannot become temporary after it was written
 * down; once it is on, the header says so and when it will go.
 */
export function ChatTemporaryToggle({
  value,
  disabled,
  onChange,
}: {
  value: boolean;
  disabled?: boolean;
  onChange: (temporary: boolean) => void;
}) {
  const ui = useAppTranslation();
  const [explaining, setExplaining] = useState(false);
  return (
    <>
      {/* A native title, not a Radix tooltip: this sits outside any TooltipProvider. */}
      <IconButton
        size="sm"
        prominence={value ? "secondary" : "internal"}
        aria-pressed={value}
        aria-label={value ? ui("Tắt chat tạm thời") : ui("Bật chat tạm thời")}
        title={value ? ui("Chat tạm thời đang bật") : ui("Chat tạm thời")}
        disabled={disabled}
        onClick={() => (value ? onChange(false) : setExplaining(true))}
      >
        <EyeOff />
      </IconButton>
      {explaining && (
        <FormDialog
          open
          onOpenChange={(open) => !open && setExplaining(false)}
          title={ui("Chat tạm thời")}
          description={ui("Dùng khi bạn không muốn cuộc trò chuyện này được lưu lại.")}
          submitLabel={ui("Bắt đầu")}
          onSubmit={async () => {
            onChange(true);
            setExplaining(false);
          }}
        >
          <ul className="flex flex-col gap-3">
            <TemporaryFact
              icon={<MessageSquareOff />}
              title={ui("Không vào lịch sử")}
              description={ui("Cuộc trò chuyện không hiện trên thanh bên và không tìm được.")}
            />
            <TemporaryFact
              icon={<Clock />}
              title={ui("Tự xoá")}
              description={ui("Cuộc trò chuyện và tệp bạn gửi vào đó bị xoá sau khi bạn dừng hỏi.")}
            />
            <TemporaryFact
              icon={<FolderX />}
              title={ui("Không chia sẻ, không dự án")}
              description={ui("Không tạo được liên kết chia sẻ và không thêm được vào dự án.")}
            />
          </ul>
        </FormDialog>
      )}
    </>
  );
}

function TemporaryFact({
  icon,
  title,
  description,
}: {
  icon: React.ReactNode;
  title: string;
  description: string;
}) {
  return (
    <li className="flex gap-3">
      <span
        className="grid size-8 shrink-0 place-items-center rounded-lg bg-surface-subtle text-content-secondary [&_svg]:size-4"
        aria-hidden="true"
      >
        {icon}
      </span>
      <span className="min-w-0">
        <span className="block font-main-ui-action text-content-primary">{title}</span>
        <span className="block font-secondary-body text-content-muted">{description}</span>
      </span>
    </li>
  );
}

/** The header's own mark on a temporary conversation, so nobody wonders whether this one is being kept. */
export function ChatTemporaryBadge({ temporary }: { temporary: boolean }) {
  const ui = useAppTranslation();
  if (!temporary) return null;
  return (
    <Badge variant="secondary">
      <EyeOff aria-hidden="true" />
      {ui("Tạm thời")}
    </Badge>
  );
}

/** The banner on a temporary conversation's own screen, with the way out of it (Mistral's incognito banner). */
export function ChatTemporaryNotice({ onLeave }: { onLeave: () => void }) {
  const ui = useAppTranslation();
  return (
    <p
      role="status"
      className="mx-auto flex w-fit max-w-full flex-wrap items-center justify-center gap-2 rounded-full border border-border-subtle bg-surface-subtle px-3 py-1 font-secondary-body text-content-secondary"
    >
      <EyeOff className="size-3.5 shrink-0" aria-hidden="true" />
      {ui("Cuộc trò chuyện này không được lưu và sẽ tự xoá cùng tệp của nó.")}
      <Button size="sm" prominence="internal" onClick={onLeave}>
        {ui("Hội thoại mới")}
      </Button>
    </p>
  );
}
