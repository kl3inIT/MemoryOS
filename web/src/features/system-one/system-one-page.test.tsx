import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vitest";
import type { ApplicationSession } from "@/features/identity/application-session-context";
import { ApplicationSessionProvider } from "@/features/identity/application-session-provider";
import type {
  InstalledAdapter,
  ManagedModel,
  ManagedProvider,
} from "@/features/models/model-catalog";
import {
  handleCreateSystemOneConnection,
  handleDeleteSystemOneConnection,
  handleListChatModelFlows,
  handleListChatProviderAdapters,
  handleListChatProviders,
  handleListConfiguredChatModels,
  handleListSystemOneConnections,
  handleListSystemOneTypes,
  handleSetChatModelFlow,
  handleSetSystemOneTask,
  handleTestSystemOneConnection,
} from "@/lib/hey-api/msw.gen";
import type {
  ModelFlow,
  SystemOneConnection,
  SystemOneConnectionRequest,
  SystemOneType,
} from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { SystemOnePage } from "./system-one-page";

const manager: ApplicationSession = {
  actorId: "5d0c6c57-4a3f-4d0e-9d62-0b7d0f8c1f11",
  displayName: null,
  authorizationVersion: 1,
  uiLanguage: "en",
  tenant: { displayName: "Fixture", role: "MEMBER" },
  capabilities: ["MODELS_MANAGE"],
  scopedCapabilities: [],
};

const types: SystemOneType[] = [
  {
    provider: "TYPESAFE",
    requiresKey: true,
    endpoint: "FIXED",
    defaultModel: "jev-latest",
    inputPrice: 0.042,
  },
  {
    provider: "CLOUDFLARE",
    requiresKey: true,
    endpoint: "ACCOUNT",
    defaultModel: "clef-flash",
    inputPrice: 0.09,
  },
  { provider: "NINEROUTER", requiresKey: true, endpoint: "URL", defaultModel: "" },
  { provider: "LAYA", requiresKey: false, endpoint: "URL", defaultModel: "auto", inputPrice: 0 },
  { provider: "SYSTEMONE_COMPATIBLE", requiresKey: false, endpoint: "URL", defaultModel: "" },
];

const nineRouter: SystemOneConnection = {
  id: "7a1f0c52-0d3e-4c57-9a55-2f3c1b6d8e01",
  provider: "NINEROUTER",
  name: "9Router Jev",
  endpoint: "http://9router.internal:20128/v1",
  model: "openrouter/typesafe/jev-1.13",
  credentialConfigured: true,
  dataBoundary: "EXTERNAL",
  inputPrice: 0.042,
  revision: 2,
};
const serving: SystemOneConnection = {
  id: "7a1f0c52-0d3e-4c57-9a55-2f3c1b6d8e02",
  provider: "SYSTEMONE_COMPATIBLE",
  name: "Serving",
  endpoint: "http://serving.internal:18100/v1",
  model: "clef-flash",
  credentialConfigured: false,
  dataBoundary: "INTERNAL",
  revision: 1,
};

const adapter: InstalledAdapter = {
  type: "openai",
  credentialRequirement: "REQUIRED",
  tokenizerProfiles: [{ id: "openai-o200k-v1", displayName: "OpenAI" }],
  nativeWebSearch: true,
  knownModels: [],
};
const provider: ManagedProvider = {
  id: "00000000-0000-0000-0000-000000000001",
  name: "OpenCode Go",
  adapterType: "openai",
  baseUrl: "http://inference.test/v1",
  enabled: true,
  isPublic: true,
  groupIds: [],
  personaIds: [],
  credentialConfigured: true,
  revision: 3,
  dataBoundary: "EXTERNAL",
};
const model: ManagedModel = {
  id: "00000000-0000-0000-0000-000000000003",
  tenantId: "00000000-0000-0000-0000-000000000004",
  providerId: provider.id,
  modelName: "deepseek-v4-flash",
  displayName: "DeepSeek V4 Flash",
  visible: true,
  revision: 7,
  settings: {
    contextWindow: 128000,
    maxOutputTokens: 8192,
    tokenizerProfile: "openai-o200k-v1",
    capabilities: { streaming: true, toolCalling: true, vision: false, reasoning: false },
    options: {},
    pricing: null,
  },
};

