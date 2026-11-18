import { createFileRoute } from "@tanstack/react-router";
import { mayWarmAdminPage } from "@/components/app-shell/admin-prefetch";
import { SourcesPage } from "@/features/sources/sources-page";
import { sourcePageQuery, sourcesSearchSchema } from "@/features/sources/sources-search";
import { listSourcePageOptions } from "@/lib/hey-api/@tanstack/react-query.gen";

export const Route = createFileRoute("/_authenticated/admin/")({
  validateSearch: sourcesSearchSchema,
  loaderDeps: ({ search }) => search,
  // Warmed, not awaited: the page shows its own loading state while the list arrives.
  loader: ({ context: { queryClient }, deps }) => {
    if (!mayWarmAdminPage(queryClient, "sources")) return;
    void queryClient.prefetchQuery({
      ...listSourcePageOptions({ query: sourcePageQuery(deps) }),
      retry: false,
    });
  },
  component: SourcesPage,
});
