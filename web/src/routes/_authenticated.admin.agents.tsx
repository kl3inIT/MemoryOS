import { createFileRoute } from "@tanstack/react-router";
import { AdminAgentsPage } from "@/features/agents/admin-agents-page";

export const Route = createFileRoute("/_authenticated/admin/agents")({
  component: AdminAgentsPage,
});
