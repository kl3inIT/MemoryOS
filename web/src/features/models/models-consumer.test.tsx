import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClientProvider, type QueryClient } from "@tanstack/react-query";
import { afterEach, assert, describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";
import type * as RouterModule from "@tanstack/react-router";
import { ApiError } from "@/lib/api";
import { createMemoryOsQueryClient } from "@/lib/query-client";
import { getCurrentIdentityQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import { ApplicationSessionBoundary } from "@/features/identity/application-session-boundary";
import type { CurrentIdentity } from "@/lib/hey-api/types.gen";
import { ModelEditor } from "./model-editor";
import { ModelsPage } from "./models-page";
import { ProviderEditor } from "./provider-editor";
import type { InstalledAdapter, ManagedModel, ManagedProvider } from "./model-catalog";

vi.mock("@tanstack/react-router", async (importOriginal) => ({
  ...(await importOriginal<typeof RouterModule>()),
  Link: ({ children, to }: { children: ReactNode; to: string }) => <a href={to}>{children}</a>,
}));

const adapter: InstalledAdapter = {
  type: "openai",
  credentialRequirement: "REQUIRED",
  tokenizerProfiles: [{ id: "openai-o200k-v1", displayName: "OpenAI" }],
  nativeWebSearch: true,
  knownModels: [],
};
const provider: ManagedProvider = {
  id: "00000000-0000-0000-0000-000000000001",
  name: "Private connection",
  adapterType: "openai",
  baseUrl: "http://inference.test/v1",
  enabled: true,
  isPublic: false,
  groupIds: ["00000000-0000-0000-0000-000000000005"],
  personaIds: [],
  credentialConfigured: true,
  revision: 3,
  dataBoundary: "EXTERNAL",
};
const model: ManagedModel = {
  id: "00000000-0000-0000-0000-000000000003",
  tenantId: "00000000-0000-0000-0000-000000000004",
  providerId: provider.id,
  modelName: "served",
  displayName: "Saved model",
  visible: true,
  revision: 7,
  settings: {
    contextWindow: 1024,
    maxOutputTokens: 128,
    tokenizerProfile: "openai-o200k-v1",
    capabilities: { streaming: true, toolCalling: false, vision: false, reasoning: false },
    options: {},
    pricing: null,
  },
};
const session: CurrentIdentity = {
  actorId: "00000000-0000-0000-0000-000000000008",
  authorizationVersion: 1,
  capabilities: ["MODELS_MANAGE"],
  scopedCapabilities: [],
  tenant: { displayName: "Manager", role: "MEMBER" },
  uiLanguage: "en",
};
const personaId = "00000000-0000-0000-0000-000000000009";

function deferredResponse() {
  let resolve!: (value: Response) => void;
  const promise = new Promise<Response>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

afterEach(() => vi.unstubAllGlobals());

/** Everything a catalog mutation keeps: its variables, data, context and the error with its cause. */
function retainedMutations(client: QueryClient) {
  const mutations = client.getMutationCache().getAll();
  for (const { state } of mutations)
    expect(state.variables === undefined || state.variables instanceof AbortSignal).toBe(true);
  return JSON.stringify(
    mutations.map(({ state }) => ({
      ...state,
      variables: undefined,
      error: state.error && { message: state.error.message, cause: state.error.cause },
      failureReason: state.failureReason && {
        message: state.failureReason.message,
        cause: state.failureReason.cause,
      },
    })),
  );
}

describe("provider connection check", () => {
  it("checks the typed key before saving and names a rejected key", async () => {
    const bodies: unknown[] = [];
    let accept = true;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (request: Request) => {
        if (request.method === "GET") return Response.json({ items: [], totalPages: 1 });
        bodies.push(await request.clone().json());
        return accept
          ? Response.json({ modelCount: 146, latencyMillis: 420 })
          : Response.json(
              { code: "CHAT_PROVIDER_CREDENTIAL_REJECTED", detail: "account acct-42" },
              { status: 400 },
            );
      }),
    );
    const client = createMemoryOsQueryClient();
    render(
      <QueryClientProvider client={client}>
        <ProviderEditor providers={[]} adapters={[adapter]} onClose={() => {}} />
      </QueryClientProvider>,
    );
    const test = screen.getByRole("button", { name: "Test connection" });
    expect(test).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Endpoint URL"), {
      target: { value: "https://9router.test/v1" },
    });
    fireEvent.change(screen.getByLabelText("API key"), { target: { value: "typed-key" } });
    fireEvent.click(test);
    expect(await screen.findByRole("status")).toHaveTextContent(
      "Connection succeeded · 420 ms · 146 models",
    );
    expect(bodies[0]).toMatchObject({
      adapterType: "openai",
      baseUrl: "https://9router.test/v1",
      credential: { action: "REPLACE", value: "typed-key" },
    });
    // The typed key stays for saving, and editing the draft clears the earlier result.
    expect(screen.getByLabelText("API key")).toHaveValue("typed-key");
    accept = false;
    fireEvent.change(screen.getByLabelText("API key"), { target: { value: "wrong-key" } });
    expect(screen.queryByRole("status")).toBeNull();
    fireEvent.click(test);
    expect(await screen.findByRole("alert")).toHaveTextContent("The provider rejected the API key");
    expect(screen.queryByText(/acct-42/)).toBeNull();
  });
});

