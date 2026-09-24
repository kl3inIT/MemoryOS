import { createFileRoute, Outlet } from "@tanstack/react-router";
import { ApplicationSessionBoundary } from "@/features/identity/application-session-boundary";
import { ChatRuntimeProvider } from "@/features/chat/runtime/chat-runtime-provider";

export const Route = createFileRoute("/_authenticated")({
  component: () => (
    <ApplicationSessionBoundary>
      <ChatRuntimeProvider>
        <Outlet />
      </ChatRuntimeProvider>
    </ApplicationSessionBoundary>
  ),
});
