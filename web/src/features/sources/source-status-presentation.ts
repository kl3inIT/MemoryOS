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
import type { StatusTone } from "@/components/ui/status-badge";
import type { SourceSummary } from "@/lib/hey-api/types.gen";

type SourceStatusPresentation = {
  label: string;
  tone: StatusTone;
  icon: LucideIcon;
};

/** Tones follow Onyx's connector status badges: indexing blue, active green, failed red. */
export const defaultStatusPresentation: SourceStatusPresentation = {
  label: "Scheduled",
  tone: "neutral",
  icon: Clock3,
};

export const sourceStatusPresentation: Record<string, SourceStatusPresentation> = {
  NOT_STARTED: defaultStatusPresentation,
  INDEXING: { label: "Indexing", tone: "info", icon: LoaderCircle },
  ACTIVE: { label: "Active", tone: "success", icon: Check },
  FAILED: { label: "Failed", tone: "danger", icon: TriangleAlert },
  DELETING: { label: "Deleting", tone: "neutral", icon: Trash2 },
};

/** Onyx colours access too: workspace-wide green, group-restricted amber, Drive-synced blue. */
export const sourceAccessPresentation: Record<
  SourceSummary["access"],
  SourceStatusPresentation & { title: string }
> = {
  PUBLIC: {
    label: "Workspace members",
    title: "Available to workspace members, not the public Internet.",
    tone: "success",
    icon: Users,
  },
  PRIVATE: {
    label: "Private",
    title: "Only members of the associated groups can read this Source.",
    tone: "warning",
    icon: Lock,
  },
  SYNC: {
    label: "Auto Sync",
    title: "Readers need access to each file in Google Drive.",
    tone: "info",
    icon: RefreshCw,
  },
};

/** Filter choices in display order, labelled like the badges. */
export const sourceStatusOptions = Object.entries(sourceStatusPresentation).map(
  ([value, { label }]) => ({ value, label }),
);

export const sourceAccessOptions = Object.entries(sourceAccessPresentation).map(
  ([value, { label }]) => ({ value, label }),
);
