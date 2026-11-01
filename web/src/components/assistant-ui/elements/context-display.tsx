// Adapted from assistant-ui (MIT), elements-context-display registry with its context-usage and tokens utilities,
// 2026-10-15: the Ring preset only, fed by a usage prop, in the MemoryOS tokens; copy comes from the caller.
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";

const UNITS = [
  { size: 1_000, suffix: "k" },
  { size: 1_000_000, suffix: "M" },
] as const;

/** Token counts as the elements print them: `950`, `48.2k`, `1.2M`. */
function formatTokenCount(tokens: number): string {
  if (!(tokens >= 1_000)) return `${tokens}`;
  let label = "";
  for (const { size, suffix } of UNITS) {
    const value = Math.round((tokens / size) * 10) / 10;
    label = `${value}${suffix}`;
    if (value < 1_000) break;
  }
  return label;
}

/** The element's thresholds: amber from 65%, red above 85%. */
function severity(percent: number): "normal" | "warning" | "critical" {
  if (percent > 85) return "critical";
  if (percent >= 65) return "warning";
  return "normal";
}

const STROKE = {
  normal: "stroke-content-secondary",
  warning: "stroke-status-warning-strong",
  critical: "stroke-status-danger-content",
} as const;

const RING_SIZE = 18;
const RING_STROKE = 2.5;
const RING_RADIUS = (RING_SIZE - RING_STROKE) / 2;
const RING_CIRCUMFERENCE = 2 * Math.PI * RING_RADIUS;

/**
 * How full the model's context is: a ring with the percentage, and the tokens against the limit on hover or focus.
 * Renders nothing until usage is known, as the element does.
 */
export function ContextDisplay({
  tokens,
  limit,
  label,
  summary,
  className,
}: {
  tokens: number | undefined;
  limit: number | undefined;
  /** The trigger's accessible name. */
  label: string;
  /** The tooltip's first line for a rounded percentage. */
  summary: (percent: number) => string;
  className?: string;
}) {
  if (!tokens || !limit) return null;
  const percent = Math.min((tokens / limit) * 100, 100);
  const level = severity(percent);
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <button
          type="button"
          data-slot="context-display-trigger"
          aria-label={label}
          className={cn(
            "inline-flex items-center gap-1.5 rounded-md px-1.5 py-1 font-secondary-body text-content-muted transition-colors hover:text-content-primary",
            className,
          )}
        >
          <svg
            aria-hidden="true"
            width={RING_SIZE}
            height={RING_SIZE}
            viewBox={`0 0 ${RING_SIZE} ${RING_SIZE}`}
            className="-rotate-90"
          >
            <circle
              cx={RING_SIZE / 2}
              cy={RING_SIZE / 2}
              r={RING_RADIUS}
              fill="none"
              strokeWidth={RING_STROKE}
              className="stroke-border-default"
            />
            <circle
              cx={RING_SIZE / 2}
              cy={RING_SIZE / 2}
              r={RING_RADIUS}
              fill="none"
              strokeWidth={RING_STROKE}
              strokeLinecap="round"
              strokeDasharray={RING_CIRCUMFERENCE}
              strokeDashoffset={RING_CIRCUMFERENCE - (percent / 100) * RING_CIRCUMFERENCE}
              className={cn("transition-all duration-300", STROKE[level])}
            />
          </svg>
          <span className="tabular-nums">{Math.round(percent)}%</span>
        </button>
      </TooltipTrigger>
      <TooltipContent side="top" data-slot="context-display-popover">
        <div className="flex items-baseline justify-between gap-6 whitespace-nowrap">
          <span>{summary(Math.round(percent))}</span>
          <span className="tabular-nums">
            {formatTokenCount(Math.min(tokens, limit))} / {formatTokenCount(limit)}
          </span>
        </div>
      </TooltipContent>
    </Tooltip>
  );
}