describe("models listed inside the provider form", () => {
  it("lists the endpoint's models with the typed key and creates the chosen ones on one save", async () => {
    const requests: { url: string; body: unknown }[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (request: Request) => {
        const url = new URL(request.url).pathname;
        if (request.method === "GET") return Response.json({ items: [], totalPages: 1 });
        requests.push({ url, body: await request.clone().json() });
        if (url.endsWith("/reported-models"))
          return Response.json({
            models: [
              {
                modelName: "gpt-5-mini",
                contextWindow: 272000,
                maxOutputTokens: 128000,
                capabilities: { toolCalling: true, vision: true, reasoning: false },
                pricing: { inputPerMillion: 0.25, outputPerMillion: 2 },
                source: "provider",
              },
              {
                modelName: "local-llama",
                contextWindow: 32000,
                maxOutputTokens: null,
                capabilities: { toolCalling: true, vision: false, reasoning: false },
                pricing: null,
                source: "none",
              },
            ],
          });
        if (url.endsWith("/models")) return Response.json({ ...model, id: crypto.randomUUID() });
        return Response.json({ ...provider, id: provider.id, revision: 1 }, { status: 201 });
      }),
    );
    const client = createMemoryOsQueryClient();
    render(
      <QueryClientProvider client={client}>
        <ProviderEditor providers={[]} adapters={[adapter]} onClose={() => {}} />
      </QueryClientProvider>,
    );

    const list = screen.getByRole("button", { name: "List models" });
    expect(list).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Provider name"), { target: { value: "Local" } });
    fireEvent.change(screen.getByLabelText("Endpoint URL"), {
      target: { value: "https://9router.test/v1" },
    });
    fireEvent.change(screen.getByLabelText("API key"), { target: { value: "typed-key" } });
    fireEvent.click(list);

    expect(await screen.findByText("gpt-5-mini")).toBeVisible();
    // The listing is read with the key the administrator just typed, before the provider exists.
    expect(requests[0]).toMatchObject({
      url: "/api/chat/providers/reported-models",
      body: {
        baseUrl: "https://9router.test/v1",
        credential: { action: "REPLACE", value: "typed-key" },
      },
    });
    expect(requests[0]?.body).not.toHaveProperty("providerId", expect.anything());
    // A model the endpoint publishes nothing for is selectable and marked as taking the default window.
    expect(screen.getAllByText("Default").length).toBeGreaterThan(0);

    fireEvent.click(screen.getByRole("checkbox", { name: /gpt-5-mini/ }));
    fireEvent.click(screen.getByRole("button", { name: "Save provider" }));

    await waitFor(() => expect(requests).toHaveLength(3));
    expect(requests[1]?.url).toBe("/api/chat/providers");
    expect(requests[2]).toMatchObject({
      url: `/api/chat/providers/${provider.id}/models`,
      body: { modelName: "gpt-5-mini", settings: { contextWindow: 272000 } },
    });
    // No request body, and so no key, is retained in a Query cache.
    expect(
      JSON.stringify(
        client
          .getQueryCache()
          .getAll()
          .map((entry) => entry.state.data),
      ),
    ).not.toContain("typed-key");
  });
});

