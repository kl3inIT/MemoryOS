import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ApplicationSession } from "@/features/identity/application-session-context";
import { ApplicationSessionProvider } from "@/features/identity/application-session-provider";
import { PrincipalPicker, type Principal } from "@/features/identity/principal-picker";

const searchPrincipals = vi.hoisted(() => vi.fn());

vi.mock("@/lib/hey-api/sdk.gen", () => ({
  searchPrincipals: (...args: unknown[]) => searchPrincipals(...args),
}));

const LAN = "b1f0c4a2-3e5d-4a7b-9c81-2d6f8a0e4b73";
const BOARD = "d4b8e2a6-7c19-4f35-b0d8-6e2a4c81f593";

const SESSION: ApplicationSession = {
  actorId: "0f2f5e6e-4e6c-4d55-9c07-6b0b1d4b39a4",
  authorizationVersion: 1,
  uiLanguage: "en",
  tenant: { displayName: "Tasco", role: "OWNER" },
  capabilities: ["SYSTEM_BASIC"],
  scopedCapabilities: [],
};

function renderPicker(groups: boolean, onPick: (principal: Principal) => void = () => {}) {
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <ApplicationSessionProvider session={SESSION}>
        <PrincipalPicker groups={groups} exclude={new Set()} onPick={onPick} />
      </ApplicationSessionProvider>
    </QueryClientProvider>,
  );
}

describe("PrincipalPicker", () => {
  beforeEach(() => {
    searchPrincipals.mockReset();
    searchPrincipals.mockResolvedValue({
      data: {
        people: [{ actorId: LAN, name: "Chị Lan", email: "lan@tasco.vn" }],
        groups: [{ id: BOARD, name: "Ban điều hành" }],
      },
    });
  });

  it("searches the identity directory for what is typed and picks a person", async () => {
    const onPick = vi.fn();
    renderPicker(true, onPick);

    await userEvent.type(screen.getByRole("combobox", { name: "Add people or Groups" }), "  lan ");

    await userEvent.click(await screen.findByRole("option", { name: /Chị Lan/ }));
    expect(searchPrincipals).toHaveBeenLastCalledWith(
      expect.objectContaining({ query: { search: "lan", size: 20 } }),
    );
    expect(onPick).toHaveBeenCalledWith({
      kind: "person",
      person: { actorId: LAN, name: "Chị Lan", email: "lan@tasco.vn" },
    });
  });

  it("offers no Groups when only people may be picked", async () => {
    renderPicker(false);

    await userEvent.type(screen.getByRole("combobox", { name: "Search people" }), "ban");

    expect(await screen.findByRole("option", { name: /Chị Lan/ })).toBeVisible();
    expect(screen.queryByRole("option", { name: /Ban điều hành/ })).toBeNull();
  });

  it("names its search field so it is reachable without seeing the placeholder", () => {
    renderPicker(true);

    expect(screen.getByRole("combobox", { name: "Add people or Groups" })).toBeVisible();
  });

  it("names the people-only field for what it searches", () => {
    renderPicker(false);

    expect(screen.getByRole("combobox", { name: "Search people" })).toBeVisible();
  });
});
