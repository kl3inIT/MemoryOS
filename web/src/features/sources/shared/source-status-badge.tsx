import { useAppTranslation } from "@/i18n/use-app-translation";
import { StatusBadge } from "@/components/ui/status-badge";
import type { SourceSummary } from "@/lib/hey-api/types.gen";
import {
  sourceAccessPresentation,
  sourceStatusPresentation,
  unknownStatusPresentation,
} from "./source-status-presentation";
import { SourceHint } from "./source-hint";
import { Spinner } from "@/components/ui/spinner";

export function SourceStatusBadge({ status }: { status: string }) {
  const ui = useAppTranslation();
  const presentation = sourceStatusPresentation[status] ?? unknownStatusPresentation;
  const StatusIcon = presentation.icon;

  return (
    <StatusBadge tone={presentation.tone}>
      {status === "INDEXING" ? (
        <Spinner aria-hidden="true" className="size-3" />
      ) : (
        <StatusIcon aria-hidden="true" />
      )}
      {ui(presentation.label)}
    </StatusBadge>
  );
}

/** Who may read the Source, as plain text with its icon: access is neither a state nor a control. */
export function SourceAccess({ access }: { access: SourceSummary["access"] }) {
  const ui = useAppTranslation();
  const presentation = sourceAccessPresentation[access];
  const AccessIcon = presentation.icon;

  return (
    <SourceHint hint={ui(presentation.title)}>
      <span className="inline-flex items-center gap-1 font-secondary-body text-content-secondary">
        <AccessIcon className="size-3.5 shrink-0" aria-hidden="true" />
        {ui(presentation.label)}
      </span>
    </SourceHint>
  );
}
