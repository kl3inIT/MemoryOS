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
import { HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import type { ApplicationSession } from "@/features/identity/application-session-context";
import { ApplicationSessionProvider } from "@/features/identity/application-session-provider";
import {
  handleCreateChatPersona,
  handleListAvailableChatModels,
  handleListChatPersonaLabels,
  handleListChatPersonaSources,
  handleListDocumentSets,
  handleListMcpConnections,
} from "@/lib/hey-api/msw.gen";
import type { ApiProblem, PersonaInput, PersonaView } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { AgentEditorPage } from "./agent-editor";

const SESSION: ApplicationSession = {
  actorId: "0f2f5e6e-4e6c-4d55-9c07-6b0b1d4b39a4",
  authorizationVersion: 1,
  uiLanguage: "en",
  tenant: { displayName: "Tasco", role: "MEMBER" },
  capabilities: ["SYSTEM_BASIC", "CHAT_READ", "CHAT_WRITE", "AGENTS_CREATE"],
  scopedCapabilities: [],
};
const draftKey = `memoryos.agent-draft.${SESSION.actorId}`;

async function renderEditor() {
  const rootRoute = createRootRoute();
  const route = createRoute({
    getParentRoute: () => rootRoute,
    path: "/agents/create",
    component: () => (
      <ApplicationSessionProvider session={SESSION}>
        <AgentEditorPage />
      </ApplicationSessionProvider>
    ),
  });
  const listRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: "/agents",
    component: () => <p>Agent catalog</p>,
  });
  const router = createRouter({
    routeTree: rootRoute.addChildren([route, listRoute]),
    history: createMemoryHistory({ initialEntries: ["/agents/create"] }),
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
    handleListChatPersonaSources({ body: [] }),
    handleListDocumentSets({ body: [] }),
    handleListAvailableChatModels({ body: [] }),
    handleListChatPersonaLabels({ body: [] }),
    handleListMcpConnections({ body: [] }),
  );
});
afterEach(() => window.localStorage.clear());

describe("AgentEditorPage", () => {
  it("creates an agent from the edited fields and forgets the browser draft", async () => {
    let sent: PersonaInput | undefined;
    server.use(
      handleCreateChatPersona(async ({ request }) => {
        sent = await request.json();
        const created: PersonaView = {
          ...sent,
          id: "70000000-0000-4000-8000-000000000099",
          revision: 0,
        };
        return HttpResponse.json(created, { status: 201 });
      }),
    );
    const user = await renderEditor();
    const create = screen.getByRole("button", { name: "Create assistant" });
    expect(create).toBeDisabled();

    await user.type(screen.getByRole("textbox", { name: "Assistant name" }), " Law ");
    await user.type(screen.getByRole("textbox", { name: /Reminder for every turn/ }), "Cite");
    await user.click(screen.getByRole("switch", { name: "Create image" }));
    await user.click(create);

    expect(await screen.findByText("Agent catalog")).toBeVisible();
    expect(sent).toMatchObject({
      name: "Law",
      taskPrompt: "Cite",
      starterPrompts: [],
      tools: ["search", "web_search", "code_interpreter"],
      modelConfigurationId: null,
      iconName: "bot",
    });
    expect(window.localStorage.getItem(draftKey)).toBeNull();
  }, 15_000);

  it("restores an unsaved new agent from this browser until the draft is discarded", async () => {
    window.localStorage.setItem(
      draftKey,
      JSON.stringify({
        name: "Contract law",
        description: "",
        iconName: "legal",
        avatarFileId: null,
        keepAvatar: false,
        labelIds: [],
        instructions: "",
        taskPrompt: "",
        starterPrompts: [],
        sourceIds: [],
        documentSetIds: [],
        fileIds: [],
        knowledgeCutoff: "",
        tools: ["search"],
        mcpServerIds: [],
        modelConfigurationId: "",
        contextTokenLimit: "",
        outputTokenLimit: "",
        replaceBaseSystemPrompt: false,
      }),
    );
    const user = await renderEditor();

    expect(await screen.findByText("Restored the draft you were creating.")).toBeVisible();
    const name = screen.getByRole("textbox", { name: "Assistant name" });
    expect(name).toHaveValue("Contract law");

    await user.click(screen.getByRole("button", { name: "Discard draft" }));

    expect(name).toHaveValue("");
    expect(screen.queryByText("Restored the draft you were creating.")).not.toBeInTheDocument();
    expect(window.localStorage.getItem(draftKey)).toBeNull();
  });

  it("places the API's field violation on its control", async () => {
    const problem: ApiProblem = {
      title: "Bad Request",
      status: 400,
      detail: "Invalid agent",
      instance: "/api/chat/personas",
      errors: [{ field: "name", message: "size", code: "SIZE", params: { min: 1, max: 200 } }],
    };
    server.use(handleCreateChatPersona(() => HttpResponse.json(problem, { status: 400 })));
    const user = await renderEditor();

    const name = screen.getByRole("textbox", { name: "Assistant name" });
    await user.type(name, "Contract law");
    await user.click(screen.getByRole("button", { name: "Create assistant" }));

    expect(await screen.findByText("Check the highlighted fields and try again.")).toBeVisible();
    expect(name).toHaveAttribute("aria-invalid", "true");
  });
});
