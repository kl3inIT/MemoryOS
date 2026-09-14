import { createFileRoute } from "@tanstack/react-router";
import { IdentityProvidersPage } from "@/features/identity-providers/identity-providers-page";

export const Route = createFileRoute("/_authenticated/admin/identity-providers")({
  component: IdentityProvidersPage,
});