function check(values: Partial<ModelFlow>): ModelFlow {
  return {
    flow: "CHAT_GUARDRAIL",
    modelConfigurationId: null,
    available: true,
    reasoningEffort: "OFF",
    revision: 4,
    ...values,
  };
}

function mount(session = manager) {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <ApplicationSessionProvider session={session}>
        <SystemOnePage />
      </ApplicationSessionProvider>
    </QueryClientProvider>,
  );
}

let flow: ModelFlow;
let saved: SystemOneConnection[];
let sent: {
  created: { provider: string; body: SystemOneConnectionRequest }[];
  tasks: unknown[];
  models: (string | null)[];
  deleted: string[];
};

beforeEach(() => {
  flow = check({ modelConfigurationId: model.id });
  saved = [nineRouter, serving];
  sent = { created: [], tasks: [], models: [], deleted: [] };
  server.use(
    handleListSystemOneTypes({ body: types }),
    handleListSystemOneConnections(() => HttpResponse.json(saved)),
    handleListChatModelFlows(() => HttpResponse.json([flow])),
    handleListChatProviders({ body: [provider] }),
    handleListChatProviderAdapters({ body: [adapter] }),
    handleListConfiguredChatModels({ body: [model] }),
    handleSetSystemOneTask(async ({ request }) => {
      const body = (await request.json()) as { connectionId: string };
      sent.tasks.push(body);
      flow = check({ systemOneConnectionId: body.connectionId, revision: flow.revision + 1 });
      return HttpResponse.json(flow);
    }),
    handleSetChatModelFlow(({ request }) => {
      const chosen = new URL(request.url).searchParams.get("modelConfigurationId");
      sent.models.push(chosen);
      flow = check({ modelConfigurationId: chosen, revision: flow.revision + 1 });
      return HttpResponse.json(flow);
    }),
    handleDeleteSystemOneConnection(({ params }) => {
      sent.deleted.push(params.id);
      saved = saved.filter((connection) => connection.id !== params.id);
      return new HttpResponse(null, { status: 204 });
    }),
  );
});

