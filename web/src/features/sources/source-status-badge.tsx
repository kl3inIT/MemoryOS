import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  Check,
  Clock3,
  LoaderCircle,
  Lock,
  Trash2,
  TriangleAlert,
  UsersRound,
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

export function SourceAccessBadge({ access }: { access: SourceSummary["access"] }) {
  const ui = useAppTranslation();

  const workspaceAccess = access === "PUBLIC";
  const AccessIcon = workspaceAccess ? UsersRound : Lock;

  return (
    <StatusBadge
      tone="neutral"
      className="gap-1.5"
      title={
        workspaceAccess
          ? ui("Available to workspace members, not the public Internet.")
          : ui("Restricted source access.")
      }
    >
      <AccessIcon className="size-3 shrink-0" aria-hidden="true" />
      {workspaceAccess ? ui("Workspace members") : ui("Restricted")}
    </StatusBadge>
  );
}
