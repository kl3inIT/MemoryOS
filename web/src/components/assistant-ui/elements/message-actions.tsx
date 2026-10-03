import { useAppTranslation } from "@/i18n/use-app-translation";
import { TooltipIconButton } from "@/components/composites/tooltip-icon-button";
// Adapted from assistant-ui MessageActions (MIT), revision 2c22f5d7.
// Native Copy and the application's Sources action remain in the surrounding ActionBar.
import { RotateCcw, ThumbsDown, ThumbsUp } from "lucide-react";

export type Reaction = "up" | "down" | null;
export function MessageActions({
  reaction,
  onReactionChange,
  onRegenerate,
  disabled,
  feedbackAvailable,
}: {
  reaction: Reaction;
  onReactionChange: (reaction: Reaction) => void;
  onRegenerate: () => void;
  disabled: boolean;
  feedbackAvailable: boolean;
}) {
  const ui = useAppTranslation();

  return (
    <div data-slot="message-actions" className="flex items-center gap-1">
      {feedbackAvailable && (
        <>
          <TooltipIconButton
            size="sm"
            prominence="internal"
            aria-label={ui("Hữu ích")}
            aria-pressed={reaction === "up"}
            disabled={disabled}
            onClick={() => onReactionChange(reaction === "up" ? null : "up")}
          >
            <ThumbsUp />
          </TooltipIconButton>
          <TooltipIconButton
            size="sm"
            prominence="internal"
            aria-label={ui("Không hữu ích")}
            aria-pressed={reaction === "down"}
            disabled={disabled}
            onClick={() => onReactionChange(reaction === "down" ? null : "down")}
          >
            <ThumbsDown />
          </TooltipIconButton>
        </>
      )}
      <TooltipIconButton
        size="sm"
        prominence="internal"
        aria-label={ui("Tạo lại câu trả lời")}
        disabled={disabled}
        onClick={onRegenerate}
      >
        <RotateCcw />
      </TooltipIconButton>
    </div>
  );
}
