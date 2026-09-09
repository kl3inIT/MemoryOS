import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { focusManager, QueryClient, QueryClientProvider, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { ApiError } from "@/lib/api";
import { getCurrentIdentityQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { CurrentIdentity } from "@/lib/hey-api/types.gen";
import { createMemoryOsQueryClient } from "@/lib/query-client";
import { ApplicationSessionBoundary } from "./application-session-boundary";

const OWNER_SESSION: CurrentIdentity = {
  actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
  authorizationVersion: 1,
  tenant: {
    displayName: "Tasco",
    role: "OWNER",
  },
  capabilities: ["USERS_MANAGE", "SOURCES_READ", "SOURCES_MANAGE"],
  scopedCapabilities: [],
};

const MEMBER_SESSION: CurrentIdentity = {
  ...OWNER_SESSION,
  actorId: "97c41cb9-55ae-4a52-94ab-7aad59be91e5",
  tenant: { ...OWNER_SESSION.tenant!, role: "MEMBER" },
  capabilities: [],
  scopedCapabilities: [],
};

afterEach(() => {
  focusManager.setFocused(undefined);
  vi.unstubAllGlobals();
});

describe("ApplicationSessionBoundary", () => {
  it("provides the authenticated session to its child layout", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => Response.json(OWNER_SESSION)),
    );

    renderBoundary(createMemoryOsQueryClient());

    expect(await screen.findByText("OWNER")).toBeInTheDocument();
  });

  it("renders the provisioning state when durable membership is absent", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        Response.json({ ...MEMBER_SESSION, tenant: null, authorizationVersion: 0 }),
      ),
    );

    renderBoundary(createMemoryOsQueryClient());

    expect(
      await screen.findByRole("heading", { name: /don’t have access yet/i }),
    ).toBeInTheDocument();
  });

  it("keeps the accepted authority fingerprint across boundary remounts", async () => {
    let currentSession = OWNER_SESSION;
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => Response.json(currentSession)),
    );
    const queryClient = createMemoryOsQueryClient();
    const firstRender = renderBoundary(queryClient);
    expect(await screen.findByText("OWNER")).toBeInTheDocument();
    firstRender.unmount();

    queryClient.setQueryData(["private-actor-state"], { secret: true });
    queryClient.getMutationCache().build(queryClient, {
      mutationKey: ["private-actor-mutation"],
      mutationFn: async () => undefined,
    });
    queryClient.removeQueries({ queryKey: getCurrentIdentityQueryKey(), exact: true });
    currentSession = {
      ...MEMBER_SESSION,
      actorId: OWNER_SESSION.actorId,
    };

    renderBoundary(queryClient);

    expect(await screen.findByText("MEMBER")).toBeInTheDocument();
    expect(queryClient.getQueryData(["private-actor-state"])).toBeUndefined();
    expect(queryClient.getMutationCache().getAll()).toHaveLength(0);
  });
  it("drops mounted private data when authority revisions change without capability changes", async () => {
    let currentSession = OWNER_SESSION;
    let sourceVisible = true;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = input instanceof Request ? input.url : input.toString();
        if (url.endsWith("/api/identity/me")) return Response.json(currentSession);
        return sourceVisible ? new Response("Private Source") : new Response(null, { status: 404 });
      }),
    );
    const queryClient = createMemoryOsQueryClient();
    renderBoundary(queryClient, <PrivateSource />);
    expect(await screen.findByText("Private Source")).toBeInTheDocument();
    sourceVisible = false;
    currentSession = { ...OWNER_SESSION, authorizationVersion: 2 };

    await act(async () => {
      await queryClient.invalidateQueries({
        queryKey: getCurrentIdentityQueryKey(),
        exact: true,
      });
    });

    expect(await screen.findByText("Source unavailable")).toBeInTheDocument();
    expect(screen.queryByText("Private Source")).not.toBeInTheDocument();
  });

  it("remounts private children when the actor changes without changing authority", async () => {
    let currentSession = OWNER_SESSION;
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => Response.json(currentSession)),
    );
    const queryClient = createMemoryOsQueryClient();
    renderBoundary(queryClient, <ActorDraft />);
    expect(await screen.findByText(OWNER_SESSION.actorId)).toBeInTheDocument();

    fireEvent.change(screen.getByRole("textbox", { name: /private draft/i }), {
      target: { value: "owner's unfinished note" },
    });
    expect(screen.getByRole("textbox", { name: /private draft/i })).toHaveValue(
      "owner's unfinished note",
    );
    currentSession = {
      ...OWNER_SESSION,
      actorId: MEMBER_SESSION.actorId,
    };

    await act(async () => {
      await queryClient.invalidateQueries({
        queryKey: getCurrentIdentityQueryKey(),
        exact: true,
      });
    });

    expect(await screen.findByText(MEMBER_SESSION.actorId)).toBeInTheDocument();
    expect(screen.queryByText(OWNER_SESSION.actorId)).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: /private draft/i })).toHaveValue("");
  });

  it("refetches identity on browser focus even while the query is fresh", async () => {
    let currentSession = OWNER_SESSION;
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => Response.json(currentSession)),
    );
    const queryClient = createMemoryOsQueryClient();
    renderBoundary(queryClient);
    expect(await screen.findByText("OWNER")).toBeInTheDocument();

    currentSession = MEMBER_SESSION;
    await act(async () => {
      focusManager.setFocused(false);
      focusManager.setFocused(true);
    });

    expect(await screen.findByText("MEMBER")).toBeInTheDocument();
  });

  it("purges private client state when the identity query becomes unauthenticated", async () => {
    let authenticated = true;
    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        authenticated ? Response.json(OWNER_SESSION) : new Response(null, { status: 401 }),
      ),
    );
    const queryClient = createMemoryOsQueryClient();
    renderBoundary(queryClient);
    expect(await screen.findByText("OWNER")).toBeInTheDocument();
    seedPrivateClientState(queryClient);
    authenticated = false;

    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: getCurrentIdentityQueryKey() });
    });

    expect(
      await screen.findByRole("heading", { name: /sign in to memoryos/i }),
    ).toBeInTheDocument();
    expectPrivateClientStatePurged(queryClient);
  });

  it("resets active identity after a private query returns unauthenticated", async () => {
    let authenticated = true;
    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        authenticated ? Response.json(OWNER_SESSION) : new Response(null, { status: 401 }),
      ),
    );
    const queryClient = createMemoryOsQueryClient();
    renderBoundary(queryClient);
    expect(await screen.findByText("OWNER")).toBeInTheDocument();
    authenticated = false;

    await act(async () => {
      await expect(
        queryClient.fetchQuery({
          queryKey: ["private-query"],
          queryFn: async () => {
            throw new ApiError(401, new Error("expired"));
          },
          retry: false,
        }),
      ).rejects.toMatchObject({ status: 401 });
    });

    expect(
      await screen.findByRole("heading", { name: /sign in to memoryos/i }),
    ).toBeInTheDocument();
    expectPrivateClientStatePurged(queryClient);
  });

  it("resets active identity after a private mutation returns unauthenticated", async () => {
    let authenticated = true;
    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        authenticated ? Response.json(OWNER_SESSION) : new Response(null, { status: 401 }),
      ),
    );
    const queryClient = createMemoryOsQueryClient();
    renderBoundary(queryClient);
    expect(await screen.findByText("OWNER")).toBeInTheDocument();
    authenticated = false;
    const mutation = queryClient.getMutationCache().build(queryClient, {
      mutationKey: ["private-mutation"],
      mutationFn: async () => {
        throw new ApiError(401, new Error("expired"));
      },
    });

    await act(async () => {
      await expect(mutation.execute(undefined)).rejects.toMatchObject({ status: 401 });
    });

    expect(
      await screen.findByRole("heading", { name: /sign in to memoryos/i }),
    ).toBeInTheDocument();
    expectPrivateClientStatePurged(queryClient);
  });

  it.each(["query", "mutation"] as const)(
    "refreshes canonical identity and purges private state after a forbidden %s reveals revoked membership",
    async (operation) => {
      let currentSession: CurrentIdentity = OWNER_SESSION;
      let identityRequests = 0;
      vi.stubGlobal(
        "fetch",
        vi.fn(async () => {
          identityRequests += 1;
          return Response.json(currentSession);
        }),
      );
      const queryClient = createMemoryOsQueryClient();
      renderBoundary(queryClient);
      expect(await screen.findByText("OWNER")).toBeInTheDocument();
      seedPrivateClientState(queryClient);
      currentSession = {
        ...OWNER_SESSION,
        tenant: null,
        capabilities: [],
        authorizationVersion: 0,
        scopedCapabilities: [],
      };

      await act(async () => {
        if (operation === "query") {
          await expect(
            queryClient.fetchQuery({
              queryKey: ["forbidden-private-query"],
              queryFn: async () => {
                throw new ApiError(403, new Error("membership revoked"));
              },
              retry: false,
            }),
          ).rejects.toMatchObject({ status: 403 });
          return;
        }

        const mutation = queryClient.getMutationCache().build(queryClient, {
          mutationKey: ["forbidden-private-mutation"],
          mutationFn: async () => {
            throw new ApiError(403, new Error("membership revoked"));
          },
        });
        await expect(mutation.execute(undefined)).rejects.toMatchObject({ status: 403 });
      });

      expect(
        await screen.findByRole("heading", { name: /don’t have access yet/i }),
      ).toBeInTheDocument();
      expect(identityRequests).toBe(2);
      expectPrivateClientStatePurged(queryClient);
    },
  );

  it("refreshes an inactive fresh identity and purges private state after a forbidden mutation", async () => {
    let currentSession: CurrentIdentity = OWNER_SESSION;
    let identityRequests = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        identityRequests += 1;
        return Response.json(currentSession);
      }),
    );
    const queryClient = createMemoryOsQueryClient();
    const firstRender = renderBoundary(queryClient);
    expect(await screen.findByText("OWNER")).toBeInTheDocument();
    expect(identityRequests).toBe(1);
    seedPrivateClientState(queryClient);
    firstRender.unmount();

    const mutation = queryClient.getMutationCache().build(queryClient, {
      mutationKey: ["inactive-forbidden-private-mutation"],
      mutationFn: async () => {
        throw new ApiError(403, new Error("membership revoked"));
      },
    });
    await act(async () => {
      await expect(mutation.execute(undefined)).rejects.toMatchObject({ status: 403 });
    });
    expect(identityRequests).toBe(1);
    currentSession = {
      ...OWNER_SESSION,
      tenant: null,
      capabilities: [],
      authorizationVersion: 0,
      scopedCapabilities: [],
    };

    renderBoundary(queryClient);

    expect(
      await screen.findByRole("heading", { name: /don’t have access yet/i }),
    ).toBeInTheDocument();
    expect(identityRequests).toBe(2);
    expectPrivateClientStatePurged(queryClient);
  });

  it("keeps private state when an ordinary forbidden operation leaves identity unchanged", async () => {
    let identityRequests = 0;
    let operationAttempts = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        identityRequests += 1;
        return Response.json(OWNER_SESSION);
      }),
    );
    const queryClient = createMemoryOsQueryClient();
    renderBoundary(queryClient);
    expect(await screen.findByText("OWNER")).toBeInTheDocument();
    seedPrivateClientState(queryClient);

    await act(async () => {
      await expect(
        queryClient.fetchQuery({
          queryKey: ["ordinary-forbidden-query"],
          queryFn: async () => {
            operationAttempts += 1;
            throw new ApiError(403, new Error("not allowed"));
          },
        }),
      ).rejects.toMatchObject({ status: 403 });
    });

    await waitFor(() => expect(identityRequests).toBe(2));
    expect(operationAttempts).toBe(1);
    expect(screen.getByText("OWNER")).toBeInTheDocument();
    expect(queryClient.getQueryData(["private-actor-state"])).toEqual({ secret: true });
  });

  it("does not recursively refresh when the identity query itself returns forbidden", async () => {
    let identityForbidden = false;
    let identityRequests = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        identityRequests += 1;
        return identityForbidden
          ? new Response(null, { status: 403 })
          : Response.json(OWNER_SESSION);
      }),
    );
    const queryClient = createMemoryOsQueryClient();
    renderBoundary(queryClient);
    expect(await screen.findByText("OWNER")).toBeInTheDocument();
    identityForbidden = true;

    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: getCurrentIdentityQueryKey() });
    });

    expect(
      await screen.findByRole("heading", { name: /couldn’t confirm your session/i }),
    ).toBeInTheDocument();
    expect(identityRequests).toBe(2);
  });
});

