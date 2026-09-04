import { createFileRoute, Outlet, useMatchRoute } from "@tanstack/react-router";
import { AppShell } from "@/components/app-shell/app-shell";
import { AccessDeniedScreen } from "@/features/identity/session-states";
import { useCan } from "@/features/identity/application-session-context";
import { SourceUploadRecoveryProvider } from "@/features/sources/source-upload-recovery-provider";

export const Route = createFileRoute("/_authenticated/admin")({
  component: function AdministrationLayout() {
    const canManageInvitations = useCan("INVITATIONS_MANAGE");
    const canManageSources = useCan("SOURCES_MANAGE");
    const matchRoute = useMatchRoute();
    const invitationsSelected = Boolean(matchRoute({ to: "/admin/invitations" }));

    if (invitationsSelected ? !canManageInvitations : !canManageSources) {
      return <AccessDeniedScreen />;
    }

    return (
      <AppShell
        area="admin"
        adminPage={invitationsSelected ? "invitations" : "sources"}
        pageTitle={invitationsSelected ? "Invitations" : "Sources"}
      >
        <SourceUploadRecoveryProvider>
          <Outlet />
        </SourceUploadRecoveryProvider>
      </AppShell>
    );
  },
});
