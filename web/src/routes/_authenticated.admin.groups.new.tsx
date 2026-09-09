import { createFileRoute } from "@tanstack/react-router";
import { CreateGroupPage } from "@/features/groups/create-group-page";
import { AccessDeniedScreen } from "@/features/identity/session-states";
import { useGlobalCapability } from "@/features/identity/application-session-context";

export const Route = createFileRoute("/_authenticated/admin/groups/new")({
  component: function CreateGroupRoute() {
    return useGlobalCapability("GROUPS_MANAGE") ? <CreateGroupPage /> : <AccessDeniedScreen />;
  },
});
