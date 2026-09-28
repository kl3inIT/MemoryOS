import { render, screen, waitFor } from "@testing-library/react";
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
import { beforeEach, describe, expect, it } from "vitest";
import type { ApplicationSession } from "@/features/identity/application-session-context";
import { ApplicationSessionProvider } from "@/features/identity/application-session-provider";
import { ActionNotifications } from "@/components/ui/action-notifications";
import { handleCreateDocumentSet, handleListChatPersonaSources } from "@/lib/hey-api/msw.gen";
import type { ApiProblem, Input, View } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { DocumentSetFormPage } from "./document-set-form-page";

const OWNER_SESSION: ApplicationSession = {
  actorId: "0f2f5e6e-4e6c-4d55-9c07-6b0b1d4b39a4",
  authorizationVersion: 1,
  uiLanguage: "en",
  tenant: { displayName: "Tasco", role: "OWNER" },
  capabilities: ["SYSTEM_BASIC", "SOURCES_READ", "AGENTS_CREATE"],
  scopedCapabilities: [],
};

const FINANCE_SOURCE = {
  id: "00000000-0000-4000-8000-000000000001",
  name: "Finance Drive",
  type: "GOOGLE_DRIVE" as const,
};

const publicExplanation = /every Tenant member can use this Document Set/;
const sharingExplanation = /Only you, agent administrators/;

async function renderForm() {
  const rootRoute = createRootRoute();
  const route = createRoute({
    getParentRoute: () => rootRoute,
    path: "/admin/document-sets/new",
    component: () => (
      <ApplicationSessionProvider session={OWNER_SESSION}>
        <ActionNotifications>
          <DocumentSetFormPage />
        </ActionNotifications>
      </ApplicationSessionProvider>
    ),
  });
  const listRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: "/admin/document-sets",
    component: () => <p>Document Set list</p>,
  });
  const router = createRouter({
    routeTree: rootRoute.addChildren([route, listRoute]),
    history: createMemoryHistory({ initialEntries: ["/admin/document-sets/new"] }),
  });
  await router.load();
  render(
    <QueryClientProvider client={new QueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

beforeEach(() => {
  server.use(handleListChatPersonaSources({ body: [FINANCE_SOURCE] }));
});

describe("DocumentSetFormPage", () => {
  it("keeps what public access means behind its help control", async () => {
    const user = await renderForm();
    const checkbox = await screen.findByRole("checkbox", {
      name: "Make this Document Set public?",
    });
    expect(screen.queryByText(publicExplanation)).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Make this Document Set public? help" }));

    expect(await screen.findByText(publicExplanation)).toBeVisible();
    // The help control sits beside the choice, so reading it does not make the set public.
    expect(checkbox).not.toBeChecked();
  });

  it("keeps who can use a shared set behind its help control", async () => {
    const user = await renderForm();
    expect(screen.queryByText(sharingExplanation)).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Share the Document Set help" }));

    expect(await screen.findByText(sharingExplanation)).toBeVisible();
  });

  it("names what is missing only once the form is submitted, and clears it as it is fixed", async () => {
    const user = await renderForm();
    const name = await screen.findByRole("textbox", { name: "Name" });
    expect(screen.queryByText("Enter a Document Set name.")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Create Document Set" }));

    expect(await screen.findByText("Enter a Document Set name.")).toBeVisible();
    expect(screen.getByText("Select at least one Source.")).toBeVisible();
    expect(name).toHaveAttribute("aria-invalid", "true");

    await user.type(name, "Finance");

    await waitFor(() =>
      expect(screen.queryByText("Enter a Document Set name.")).not.toBeInTheDocument(),
    );
    expect(name).not.toHaveAttribute("aria-invalid");
  });

  it("creates the set with its trimmed name and chosen Sources", async () => {
    let sent: Input | undefined;
    server.use(
      handleCreateDocumentSet(async ({ request }) => {
        sent = await request.json();
        const created: View = {
          id: "6f7dfd15-4a1e-4d7e-bd4a-1ee4b3a4f2f1",
          revision: 1,
          ...sent,
          sources: [FINANCE_SOURCE],
          userShares: [],
          groupShares: [],
          permissions: { edit: true, share: true, delete: true, manage: false },
        };
        return HttpResponse.json(created, { status: 201 });
      }),
    );
    const user = await renderForm();

    await user.type(await screen.findByRole("textbox", { name: "Name" }), "  Finance  ");
    await user.click(screen.getByRole("combobox", { name: "Search sources…" }));
    await user.click(await screen.findByRole("option", { name: /Finance Drive/ }));
    await user.click(screen.getByRole("button", { name: "Create Document Set" }));

    expect(await screen.findByText("Document Set list")).toBeVisible();
    expect(sent).toEqual({
      name: "Finance",
      description: "",
      sourceIds: [FINANCE_SOURCE.id],
      isPublic: false,
    });
  });

  it("shows the API's field and form errors on the form", async () => {
    const problem: ApiProblem = {
      title: "Bad Request",
      status: 400,
      detail: "Invalid Document Set",
      instance: "/api/chat/document-sets",
      errors: [{ field: "name", message: "size", code: "SIZE", params: { min: 1, max: 200 } }],
    };
    server.use(handleCreateDocumentSet(() => HttpResponse.json(problem, { status: 400 })));
    const user = await renderForm();

    const name = await screen.findByRole("textbox", { name: "Name" });
    await user.type(name, "Finance");
    await user.click(screen.getByRole("combobox", { name: "Search sources…" }));
    await user.click(await screen.findByRole("option", { name: /Finance Drive/ }));
    await user.click(screen.getByRole("button", { name: "Create Document Set" }));

    expect(await screen.findByText("Check the highlighted fields and try again.")).toBeVisible();
    expect(name).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByRole("button", { name: "Create Document Set" })).toBeEnabled();
  });
});
