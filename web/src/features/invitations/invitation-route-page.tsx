import { getRouteApi } from "@tanstack/react-router";
import { InvitationLandingPage } from "@/features/invitations/invitation-landing-page";

const route = getRouteApi("/invitation");

export function InvitationRoutePage() {
  const { reason } = route.useSearch();
  return <InvitationLandingPage reason={reason} />;
}
