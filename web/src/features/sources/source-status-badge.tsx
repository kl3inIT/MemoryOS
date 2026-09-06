import { Check, Clock3, LoaderCircle, Trash2, TriangleAlert, type LucideIcon } from "lucide-react";
import { StatusBadge, type StatusTone } from "@/components/ui/status-badge";

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
      {presentation.label}
    </StatusBadge>
  );
}
