import { createFileRoute } from "@tanstack/react-router";
import { invitationListSearchSchema } from "@/features/invitations/invitation-list-search";
import { OrganizationInvitationsPage } from "@/features/invitations/organization-invitations-page";

export const Route = createFileRoute("/_authenticated/admin/invitations")({
  validateSearch: invitationListSearchSchema,
  component: OrganizationInvitationsPage,
});