describe("System One administration", () => {
  it("lists the connections with where they point and offers every type", async () => {
    mount();

    const connections = within(
      await screen.findByRole("region", { name: "Available connections" }),
    );
    const gateway = within(connections.getByRole("listitem", { name: "9Router Jev" }));
    expect(
      gateway.getByText("9router.internal:20128 · openrouter/typesafe/jev-1.13"),
    ).toBeVisible();
    expect(
      within(connections.getByRole("listitem", { name: "Serving" })).getByText(
        "serving.internal:18100 · clef-flash",
      ),
    ).toBeVisible();
    const add = within(screen.getByRole("region", { name: "Add a connection" }));
    expect(add.getAllByRole("listitem")).toHaveLength(5);
    expect(add.getByRole("listitem", { name: "Cloudflare" })).toBeVisible();
  });

  it("runs the question check on a connection as soon as one is chosen, and on a model again", async () => {
    const user = userEvent.setup();
    mount();

    const trigger = await screen.findByRole("combobox", { name: "Question check runs on" });
    await waitFor(() => expect(trigger).toHaveTextContent("DeepSeek V4 Flash"));
    await user.click(trigger);
    await user.click(await screen.findByRole("option", { name: /9Router Jev/ }));

    await waitFor(() => expect(sent.tasks).toEqual([{ connectionId: nineRouter.id, revision: 4 }]));
    await waitFor(() => expect(trigger).toHaveTextContent("9Router Jev"));
    const connections = within(screen.getByRole("region", { name: "Available connections" }));
    expect(
      await within(connections.getByRole("listitem", { name: "9Router Jev" })).findByText("Active"),
    ).toBeVisible();
    await user.click(trigger);
    await user.click(await screen.findByRole("option", { name: /DeepSeek V4 Flash/ }));
    await waitFor(() => expect(sent.models).toEqual([model.id]));
    await waitFor(() => expect(trigger).toHaveTextContent("DeepSeek V4 Flash"));
  });

  it("adds a Cloudflare connection with its account, default model and published price", async () => {
    server.use(
      handleCreateSystemOneConnection(async ({ params, request }) => {
        const body = (await request.json()) as SystemOneConnectionRequest;
        sent.created.push({ provider: params.provider, body });
        const created: SystemOneConnection = {
          id: "7a1f0c52-0d3e-4c57-9a55-2f3c1b6d8e03",
          provider: "CLOUDFLARE",
          name: body.name,
          endpoint: body.endpoint,
          model: body.model,
          credentialConfigured: true,
          dataBoundary: body.dataBoundary,
          inputPrice: body.inputPrice,
          revision: 0,
        };
        saved = [...saved, created];
        return HttpResponse.json(created);
      }),
    );
    const user = userEvent.setup();
    mount();

    const add = within(await screen.findByRole("region", { name: "Add a connection" }));
    await user.click(add.getByRole("button", { name: "Connect Cloudflare" }));
    const dialog = within(await screen.findByRole("dialog", { name: "Connect Cloudflare" }));
    expect(dialog.getByLabelText("Model")).toHaveValue("clef-flash");
    expect(dialog.getByLabelText("Input price (USD / 1M tokens)")).toHaveValue("0.09");
    await user.type(dialog.getByLabelText("Account ID"), "3f9a1c0b7d2e4a56b8c9d0e1f2a3b4c5");
    await user.type(dialog.getByLabelText("API key"), "synthetic-key");
    await user.click(dialog.getByRole("button", { name: "Connect" }));

    await waitFor(() =>
      expect(sent.created).toEqual([
        {
          provider: "CLOUDFLARE",
          body: {
            name: "Cloudflare",
            endpoint: "3f9a1c0b7d2e4a56b8c9d0e1f2a3b4c5",
            model: "clef-flash",
            credentialAction: "REPLACE",
            credentialValue: "synthetic-key",
            dataBoundary: "EXTERNAL",
            inputPrice: 0.09,
            revision: 0,
          },
        },
      ]),
    );
    expect(
      await within(screen.getByRole("region", { name: "Available connections" })).findByRole(
        "listitem",
        {
          name: "Cloudflare",
        },
      ),
    ).toBeVisible();
  });

  it("tests a saved connection from its settings and reports a service that does not answer", async () => {
    server.use(
      handleTestSystemOneConnection(() =>
        HttpResponse.json(
          { code: "CHAT_PROVIDER_UNAVAILABLE", status: 503, title: "Unavailable" },
          { status: 503, headers: { "Content-Type": "application/problem+json" } },
        ),
      ),
    );
    const user = userEvent.setup();
    mount();

    const connections = within(
      await screen.findByRole("region", { name: "Available connections" }),
    );
    await user.click(
      within(connections.getByRole("listitem", { name: "Serving" })).getByRole("button", {
        name: "Configure Serving",
      }),
    );
    const dialog = within(await screen.findByRole("dialog", { name: "Configure Serving" }));
    expect(dialog.queryByLabelText("Account ID")).not.toBeInTheDocument();
    expect(dialog.getByLabelText("Endpoint")).toHaveValue("http://serving.internal:18100/v1");
    await user.click(dialog.getByRole("button", { name: "Test connection" }));
    expect(
      await dialog.findByText(
        "The System One service could not be reached, rejected the key or gave no answer. Check the address, key and model, then try again.",
      ),
    ).toBeVisible();
  });

  it("deletes a connection the task does not run on", async () => {
    const user = userEvent.setup();
    mount();

    const connections = within(
      await screen.findByRole("region", { name: "Available connections" }),
    );
    await user.click(connections.getByRole("button", { name: "Delete connection Serving" }));
    const confirm = within(await screen.findByRole("alertdialog"));
    await user.click(confirm.getByRole("button", { name: "Delete connection" }));
    await waitFor(() => expect(sent.deleted).toEqual([serving.id]));
    await waitFor(() =>
      expect(connections.queryByRole("listitem", { name: "Serving" })).not.toBeInTheDocument(),
    );
  });

  it("is refused to a member who does not manage models", () => {
    mount({ ...manager, capabilities: [] });
    expect(screen.getByRole("alert")).toHaveTextContent(
      "You do not have permission to manage models.",
    );
  });
});
