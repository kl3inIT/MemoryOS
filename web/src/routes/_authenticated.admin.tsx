import { createFileRoute, Outlet, useMatchRoute } from "@tanstack/react-router";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { AppShell } from "@/components/app-shell/app-shell";
import { AccessDeniedScreen } from "@/features/identity/session-states";
import { useAdminAccess } from "@/features/identity/application-session-context";
import { SourceUploadRecoveryProvider } from "@/features/sources/source-upload-recovery-provider";

export const Route = createFileRoute("/_authenticated/admin")({
  component: function AdministrationLayout() {
    const ui = useAppTranslation();
    const { canManageUsers, canReadGroups, canReadSources, canManageModels, canManageProviders } =
      useAdminAccess();
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
    const modelsSelected = Boolean(matchRoute({ to: "/admin/models" }));
    const providersSelected = Boolean(matchRoute({ to: "/admin/identity-providers" }));
    const page = usersSelected
      ? "users"
      : groupsSelected
        ? "groups"
        : modelsSelected
          ? "models"
          : providersSelected
            ? "providers"
            : "sources";
    const allowed =
      page === "users"
        ? canManageUsers
        : page === "groups"
          ? canReadGroups
          : page === "models"
            ? canManageModels
            : page === "providers"
              ? canManageProviders
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
              : page === "models"
                ? "Models"
                : page === "providers"
                  ? "Sign-in providers"
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
