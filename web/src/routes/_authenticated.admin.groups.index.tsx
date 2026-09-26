import { createFileRoute } from "@tanstack/react-router";
import { mayWarmAdminPage } from "@/components/app-shell/admin-prefetch";
import { GroupsPage } from "@/features/groups/groups-page";
import { groupsSearchSchema } from "@/features/groups/groups-search";
import { listGroupsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";

export const Route = createFileRoute("/_authenticated/admin/groups/")({
  validateSearch: groupsSearchSchema,
  loaderDeps: ({ search }) => search,
  loader: ({ context: { queryClient }, deps }) => {
    if (!mayWarmAdminPage(queryClient, "groups")) return;
    void queryClient.prefetchQuery({ ...listGroupsOptions({ query: deps }), retry: false });
  },
  component: GroupsPage,
});
