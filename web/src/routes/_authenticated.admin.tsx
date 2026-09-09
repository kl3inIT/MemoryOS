import { createFileRoute, Outlet, useMatchRoute } from "@tanstack/react-router";
import { AppShell } from "@/components/app-shell/app-shell";
import { AccessDeniedScreen } from "@/features/identity/session-states";
import {
  useCapabilityAuthority,
  useGlobalCapability,
} from "@/features/identity/application-session-context";
import { SourceUploadRecoveryProvider } from "@/features/sources/source-upload-recovery-provider";

export const Route = createFileRoute("/_authenticated/admin")({
  component: function AdministrationLayout() {
    const canManageUsers = useGlobalCapability("USERS_MANAGE");
    const canReadGroups = useCapabilityAuthority("GROUPS_READ") !== "none";
    const canReadSources = useCapabilityAuthority("SOURCES_READ") !== "none";
    const matchRoute = useMatchRoute();
    const sourceSetupStep = matchRoute({
      to: "/admin/sources/new/google-drive",
      search: { step: "connector" },
      includeSearch: true,
    })
      ? 1
      : matchRoute({ to: "/admin/sources/new/google-drive" })
        ? 0
        : undefined;
    const usersSelected = Boolean(matchRoute({ to: "/admin/users" }));
    const groupsSelected = Boolean(matchRoute({ to: "/admin/groups", fuzzy: true }));
    const page = usersSelected ? "users" : groupsSelected ? "groups" : "sources";
    const allowed =
      page === "users" ? canManageUsers : page === "groups" ? canReadGroups : canReadSources;

    if (!allowed) {
      return <AccessDeniedScreen />;
    }

    return (
      <AppShell
        area="admin"
        adminPage={page}
        pageTitle={page === "users" ? "Users" : page === "groups" ? "Groups" : "Sources"}
        sourceSetupStep={sourceSetupStep}
      >
        <SourceUploadRecoveryProvider>
          <Outlet />
        </SourceUploadRecoveryProvider>
      </AppShell>
    );
  },
});
