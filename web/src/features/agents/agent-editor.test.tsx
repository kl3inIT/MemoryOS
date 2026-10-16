import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState, type ReactNode } from "react";
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from "@tanstack/react-router";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AppShellHeaderSlot } from "@/components/app-shell/app-shell-header-slot";
import type { ApplicationSession } from "@/features/identity/application-session-context";
import { ApplicationSessionProvider } from "@/features/identity/application-session-provider";
import {
  handleCreateChatPersona,
  handleGetChatPersona,
  handleListChatPersonaLabels,
  handleListChatPersonaModels,
  handleListChatPersonaSources,
  handleListDocumentSets,
  handleListMcpConnections,
  handleShareChatPersona,
  handleUpdateChatPersona,
} from "@/lib/hey-api/msw.gen";
import type { ApiProblem, PersonaInput, PersonaView, SharingInput } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { AgentCreateDialog } from "./agent-create-dialog";
import { AgentEditorPage } from "./agent-editor";
import type { AgentEditorTab } from "./agent-editor-search";

const SESSION: ApplicationSession = {
  actorId: "0f2f5e6e-4e6c-4d55-9c07-6b0b1d4b39a4",
  displayName: null,
  authorizationVersion: 1,
  uiLanguage: "en",
  tenant: { displayName: "Tasco", role: "MEMBER" },
  capabilities: ["SYSTEM_BASIC", "CHAT_READ", "CHAT_WRITE", "AGENTS_CREATE"],
  scopedCapabilities: [],
};
const agentId = "70000000-0000-4000-8000-000000000001";

function saved(overrides: Partial<PersonaView> = {}): PersonaView {
  return {
    id: agentId,
    revision: 3,
    builtin: false,
    permissions: {
      edit: true,
      share: true,
      setPublic: true,
      delete: true,
      transfer: false,
      leave: false,
      manage: false,
    },
    name: "Contract law",
    title: "Contract lawyer",
    description: "Reviews clauses against the issued templates.",
    instructions: "Quote the template number.",
    starterPrompts: [],
    sourceIds: [],
    sources: [],
    documentSetIds: [],
    documentSets: [],
    tools: ["search", "web_search"],
    mcpServers: [],
    fileIds: [],
    avatarSeed: "contract-law",
    hasAvatar: false,
    labels: [],
    owner: { actor: { actorId: SESSION.actorId, name: "Đạt Phan", email: "dat@tasco.vn" } },
    userShares: [],
    groupShares: [],
    isPublic: false,
    publicPermission: "VIEWER",
    listed: true,
    featured: false,
    grounded: false,
    ...overrides,
  };
}

/** The shell's header, where the editor puts its way back, its state and Lưu. */
function Shell({ children }: { children: ReactNode }) {
  const [slot, setSlot] = useState<HTMLElement | null>(null);
  return (
    <AppShellHeaderSlot value={slot}>
      <header ref={setSlot} />
      {children}
    </AppShellHeaderSlot>
  );
}

/** The saved assistant the API answers a write with. */
function echo(sent: PersonaInput, overrides: Partial<PersonaView>): PersonaView {
  return saved({
    ...sent,
    title: sent.title ?? undefined,
    greeting: sent.greeting ?? undefined,
    avatarSeed: sent.avatarSeed ?? "contract-law",
    ...overrides,
  });
}

async function renderEditor(initialTab: AgentEditorTab = "config") {
  const rootRoute = createRootRoute();
  const route = createRoute({
    getParentRoute: () => rootRoute,
    path: "/agents/$agentId/edit",
    component: function Page() {
      const [tab, setTab] = useState(initialTab);
      return (
        <ApplicationSessionProvider session={SESSION}>
          <Shell>
            <AgentEditorPage agentId={agentId} tab={tab} onTabChange={setTab} />
          </Shell>
        </ApplicationSessionProvider>
      );
    },
  });
  const listRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: "/agents",
    component: () => <p>Agent catalog</p>,
  });
  const router = createRouter({
    routeTree: rootRoute.addChildren([route, listRoute]),
    history: createMemoryHistory({ initialEntries: [`/agents/${agentId}/edit`] }),
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
  server.use(
    handleGetChatPersona({ body: saved() }),
    handleListChatPersonaSources({ body: [] }),
    handleListDocumentSets({ body: [] }),
    handleListChatPersonaModels({ body: [] }),
    handleListChatPersonaLabels({ body: [] }),
    handleListMcpConnections({ body: [] }),
  );
});

afterEach(() => vi.restoreAllMocks());

