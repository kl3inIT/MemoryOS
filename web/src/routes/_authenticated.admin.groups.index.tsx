import { createFileRoute } from "@tanstack/react-router";
import { GroupsPage } from "@/features/groups/groups-page";
import { groupsSearchSchema } from "@/features/groups/groups-search";

export const Route = createFileRoute("/_authenticated/admin/groups/")({
  validateSearch: groupsSearchSchema,
  component: GroupsPage,
});
