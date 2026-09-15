import { useAppTranslation } from "@/i18n/use-app-translation";
import { StatusBadge } from "@/components/ui/status-badge";
import type { SourceSummary } from "@/lib/hey-api/types.gen";
import {
  defaultStatusPresentation,
  sourceAccessPresentation,
  sourceStatusPresentation,
} from "./source-status-presentation";

export function SourceStatusBadge({ status }: { status?: string }) {
  const ui = useAppTranslation();
  const presentation = status
    ? (sourceStatusPresentation[status] ?? defaultStatusPresentation)
    : defaultStatusPresentation;
  const StatusIcon = presentation.icon;

  return (
    <StatusBadge
      tone={presentation.tone}
      className="items-center gap-1.5 tracking-normal normal-case"
    >
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
    <StatusBadge tone="neutral" className="gap-1.5" title={ui(presentation.title)}>
      <AccessIcon className="size-3 shrink-0" aria-hidden="true" />
      {ui(presentation.label)}
    </StatusBadge>
  );
}
