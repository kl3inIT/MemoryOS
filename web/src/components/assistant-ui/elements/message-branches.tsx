import { useAppTranslation } from "@/i18n/use-app-translation";
// Adapted from assistant-ui MessageBranches (MIT), revision 2c22f5d7.
// Server branch IDs/count are supplied by the caller; the transcript is rendered once by Thread.
import { ChevronLeft, ChevronRight } from "lucide-react";
import { IconButton } from "@/components/ui/icon-button";

export function MessageBranches({
  count,
  index,
  onIndexChange,
  disabled,
  label,
}: {
  count: number;
  index: number;
  onIndexChange: (index: number) => void;
  disabled: boolean;
  label: string;
}) {
  const ui = useAppTranslation();

  if (count <= 1) return null;
  return (
    <div
      data-slot="message-branches"
      role="group"
      aria-label={label}
      className="flex items-center gap-1 text-xs tabular-nums"
    >
      <IconButton
        size="sm"
        prominence="internal"
        aria-label={ui("Phiên bản trước")}
        disabled={disabled || index <= 0}
        onClick={() => onIndexChange(index - 1)}
      >
        <ChevronLeft />
      </IconButton>
      <span>
        {index + 1} / {count}
      </span>
      <IconButton
        size="sm"
        prominence="internal"
        aria-label={ui("Phiên bản sau")}
        disabled={disabled || index >= count - 1}
        onClick={() => onIndexChange(index + 1)}
      >
        <ChevronRight />
      </IconButton>
    </div>
  );
}
