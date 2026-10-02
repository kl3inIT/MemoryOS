import { useAppTranslation } from "@/i18n/use-app-translation";
// Adapted from assistant-ui MessageActions (MIT), revision 2c22f5d7.
// Native Copy and the application's Sources action remain in the surrounding ActionBar.
import { RotateCcw, ThumbsDown, ThumbsUp } from "lucide-react";
import { IconButton } from "@/components/ui/icon-button";

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
          <IconButton
            size="sm"
            prominence="internal"
            aria-label={ui("Hữu ích")}
            tooltip
            aria-pressed={reaction === "up"}
            disabled={disabled}
            onClick={() => onReactionChange(reaction === "up" ? null : "up")}
          >
            <ThumbsUp />
          </IconButton>
          <IconButton
            size="sm"
            prominence="internal"
            aria-label={ui("Không hữu ích")}
            tooltip
            aria-pressed={reaction === "down"}
            disabled={disabled}
            onClick={() => onReactionChange(reaction === "down" ? null : "down")}
          >
            <ThumbsDown />
          </IconButton>
        </>
      )}
      <IconButton
        size="sm"
        prominence="internal"
        aria-label={ui("Tạo lại câu trả lời")}
        tooltip
        disabled={disabled}
        onClick={onRegenerate}
      >
        <RotateCcw />
      </IconButton>
    </div>
  );
}
