import { Check, Sparkles, TextSearch } from "lucide-react";
import { StatusBadge } from "@/components/ui/status-badge";
import type { MatchKind } from "@/features/preview/preview-highlight";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";

const TONE = { exact: "success", partial: "warning", none: "info" } as const;
const DOT = {
  exact: "bg-status-success-content",
  partial: "bg-status-warning-content",
  none: "bg-status-info-content",
} as const;

function useMatchKindLabel(kind: MatchKind) {
  const ui = useAppTranslation();
  return kind === "exact"
    ? ui("Khớp chính xác")
    : kind === "partial"
      ? ui("Khớp một phần")
      : ui("Liên quan về nghĩa");
}

/**
 * How a passage answers the question: it holds the whole question, some of its words, or only its meaning. A
 * reader can then tell a literal hit, which is marked, from a semantic one, which has nothing to mark.
 */
export function MatchKindBadge({ kind }: { kind: MatchKind }) {
  const label = useMatchKindLabel(kind);
  const Icon = kind === "exact" ? Check : kind === "partial" ? TextSearch : Sparkles;
  return (
    <StatusBadge tone={TONE[kind]} size="sm" data-match-kind={kind}>
      <Icon aria-hidden="true" />
      {label}
    </StatusBadge>
  );
}

/** The badge's tone as a dot, for a compact line that names the kind only to assistive technology. */
export function MatchKindDot({ kind, className }: { kind: MatchKind; className?: string }) {
  const label = useMatchKindLabel(kind);
  return (
    <span
      role="img"
      aria-label={label}
      title={label}
      data-match-kind={kind}
      className={cn("inline-block size-1.5 shrink-0 rounded-full", DOT[kind], className)}
    />
  );
}
