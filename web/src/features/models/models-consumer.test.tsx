import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";
import type * as RouterModule from "@tanstack/react-router";
import { createMemoryOsQueryClient } from "@/lib/query-client";
import { getCurrentIdentityQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import { ApplicationSessionBoundary } from "@/features/identity/application-session-boundary";
import type { CurrentIdentity } from "@/lib/hey-api/types.gen";
import { ModelEditor } from "./model-editor";
import { ModelDefaults } from "./model-defaults";
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

describe("provider secret lifetime and coherent Access", () => {
  it("consumes the key outside Query caches and ignores a write that finishes after closing", async () => {
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
    expect(await requests[0]!.clone().json()).toMatchObject({
      credential: { action: "REPLACE", value: "synthetic-secret-not-for-storage" },
    });
    expect(requests[0]!.headers.get("X-MemoryOS-CSRF")).toBe("1");
    expect(client.getMutationCache().getAll()).toHaveLength(0);
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
    expect(client.getQueryCache().getAll()).toHaveLength(0);
    expect(screen.queryByText(/Provider saved/)).not.toBeInTheDocument();
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
    expect(new URL(writes[0]!.url).searchParams.get("revision")).toBe("4");
    expect(await writes[0]!.clone().json()).toMatchObject({
      name: "My unsaved rename",
      isPublic: true,
      groupIds: newer.groupIds,
      personaIds: newer.personaIds,
      credential: { action: "KEEP" },
    });
    expect((await writes[0]!.clone().json()).credential).not.toHaveProperty("value");
    client.clear();
  });

  it("requires explicitly disabling a required-key provider before REMOVE and omits the value", async () => {
    const writes: Request[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (request: Request) => {
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
    expect(await writes[0]!.clone().json()).toMatchObject({
      enabled: false,
      credential: { action: "REMOVE" },
    });
    expect((await writes[0]!.clone().json()).credential).not.toHaveProperty("value");
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
    expect(client.getMutationCache().getAll()).toHaveLength(0);
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
    const editProvider = await screen.findByRole("button", { name: "Edit provider" });
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
    const remove = await screen.findByRole("button", { name: "Delete provider" });
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

describe("Persona saved selection", () => {
  it("labels a saved hidden selection and sends Inherit with its own revision and no model ID", async () => {
    let selected: { personaId: string; modelConfigurationId: string | null; revision: number } = {
      personaId,
      modelConfigurationId: model.id,
      revision: 47,
    };
    const writes: Request[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (request: Request) => {
        const path = new URL(request.url).pathname;
        if (request.method === "PUT") {
          writes.push(request);
          selected = { ...selected, modelConfigurationId: null, revision: 48 };
          return Response.json(selected);
        }
        if (path === "/api/chat/model-personas")
          return Response.json({ items: [{ id: personaId, name: "Builtin" }], nextCursor: null });
        if (path === "/api/chat/model-default")
          return Response.json({ modelConfigurationId: model.id, revision: 12 });
        return Response.json(selected);
      }),
    );
    const client = createMemoryOsQueryClient();
    render(
      <QueryClientProvider client={client}>
        <ModelDefaults
          providers={[provider]}
          models={[{ ...model, visible: false }]}
          adapters={[adapter]}
        />
      </QueryClientProvider>,
    );
    const selector = await screen.findByLabelText("Persona", { exact: true });
    fireEvent.change(selector, { target: { value: personaId } });
    const defaultSelector = await screen.findByLabelText("Persona model default");
    expect(defaultSelector).toHaveValue(model.id);
    expect(
      within(defaultSelector).getByRole("option", {
        name: /Saved model.*saved; hidden or unavailable/,
      }),
    ).toBeDisabled();
    fireEvent.change(defaultSelector, { target: { value: "" } });
    fireEvent.click(screen.getByRole("button", { name: "Save Persona default" }));
    await waitFor(() => expect(writes).toHaveLength(1));
    const url = new URL(writes[0]!.url);
    expect(url.searchParams.get("revision")).toBe("47");
    expect(url.searchParams.has("modelConfigurationId")).toBe(false);
    expect(writes[0]!.headers.get("X-MemoryOS-CSRF")).toBe("1");
    await waitFor(() => expect(defaultSelector).toHaveValue(""));
    client.clear();
  });
});
