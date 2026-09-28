import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { ActionNotifications } from "@/components/ui/action-notifications";
import { handleCreateIdentityProvider, handleListIdentityProviders } from "@/lib/hey-api/msw.gen";
import type {
  CreateIdentityProviderRequest,
  IdentityProviderResponse,
} from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { IdentityProvidersPage } from "./identity-providers-page";

const PARTNER: IdentityProviderResponse = {
  alias: "partner",
  displayName: "Sign in with Partner",
  issuer: "https://keycloak.example.com/realms/partner",
  clientId: "memoryos-broker",
  enabled: true,
  jitAllowed: false,
  brokerRedirectUri: "https://id.example.com/realms/memoryos/broker/partner/endpoint",
};

function renderPage() {
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <ActionNotifications>
        <IdentityProvidersPage />
      </ActionNotifications>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

describe("IdentityProvidersPage", () => {
  it("adds a provider whose alias follows the issuer until it is typed", async () => {
    let sent: CreateIdentityProviderRequest | undefined;
    let providers: IdentityProviderResponse[] = [];
    server.use(
      handleListIdentityProviders(() => HttpResponse.json(providers)),
      handleCreateIdentityProvider(async ({ request }) => {
        sent = await request.json();
        providers = [PARTNER];
        return HttpResponse.json(PARTNER, { status: 201 });
      }),
    );
    const user = renderPage();
    expect(await screen.findByText("No identity providers yet.")).toBeVisible();

    await user.click(screen.getByRole("button", { name: "Add SSO" }));
    const dialog = await screen.findByRole("dialog", { name: "Add identity provider" });
    await user.type(within(dialog).getByRole("textbox", { name: "Issuer URL" }), PARTNER.issuer);
    expect(within(dialog).getByRole("textbox", { name: "Alias" })).toHaveValue("partner");
    await user.type(
      within(dialog).getByRole("textbox", { name: "Display name" }),
      PARTNER.displayName,
    );
    await user.type(within(dialog).getByRole("textbox", { name: "Client ID" }), PARTNER.clientId);
    await user.type(within(dialog).getByLabelText("Client secret"), "secret");
    await user.click(within(dialog).getByRole("button", { name: "Add provider" }));

    expect(await screen.findByText(PARTNER.displayName)).toBeVisible();
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(sent).toEqual({
      alias: "partner",
      displayName: PARTNER.displayName,
      issuerUrl: PARTNER.issuer,
      clientId: PARTNER.clientId,
      clientSecret: "secret",
      jitAllowed: false,
    });
  });

  it("names the missing fields and keeps the dialog open on a conflicting alias", async () => {
    server.use(
      handleListIdentityProviders({ body: [] }),
      handleCreateIdentityProvider(() =>
        HttpResponse.json(
          { title: "Conflict", status: 409, code: "IDP_ALIAS_CONFLICT" },
          { status: 409 },
        ),
      ),
    );
    const user = renderPage();
    await user.click(await screen.findByRole("button", { name: "Add SSO" }));
    const dialog = await screen.findByRole("dialog", { name: "Add identity provider" });

    await user.click(within(dialog).getByRole("button", { name: "Add provider" }));
    expect(within(dialog).getByText("Enter an alias.")).toBeVisible();
    expect(within(dialog).getByText("Enter the client secret.")).toBeVisible();

    await user.type(within(dialog).getByRole("textbox", { name: "Alias" }), "tasco");
    await user.type(within(dialog).getByRole("textbox", { name: "Display name" }), "Partner");
    await user.type(within(dialog).getByRole("textbox", { name: "Issuer URL" }), PARTNER.issuer);
    expect(within(dialog).getByRole("textbox", { name: "Alias" })).toHaveValue("tasco");
    await user.type(within(dialog).getByRole("textbox", { name: "Client ID" }), "broker");
    await user.type(within(dialog).getByLabelText("Client secret"), "secret");
    await user.click(within(dialog).getByRole("button", { name: "Add provider" }));

    expect(
      await within(dialog).findByText("A provider with this alias already exists."),
    ).toBeVisible();
  });
});
