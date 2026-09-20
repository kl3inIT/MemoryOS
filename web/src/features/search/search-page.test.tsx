import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from "@tanstack/react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ApplicationSession } from "@/features/identity/application-session-context";
import { ApplicationSessionProvider } from "@/features/identity/application-session-provider";
import { ThemeProvider } from "@/features/theme/theme-provider";
import { MicrophoneUnavailableError } from "@/features/voice/capture/audio-capture";
import type * as VoiceDictationModule from "@/features/voice/voice-dictation";
import { SearchPage } from "./search-page";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type * as ChatSdk from "@/lib/hey-api/sdk.gen";
import type * as ChatWorkspaceApi from "@/features/chat/chat-workspace-api";

const searchDocumentsMock = vi.hoisted(() => vi.fn());
const voiceAvailabilityMock = vi.hoisted(() => vi.fn());
const startVoiceDictationMock = vi.hoisted(() => vi.fn());
const loadDocumentSetsMock = vi.hoisted(() => vi.fn());

vi.mock("@/lib/hey-api/sdk.gen", async (importOriginal) => ({
  ...(await importOriginal<typeof ChatSdk>()),
  searchDocuments: searchDocumentsMock,
  listChatSessions: vi.fn().mockResolvedValue({ data: [] }),
  getChatVoiceAvailability: voiceAvailabilityMock,
}));

vi.mock("@/features/voice/voice-dictation", async (importOriginal) => ({
  ...(await importOriginal<typeof VoiceDictationModule>()),
  startVoiceDictation: startVoiceDictationMock,
}));

vi.mock("@/features/chat/chat-workspace-api", async (importOriginal) => ({
  ...(await importOriginal<typeof ChatWorkspaceApi>()),
  loadProjects: vi.fn().mockResolvedValue([]),
  loadDocumentSets: loadDocumentSetsMock,
}));

const OWNER_SESSION: ApplicationSession = {
  actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
  authorizationVersion: 1,
  uiLanguage: "en",
  tenant: {
    displayName: "Tasco",
    role: "OWNER",
  },
  capabilities: [
    "SYSTEM_ADMIN",
    "SYSTEM_BASIC",
    "SEARCH_READ",
    "CHAT_READ",
    "CHAT_WRITE",
    "IMAGE_GENERATE",
    "LLM_GATEWAY_USE",
    "USERS_MANAGE",
    "GROUPS_READ",
    "GROUPS_MANAGE",
    "SOURCES_READ",
    "SOURCES_MANAGE",
    "SOURCES_DELETE",
    "MODELS_MANAGE",
  ],
  scopedCapabilities: [],
};

