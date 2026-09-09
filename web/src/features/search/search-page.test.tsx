import { act, render, screen, waitFor } from "@testing-library/react";
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

class MockSpeechRecognition {
  static current: MockSpeechRecognition | null = null;

  continuous = false;
  interimResults = false;
  lang = "";
  onend: (() => void) | null = null;
  onerror: ((event: { error: string }) => void) | null = null;
  onresult:
    | ((event: {
        results: ArrayLike<{
          readonly isFinal: boolean;
          readonly length: number;
          readonly [index: number]: { transcript: string };
        }>;
      }) => void)
    | null = null;
  onstart: (() => void) | null = null;
  abort = vi.fn();
  start = vi.fn(() => this.onstart?.());
  stop = vi.fn(() => this.onend?.());

  constructor() {
    MockSpeechRecognition.current = this;
  }
}

async function renderNewSession(session: ApplicationSession = OWNER_SESSION) {
  vi.stubGlobal("scrollTo", vi.fn());
  const rootRoute = createRootRoute();
  const indexRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: "/search",
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
    history: createMemoryHistory({ initialEntries: ["/search"] }),
  });
  await router.load();
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

afterEach(() => {
  MockSpeechRecognition.current = null;
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
    expect(screen.getByRole("heading", { name: "Search your workspace" })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Search documents" })).toBeEnabled();
    expect(screen.queryByRole("button", { name: "Add to search" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Search by voice" })).toBeDisabled();
    expect(screen.getAllByRole("status").some((status) => status.textContent === "")).toBe(true);
    expect(screen.getByRole("button", { name: "Tenant owner" })).toBeInTheDocument();
  });

  it("adds a spoken transcript to the controlled query without searching automatically", async () => {
    vi.stubGlobal("SpeechRecognition", MockSpeechRecognition);
    const user = userEvent.setup();
    await renderNewSession();

    const input = screen.getByRole("textbox", { name: "Search documents" });
    await user.type(input, "quy định");
    await user.click(screen.getByRole("button", { name: "Search by voice" }));

    expect(screen.getByRole("button", { name: "Stop voice search" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(screen.getByText("Listening… Speak now, then review your query.")).toBeVisible();

    act(() => {
      MockSpeechRecognition.current?.onresult?.({
        results: [{ 0: { transcript: "nghỉ phép" }, isFinal: true, length: 1 }],
      });
    });

    expect(input).toHaveValue("quy định nghỉ phép");
    expect(searchDocumentsMock).not.toHaveBeenCalled();

    await user.click(screen.getByRole("button", { name: "Stop voice search" }));
    expect(screen.getByRole("button", { name: "Search by voice" })).toHaveAttribute(
      "aria-pressed",
      "false",
    );
    expect(input).toHaveFocus();
  });

  it("explains how to recover when microphone permission is denied", async () => {
    vi.stubGlobal("SpeechRecognition", MockSpeechRecognition);
    const user = userEvent.setup();
    await renderNewSession();

    await user.click(screen.getByRole("button", { name: "Search by voice" }));
    act(() => MockSpeechRecognition.current?.onerror?.({ error: "not-allowed" }));

    expect(
      screen.getByText(
        "Microphone access was not granted. Enable it in your browser and try again.",
      ),
    ).toBeVisible();
    expect(screen.getByRole("button", { name: "Search by voice" })).toHaveAttribute(
      "aria-pressed",
      "false",
    );
  });

  it("renders the result workspace and applies the compact filter menus", async () => {
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

    await user.click(screen.getByRole("button", { name: "File type: All file types" }));
    await user.click(screen.getByRole("menuitemradio", { name: "PDF" }));

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
    expect(
      screen.getByRole("button", { name: "Word document: 0 results on this page" }),
    ).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Word document: 0 results on this page" }));
    await waitFor(() => expect(searchDocumentsMock).toHaveBeenCalledTimes(3));
    expect(searchDocumentsMock.mock.calls.at(-1)?.[0]).toMatchObject({
      body: {
        query: "nghỉ phép",
        mediaTypes: ["application/vnd.openxmlformats-officedocument.wordprocessingml.document"],
        page: 0,
      },
    });

    await user.click(screen.getByRole("button", { name: "Updated: All time" }));
    await user.click(screen.getByRole("menuitemradio", { name: "Past 30 days" }));

    await waitFor(() =>
      expect(searchDocumentsMock).toHaveBeenCalledWith(
        expect.objectContaining({
          body: expect.objectContaining({
            query: "nghỉ phép",
            mediaTypes: ["application/vnd.openxmlformats-officedocument.wordprocessingml.document"],
            page: 0,
            updatedSince: expect.stringMatching(/T00:00:00\.000Z$/),
          }),
        }),
      ),
    );
    expect(screen.getByRole("button", { name: "Updated: Past 30 days" })).toBeInTheDocument();
  });

  it("uses the loading screen for a repeated search immediately", async () => {
    const user = userEvent.setup();
    searchDocumentsMock.mockResolvedValueOnce({
      data: {
        page: 0,
        hasMore: false,
        candidateLimit: 500,
        results: [
          {
            documentId: "73835d74-d386-4b4e-b392-ad7f81e3b55a",
            generation: "6b780b3a-de22-4307-ace9-6c2f44e22fc1",
            title: "Existing policy document",
            mediaType: "application/pdf",
            updatedAt: "2026-09-08T00:00:00Z",
            score: 0.8,
            sections: [],
          },
        ],
      },
    });
    let resolveSecondSearch: (value: unknown) => void = () => undefined;
    searchDocumentsMock.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveSecondSearch = resolve;
        }),
    );
    await renderNewSession();

    const input = screen.getByRole("textbox", { name: "Search documents" });
    await user.type(input, "policy");
    await user.click(screen.getByRole("button", { name: "Search" }));
    expect(await screen.findByText("Existing policy document")).toBeVisible();

    await user.clear(input);
    await user.type(input, "onboarding");
    await user.click(screen.getByRole("button", { name: "Search" }));

    expect(screen.queryByText("Existing policy document")).not.toBeInTheDocument();
    expect(screen.getByText("Searching documents…")).toBeVisible();
    expect(screen.getByRole("button", { name: "Search is loading" })).toBeDisabled();

    resolveSecondSearch({
      data: {
        page: 0,
        hasMore: false,
        candidateLimit: 500,
        results: [],
      },
    });
    await waitFor(() => expect(screen.getByRole("button", { name: "Search" })).toBeEnabled());
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
