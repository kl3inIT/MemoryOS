import { act, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@/features/theme/theme-provider";
import { getCurrentIdentityQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { CurrentIdentity } from "@/lib/hey-api/types.gen";
import { ApplicationSessionBoundary } from "./application-session-boundary";

const OWNER_SESSION: CurrentIdentity = {
  actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
  organization: {
    displayName: "Tasco",
    role: "OWNER",
  },
  capabilities: ["INVITATIONS_MANAGE"],
};

const MEMBER_SESSION: CurrentIdentity = {
  ...OWNER_SESSION,
  actorId: "97c41cb9-55ae-4a52-94ab-7aad59be91e5",
  organization: { ...OWNER_SESSION.organization!, role: "MEMBER" },
  capabilities: [],
};

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("ApplicationSessionBoundary", () => {
  it("denies a member administration deep link without issuing invitation requests", async () => {
    let invitationRequests = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = input instanceof Request ? input.url : String(input);
        if (new URL(url).pathname.startsWith("/api/invitations")) invitationRequests += 1;
        return Response.json(MEMBER_SESSION);
      }),
    );

    renderBoundary(new QueryClient(), "invitations");

    expect(
      await screen.findByRole("heading", { name: "You don’t have access to this area." }),
    ).toBeInTheDocument();
    expect(invitationRequests).toBe(0);
  });

  it("renders the provisioning state when durable membership is absent", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => Response.json({ ...MEMBER_SESSION, organization: null })),
    );

    renderBoundary(new QueryClient());

    expect(
      await screen.findByRole("heading", { name: /don’t have access yet/i }),
    ).toBeInTheDocument();
  });

  it("clears prior actor queries and mutations before rendering a replacement actor", async () => {
    let currentSession = OWNER_SESSION;
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => Response.json(currentSession)),
    );
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false, staleTime: 0 } },
    });
    renderBoundary(queryClient);
    expect(await screen.findByRole("button", { name: "Organization owner" })).toBeInTheDocument();

    queryClient.setQueryData(["private-actor-state"], { secret: true });
    queryClient.getMutationCache().build(queryClient, {
      mutationKey: ["private-actor-mutation"],
      mutationFn: async () => undefined,
    });
    currentSession = MEMBER_SESSION;

    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: getCurrentIdentityQueryKey() });
    });

    expect(await screen.findByRole("button", { name: "Organization member" })).toBeInTheDocument();
    await waitFor(() => {
      expect(queryClient.getQueryData(["private-actor-state"])).toBeUndefined();
      expect(queryClient.getMutationCache().getAll()).toHaveLength(0);
    });
  });
});

function renderBoundary(
  queryClient: QueryClient,
  page: "new-session" | "sources" | "invitations" = "new-session",
) {
  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <ApplicationSessionBoundary page={page} />
      </ThemeProvider>
    </QueryClientProvider>,
  );
}
