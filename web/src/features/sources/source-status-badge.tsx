import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  Check,
  Clock3,
  LoaderCircle,
  Lock,
  RefreshCw,
  Trash2,
  TriangleAlert,
  Users,
  type LucideIcon,
} from "lucide-react";
import { StatusBadge, type StatusTone } from "@/components/ui/status-badge";
import type { SourceSummary } from "@/lib/hey-api/types.gen";

type SourceStatusPresentation = {
  label: string;
  tone: StatusTone;
  icon: LucideIcon;
};

const defaultStatusPresentation: SourceStatusPresentation = {
  label: "Scheduled",
  tone: "info",
  icon: Clock3,
};

const sourceStatusPresentation: Record<string, SourceStatusPresentation> = {
  NOT_STARTED: defaultStatusPresentation,
  INDEXING: { label: "Indexing", tone: "warning", icon: LoaderCircle },
  ACTIVE: { label: "Active", tone: "success", icon: Check },
  FAILED: { label: "Failed", tone: "danger", icon: TriangleAlert },
  DELETING: { label: "Deleting", tone: "neutral", icon: Trash2 },
};

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

const sourceAccessPresentation: Record<
  SourceSummary["access"],
  { label: string; title: string; icon: LucideIcon }
> = {
  PUBLIC: {
    label: "Workspace members",
    title: "Available to workspace members, not the public Internet.",
    icon: Users,
  },
  PRIVATE: {
    label: "Private",
    title: "Only members of the associated groups can read this Source.",
    icon: Lock,
  },
  SYNC: {
    label: "Auto Sync",
    title: "Readers need access to each file in Google Drive.",
    icon: RefreshCw,
  },
};

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
