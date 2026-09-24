import { render, screen } from "@testing-library/react";
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
import { ActionNotifications } from "@/components/ui/action-notifications";
import type * as ChatPersonasApi from "@/features/chat/chat-personas-api";
import { DocumentSetFormPage } from "./document-set-form-page";

const loadPersonaSourcesMock = vi.hoisted(() => vi.fn());

vi.mock("@/features/chat/chat-personas-api", async (importOriginal) => ({
  ...(await importOriginal<typeof ChatPersonasApi>()),
  loadPersonaSources: loadPersonaSourcesMock,
}));

const OWNER_SESSION: ApplicationSession = {
  actorId: "0f2f5e6e-4e6c-4d55-9c07-6b0b1d4b39a4",
  authorizationVersion: 1,
  uiLanguage: "en",
  tenant: { displayName: "Tasco", role: "OWNER" },
  capabilities: ["SYSTEM_BASIC", "SOURCES_READ", "AGENTS_CREATE"],
  scopedCapabilities: [],
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
  const router = createRouter({
    routeTree: rootRoute.addChildren([route]),
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
  loadPersonaSourcesMock.mockResolvedValue([]);
});

afterEach(() => {
  loadPersonaSourcesMock.mockReset();
});

describe("DocumentSetFormPage", () => {
  it("keeps what public access means behind its help control", async () => {
    const user = await renderForm();
    const checkbox = await screen.findByRole("checkbox");
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
});
