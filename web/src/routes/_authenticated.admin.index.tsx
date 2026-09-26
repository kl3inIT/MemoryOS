import { createFileRoute } from "@tanstack/react-router";
import { SourcesPage } from "@/features/sources/sources-page";
import { listSourcesOptions } from "@/lib/hey-api/@tanstack/react-query.gen";

export const Route = createFileRoute("/_authenticated/admin/")({
  // Warmed, not awaited: the page shows its own loading state while the list arrives.
  loader: ({ context: { queryClient } }) => {
    void queryClient.prefetchQuery({ ...listSourcesOptions(), retry: false });
  },
  component: SourcesPage,
});
