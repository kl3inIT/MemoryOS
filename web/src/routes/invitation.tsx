import { createFileRoute } from "@tanstack/react-router";
import { z } from "zod";
import { InvitationRoutePage } from "@/features/invitations/invitation-route-page";

export const Route = createFileRoute("/invitation")({
  validateSearch: z.object({ reason: z.string().optional().catch(undefined) }),
  component: InvitationRoutePage,
});
