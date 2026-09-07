import type { QueryClient } from "@tanstack/react-query";
import { createRootRouteWithContext, Outlet, useLocation } from "@tanstack/react-router";
import { RouteNotFound } from "@/components/states/route-states";
import { ActionNotifications } from "@/components/ui/action-notifications";

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
  notFoundComponent: RouteNotFound,
});
