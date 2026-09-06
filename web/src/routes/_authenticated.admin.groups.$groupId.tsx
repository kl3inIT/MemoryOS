import { createFileRoute } from "@tanstack/react-router";
import { GroupDetailPage } from "@/features/groups/group-detail-page";

export const Route = createFileRoute("/_authenticated/admin/groups/$groupId")({
  component: GroupDetailPage,
});
