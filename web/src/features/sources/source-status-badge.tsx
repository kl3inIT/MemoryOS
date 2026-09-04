import { StatusBadge, type StatusTone } from "@/components/ui/status-badge";

const sourceStatusTones: Partial<Record<string, StatusTone>> = {
  ACTIVE: "success",
  FAILED: "danger",
};

export function SourceStatusBadge({ status }: { status?: string }) {
  return (
    <StatusBadge tone={(status && sourceStatusTones[status]) || "info"}>
      {status ?? "NOT_STARTED"}
    </StatusBadge>
  );
}
