import { createFileRoute, stripSearchParams } from "@tanstack/react-router";
import { AuditLogPage } from "@/features/audit/audit-log-page";
import { auditLogSearchSchema, DEFAULT_AUDIT_PERIOD } from "@/features/audit/audit-log-search";

export const Route = createFileRoute("/_authenticated/admin/audit")({
  validateSearch: auditLogSearchSchema,
  search: { middlewares: [stripSearchParams({ period: DEFAULT_AUDIT_PERIOD })] },
  component: AuditLogPage,
});
