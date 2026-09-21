import { createFileRoute } from "@tanstack/react-router";
import { AuditLogPage } from "@/features/audit/audit-log-page";

export const Route = createFileRoute("/_authenticated/admin/audit")({
  component: AuditLogPage,
});
