import { createFileRoute } from "@tanstack/react-router";
import { invitationListSearchSchema } from "@/features/invitations/invitation-list-search";
import { TenantInvitationsPage } from "@/features/invitations/tenant-invitations-page";

export const Route = createFileRoute("/_authenticated/admin/invitations")({
  validateSearch: invitationListSearchSchema,
  component: TenantInvitationsPage,
});
