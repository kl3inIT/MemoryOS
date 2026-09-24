import { useAppTranslation } from "@/i18n/use-app-translation";
import { ActionBarMorePrimitive as More } from "@assistant-ui/react";
import { ChevronDown } from "lucide-react";
import { IconButton } from "@/components/ui/icon-button";
import { useChatModels } from "@/features/chat/chat-models";

/** Regenerates through the saved server command with a model from the authorized catalog. */
export function ChatRegenerateMenu({
  sessionId,
  disabled,
  onSelect,
}: {
  sessionId: string;
  disabled: boolean;
  onSelect: (modelConfigurationId: string) => void;
}) {
  const ui = useAppTranslation();
  const { models } = useChatModels(sessionId);
  if (models.length < 2) return null;
  return (
    <More.Root>
      <More.Trigger asChild>
        <IconButton
          size="sm"
          prominence="internal"
          aria-label={ui("Tạo lại bằng mô hình khác")}
          title={ui("Tạo lại bằng mô hình khác")}
          disabled={disabled}
        >
          <ChevronDown />
        </IconButton>
      </More.Trigger>
      <More.Content
        align="start"
        sideOffset={5}
        className="z-50 max-h-72 min-w-52 max-w-[calc(100vw-2rem)] overflow-y-auto rounded-xl border border-border-subtle bg-surface-overlay p-1.5 shadow-md"
      >
        <p className="px-3 pt-1 pb-1.5 text-xs text-content-muted">{ui("Tạo lại bằng")}</p>
        {models.map((model) => (
          <More.Item
            key={model.id}
            className="flex cursor-default items-center gap-2 rounded-lg px-3 py-2 text-sm outline-none data-[highlighted]:bg-surface-sunken"
            onSelect={() => onSelect(model.id)}
          >
            {model.icon}
            <span className="truncate">{model.name}</span>
          </More.Item>
        ))}
      </More.Content>
    </More.Root>
  );
}
