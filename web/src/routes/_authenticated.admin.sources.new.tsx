import { createFileRoute, Outlet } from "@tanstack/react-router";
import { useCapabilityAuthority } from "@/features/identity/application-session-context";
import { AccessDeniedScreen } from "@/features/identity/session-states";

export const Route = createFileRoute("/_authenticated/admin/sources/new")({
  component: function CreateSourceLayout() {
    return useCapabilityAuthority("SOURCES_MANAGE") !== "none" ? (
      <Outlet />
    ) : (
      <AccessDeniedScreen />
    );
  },
});
