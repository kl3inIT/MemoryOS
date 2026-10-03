import {
  Check,
  CircleHelp,
  Clock3,
  LoaderCircle,
  Lock,
  Pause,
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

type SourceAccessPresentation = {
  label: string;
  title: string;
  icon: LucideIcon;
};

/** A status this client does not know yet, rather than one it would pass off as another. */
export const unknownStatusPresentation: SourceStatusPresentation = {
  label: "Unknown",
  tone: "neutral",
  icon: CircleHelp,
};

/** Tones follow Onyx's connector status badges: indexing blue, active green, failed red. */
export const sourceStatusPresentation: Record<string, SourceStatusPresentation> = {
  NOT_STARTED: { label: "Scheduled", tone: "neutral", icon: Clock3 },
  INDEXING: { label: "Indexing", tone: "info", icon: LoaderCircle },
  ACTIVE: { label: "Active", tone: "success", icon: Check },
  FAILED: { label: "Failed", tone: "danger", icon: TriangleAlert },
  PAUSED: { label: "Paused", tone: "warning", icon: Pause },
  PAUSING: { label: "Pausing", tone: "warning", icon: LoaderCircle },
  DELETING: { label: "Deleting", tone: "neutral", icon: Trash2 },
};
/** Access is who may read, not a state, so it takes no status tone: its icon and label tell the modes apart. */
export const sourceAccessPresentation: Record<SourceSummary["access"], SourceAccessPresentation> = {
  PUBLIC: {
    label: "All members",
    title: "Everyone can read it.",
    icon: Users,
  },
  PRIVATE: {
    label: "Specific groups",
    title: "Only members of the chosen groups can read it.",
    icon: Lock,
  },
  SYNC: {
    label: "Sync permissions from source",
    title: "Permissions come from the source.",
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
