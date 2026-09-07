import { createFileRoute, Outlet } from "@tanstack/react-router";
import { useGlobalCapability } from "@/features/identity/application-session-context";
import { AccessDeniedScreen } from "@/features/identity/session-states";

export const Route = createFileRoute("/_authenticated/admin/sources/new")({
  component: function CreateSourceLayout() {
    return useGlobalCapability("SOURCES_MANAGE") ? <Outlet /> : <AccessDeniedScreen />;
  },
});
