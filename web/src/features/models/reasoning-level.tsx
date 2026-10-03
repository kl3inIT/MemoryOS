import { Brain } from "lucide-react";
import { cn } from "@/lib/utils";

const bars: Record<string, number> = { LOW: 1, MEDIUM: 2, HIGH: 3 };

/**
 * The level beside a model in its picker: a light tint of the selection accent, the same for every level, with as many
 * bars as the level is high. Off and a model that does not reason show nothing, as ChatGPT shows its Think chip only
 * while it is on.
 */
export function ReasoningLevel({ level, name }: { level?: string; name?: string }) {
  const filled = level ? bars[level] : undefined;
  if (!filled || !name) return null;
  return (
    <span
      data-slot="reasoning-level"
      className="inline-flex h-5 shrink-0 items-center gap-1 rounded-full bg-reasoning-surface ps-1.5 pe-2 font-secondary-action text-reasoning-content"
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
              index < filled ? "bg-current" : "bg-current/30",
            )}
          />
        ))}
      </span>
    </span>
  );
}
