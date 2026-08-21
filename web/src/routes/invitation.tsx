import { createFileRoute } from "@tanstack/react-router";
import { InvitationRoutePage } from "@/features/invitations/invitation-route-page";

type InvitationSearch = {
  reason?: string;
};

export const Route = createFileRoute("/invitation")({
  validateSearch: (search): InvitationSearch => ({
    reason: typeof search.reason === "string" ? search.reason : undefined,
  }),
  component: InvitationRoutePage,
});
