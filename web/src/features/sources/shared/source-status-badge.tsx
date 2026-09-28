import { useAppTranslation } from "@/i18n/use-app-translation";
import { StatusBadge } from "@/components/ui/status-badge";
import type { SourceSummary } from "@/lib/hey-api/types.gen";
import {
  defaultStatusPresentation,
  sourceAccessPresentation,
  sourceStatusPresentation,
} from "./source-status-presentation";
import { SourceHint } from "./source-hint";
import { Spinner } from "@/components/ui/spinner";

export function SourceStatusBadge({ status }: { status?: string }) {
  const ui = useAppTranslation();
  const presentation = status
    ? (sourceStatusPresentation[status] ?? defaultStatusPresentation)
    : defaultStatusPresentation;
  const StatusIcon = presentation.icon;

  return (
    <StatusBadge tone={presentation.tone} variant="pill">
      {status === "INDEXING" ? (
        <Spinner aria-hidden="true" className="size-3" />
      ) : (
        <StatusIcon aria-hidden="true" />
      )}
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
        <StatusBadge tone={presentation.tone} variant="pill">
          <AccessIcon aria-hidden="true" />
          {ui(presentation.label)}
        </StatusBadge>
      </span>
    </SourceHint>
  );
}
