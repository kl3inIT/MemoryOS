import { createFileRoute } from "@tanstack/react-router";
import { IdentityProvidersPage } from "@/features/identity-providers/identity-providers-page";
import { listIdentityProvidersOptions } from "@/lib/hey-api/@tanstack/react-query.gen";

export const Route = createFileRoute("/_authenticated/admin/identity-providers")({
  loader: ({ context: { queryClient } }) => {
    void queryClient.prefetchQuery({ ...listIdentityProvidersOptions(), retry: false });
  },
  component: IdentityProvidersPage,
});
