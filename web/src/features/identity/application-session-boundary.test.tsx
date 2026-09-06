import { act, render, screen } from "@testing-library/react";
import { focusManager, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { ApiError } from "@/lib/api";
import { getCurrentIdentityQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { CurrentIdentity } from "@/lib/hey-api/types.gen";
import { createMemoryOsQueryClient } from "@/lib/query-client";
import { ApplicationSessionBoundary } from "./application-session-boundary";

const OWNER_SESSION: CurrentIdentity = {
  actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
  tenant: {
    displayName: "Tasco",
    role: "OWNER",
  },
  capabilities: ["INVITATIONS_MANAGE", "SOURCES_MANAGE"],
};

const MEMBER_SESSION: CurrentIdentity = {
  ...OWNER_SESSION,
  actorId: "97c41cb9-55ae-4a52-94ab-7aad59be91e5",
  tenant: { ...OWNER_SESSION.tenant!, role: "MEMBER" },
  capabilities: [],
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
      vi.fn(async () => Response.json({ ...MEMBER_SESSION, tenant: null })),
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
});

function SessionRole() {
  const role = useApplicationSession().tenant.role;
  return <span>{role}</span>;
}

function renderBoundary(queryClient: QueryClient) {
  return render(
    <QueryClientProvider client={queryClient}>
      <ApplicationSessionBoundary>
        <SessionRole />
      </ApplicationSessionBoundary>
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
