import { createFileRoute, Outlet, useMatchRoute } from "@tanstack/react-router";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { AppShell } from "@/components/app-shell/app-shell";
import { AccessDeniedScreen } from "@/features/identity/session-states";
import { useAdminAccess } from "@/features/identity/application-session-context";
import { SourceUploadRecoveryProvider } from "@/features/sources/source-upload-recovery-provider";

export const Route = createFileRoute("/_authenticated/admin")({
  component: function AdministrationLayout() {
    const ui = useAppTranslation();
    const {
      canManageUsers,
      canReadGroups,
      canReadSources,
      canManageModels,
      canManageProviders,
      canManageMcp,
      canManageAgents,
    } = useAdminAccess();
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
    const providersSelected = Boolean(matchRoute({ to: "/admin/identity-providers" }));
    const modelsSelected = Boolean(matchRoute({ to: "/admin/models" }));
    const webSearchSelected = Boolean(matchRoute({ to: "/admin/web-search" }));
    const mcpSelected = Boolean(matchRoute({ to: "/admin/mcp" }));
    const agentsSelected = Boolean(matchRoute({ to: "/admin/agents" }));
    const page = usersSelected
      ? "users"
      : groupsSelected
        ? "groups"
        : providersSelected
          ? "providers"
          : modelsSelected
            ? "models"
            : webSearchSelected
              ? "web"
              : mcpSelected
                ? "mcp"
                : agentsSelected
                  ? "agents"
                  : "sources";
    const allowed =
      page === "users"
        ? canManageUsers
        : page === "groups"
          ? canReadGroups
          : page === "providers"
            ? canManageProviders
            : page === "models" || page === "web"
              ? canManageModels
              : page === "mcp"
                ? canManageMcp
                : page === "agents"
                  ? canManageAgents
                  : canReadSources;

    if (!allowed) {
      return <AccessDeniedScreen />;
    }

    return (
      <AppShell
        area="admin"
        adminPage={page}
        pageTitle={ui(
          page === "users"
            ? "Users"
            : page === "groups"
              ? "Groups"
              : page === "providers"
                ? "Sign-in providers"
                : page === "models"
                  ? "Models"
                  : page === "web"
                    ? "Tìm kiếm Web"
                    : page === "mcp"
                      ? "Máy chủ MCP"
                      : page === "agents"
                        ? "Quản lý trợ lý"
                        : "Sources",
        )}
        sourceSetupStep={sourceSetupStep}
      >
        <SourceUploadRecoveryProvider>
          <Outlet />
        </SourceUploadRecoveryProvider>
      </AppShell>
    );
  },
});
