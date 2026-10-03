import { useAppTranslation } from "@/i18n/use-app-translation";
import { TooltipIconButton } from "@/components/composites/tooltip-icon-button";
import { ActionBarMorePrimitive as More } from "@assistant-ui/react";
import { GitBranch, MoreHorizontal } from "lucide-react";
import { useChatModels } from "@/features/chat/chat-models";
import { useChatBranch } from "./use-chat-branch";

const itemClass =
  "flex cursor-default items-center gap-2 rounded-lg px-3 py-2 text-sm outline-none data-[highlighted]:bg-surface-sunken [&_svg]:size-4 [&_svg]:shrink-0";

/**
 * The answer's less frequent actions behind one "…": regenerating with another model from the authorized catalog
 * through the saved server command, and branching into a new chat. Keeping them here leaves the bar to copy,
 * Sources, feedback and regenerate.
 */
export function ChatAnswerMenu({
  sessionId,
  messageId,
  originTitle,
  disabled,
  onRegenerateWith,
}: {
  sessionId: string;
  messageId: string;
  originTitle?: string;
  disabled: boolean;
  onRegenerateWith: (modelConfigurationId: string) => void;
}) {
  const ui = useAppTranslation();
  const { models } = useChatModels(sessionId);
  const branch = useChatBranch({ sessionId, messageId, originTitle });
  return (
    <>
      <More.Root>
        <More.Trigger asChild>
          <TooltipIconButton
            size="sm"
            prominence="internal"
            aria-label={ui("Thao tác khác")}
            pending={branch.pending}
            disabled={disabled || branch.pending}
          >
            <MoreHorizontal />
          </TooltipIconButton>
        </More.Trigger>
        <More.Content
          align="start"
          sideOffset={5}
          className="z-50 max-h-72 max-w-[calc(100vw-2rem)] min-w-52 overflow-y-auto rounded-xl border border-border-subtle bg-surface-overlay p-1.5 shadow-md"
        >
          {models.length > 1 && (
            <>
              <p className="px-3 pt-1 pb-1.5 text-xs text-content-muted">{ui("Tạo lại bằng")}</p>
              {models.map((model) => (
                <More.Item
                  key={model.id}
                  className={itemClass}
                  onSelect={() => onRegenerateWith(model.id)}
                >
                  {model.icon}
                  <span className="truncate">{model.name}</span>
                </More.Item>
              ))}
              <More.Separator className="-mx-1.5 my-1.5 h-px bg-border-subtle" />
            </>
          )}
          <More.Item className={itemClass} onSelect={branch.start}>
            <GitBranch aria-hidden="true" />
            {ui("Tách sang hội thoại mới")}
          </More.Item>
        </More.Content>
      </More.Root>
      {branch.error && (
        <p role="alert" className="text-xs text-status-danger-content">
          {branch.error}
        </p>
      )}
    </>
  );
}
