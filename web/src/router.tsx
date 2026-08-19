import { createRouter } from "@tanstack/react-router";
import { RouteError, RouteNotFound, RoutePending } from "@/components/states/route-states";
import { queryClient } from "@/lib/query-client";
import { routeTree } from "@/routeTree.gen";

export const router = createRouter({
  routeTree,
  context: { queryClient },
  defaultPreload: "intent",
  defaultPendingMs: 200,
  defaultPendingMinMs: 300,
  defaultPendingComponent: RoutePending,
  defaultErrorComponent: RouteError,
  defaultNotFoundComponent: RouteNotFound,
  notFoundMode: "root",
  scrollRestoration: true,
});

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router;
  }
}