function SessionRole() {
  const role = useApplicationSession().tenant.role;
  return <span>{role}</span>;
}

function PrivateSource() {
  const source = useQuery({
    queryKey: ["private-source"],
    queryFn: async () => {
      const response = await fetch("/api/private-source");
      return response.ok ? response.text() : null;
    },
  });
  return <span>{source.isPending ? "Loading Source" : (source.data ?? "Source unavailable")}</span>;
}

function ActorDraft() {
  const session = useApplicationSession();
  const [draft, setDraft] = useState("");
  return (
    <>
      <span>{session.actorId}</span>
      <label>
        Private draft
        <input value={draft} onChange={(event) => setDraft(event.target.value)} />
      </label>
    </>
  );
}

function renderBoundary(queryClient: QueryClient, children = <SessionRole />) {
  return render(
    <QueryClientProvider client={queryClient}>
      <ApplicationSessionBoundary>{children}</ApplicationSessionBoundary>
    </QueryClientProvider>,
  );
}

function seedPrivateClientState(queryClient: QueryClient) {
  queryClient.setQueryData(["private-actor-state"], { secret: true });
  queryClient.getMutationCache().build(queryClient, {
    mutationKey: ["private-actor-mutation"],
    mutationFn: async () => undefined,
  });
}

function expectPrivateClientStatePurged(queryClient: QueryClient) {
  expect(queryClient.getQueryData(["private-actor-state"])).toBeUndefined();
  expect(queryClient.getMutationCache().getAll()).toHaveLength(0);
}
