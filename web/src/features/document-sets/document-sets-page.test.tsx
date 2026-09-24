import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from "@tanstack/react-router";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ApplicationSession } from "@/features/identity/application-session-context";
import { ApplicationSessionProvider } from "@/features/identity/application-session-provider";
import type { DocumentSet } from "@/features/document-sets/document-sets-api";
import type * as ChatPersonasApi from "@/features/chat/chat-personas-api";
import type * as DocumentSetsApi from "./document-sets-api";
import type * as ChatSdk from "@/lib/hey-api/sdk.gen";
import { DocumentSetsPage } from "./document-sets-page";

const loadDocumentSetsMock = vi.hoisted(() => vi.fn());
const loadPersonaSourcesMock = vi.hoisted(() => vi.fn());
const deleteDocumentSetMock = vi.hoisted(() => vi.fn());

vi.mock("@/features/document-sets/document-sets-api", async (importOriginal) => ({
  ...(await importOriginal<typeof DocumentSetsApi>()),
  loadDocumentSets: loadDocumentSetsMock,
}));

vi.mock("@/features/chat/chat-personas-api", async (importOriginal) => ({
  ...(await importOriginal<typeof ChatPersonasApi>()),
  loadPersonaSources: loadPersonaSourcesMock,
}));

vi.mock("@/lib/hey-api/sdk.gen", async (importOriginal) => ({
  ...(await importOriginal<typeof ChatSdk>()),
  deleteDocumentSet: deleteDocumentSetMock,
}));

const OWNER_SESSION: ApplicationSession = {
  actorId: "0f2f5e6e-4e6c-4d55-9c07-6b0b1d4b39a4",
  authorizationVersion: 1,
  uiLanguage: "en",
  tenant: { displayName: "Tasco", role: "OWNER" },
  capabilities: ["SYSTEM_BASIC", "SOURCES_READ", "AGENTS_CREATE"],
  scopedCapabilities: [],
};

function source(index: number) {
  return { id: `00000000-0000-4000-8000-00000000000${index}`, name: `Source ${index}` };
}

function documentSet(overrides: Partial<DocumentSet> = {}): DocumentSet {
  return {
    id: "6f7dfd15-4a1e-4d7e-bd4a-1ee4b3a4f2f1",
    revision: 3,
    name: "Finance",
    description: "",
    isPublic: false,
    sourceIds: [],
    userShares: [],
    groupShares: [],
    sources: [],
    hiddenSources: 0,
    permissions: { edit: true, share: true, delete: true, manage: false },
    ...overrides,
  };
}

async function renderPage() {
  const rootRoute = createRootRoute();
  const route = createRoute({
    getParentRoute: () => rootRoute,
    path: "/admin/document-sets",
    component: () => (
      <ApplicationSessionProvider session={OWNER_SESSION}>
        <DocumentSetsPage />
      </ApplicationSessionProvider>
    ),
  });
  const router = createRouter({
    routeTree: rootRoute.addChildren([route]),
    history: createMemoryHistory({ initialEntries: ["/admin/document-sets"] }),
  });
  await router.load();
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  loadPersonaSourcesMock.mockResolvedValue([]);
  deleteDocumentSetMock.mockResolvedValue({ data: undefined });
});

afterEach(() => {
  loadDocumentSetsMock.mockReset();
  loadPersonaSourcesMock.mockReset();
  deleteDocumentSetMock.mockReset();
});

describe("DocumentSetsPage", () => {
  it("names every Source it may show and counts the ones the viewer cannot read", async () => {
    loadDocumentSetsMock.mockResolvedValue([
      documentSet({ sources: [1, 2, 3, 4, 5].map(source), hiddenSources: 2 }),
    ]);
    await renderPage();

    const row = within(await screen.findByRole("table")).getAllByRole("row")[1];
    expect(within(row).getByText("Source 1")).toBeVisible();
    expect(within(row).getByText("Source 5")).toBeVisible();
    // Sources the viewer cannot select are never named, only counted.
    expect(within(row).getByText("+2 Sources you cannot read")).toBeVisible();
  });

  it("shows public, shared and private access for each set", async () => {
    loadDocumentSetsMock.mockResolvedValue([
      documentSet({ id: "11111111-1111-4111-8111-111111111111", name: "A", isPublic: true }),
      documentSet({
        id: "22222222-2222-4222-8222-222222222222",
        name: "B",
        groupShares: [{ id: "33333333-3333-4333-8333-333333333333", name: "Finance" }],
      }),
      documentSet({ id: "44444444-4444-4444-8444-444444444444", name: "C" }),
    ]);
    await renderPage();

    const rows = within(await screen.findByRole("table"))
      .getAllByRole("row")
      .slice(1);
    expect(within(rows[0]).getByText("Public")).toBeVisible();
    expect(within(rows[1]).getByText("Shared")).toBeVisible();
    expect(within(rows[2]).getByText("Private")).toBeVisible();
  });

  it("deletes a set at its current revision after the confirmation", async () => {
    loadDocumentSetsMock.mockResolvedValue([documentSet()]);
    const user = userEvent.setup();
    await renderPage();

    await user.click(await screen.findByRole("button", { name: "Delete Finance" }));
    expect(
      screen.getByText(
        "The Document Set will be removed from every agent that uses it. Sources and documents are not deleted.",
      ),
    ).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Delete Document Set" }));

    await waitFor(() =>
      expect(deleteDocumentSetMock).toHaveBeenCalledWith(
        expect.objectContaining({
          path: { documentSetId: "6f7dfd15-4a1e-4d7e-bd4a-1ee4b3a4f2f1" },
          query: { revision: 3 },
        }),
      ),
    );
  });

  it("offers no editing controls on a set the viewer may only use", async () => {
    loadDocumentSetsMock.mockResolvedValue([
      documentSet({ permissions: { edit: false, share: false, delete: false, manage: false } }),
    ]);
    await renderPage();

    expect(await screen.findByRole("table")).toBeVisible();
    expect(screen.queryByRole("link", { name: "Edit Finance" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Delete Finance" })).not.toBeInTheDocument();
  });
});
