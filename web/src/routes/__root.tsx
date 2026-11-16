import type { QueryClient } from "@tanstack/react-query";
import { createRootRouteWithContext, Outlet, useLocation } from "@tanstack/react-router";
import { ActionNotifications } from "@/components/ui/action-notifications";
import { AccountNotFound } from "@/features/identity/account-not-found";

type RouterContext = {
  queryClient: QueryClient;
};

export const Route = createRootRouteWithContext<RouterContext>()({
  component: function RootComponent() {
    const pathname = useLocation({ select: (location) => location.pathname });
    return (
      <ActionNotifications scope={pathname}>
        <Outlet />
      </ActionNotifications>
    );
  },
  notFoundComponent: AccountNotFound,
});
