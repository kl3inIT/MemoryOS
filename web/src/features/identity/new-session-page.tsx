import { Link } from "@tanstack/react-router";
import { ArrowRight, Plug, UserPlus } from "lucide-react";
import { Brand } from "@/components/brand";
import { AppShell } from "@/components/app-shell/app-shell";
import { Button } from "@/components/ui/button";
import {
  useAdminAccess,
  useApplicationSession,
} from "@/features/identity/application-session-context";
import { defaultInvitationListSearch } from "@/features/invitations/invitation-list-search";

export function NewSessionPage() {
  const { tenant } = useApplicationSession();
  const { canManageSources, canManageInvitations } = useAdminAccess();

  return (
    <AppShell pageTitle="Home">
      <section className="flex min-h-full items-center justify-center px-4 py-12 sm:px-8">
        <div className="w-full max-w-(--page-width-narrow) md:-translate-y-8">
          <Brand compact />
          <h1 className="mt-5 font-heading-h2 text-content-primary">Welcome to MemoryOS</h1>
          <p className="mt-2 break-words font-main-content-body text-content-secondary">
            You are signed in to {tenant.displayName}.
          </p>
          {canManageSources || canManageInvitations ? (
            <div className="mt-8 flex flex-col gap-3 sm:flex-row sm:flex-wrap">
              {canManageSources && (
                <Button asChild size="lg">
                  <Link to="/admin">
                    <Plug />
                    Manage sources
                    <ArrowRight />
                  </Link>
                </Button>
              )}
              {canManageInvitations && (
                <Button asChild size="lg" prominence={canManageSources ? "secondary" : "primary"}>
                  <Link to="/admin/invitations" search={defaultInvitationListSearch}>
                    <UserPlus />
                    Manage invitations
                  </Link>
                </Button>
              )}
            </div>
          ) : (
            <p className="mt-6 max-w-lg font-main-ui-body text-content-muted">
              Your account is active. A Tenant owner manages sources and invitations for this
              workspace.
            </p>
          )}
        </div>
      </section>
    </AppShell>
  );
}