describe("AgentEditorPage", () => {
  it("saves in place, then starts again from what the server kept", async () => {
    let sent: PersonaInput | undefined;
    let revision: string | null = null;
    server.use(
      handleUpdateChatPersona(async ({ request }) => {
        sent = await request.json();
        revision = new URL(request.url).searchParams.get("revision");
        return HttpResponse.json(echo(sent, { revision: 4 }));
      }),
    );
    const user = await renderEditor();
    const name = await screen.findByRole("textbox", { name: "Assistant name" });
    const save = () => screen.getByRole("button", { name: "Save" });
    expect(save()).toHaveAttribute("aria-disabled", "true");

    await user.clear(screen.getByRole("textbox", { name: "Title" }));
    await user.type(screen.getByRole("textbox", { name: "Title" }), "Senior contract lawyer");
    expect(screen.getByText("Unsaved changes")).toBeVisible();
    await user.click(save());

    expect(await screen.findByText("Saved")).toBeVisible();
    expect(name).toHaveValue("Contract law");
    expect(save()).toHaveAttribute("aria-disabled", "true");
    expect(screen.queryByText("Agent catalog")).not.toBeInTheDocument();
    expect(revision).toBe("3");
    expect(sent).toMatchObject({
      name: "Contract law",
      title: "Senior contract lawyer",
      tools: ["search", "web_search"],
      labelIds: [],
    });
  });

  it("turns document search off from the section's switch and stops adding documents", async () => {
    let sent: PersonaInput | undefined;
    server.use(
      handleUpdateChatPersona(async ({ request }) => {
        sent = await request.json();
        return HttpResponse.json(echo(sent, { revision: 4 }));
      }),
    );
    const user = await renderEditor();
    const add = await screen.findByRole("button", { name: "Add documents" });
    await waitFor(() => expect(add).toBeEnabled());
    await user.click(screen.getByRole("switch", { name: "Search internal documents" }));

    expect(screen.queryByRole("button", { name: "Add documents" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(sent?.tools).toEqual(["web_search"]));
  });

  it("places the API's title violation on its control", async () => {
    const problem: ApiProblem = {
      title: "Validation failed",
      status: 400,
      detail: "One or more request values are invalid.",
      instance: `/api/chat/personas/${agentId}`,
      code: "REQUEST_VALIDATION",
      errors: [{ field: "title", message: "size", code: "SIZE", params: { min: 0, max: 80 } }],
    };
    server.use(handleUpdateChatPersona(() => HttpResponse.json(problem, { status: 400 })));
    const user = await renderEditor();

    const title = await screen.findByRole("textbox", { name: "Title" });
    await user.type(title, " II");
    await user.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(title).toHaveAttribute("aria-invalid", "true"));
  });

  it("offers a reasoning level only for a model that reasons and sends the one chosen", async () => {
    let sent: PersonaInput | undefined;
    server.use(
      handleListChatPersonaModels({
        body: [
          model("10000000-0000-4000-8000-000000000001", "Fast answers", false, true),
          model("10000000-0000-4000-8000-000000000002", "Deep thinker", true, false),
        ],
      }),
      handleUpdateChatPersona(async ({ request }) => {
        sent = await request.json();
        return HttpResponse.json(echo(sent, { revision: 4 }));
      }),
    );
    const user = await renderEditor();

    const picker = await screen.findByRole("combobox", { name: "Default model" });
    await waitFor(() => expect(picker).toBeEnabled());
    await user.click(picker);
    expect(screen.queryByRole("radiogroup", { name: "Reasoning" })).not.toBeInTheDocument();
    await user.click(await screen.findByRole("option", { name: /Deep thinker/ }));
    await user.click(picker);
    const levels = await screen.findByRole("radiogroup", { name: "Reasoning" });
    expect(screen.getByRole("radio", { name: "System default" })).toBeChecked();
    await user.click(within(levels).getByRole("radio", { name: "High" }));
    await user.keyboard("{Escape}");
    await user.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(sent).toMatchObject({
        modelConfigurationId: "10000000-0000-4000-8000-000000000002",
        reasoningEffort: "HIGH",
      }),
    );
  }, 15_000);

  it("shows the test chat beside the form on a wide screen, following unsaved edits", async () => {
    vi.spyOn(window, "matchMedia").mockImplementation(
      (query: string) =>
        ({
          matches: query === "(min-width: 1024px)",
          media: query,
          onchange: null,
          addEventListener() {},
          removeEventListener() {},
          addListener() {},
          removeListener() {},
          dispatchEvent: () => false,
        }) as MediaQueryList,
    );
    const user = await renderEditor();
    const chat = await screen.findByRole("region", { name: "Test chat" }, { timeout: 15_000 });
    expect(await within(chat).findByRole("heading", { name: "Contract law" })).toBeVisible();
    expect(within(chat).getByText("Contract lawyer")).toBeVisible();
    expect(within(chat).queryByText("Using unsaved changes")).not.toBeInTheDocument();

    // jsdom lays everything out at 0×0, so a click would land on the panels' separator; type in place instead.
    const greeting = screen.getByRole("textbox", { name: "Greeting" });
    greeting.focus();
    await user.type(greeting, "Ask me about a clause.", { skipClick: true });
    expect(within(chat).getByText("Ask me about a clause.")).toBeVisible();
    expect(within(chat).getByText("Using unsaved changes")).toBeVisible();
    expect(screen.queryByRole("button", { name: "Test chat" })).not.toBeInTheDocument();
  }, 30_000);

  it("opens the test chat from a button on a narrow screen", async () => {
    const user = await renderEditor();
    await screen.findByRole("textbox", { name: "Assistant name" });
    expect(screen.queryByRole("region", { name: "Test chat" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Test chat" }));
    const chat = await screen.findByRole("region", { name: "Test chat" }, { timeout: 15_000 });
    expect(await within(chat).findByRole("heading", { name: "Contract law" })).toBeVisible();
  }, 30_000);

  it("saves a sharing change at once and keeps the configuration's next save in step", async () => {
    let shared: SharingInput | undefined;
    let updateRevision: string | null = null;
    server.use(
      handleShareChatPersona(async ({ request }) => {
        shared = await request.json();
        return HttpResponse.json(saved({ revision: 4, isPublic: true }));
      }),
      handleUpdateChatPersona(async ({ request }) => {
        updateRevision = new URL(request.url).searchParams.get("revision");
        const sent = await request.json();
        return HttpResponse.json(echo(sent, { revision: 5 }));
      }),
    );
    const user = await renderEditor("share");

    await user.selectOptions(
      await screen.findByRole("combobox", { name: "General access" }),
      "VIEWER",
    );
    await waitFor(() =>
      expect(shared).toEqual({
        users: [],
        groups: [],
        isPublic: true,
        publicPermission: "VIEWER",
      }),
    );

    await user.click(screen.getByRole("tab", { name: "Configure" }));
    await user.type(screen.getByRole("textbox", { name: "Title" }), " II");
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(updateRevision).toBe("4"));
  });
});

describe("AgentCreateDialog", () => {
  it("creates an assistant with the defaults of a new one and opens its editor", async () => {
    let sent: PersonaInput | undefined;
    server.use(
      handleCreateChatPersona(async ({ request }) => {
        sent = await request.json();
        return HttpResponse.json(
          echo(sent, { id: "70000000-0000-4000-8000-000000000099", revision: 0 }),
          { status: 201 },
        );
      }),
    );
    const rootRoute = createRootRoute();
    const listRoute = createRoute({
      getParentRoute: () => rootRoute,
      path: "/agents",
      component: () => (
        <ApplicationSessionProvider session={SESSION}>
          <AgentCreateDialog onClose={() => {}} />
        </ApplicationSessionProvider>
      ),
    });
    const editRoute = createRoute({
      getParentRoute: () => rootRoute,
      path: "/agents/$agentId/edit",
      component: () => <p>Editor opened</p>,
    });
    const router = createRouter({
      routeTree: rootRoute.addChildren([listRoute, editRoute]),
      history: createMemoryHistory({ initialEntries: ["/agents"] }),
    });
    await router.load();
    render(
      <QueryClientProvider client={new QueryClient()}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );
    const user = userEvent.setup();
    const create = await screen.findByRole("button", { name: "Create assistant" });
    expect(create).toBeDisabled();
    await user.type(screen.getByRole("textbox", { name: "Assistant name" }), " Law ");
    await user.type(screen.getByRole("textbox", { name: "Title" }), "Contract lawyer");
    await user.click(create);

    expect(await screen.findByText("Editor opened")).toBeVisible();
    expect(sent).toMatchObject({
      name: "Law",
      title: "Contract lawyer",
      tools: ["search", "web_search"],
      starterPrompts: [],
      grounded: false,
      avatarSeed: expect.stringMatching(/^[A-Za-z0-9_-]{1,64}$/),
    });
    expect(sent).not.toHaveProperty("reasoningEffort");
  });
});

function model(id: string, name: string, reasoning: boolean, isDefault: boolean) {
  return {
    id,
    providerId: "20000000-0000-4000-8000-000000000001",
    providerName: "Provider",
    modelName: name.toLowerCase().replace(" ", "-"),
    displayName: name,
    capabilities: {
      streaming: true,
      toolCalling: true,
      vision: false,
      reasoning,
      structuredOutput: false,
    },
    contextWindow: 128000,
    maxOutputTokens: 4096,
    pricing: null,
    isDefault,
  };
}
