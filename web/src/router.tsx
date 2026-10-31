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
  // The shell stays mounted, so its scrolling main region starts each new page at the top like the window does.
  scrollToTopSelectors: ["#main-content"],
});

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router;
  }
  interface HistoryState {
    /**
     * A first question for a new topic, sent once. It travels in history state, never in the URL, so a link cannot
     * make a person send a message they did not write.
     */
    ask?: string;
  }
}
