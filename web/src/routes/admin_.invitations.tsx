import { createFileRoute } from "@tanstack/react-router";
import { ApplicationSessionBoundary } from "@/features/identity/application-session-boundary";
import { invitationListSearchSchema } from "@/features/invitations/invitation-list-search";

export const Route = createFileRoute("/admin_/invitations")({
  validateSearch: invitationListSearchSchema,
  component: () => <ApplicationSessionBoundary page="invitations" />,
});