describe("provider secret lifetime and coherent Access", () => {
  it("consumes the key outside Query caches and ignores a write that finishes after closing", async () => {
    const pending = deferredResponse();
    const requests: Request[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (request: Request) => {
        // The Access picker also reads Group options; only mutations are counted here.
        if (request.method === "GET") return Response.json({ items: [], totalPages: 1 });
        requests.push(request);
        return pending.promise;
      }),
    );
    const client = createMemoryOsQueryClient();
    const view = render(
      <QueryClientProvider client={client}>
        <ProviderEditor
          initial={provider}
          providers={[provider]}
          adapters={[adapter]}
          onClose={() => view.unmount()}
        />
      </QueryClientProvider>,
    );
    fireEvent.change(screen.getByLabelText("Credential action"), { target: { value: "REPLACE" } });
    fireEvent.change(screen.getByLabelText("API key"), {
      target: { value: "synthetic-secret-not-for-storage" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save provider" }));
    await waitFor(() => expect(requests).toHaveLength(1));
    expect(screen.getByLabelText("API key")).toHaveValue("");
    expect(await requests[0]?.clone().json()).toMatchObject({
      credential: { action: "REPLACE", value: "synthetic-secret-not-for-storage" },
    });
    expect(requests[0]?.headers.get("X-MemoryOS-CSRF")).toBe("1");
    // The write in flight is a mutation, but its variables are only its AbortSignal.
    expect(client.getMutationCache().getAll()).toHaveLength(1);
    expect(retainedMutations(client)).not.toContain("synthetic-secret-not-for-storage");
    expect(
      JSON.stringify(
        client
          .getQueryCache()
          .getAll()
          .map((query) => query.state),
      ),
    ).not.toContain("synthetic-secret-not-for-storage");
    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    await act(async () => {
      pending.resolve(Response.json({ ...provider, revision: 4 }));
    });
    expect(requests).toHaveLength(1);
    expect(
      JSON.stringify(
        client
          .getQueryCache()
          .getAll()
          .map((query) => query.state),
      ),
    ).not.toContain("synthetic-secret-not-for-storage");
    expect(screen.queryByText(/Provider saved/)).not.toBeInTheDocument();
    // Closing discarded the write, and nothing observes it any more, so it is collected at once.
    await waitFor(() => expect(client.getMutationCache().getAll()).toHaveLength(0));
    client.clear();
  });

  it("cannot attach a refetched revision to old Access and requires explicit whole-baseline reconciliation", async () => {
    const newer = {
      ...provider,
      revision: 4,
      isPublic: true,
      groupIds: ["00000000-0000-0000-0000-000000000006"],
      personaIds: [personaId],
    };
    const writes: Request[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (request: Request) => {
        if (request.method === "GET") return Response.json([newer]);
        writes.push(request);
        return Response.json({ ...newer, revision: 5 });
      }),
    );
    const client = createMemoryOsQueryClient();
    const view = render(
      <QueryClientProvider client={client}>
        <ProviderEditor
          initial={provider}
          providers={[provider]}
          adapters={[adapter]}
          onClose={() => undefined}
        />
      </QueryClientProvider>,
    );
    fireEvent.change(screen.getByLabelText("Provider name"), {
      target: { value: "My unsaved rename" },
    });
    view.rerender(
      <QueryClientProvider client={client}>
        <ProviderEditor
          initial={provider}
          providers={[newer]}
          adapters={[adapter]}
          onClose={() => undefined}
        />
      </QueryClientProvider>,
    );
    expect(screen.getByRole("button", { name: "Save provider" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Reconcile saved provider" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Save provider" })).toBeEnabled(),
    );
    expect(screen.getByLabelText("Provider name")).toHaveValue("My unsaved rename");
    fireEvent.click(screen.getByRole("button", { name: "Save provider" }));
    await waitFor(() => expect(writes).toHaveLength(1));
    const [write] = writes;
    assert.isDefined(write);
    expect(new URL(write.url).searchParams.get("revision")).toBe("4");
    const body = await write.clone().json();
    expect(body).toMatchObject({
      name: "My unsaved rename",
      isPublic: true,
      groupIds: newer.groupIds,
      personaIds: newer.personaIds,
      credential: { action: "KEEP" },
    });
    expect(body.credential).not.toHaveProperty("value");
    client.clear();
  });

  it("requires explicitly disabling a required-key provider before REMOVE and omits the value", async () => {
    const writes: Request[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (request: Request) => {
        // The Access picker also reads Group options; only mutations are counted here.
        if (request.method === "GET") return Response.json({ items: [], totalPages: 1 });
        writes.push(request);
        return Response.json({
          ...provider,
          enabled: false,
          credentialConfigured: false,
          revision: 4,
        });
      }),
    );
    const client = createMemoryOsQueryClient();
    render(
      <QueryClientProvider client={client}>
        <ProviderEditor
          initial={provider}
          providers={[provider]}
          adapters={[adapter]}
          onClose={() => undefined}
        />
      </QueryClientProvider>,
    );
    fireEvent.change(screen.getByLabelText("Credential action"), { target: { value: "REMOVE" } });
    expect(screen.getByRole("button", { name: "Save provider" })).toBeDisabled();
    fireEvent.click(screen.getByLabelText("Provider enabled"));
    fireEvent.click(screen.getByRole("button", { name: "Save provider" }));
    await waitFor(() => expect(writes).toHaveLength(1));
    const [write] = writes;
    assert.isDefined(write);
    const body = await write.clone().json();
    expect(body).toMatchObject({ enabled: false, credential: { action: "REMOVE" } });
    expect(body.credential).not.toHaveProperty("value");
    client.clear();
  });

  it("retains only the non-secret draft after 409 and does not permit retry after failed reconciliation", async () => {
    let writes = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (request: Request) => {
        if (request.method === "GET") return new Response(null, { status: 503 });
        writes += 1;
        return Response.json({ detail: "synthetic-sensitive-provider-payload" }, { status: 409 });
      }),
    );
    const client = createMemoryOsQueryClient();
    render(
      <QueryClientProvider client={client}>
        <ProviderEditor
          initial={provider}
          providers={[provider]}
          adapters={[adapter]}
          onClose={() => undefined}
        />
      </QueryClientProvider>,
    );
    fireEvent.change(screen.getByLabelText("Provider name"), {
      target: { value: "Retained rename" },
    });
    fireEvent.change(screen.getByLabelText("Credential action"), { target: { value: "REPLACE" } });
    fireEvent.change(screen.getByLabelText("API key"), {
      target: { value: "synthetic-conflicted-key" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save provider" }));
    fireEvent.click(await screen.findByRole("button", { name: "Reconcile saved provider" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Reconcile saved provider" })).toBeEnabled(),
    );
    expect(screen.getByRole("button", { name: "Save provider" })).toBeDisabled();
    expect(screen.getByLabelText("Provider name")).toHaveValue("Retained rename");
    expect(screen.getByLabelText("API key")).toHaveValue("");
    expect(screen.queryByText("synthetic-sensitive-provider-payload")).not.toBeInTheDocument();
    expect(writes).toBe(1);
    // The failed reconciliation stays observed for its feedback, keeping only its status and safe message.
    const failures = client
      .getMutationCache()
      .getAll()
      .map(({ state }) => state.error);
    expect(failures).toHaveLength(1);
    expect(failures[0]).toBeInstanceOf(ApiError);
    expect(failures[0]).toMatchObject({ status: 503, cause: undefined });
    expect(failures[0]?.message).toMatch(/^The provider could not be acquired/);
    const retained = retainedMutations(client);
    expect(retained).not.toContain("synthetic-sensitive-provider-payload");
    expect(retained).not.toContain("synthetic-conflicted-key");
    client.clear();
  });
});

describe("saved validation races", () => {
  it.each(["provider", "model"] as const)(
    "discards reachability when the %s revision changes during the check",
    async (changed) => {
      const pending = deferredResponse();
      const reads: string[] = [];
      vi.stubGlobal(
        "fetch",
        vi.fn(async (request: Request) => {
          if (request.method === "POST") return pending.promise;
          reads.push(request.url);
          return new URL(request.url).pathname.endsWith("/models")
            ? Response.json([{ ...model, revision: changed === "model" ? 8 : 7 }])
            : Response.json([{ ...provider, revision: changed === "provider" ? 4 : 3 }]);
        }),
      );
      const client = createMemoryOsQueryClient();
      render(
        <QueryClientProvider client={client}>
          <ModelEditor
            initial={model}
            models={[model]}
            provider={provider}
            adapter={adapter}
            onClose={() => undefined}
          />
        </QueryClientProvider>,
      );
      fireEvent.click(screen.getByRole("button", { name: "Validate saved connection" }));
      await act(async () => {
        pending.resolve(Response.json({ reachable: true, failureCode: null }));
      });
      await waitFor(() =>
        expect(screen.getByRole("button", { name: "Validate saved connection" })).toBeEnabled(),
      );
      expect(reads.some((url) => new URL(url).pathname === "/api/chat/providers")).toBe(true);
      expect(
        reads.some((url) => new URL(url).pathname.endsWith(`/providers/${provider.id}/models`)),
      ).toBe(true);
      expect(screen.queryByText(/Saved connection reached/)).not.toBeInTheDocument();
      client.clear();
    },
  );

  it("discards a late validation after local edits, even when the saved revisions still match", async () => {
    const pending = deferredResponse();
    const requests: Request[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (request: Request) => {
        requests.push(request);
        return pending.promise;
      }),
    );
    const client = createMemoryOsQueryClient();
    render(
      <QueryClientProvider client={client}>
        <ModelEditor
          initial={model}
          models={[model]}
          provider={provider}
          adapter={adapter}
          onClose={() => undefined}
        />
      </QueryClientProvider>,
    );
    fireEvent.click(screen.getByRole("button", { name: "Validate saved connection" }));
    await waitFor(() => expect(requests).toHaveLength(1));
    fireEvent.change(screen.getByLabelText("Display name"), {
      target: { value: "Unsubmitted edit" },
    });
    await act(async () => {
      pending.resolve(Response.json({ reachable: true, failureCode: null }));
    });
    expect(screen.queryByText(/Saved connection reached/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Validate saved connection" })).toBeDisabled();
    expect(requests).toHaveLength(1);
    client.clear();
  });
});

describe("model manager authority", () => {
  it("purges catalog and secret draft on same-actor revocation and ignores the late write", async () => {
    let identity = session;
    const pending = deferredResponse();
    const writes: Request[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (request: Request) => {
        const path = new URL(request.url).pathname;
        if (path === "/api/identity/me") return Response.json(identity);
        if (request.method === "PUT") {
          writes.push(request);
          return pending.promise;
        }
        if (path === "/api/chat/providers") return Response.json([provider]);
        if (path === "/api/chat/provider-adapters") return Response.json([adapter]);
        if (path.endsWith(`/providers/${provider.id}/models`)) return Response.json([model]);
        if (path === "/api/chat/model-default")
          return Response.json({ modelConfigurationId: model.id, revision: 1 });
        if (path === "/api/chat/model-flows")
          return Response.json([
            { flow: "CHAT_NAMING", modelConfigurationId: null, available: true, revision: 1 },
          ]);
        if (path === "/api/chat/model-personas")
          return Response.json({ items: [], nextCursor: null });
        throw new Error(`Unexpected synthetic route: ${path}`);
      }),
    );
    const client = createMemoryOsQueryClient();
    render(
      <QueryClientProvider client={client}>
        <ApplicationSessionBoundary>
          <ModelsPage />
        </ApplicationSessionBoundary>
      </QueryClientProvider>,
    );
    const editProvider = await screen.findByRole("button", { name: /Edit provider/ });
    await waitFor(() => expect(editProvider).toBeEnabled());
    fireEvent.click(editProvider);
    fireEvent.change(screen.getByLabelText("Credential action"), { target: { value: "REPLACE" } });
    fireEvent.change(screen.getByLabelText("API key"), {
      target: { value: "revoked-session-secret" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save provider" }));
    await waitFor(() => expect(writes).toHaveLength(1));
    identity = { ...session, authorizationVersion: 2, capabilities: [] };
    await act(async () => {
      await client.invalidateQueries({ queryKey: getCurrentIdentityQueryKey() });
    });
    expect(
      await screen.findByRole("heading", { name: /don’t have access to this area/ }),
    ).toBeInTheDocument();
    await act(async () => {
      pending.resolve(Response.json({ ...provider, revision: 4 }));
    });
    expect(screen.queryByLabelText("API key")).not.toBeInTheDocument();
    expect(screen.queryByText("Private connection")).not.toBeInTheDocument();
    expect(
      JSON.stringify(
        client
          .getQueryCache()
          .getAll()
          .map((query) => query.state.data),
      ),
    ).not.toContain("Private connection");
    expect(client.getMutationCache().getAll()).toHaveLength(0);
    client.clear();
  });

  it("aborts a write in flight and drops the key draft when the page is hidden", async () => {
    const pending = deferredResponse();
    const writes: Request[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (request: Request) => {
        const path = new URL(request.url).pathname;
        if (path === "/api/identity/me") return Response.json(session);
        if (request.method === "PUT") {
          writes.push(request);
          return pending.promise;
        }
        if (path === "/api/chat/providers") return Response.json([provider]);
        if (path === "/api/chat/provider-adapters") return Response.json([adapter]);
        if (path.endsWith(`/providers/${provider.id}/models`)) return Response.json([model]);
        if (path === "/api/chat/model-default")
          return Response.json({ modelConfigurationId: model.id, revision: 1 });
        if (path === "/api/chat/model-flows")
          return Response.json([
            { flow: "CHAT_NAMING", modelConfigurationId: null, available: true, revision: 1 },
          ]);
        if (path === "/api/chat/model-personas")
          return Response.json({ items: [], nextCursor: null });
        return Response.json({ items: [], totalPages: 1 });
      }),
    );
    const client = createMemoryOsQueryClient();
    render(
      <QueryClientProvider client={client}>
        <ApplicationSessionBoundary>
          <ModelsPage />
        </ApplicationSessionBoundary>
      </QueryClientProvider>,
    );
    const editProvider = await screen.findByRole("button", { name: /Edit provider/ });
    await waitFor(() => expect(editProvider).toBeEnabled());
    fireEvent.click(editProvider);
    fireEvent.change(screen.getByLabelText("Credential action"), { target: { value: "REPLACE" } });
    fireEvent.change(screen.getByLabelText("API key"), {
      target: { value: "hidden-page-secret" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save provider" }));
    await waitFor(() => expect(writes).toHaveLength(1));
    act(() => {
      window.dispatchEvent(new Event("pagehide"));
    });
    // The unmount is synchronous, so the request is aborted before the page can be cached.
    expect(writes[0]?.signal.aborted).toBe(true);
    expect(screen.queryByLabelText("API key")).not.toBeInTheDocument();
    await act(async () => {
      pending.resolve(Response.json({ ...provider, revision: 4 }));
    });
    await waitFor(() => expect(client.getMutationCache().getAll()).toHaveLength(0));
    expect(screen.queryByText(/Provider saved/)).not.toBeInTheDocument();
    client.clear();
  });

  it("mounts no protected catalog request for a denied manager deep link", async () => {
    const paths: string[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (request: Request) => {
        paths.push(new URL(request.url).pathname);
        return Response.json({ ...session, capabilities: [] });
      }),
    );
    const client = createMemoryOsQueryClient();
    render(
      <QueryClientProvider client={client}>
        <ApplicationSessionBoundary>
          <ModelsPage />
        </ApplicationSessionBoundary>
      </QueryClientProvider>,
    );
    expect(
      await screen.findByRole("heading", { name: /don’t have access to this area/ }),
    ).toBeInTheDocument();
    expect(paths).toEqual(["/api/identity/me"]);
    client.clear();
  });
});

describe("provider data boundary", () => {
  it("marks a provider Internal only after confirmation and sends the boundary", async () => {
    const writes: unknown[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (request: Request) => {
        if (request.method === "PUT") {
          const body = await request.json();
          writes.push(body);
          return Response.json({ ...provider, ...body, revision: 4 });
        }
        return Response.json([provider]);
      }),
    );
    const client = createMemoryOsQueryClient();
    render(
      <QueryClientProvider client={client}>
        <ProviderEditor
          initial={provider}
          providers={[provider]}
          adapters={[adapter]}
          onClose={() => undefined}
        />
      </QueryClientProvider>,
    );
    const internal = screen.getByRole("radio", { name: /^Internal/ });
    fireEvent.click(internal);
    const confirm = await screen.findByRole("alertdialog");
    fireEvent.click(within(confirm).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    expect(screen.getByRole("radio", { name: /^External/ })).toBeChecked();
    fireEvent.click(screen.getByRole("radio", { name: /^Internal/ }));
    fireEvent.click(
      within(await screen.findByRole("alertdialog")).getByRole("button", {
        name: "Mark as Internal",
      }),
    );
    await waitFor(() => expect(screen.getByRole("radio", { name: /^Internal/ })).toBeChecked());
    fireEvent.click(screen.getByRole("button", { name: "Save provider" }));
    await waitFor(() => expect(writes).toHaveLength(1));
    expect(writes[0]).toMatchObject({ dataBoundary: "INTERNAL" });
    client.clear();
  });
});

describe("models by task", () => {
  it("names the model a task falls back to and sets it from Tenant-wide models", async () => {
    const publicProvider: ManagedProvider = { ...provider, isPublic: true, groupIds: [] };
    const writes: URL[] = [];
    let naming = {
      flow: "CHAT_NAMING",
      modelConfigurationId: null as string | null,
      available: true,
      revision: 1,
    };
    vi.stubGlobal(
      "fetch",
      vi.fn(async (request: Request) => {
        const url = new URL(request.url);
        const path = url.pathname;
        if (path === "/api/identity/me") return Response.json(session);
        if (request.method === "PUT" && path === "/api/chat/model-flows/CHAT_NAMING") {
          writes.push(url);
          naming = {
            ...naming,
            modelConfigurationId: url.searchParams.get("modelConfigurationId"),
            revision: naming.revision + 1,
          };
          return Response.json(naming);
        }
        if (path === "/api/chat/providers") return Response.json([publicProvider]);
        if (path === "/api/chat/provider-adapters") return Response.json([adapter]);
        if (path.endsWith(`/providers/${provider.id}/models`)) return Response.json([model]);
        if (path === "/api/chat/model-default")
          return Response.json({ modelConfigurationId: model.id, revision: 1 });
        if (path === "/api/chat/model-flows") return Response.json([naming]);
        if (path === "/api/chat/model-personas")
          return Response.json({ items: [], nextCursor: null });
        throw new Error(`Unexpected synthetic route: ${path}`);
      }),
    );
    const client = createMemoryOsQueryClient();
    render(
      <QueryClientProvider client={client}>
        <ApplicationSessionBoundary>
          <ModelsPage />
        </ApplicationSessionBoundary>
      </QueryClientProvider>,
    );
    const picker = await screen.findByRole("button", { name: "Conversation naming model" });
    expect(picker).toHaveTextContent("Choose an eligible model");
    expect(
      await screen.findByText(/^No model chosen; Saved model .* is used\.$/),
    ).toBeInTheDocument();
    expect(screen.getAllByText("External").length).toBeGreaterThan(0);
    fireEvent.click(picker);
    fireEvent.click(await screen.findByRole("button", { name: /Saved model/ }));
    fireEvent.click(screen.getByRole("button", { name: "Save task model" }));
    await waitFor(() => expect(writes).toHaveLength(1));
    expect(writes[0]?.searchParams.get("modelConfigurationId")).toBe(model.id);
    expect(writes[0]?.searchParams.get("revision")).toBe("1");
    expect(await screen.findByText("Task model saved.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Conversation naming model" })).toHaveTextContent(
      "Saved model",
    );
    expect(screen.queryByText(/^No model chosen/)).not.toBeInTheDocument();
    client.clear();
  });
});

describe("provider deletion reconciliation", () => {
  it("closes a successful deletion while the provider-list refresh is delayed", async () => {
    let deleted = false;
    const refreshed = deferredResponse();
    vi.stubGlobal(
      "fetch",
      vi.fn(async (request: Request) => {
        const path = new URL(request.url).pathname;
        if (path === "/api/identity/me") return Response.json(session);
        if (request.method === "DELETE") {
          deleted = true;
          return new Response(null, { status: 204 });
        }
        if (path === "/api/chat/providers")
          return deleted ? refreshed.promise : Response.json([provider]);
        if (path === "/api/chat/provider-adapters") return Response.json([adapter]);
        if (path.endsWith(`/providers/${provider.id}/models`))
          return deleted ? new Response(null, { status: 404 }) : Response.json([model]);
        if (path === "/api/chat/model-default")
          return Response.json({ modelConfigurationId: null, revision: 1 });
        if (path === "/api/chat/model-flows")
          return Response.json([
            { flow: "CHAT_NAMING", modelConfigurationId: null, available: true, revision: 1 },
          ]);
        if (path === "/api/chat/model-personas")
          return Response.json({ items: [], nextCursor: null });
        throw new Error(`Unexpected synthetic route: ${path}`);
      }),
    );
    const client = createMemoryOsQueryClient();
    render(
      <QueryClientProvider client={client}>
        <ApplicationSessionBoundary>
          <ModelsPage />
        </ApplicationSessionBoundary>
      </QueryClientProvider>,
    );
    const remove = await screen.findByRole("button", { name: /Delete provider/ });
    await waitFor(() => expect(remove).toBeEnabled());
    fireEvent.click(remove);
    const dialog = await screen.findByRole("alertdialog");
    fireEvent.click(within(dialog).getByRole("button", { name: "Delete configuration" }));
    await waitFor(() => expect(deleted).toBe(true));
    await act(async () => {
      refreshed.resolve(Response.json([]));
    });
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    expect(screen.queryByText(provider.name, { exact: true })).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    client.clear();
  });
});
