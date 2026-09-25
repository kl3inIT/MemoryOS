import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";
import type { ApplicationSession } from "@/features/identity/application-session-context";
import { ApplicationSessionProvider } from "@/features/identity/application-session-provider";
import { PrincipalPicker } from "@/features/identity/principal-picker";

const SESSION: ApplicationSession = {
  actorId: "0f2f5e6e-4e6c-4d55-9c07-6b0b1d4b39a4",
  authorizationVersion: 1,
  uiLanguage: "en",
  tenant: { displayName: "Tasco", role: "OWNER" },
  capabilities: ["SYSTEM_BASIC"],
  scopedCapabilities: [],
};

function renderPicker(groups: boolean) {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <ApplicationSessionProvider session={SESSION}>
        <PrincipalPicker groups={groups} exclude={new Set()} onPick={() => {}} />
      </ApplicationSessionProvider>
    </QueryClientProvider>,
  );
}

describe("PrincipalPicker", () => {
  it("names its search field so it is reachable without seeing the placeholder", () => {
    renderPicker(true);

    expect(screen.getByRole("combobox", { name: "Add people or Groups" })).toBeVisible();
  });

  it("names the people-only field for what it searches", () => {
    renderPicker(false);

    expect(screen.getByRole("combobox", { name: "Search people" })).toBeVisible();
  });
});
