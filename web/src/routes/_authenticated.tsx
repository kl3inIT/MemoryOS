import { createFileRoute, Outlet } from "@tanstack/react-router";
import { AppShell } from "@/components/app-shell/app-shell";
import { ApplicationSessionBoundary } from "@/features/identity/application-session-boundary";
import { currentIdentityQueryOptions } from "@/features/identity/current-identity-query";
import { SessionLoadingScreen } from "@/features/identity/session-states";
import { ChatRuntimeProvider } from "@/features/chat/runtime/chat-runtime-provider";

export const Route = createFileRoute("/_authenticated")({
  /**
   * Identity is read before any authenticated page loads, so pages and their loaders start with it cached. A failure
   * is left in the query for the session boundary, which owns the sign-in redirect and the failure screens.
   */
  beforeLoad: async ({ context: { queryClient } }) => {
    await queryClient
      .ensureQueryData(currentIdentityQueryOptions(queryClient))
      .catch(() => undefined);
  },
  // The opening screen shows at once and for no longer than the read, as it did before the read moved here.
  pendingComponent: SessionLoadingScreen,
  pendingMs: 0,
  pendingMinMs: 0,
  component: () => (
    <ApplicationSessionBoundary>
      {/* The sidebar lists conversations on every application page, so the thread list sits above the shell. */}
      <ChatRuntimeProvider>
        <AppShell>
          <Outlet />
        </AppShell>
      </ChatRuntimeProvider>
    </ApplicationSessionBoundary>
  ),
});
