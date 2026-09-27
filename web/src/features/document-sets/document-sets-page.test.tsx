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
import { HttpResponse } from "msw";
import { assert, beforeEach, describe, expect, it } from "vitest";
import type { ApplicationSession } from "@/features/identity/application-session-context";
import { ApplicationSessionProvider } from "@/features/identity/application-session-provider";
import {
  handleDeleteDocumentSet,
  handleListChatPersonaSources,
  handleListDocumentSets,
} from "@/lib/hey-api/msw.gen";
import type { View } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { DocumentSetsPage } from "./document-sets-page";

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

function documentSet(overrides: Partial<View> = {}): View {
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

const bodyRows = async () =>
  within(await screen.findByRole("table", { name: "Document Sets table" }))
    .getAllByRole("row")
    .slice(1);

beforeEach(() => {
  server.use(handleListChatPersonaSources({ body: [] }));
});

describe("DocumentSetsPage", () => {
  it("names every Source it may show and counts the ones the viewer cannot read", async () => {
    server.use(
      handleListDocumentSets({
        body: [documentSet({ sources: [1, 2, 3, 4, 5].map(source), hiddenSources: 2 })],
      }),
    );
    await renderPage();

    const [row] = await bodyRows();
    expect(within(row!).getByText("Source 1")).toBeVisible();
    expect(within(row!).getByText("Source 5")).toBeVisible();
    // Sources the viewer cannot select are never named, only counted.
    expect(within(row!).getByText("+2 Sources you cannot read")).toBeVisible();
  });

  it("shows public, shared and private access for each set", async () => {
    server.use(
      handleListDocumentSets({
        body: [
          documentSet({ id: "11111111-1111-4111-8111-111111111111", name: "A", isPublic: true }),
          documentSet({
            id: "22222222-2222-4222-8222-222222222222",
            name: "B",
            groupShares: [{ id: "33333333-3333-4333-8333-333333333333", name: "Finance" }],
          }),
          documentSet({ id: "44444444-4444-4444-8444-444444444444", name: "C" }),
        ],
      }),
    );
    await renderPage();

    const [publicRow, sharedRow, privateRow] = await bodyRows();
    assert.isDefined(publicRow);
    assert.isDefined(sharedRow);
    assert.isDefined(privateRow);
    expect(within(publicRow).getByText("Public")).toBeVisible();
    expect(within(sharedRow).getByText("Shared")).toBeVisible();
    expect(within(privateRow).getByText("Private")).toBeVisible();
  });

  it("pages through the sets on the server, one page at a time", async () => {
    const requested: (string | null)[] = [];
    server.use(
      handleListDocumentSets(({ request }) => {
        const query = new URL(request.url).searchParams;
        requested.push(`${query.get("offset")}/${query.get("limit")}`);
        const offset = Number(query.get("offset"));
        // A full page plus one row tells the table that a next page exists.
        const count = offset === 0 ? 51 : 1;
        return HttpResponse.json(
          Array.from({ length: count }, (_, index) =>
            documentSet({
              id: `00000000-0000-4000-8000-${String(offset + index).padStart(12, "0")}`,
              name: `Set ${offset + index}`,
            }),
          ),
        );
      }),
    );
    const user = userEvent.setup();
    await renderPage();

    expect(await bodyRows()).toHaveLength(50);
    await user.click(screen.getByRole("button", { name: "Next" }));

    await waitFor(() => expect(screen.getByText("Set 50")).toBeVisible());
    expect(await bodyRows()).toHaveLength(1);
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
    expect(requested).toEqual(["0/51", "50/51"]);
  });

  it("deletes a set at its current revision after the confirmation", async () => {
    let deleted: { id: string | undefined; revision: string | null } | undefined;
    server.use(
      handleListDocumentSets({ body: [documentSet()] }),
      handleDeleteDocumentSet(({ request, params }) => {
        deleted = {
          id: params.documentSetId,
          revision: new URL(request.url).searchParams.get("revision"),
        };
        return new HttpResponse(null, { status: 204 });
      }),
    );
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
      expect(deleted).toEqual({ id: "6f7dfd15-4a1e-4d7e-bd4a-1ee4b3a4f2f1", revision: "3" }),
    );
  });

  it("offers no editing controls on a set the viewer may only use", async () => {
    server.use(
      handleListDocumentSets({
        body: [
          documentSet({
            permissions: { edit: false, share: false, delete: false, manage: false },
          }),
        ],
      }),
    );
    await renderPage();

    expect(await bodyRows()).toHaveLength(1);
    expect(screen.queryByRole("link", { name: "Edit Finance" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Delete Finance" })).not.toBeInTheDocument();
  });
});
