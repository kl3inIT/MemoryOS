import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
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
import { ThemeProvider } from "@/features/theme/theme-provider";
import { SearchPage } from "./search-page";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

const searchDocumentsMock = vi.hoisted(() => vi.fn());

vi.mock("@/lib/hey-api/sdk.gen", () => ({
  searchDocuments: searchDocumentsMock,
}));

const OWNER_SESSION: ApplicationSession = {
  actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
  authorizationVersion: 1,
  tenant: {
    displayName: "Tasco",
    role: "OWNER",
  },
  capabilities: ["USERS_MANAGE", "SOURCES_READ", "SOURCES_MANAGE"],
  scopedCapabilities: [],
};

async function renderNewSession(session: ApplicationSession = OWNER_SESSION) {
  vi.stubGlobal("scrollTo", vi.fn());
  const rootRoute = createRootRoute();
  const indexRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: "/",
    component: () => (
      <ApplicationSessionProvider session={session}>
        <ThemeProvider>
          <SearchPage />
        </ThemeProvider>
      </ApplicationSessionProvider>
    ),
  });
  const router = createRouter({
    routeTree: rootRoute.addChildren([indexRoute]),
    history: createMemoryHistory({ initialEntries: ["/"] }),
  });
  await router.load();
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

afterEach(() => {
  searchDocumentsMock.mockReset();
  window.localStorage.clear();
  document.documentElement.classList.remove("dark");
  document.documentElement.style.removeProperty("color-scheme");
  vi.unstubAllGlobals();
});

describe("SearchPage", () => {
  it("renders Search inside the authenticated application shell", async () => {
    await renderNewSession();

    expect(screen.getByRole("navigation", { name: "Primary navigation" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Search" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Admin Panel" })).toHaveAttribute("href", "/admin");
    expect(screen.getByRole("heading", { name: "Search documents" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "How can I help?" })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Search documents" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Tenant owner" })).toBeInTheDocument();
  });

  it("renders the result workspace and applies a file-type facet", async () => {
    const user = userEvent.setup();
    searchDocumentsMock.mockResolvedValue({
      data: {
        page: 0,
        hasMore: false,
        candidateLimit: 500,
        results: [
          {
            documentId: "73835d74-d386-4b4e-b392-ad7f81e3b55a",
            generation: "6b780b3a-de22-4307-ace9-6c2f44e22fc1",
            title: "HR-2026 Quy định nghỉ phép",
            mediaType: "application/pdf",
            updatedAt: "2026-09-08T00:00:00Z",
            score: 0.8,
            sections: [
              {
                startOrdinal: 2,
                endOrdinal: 2,
                matchingOrdinal: 2,
                score: 0.8,
                content: "Nhân viên có 12 ngày nghỉ phép.",
                provenance: [],
              },
            ],
          },
        ],
      },
    });
    await renderNewSession();

    await user.type(screen.getByRole("textbox", { name: "Search documents" }), "nghỉ phép");
    await user.click(screen.getByRole("button", { name: "Search" }));

    expect(await screen.findByRole("heading", { name: "1 result" })).toBeInTheDocument();
    expect(
      screen.getByRole("complementary", { name: "File types on this page" }),
    ).toBeInTheDocument();
    const pdfFacet = screen.getByRole("button", { name: "PDF: 1 result on this page" });
    expect(pdfFacet).toHaveAttribute("aria-pressed", "false");

    await user.click(pdfFacet);

    await waitFor(() => expect(searchDocumentsMock).toHaveBeenCalledTimes(2));
    expect(searchDocumentsMock.mock.calls.at(-1)?.[0]).toMatchObject({
      body: { query: "nghỉ phép", mediaTypes: ["application/pdf"], page: 0 },
    });
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "PDF: 1 result on this page" })).toHaveAttribute(
        "aria-pressed",
        "true",
      ),
    );
  });

  it("removes owner administration affordances for a member", async () => {
    await renderNewSession({
      ...OWNER_SESSION,
      tenant: { ...OWNER_SESSION.tenant, role: "MEMBER" },
      capabilities: [],
      scopedCapabilities: [],
    });

    expect(screen.getByRole("button", { name: "Tenant member" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Admin Panel" })).not.toBeInTheDocument();
  });
  it("routes user-only administrators to users", async () => {
    await renderNewSession({
      ...OWNER_SESSION,
      capabilities: ["USERS_MANAGE"],
    });

    expect(screen.getByRole("link", { name: "Admin Panel" })).toHaveAttribute(
      "href",
      "/admin/users",
    );
  });

  it("routes group-scoped managers to the Groups surface", async () => {
    await renderNewSession({
      ...OWNER_SESSION,
      tenant: { ...OWNER_SESSION.tenant, role: "MEMBER" },
      capabilities: [],
      scopedCapabilities: ["GROUPS_READ"],
    });

    expect(screen.getByRole("link", { name: "Admin Panel" })).toHaveAttribute(
      "href",
      "/admin/groups",
    );
  });

  it("collapses and expands the desktop sidebar", async () => {
    const user = userEvent.setup();
    await renderNewSession();

    await user.click(screen.getByRole("button", { name: "Collapse sidebar" }));
    expect(screen.getByRole("button", { name: "Expand sidebar" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Expand sidebar" }));
    expect(screen.getByRole("button", { name: "Collapse sidebar" })).toBeInTheDocument();
  });

  it("persists a real dark theme preference", async () => {
    const user = userEvent.setup();
    await renderNewSession();

    await user.click(screen.getByRole("button", { name: "Tenant owner" }));
    await user.click(screen.getByRole("button", { name: "Use dark theme" }));

    expect(document.documentElement).toHaveClass("dark");
    expect(window.localStorage.getItem("memoryos-theme")).toBe("dark");
    expect(screen.getByRole("button", { name: "Use light theme" })).toBeInTheDocument();
  });

  it("sends a guarded same-origin sign-out request", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn(() => new Promise<Response>(() => undefined));
    vi.stubGlobal("fetch", fetchMock);
    await renderNewSession();

    await user.click(screen.getByRole("button", { name: "Tenant owner" }));
    await user.click(screen.getByRole("button", { name: "Sign out" }));

    expect(fetchMock).toHaveBeenCalledWith("/logout", {
      method: "POST",
      credentials: "same-origin",
      headers: { "X-MemoryOS-CSRF": "1" },
    });
    expect(screen.getByRole("button", { name: "Signing out…" })).toBeDisabled();
  });

  it("keeps the account menu actionable when sign-out fails", async () => {
    const user = userEvent.setup();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 500 })));
    await renderNewSession();

    await user.click(screen.getByRole("button", { name: "Tenant owner" }));
    await user.click(screen.getByRole("button", { name: "Sign out" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "We couldn't sign you out. Try again.",
    );
    expect(screen.getByRole("button", { name: "Sign out" })).toBeEnabled();
  });
});
