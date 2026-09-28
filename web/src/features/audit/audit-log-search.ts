import { z } from "zod";
import type { AuditEvent } from "@/lib/hey-api/types.gen";
import { classLabels, outcomeLabels, periodDays, type AuditPeriod } from "./audit-actions";

const known =
  <T extends string>(labels: Record<T, unknown>) =>
  (value: unknown): value is T =>
    typeof value === "string" && Object.hasOwn(labels, value);

export const DEFAULT_AUDIT_PERIOD: AuditPeriod = "7d";

/** The audit log's filters in its address; the default period and empty filters are left out. */
export const auditLogSearchSchema = z.object({
  period: z
    .custom<AuditPeriod>(known(periodDays))
    .default(DEFAULT_AUDIT_PERIOD)
    .catch(DEFAULT_AUDIT_PERIOD),
  q: z.string().trim().min(1).max(200).optional().catch(undefined),
  eventClass: z.custom<AuditEvent["eventClass"]>(known(classLabels)).optional().catch(undefined),
  outcome: z.custom<AuditEvent["outcome"]>(known(outcomeLabels)).optional().catch(undefined),
});

export type AuditLogSearch = z.output<typeof auditLogSearchSchema>;
