import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from "@tanstack/react-router";
import { HttpResponse } from "msw";
import { afterEach, expect, it, vi } from "vitest";
import { handleListSourcePage } from "@/lib/hey-api/msw.gen";
import type { SourcePage, SourceSummary } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { SourcesPage } from "./sources-page";
import { sourcesSearchSchema } from "./sources-search";

vi.mock("@/features/identity/application-session-context", () => ({
  useCapabilityAuthority: () => "none",
}));

const handbooks: SourceSummary = {
  id: "20000000-0000-4000-8000-000000000001",
  name: "Handbooks",
  type: "FILE",
  access: "PUBLIC",
  status: "ACTIVE",
  documentCount: 4,
  lastSucceededAt: "2026-09-20T08:00:00Z",
  managerName: "Lan Nguyen",
  errorCode: null,
  pendingWork: false,
  permissions: {},
} as SourceSummary;

const counts: SourcePage["counts"] = {
  total: 34,
  notStarted: 0,
  indexing: 0,
  active: 34,
  failed: 0,
  paused: 0,
  pausing: 0,
  deleting: 0,
  documents: 120,
  pendingWork: false,
};

const clients: QueryClient[] = [];

afterEach(() => {
  for (const client of clients) client.clear();
  clients.length = 0;
});

async function renderPage(initial: string, requests: URL[]) {
  server.use(
    handleListSourcePage(({ request }) => {
      requests.push(new URL(request.url));
      return HttpResponse.json({
        items: [handbooks],
        page: 0,
        size: 20,
        totalItems: 34,
        totalPages: 2,
        counts,
      } satisfies SourcePage);
    }),
  );
  const root = createRootRoute();
  const authenticated = createRoute({ getParentRoute: () => root, id: "_authenticated" });
  const admin = createRoute({ getParentRoute: () => authenticated, path: "admin" });
  const index = createRoute({
    getParentRoute: () => admin,
    path: "/",
    validateSearch: sourcesSearchSchema,
    component: SourcesPage,
  });
  const router = createRouter({
    routeTree: root.addChildren([authenticated.addChildren([admin.addChildren([index])])]),
    history: createMemoryHistory({ initialEntries: [initial] }),
  });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  clients.push(client);
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  await screen.findByText("Handbooks");
  return router;
}

it("reads the search, filters and page from the address and sends them to the page query", async () => {
  const requests: URL[] = [];
  await renderPage("/admin?search=hand&status=FAILED&provider=FILE&access=PUBLIC&page=1", requests);

  const first = requests[0]!;
  expect(first.searchParams.get("search")).toBe("hand");
  expect(first.searchParams.get("status")).toBe("FAILED");
  expect(first.searchParams.get("type")).toBe("FILE");
  expect(first.searchParams.get("access")).toBe("PUBLIC");
  expect(first.searchParams.get("page")).toBe("1");
});

it("pages through the address, and returns to the first page when a filter changes", async () => {
  const user = userEvent.setup();
  const requests: URL[] = [];
  const router = await renderPage("/admin?page=0", requests);

  expect(screen.getByText("Showing 1–20 of 34 · 120 documents")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Next" }));
  await waitFor(() => expect(router.state.location.search).toMatchObject({ page: 1 }));

  const chips = screen.getByRole("group", { name: "Filter sources by status" });
  await user.click(within(chips).getByRole("button", { name: /Active/ }));
  await waitFor(() =>
    expect(router.state.location.search).toMatchObject({ status: "ACTIVE", page: 0 }),
  );
  expect(within(screen.getByRole("table")).getByText("Handbooks")).toBeInTheDocument();
});
