import { Brain } from "lucide-react";
import { cn } from "@/lib/utils";

const strength: Record<string, { bars: number; tone: string }> = {
  LOW: { bars: 1, tone: "bg-reasoning-low-surface text-reasoning-low-content" },
  MEDIUM: { bars: 2, tone: "bg-reasoning-medium-surface text-reasoning-medium-content" },
  HIGH: { bars: 3, tone: "bg-reasoning-high-surface text-reasoning-high-content" },
};

/**
 * The level beside a model in its picker: the selection accent, stronger per level, with as many bars as the level
 * is high. Off and a model that does not reason show nothing, as ChatGPT shows its Think chip only while it is on.
 */
export function ReasoningLevel({ level, name }: { level?: string; name?: string }) {
  const shown = level ? strength[level] : undefined;
  if (!shown || !name) return null;
  return (
    <span
      data-slot="reasoning-level"
      className={cn(
        "inline-flex h-5 shrink-0 items-center gap-1 rounded-full ps-1.5 pe-2 font-secondary-action",
        shown.tone,
      )}
    >
      <Brain className="size-3" aria-hidden="true" />
      {name}
      <span className="flex items-end gap-px" aria-hidden="true">
        {(["h-1.5", "h-2", "h-2.5"] as const).map((height, index) => (
          <span
            key={height}
            className={cn(
              "w-0.75 rounded-full",
              height,
              index < shown.bars ? "bg-current" : "bg-current/30",
            )}
          />
        ))}
      </span>
    </span>
  );
}
