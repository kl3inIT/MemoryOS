import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from "@tanstack/react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ApplicationSession } from "@/features/identity/application-session-context";
import { ApplicationSessionProvider } from "@/features/identity/application-session-provider";
import type * as Sdk from "@/lib/hey-api/sdk.gen";
import type { SourceSummary } from "@/lib/hey-api/types.gen";
import { SourcesPage } from "./sources-page";

const listSourcesMock = vi.hoisted(() => vi.fn());

vi.mock("@/lib/hey-api/sdk.gen", async (importOriginal) => ({
  ...(await importOriginal<typeof Sdk>()),
  listSources: listSourcesMock,
}));

const MANAGER_SESSION: ApplicationSession = {
  actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
  authorizationVersion: 1,
  uiLanguage: "en",
  tenant: { displayName: "Tasco", role: "OWNER" },
  capabilities: ["SYSTEM_BASIC", "SOURCES_READ", "SOURCES_MANAGE"],
  scopedCapabilities: [],
};

const NO_PERMISSIONS: SourceSummary["permissions"] = {
  edit: false,
  delete: false,
  publish: false,
  manageConfiguration: false,
  removeItems: false,
};

function sourceSummary(
  source: Pick<SourceSummary, "id" | "name"> & Partial<SourceSummary>,
): SourceSummary {
  return {
    type: "FILE",
    access: "PUBLIC",
    status: "ACTIVE",
    pendingWork: false,
    documentCount: 0,
    lastSucceededAt: null,
    errorCode: null,
    permissions: NO_PERMISSIONS,
    ...source,
  };
}

const HANDBOOK = sourceSummary({
  id: "0d1c4a4e-5b0e-4a53-9d1e-2f6f0b8f7a11",
  name: "Employee handbook",
  documentCount: 12,
  lastSucceededAt: "2026-09-14T08:30:00Z",
  permissions: { ...NO_PERMISSIONS, edit: true },
});

const REPORTS = sourceSummary({
  id: "6a0b9c52-8f3e-4d8a-a1c7-94b2e5d3f022",
  name: "Quarterly reports",
  type: "GOOGLE_DRIVE",
  access: "SYNC",
  status: "FAILED",
  documentCount: 30,
});

async function renderSourcesPage() {
  const rootRoute = createRootRoute();
  const sourcesRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: "/admin",
    component: () => (
      <ApplicationSessionProvider session={MANAGER_SESSION}>
        <SourcesPage />
      </ApplicationSessionProvider>
    ),
  });
  const router = createRouter({
    routeTree: rootRoute.addChildren([sourcesRoute]),
    history: createMemoryHistory({ initialEntries: ["/admin"] }),
  });
  await router.load();
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

function rowOf(table: HTMLElement, sourceName: string) {
  const row = within(table).getByRole("link", { name: sourceName }).closest("tr");
  expect(row).not.toBeNull();
  return row as HTMLElement;
}

function metric(label: string) {
  const term = screen.getByText(label, { selector: "dt" });
  return term.nextElementSibling?.textContent;
}

afterEach(() => {
  listSourcesMock.mockReset();
});

describe("SourcesPage", () => {
  it("lists each Source with its provider, status and access under an overview", async () => {
    listSourcesMock.mockResolvedValue({ data: [HANDBOOK, REPORTS] });
    await renderSourcesPage();

    const table = await screen.findByRole("table", { name: "Connected sources" });
    expect(within(table).getAllByRole("row")).toHaveLength(3);

    const handbook = rowOf(table, "Employee handbook");
    expect(within(handbook).getByText("File")).toBeInTheDocument();
    expect(within(handbook).getByText("Active")).toBeInTheDocument();
    expect(within(handbook).getByText("Workspace members")).toBeInTheDocument();
    expect(
      within(handbook).getByRole("link", { name: "Manage Employee handbook" }),
    ).toBeInTheDocument();

    const reports = rowOf(table, "Quarterly reports");
    expect(within(reports).getByText("Google Drive")).toBeInTheDocument();
    expect(within(reports).getByText("Failed")).toBeInTheDocument();
    expect(within(reports).getByText("Auto Sync")).toBeInTheDocument();
    expect(within(reports).getByText("Not yet")).toBeInTheDocument();
    // Without any Source action the row offers no settings shortcut.
    expect(within(reports).queryByRole("link", { name: /^Manage/ })).not.toBeInTheDocument();

    expect(metric("Total sources")).toBe("2");
    expect(metric("Active sources")).toBe("1/2");
    expect(metric("Failed sources")).toBe("1");
    expect(metric("Total docs indexed")).toBe("42");
  });

  it("narrows the list by search and filters, then clears both", async () => {
    listSourcesMock.mockResolvedValue({ data: [HANDBOOK, REPORTS] });
    const user = userEvent.setup();
    await renderSourcesPage();
    const table = await screen.findByRole("table", { name: "Connected sources" });
    const search = screen.getByRole("searchbox", { name: "Search sources" });

    // Search also matches the provider name.
    await user.type(search, "drive");
    expect(within(table).queryByText("Employee handbook")).not.toBeInTheDocument();
    expect(within(table).getByText("Quarterly reports")).toBeInTheDocument();
    await user.clear(search);

    await user.click(screen.getByRole("button", { name: /^Status/ }));
    await user.click(await screen.findByRole("menuitemradio", { name: "Failed" }));
    expect(screen.getByRole("button", { name: /^Status\s*Failed/ })).toBeInTheDocument();
    expect(within(table).queryByText("Employee handbook")).not.toBeInTheDocument();
    expect(within(table).getByText("Quarterly reports")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /^Access/ }));
    await user.click(await screen.findByRole("menuitemradio", { name: "Private" }));
    expect(screen.getByText("No sources match your search and filters.")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Clear search and filters" }));
    expect(within(table).getByText("Employee handbook")).toBeInTheDocument();
    expect(within(table).getByText("Quarterly reports")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Clear filters" })).not.toBeInTheDocument();
  });

  it("invites a manager to add the first Source", async () => {
    listSourcesMock.mockResolvedValue({ data: [] });
    await renderSourcesPage();

    expect(await screen.findByText("No sources yet")).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: "Add source" })).toHaveLength(2);
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("offers a retry when the list cannot be loaded", async () => {
    listSourcesMock
      .mockRejectedValueOnce(new Error("unavailable"))
      .mockResolvedValueOnce({ data: [HANDBOOK] });
    const user = userEvent.setup();
    await renderSourcesPage();

    expect(await screen.findByText("Sources unavailable")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("link", { name: "Employee handbook" })).toBeInTheDocument();
  });
});
