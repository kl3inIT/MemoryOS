import { Check, Sparkles, TextSearch } from "lucide-react";
import { StatusBadge } from "@/components/ui/status-badge";
import type { MatchKind } from "@/features/preview/preview-highlight";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";

const TONE = { exact: "success", partial: "warning", none: "info" } as const;
const ICON = { exact: Check, partial: TextSearch, none: Sparkles } as const;
const INK = {
  exact: "text-status-success-strong",
  partial: "text-status-warning-strong",
  none: "text-status-info-strong",
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
  const Icon = ICON[kind];
  return (
    <StatusBadge tone={TONE[kind]} size="sm" data-match-kind={kind}>
      <Icon aria-hidden="true" />
      {label}
    </StatusBadge>
  );
}

/**
 * The badge's icon alone, for a compact line: its shape tells the kinds apart without the colour, and its name
 * is the badge's label.
 */
export function MatchKindIcon({ kind, className }: { kind: MatchKind; className?: string }) {
  const label = useMatchKindLabel(kind);
  const Icon = ICON[kind];
  return (
    <Icon
      role="img"
      aria-label={label}
      data-match-kind={kind}
      className={cn("size-3.5 shrink-0", INK[kind], className)}
    >
      <title>{label}</title>
    </Icon>
  );
}