function speechToTextAvailable(sttAvailable: boolean) {
  voiceAvailabilityMock.mockResolvedValue({ data: { sttAvailable, ttsAvailable: false } });
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

beforeEach(() => {
  speechToTextAvailable(false);
  loadDocumentSetsMock.mockResolvedValue([]);
});

afterEach(() => {
  startVoiceDictationMock.mockReset();
  searchDocumentsMock.mockReset();
  loadDocumentSetsMock.mockReset();
  window.localStorage.clear();
  document.documentElement.classList.remove("dark");
  document.documentElement.style.removeProperty("color-scheme");
  vi.unstubAllGlobals();
});

describe("SearchPage", () => {
  it("renders Search inside the authenticated application shell", async () => {
    await renderNewSession();

    expect(screen.getByRole("navigation", { name: "Primary navigation" })).toBeInTheDocument();
    // Document Search is its own sidebar entry; the header has no Chat/Search mode menu.
    expect(screen.getByRole("link", { name: "Search documents" })).toHaveAttribute(
      "href",
      "/search",
    );
    expect(screen.queryByRole("button", { name: /switch mode/ })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Search conversations" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Admin Panel" })).toHaveAttribute("href", "/admin");
    expect(screen.getByRole("heading", { name: "Search documents" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Search your workspace" })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Search documents" })).toBeEnabled();
    expect(screen.queryByRole("button", { name: "Add to search" })).not.toBeInTheDocument();
    // Without a Tenant speech-to-text provider there is no microphone, and no browser recognition fallback.
    expect(screen.queryByRole("button", { name: "Search by voice" })).not.toBeInTheDocument();
    expect(screen.getAllByRole("status").some((status) => status.textContent === "")).toBe(true);
    expect(screen.getByRole("button", { name: "Tenant owner" })).toBeInTheDocument();
  });

  it("adds the MemoryOS transcript to the controlled query without searching automatically", async () => {
    speechToTextAvailable(true);
    let options: VoiceDictationModule.VoiceDictationOptions | undefined;
    const dictation = {
      setMuted: vi.fn(),
      stop: vi.fn(async () => "nghỉ phép năm"),
      cancel: vi.fn(),
    };
    startVoiceDictationMock.mockImplementation(
      async (next: VoiceDictationModule.VoiceDictationOptions) => {
        options = next;
        return dictation;
      },
    );
    const user = userEvent.setup();
    await renderNewSession();

    const input = screen.getByRole("textbox", { name: "Search documents" });
    await user.type(input, "quy định");
    await user.click(await screen.findByRole("button", { name: "Search by voice" }));

    expect(await screen.findByRole("button", { name: "Stop voice search" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(screen.getByText("Listening… Speak now, then review your query.")).toBeVisible();
    expect(options?.language).toBe("en");

    act(() => options?.onInterim("nghỉ phép"));
    expect(input).toHaveValue("quy định nghỉ phép");

    await user.click(screen.getByRole("button", { name: "Stop voice search" }));
    await waitFor(() => expect(input).toHaveValue("quy định nghỉ phép năm"));
    expect(screen.getByRole("button", { name: "Search by voice" })).toHaveAttribute(
      "aria-pressed",
      "false",
    );
    expect(input).toHaveFocus();
    expect(searchDocumentsMock).not.toHaveBeenCalled();
  });

  it("explains how to recover when microphone permission is denied", async () => {
    speechToTextAvailable(true);
    startVoiceDictationMock.mockRejectedValue(
      new MicrophoneUnavailableError(true, new DOMException("denied", "NotAllowedError")),
    );
    const user = userEvent.setup();
    await renderNewSession();

    await user.click(await screen.findByRole("button", { name: "Search by voice" }));

    expect(
      await screen.findByText(
        "Microphone access is not allowed in this browser. Allow it, then try again.",
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
        totalResults: 1,
        candidateLimit: 500,
        results: [
          {
            documentId: "73835d74-d386-4b4e-b392-ad7f81e3b55a",
            generation: "6b780b3a-de22-4307-ace9-6c2f44e22fc1",
            title: "HR-2026 Quy định nghỉ phép",
            mediaType: "application/pdf",
            sourceTypes: [],
            authors: [],
            providerUrl: null,
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
    loadDocumentSetsMock.mockResolvedValue([
      {
        id: "d384ef32-9da5-4f80-84f6-b3d01f31aa3e",
        revision: 0,
        name: "People",
        description: "",
        sourceIds: [],
        userShares: [],
        groupShares: [],
        sources: [],
        permissions: { edit: false, share: false, delete: false, manage: false },
      },
    ]);
    await renderNewSession();

    await user.type(screen.getByRole("textbox", { name: "Search documents" }), "nghỉ phép");
    await user.click(screen.getByRole("button", { name: "Search" }));

    expect(await screen.findByRole("heading", { name: /^1 result for “/ })).toBeInTheDocument();
    expect(
      screen.queryByRole("complementary", { name: "File types on this page" }),
    ).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "File type: All file types" }));
    await user.click(screen.getByRole("menuitemradio", { name: "PDF" }));

    await waitFor(() => expect(searchDocumentsMock).toHaveBeenCalledTimes(2));
    expect(searchDocumentsMock.mock.calls.at(-1)?.[0]).toMatchObject({
      body: { query: "nghỉ phép", mediaTypes: ["application/pdf"], page: 0 },
    });
    await user.click(await screen.findByRole("button", { name: "File type: PDF" }));
    await user.click(screen.getByRole("menuitemradio", { name: "Word document" }));
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
    await user.click(screen.getByRole("button", { name: "Document Sets: All Document Sets" }));
    await user.click(screen.getByRole("menuitemradio", { name: "People" }));
    await waitFor(() => expect(searchDocumentsMock).toHaveBeenCalledTimes(5));
    expect(searchDocumentsMock.mock.calls.at(-1)?.[0]).toMatchObject({
      body: {
        query: "nghỉ phép",
        documentSetIds: ["d384ef32-9da5-4f80-84f6-b3d01f31aa3e"],
        page: 0,
      },
    });

    expect(screen.getByRole("button", { name: "Updated: Past 30 days" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Clear filters" }));
    expect(screen.getByRole("button", { name: "File type: All file types" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Updated: All time" })).toBeInTheDocument();
  }, 10_000);

  it("narrows results to one connector from the Source rail and clears it with the filters", async () => {
    const user = userEvent.setup();
    searchDocumentsMock.mockResolvedValue({
      data: {
        page: 0,
        hasMore: false,
        totalResults: 3,
        candidateLimit: 500,
        sourceFacets: {
          total: 3,
          types: [
            { type: "FILE", count: 2 },
            { type: "GOOGLE_DRIVE", count: 2 },
          ],
        },
        results: [
          {
            documentId: "73835d74-d386-4b4e-b392-ad7f81e3b55a",
            generation: "6b780b3a-de22-4307-ace9-6c2f44e22fc1",
            title: "Báo cáo tài chính bán niên",
            mediaType: "application/pdf",
            sourceTypes: ["FILE", "GOOGLE_DRIVE"],
            authors: [],
            providerUrl: null,
            updatedAt: "2026-09-08T00:00:00Z",
            score: 0.8,
            sections: [],
          },
        ],
      },
    });
    await renderNewSession();

    await user.type(screen.getByRole("textbox", { name: "Search documents" }), "báo cáo");
    await user.click(screen.getByRole("button", { name: "Search" }));

    const rail = await screen.findByRole("complementary", { name: "Source" });
    expect(within(rail).getByRole("button", { name: "All sources: 3 results" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(within(rail).getByRole("button", { name: "Uploaded files: 2 results" })).toBeVisible();
    await user.click(within(rail).getByRole("button", { name: "Google Drive: 2 results" }));

    await waitFor(() => expect(searchDocumentsMock).toHaveBeenCalledTimes(2));
    expect(searchDocumentsMock.mock.calls.at(-1)?.[0]).toMatchObject({
      body: { query: "báo cáo", sourceTypes: ["GOOGLE_DRIVE"], page: 0 },
    });
    expect(
      await within(rail).findByRole("button", { name: "Google Drive: 2 results" }),
    ).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "Source: Google Drive" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Clear filters" }));
    const clearedRail = await screen.findByRole("complementary", { name: "Source" });
    expect(
      within(clearedRail).getByRole("button", { name: "All sources: 3 results" }),
    ).toHaveAttribute("aria-pressed", "true");
    // The unfiltered request is still cached, so clearing the connector reuses it.
    expect(searchDocumentsMock).toHaveBeenCalledTimes(2);
  });

  it("keeps the Source rail and lists connectors without results when a filter leaves one", async () => {
    const user = userEvent.setup();
    searchDocumentsMock.mockResolvedValue({
      data: {
        page: 0,
        hasMore: false,
        totalResults: 1,
        candidateLimit: 500,
        sourceFacets: { total: 1, types: [{ type: "FILE", count: 1 }] },
        results: [],
      },
    });
    await renderNewSession();

    await user.type(screen.getByRole("textbox", { name: "Search documents" }), "sổ tay");
    await user.click(screen.getByRole("button", { name: "Search" }));

    const rail = await screen.findByRole("complementary", { name: "Source" });
    expect(within(rail).getByRole("button", { name: "Uploaded files: 1 results" })).toBeEnabled();
    expect(within(rail).getByRole("button", { name: "Google Drive: 0 results" })).toBeDisabled();
  });

  it("uses the loading screen for a repeated search immediately", async () => {
    const user = userEvent.setup();
    searchDocumentsMock.mockResolvedValueOnce({
      data: {
        page: 0,
        hasMore: false,
        totalResults: 1,
        candidateLimit: 500,
        results: [
          {
            documentId: "73835d74-d386-4b4e-b392-ad7f81e3b55a",
            generation: "6b780b3a-de22-4307-ace9-6c2f44e22fc1",
            title: "Existing policy document",
            mediaType: "application/pdf",
            sourceTypes: [],
            authors: [],
            providerUrl: null,
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
    expect(screen.queryByRole("button", { name: "Cancel" })).not.toBeInTheDocument();

    resolveSecondSearch({
      data: {
        page: 0,
        hasMore: false,
        totalResults: 0,
        candidateLimit: 500,
        results: [],
      },
    });
    await waitFor(() => expect(screen.getByRole("button", { name: "Search" })).toBeEnabled());
  });

  it("preselects a landing file type and reruns this actor's recent search with the real total", async () => {
    const user = userEvent.setup();
    const key = `memoryos:search:recent:${OWNER_SESSION.actorId}`;
    window.localStorage.setItem(key, JSON.stringify(["hợp đồng", "nghỉ phép"]));
    window.localStorage.setItem("memoryos:search:recent:someone-else", JSON.stringify(["private"]));
    searchDocumentsMock.mockResolvedValue({
      data: {
        page: 0,
        hasMore: true,
        totalResults: 23,
        candidateLimit: 500,
        results: [
          {
            documentId: "73835d74-d386-4b4e-b392-ad7f81e3b55a",
            generation: "6b780b3a-de22-4307-ace9-6c2f44e22fc1",
            title: "HR-2026 Quy định nghỉ phép",
            mediaType: "application/pdf",
            sourceTypes: [],
            authors: [],
            providerUrl: null,
            updatedAt: "2026-09-08T00:00:00Z",
            score: 0.8,
            sections: [],
          },
        ],
      },
    });
    await renderNewSession();

    const recent = screen.getByRole("region", { name: "Recent searches" });
    expect(within(recent).queryByText("private")).not.toBeInTheDocument();
    const pdf = within(screen.getByRole("group", { name: "File type" })).getByRole("button", {
      name: /PDF/,
    });
    await user.click(pdf);
    expect(pdf).toHaveAttribute("aria-pressed", "true");
    // A chip only preselects the filter; searching still needs a query.
    expect(searchDocumentsMock).not.toHaveBeenCalled();

    await user.click(within(recent).getByRole("button", { name: "nghỉ phép" }));

    await waitFor(() => expect(searchDocumentsMock).toHaveBeenCalledTimes(1));
    expect(searchDocumentsMock.mock.calls[0]?.[0]).toMatchObject({
      body: { query: "nghỉ phép", mediaTypes: ["application/pdf"], page: 0, pageSize: 10 },
    });
    expect(await screen.findByRole("heading", { name: /^23 results for “/ })).toBeInTheDocument();
    expect(screen.getByText("Showing 1–1 of 23")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Next" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();
    expect(screen.getByRole("textbox", { name: "Search documents" })).toHaveValue("nghỉ phép");
    expect(JSON.parse(window.localStorage.getItem(key) ?? "[]")).toEqual(["nghỉ phép", "hợp đồng"]);
  });

  it("clears recent searches and tolerates unreadable storage", async () => {
    const user = userEvent.setup();
    const key = `memoryos:search:recent:${OWNER_SESSION.actorId}`;
    window.localStorage.setItem(key, JSON.stringify(["hợp đồng"]));
    await renderNewSession();

    await user.click(screen.getByRole("button", { name: "Clear all" }));

    expect(screen.queryByRole("region", { name: "Recent searches" })).not.toBeInTheDocument();
    expect(window.localStorage.getItem(key)).toBeNull();
    expect(screen.getByRole("textbox", { name: "Search documents" })).toHaveFocus();

    window.localStorage.setItem(key, "{not json");
    await renderNewSession();
    expect(screen.queryByRole("region", { name: "Recent searches" })).not.toBeInTheDocument();
  });

  it("removes owner administration affordances for a member", async () => {
    await renderNewSession({
      ...OWNER_SESSION,
      tenant: { ...OWNER_SESSION.tenant, role: "MEMBER" },
      capabilities: [
        "SYSTEM_BASIC",
        "SEARCH_READ",
        "CHAT_READ",
        "CHAT_WRITE",
        "IMAGE_GENERATE",
        "LLM_GATEWAY_USE",
      ],
      scopedCapabilities: [],
    });

    expect(screen.getByRole("button", { name: "Tenant member" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Admin Panel" })).not.toBeInTheDocument();
  });

  it("denies Search without global SEARCH_READ and sends no Search or reader requests", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    await renderNewSession({
      ...OWNER_SESSION,
      capabilities: ["USERS_MANAGE", "SOURCES_READ", "SOURCES_MANAGE", "SOURCES_DELETE"],
      scopedCapabilities: ["SEARCH_READ"],
    });

    expect(screen.getByRole("alert")).toHaveTextContent("Search access denied");
    expect(screen.queryByRole("textbox", { name: "Search documents" })).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Document passages" })).not.toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
    expect(searchDocumentsMock).not.toHaveBeenCalled();
  });
  it("routes user-only administrators to users", async () => {
    await renderNewSession({
      ...OWNER_SESSION,
      capabilities: [
        "SYSTEM_BASIC",
        "SEARCH_READ",
        "CHAT_READ",
        "CHAT_WRITE",
        "IMAGE_GENERATE",
        "LLM_GATEWAY_USE",
        "USERS_MANAGE",
      ],
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
      capabilities: [
        "SYSTEM_BASIC",
        "SEARCH_READ",
        "CHAT_READ",
        "CHAT_WRITE",
        "IMAGE_GENERATE",
        "LLM_GATEWAY_USE",
      ],
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

    expect(await screen.findByRole("alert")).toBeVisible();
    expect(screen.getByRole("button", { name: "Sign out" })).toBeEnabled();
  });
});
