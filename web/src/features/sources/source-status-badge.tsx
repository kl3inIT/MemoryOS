import { useAppTranslation } from "@/i18n/use-app-translation";
import { StatusBadge } from "@/components/ui/status-badge";
import type { SourceSummary } from "@/lib/hey-api/types.gen";
import {
  defaultStatusPresentation,
  sourceAccessPresentation,
  sourceStatusPresentation,
  statusPill,
} from "./source-status-presentation";
import { SourceHint } from "./source-hint";

export function SourceStatusBadge({ status }: { status?: string }) {
  const ui = useAppTranslation();
  const presentation = status
    ? (sourceStatusPresentation[status] ?? defaultStatusPresentation)
    : defaultStatusPresentation;
  const StatusIcon = presentation.icon;

  return (
    <StatusBadge tone={presentation.tone} className={statusPill(presentation.tone)}>
      <StatusIcon
        className={`size-3 ${status === "INDEXING" ? "animate-spin motion-reduce:animate-none" : ""}`}
        aria-hidden="true"
      />
      {ui(presentation.label)}
    </StatusBadge>
  );
}

export function SourceAccessBadge({ access }: { access: SourceSummary["access"] }) {
  const ui = useAppTranslation();
  const presentation = sourceAccessPresentation[access];
  const AccessIcon = presentation.icon;

  return (
    <SourceHint hint={ui(presentation.title)}>
      <span className="inline-flex">
        <StatusBadge tone={presentation.tone} className={statusPill(presentation.tone)}>
          <AccessIcon className="size-3 shrink-0" aria-hidden="true" />
          {ui(presentation.label)}
        </StatusBadge>
      </span>
    </SourceHint>
  );
}
