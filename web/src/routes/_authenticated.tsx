import { createFileRoute, Outlet } from "@tanstack/react-router";
import { AppShell } from "@/components/app-shell/app-shell";
import { ApplicationSessionBoundary } from "@/features/identity/application-session-boundary";
import { ChatRuntimeProvider } from "@/features/chat/runtime/chat-runtime-provider";

export const Route = createFileRoute("/_authenticated")({
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
