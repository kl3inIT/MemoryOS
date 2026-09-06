import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { AccessNotProvisionedScreen, SignInScreen } from "./session-states";

describe("browser authentication states", () => {
  it("renders the signed-out state as a direct authentication gate", () => {
    render(<SignInScreen />);

    expect(screen.getByRole("heading", { name: /sign in to memoryos/i })).toBeVisible();
    expect(screen.getByRole("link", { name: /continue with company account/i })).toHaveAttribute(
      "href",
      "/oauth2/authorization/memoryos",
    );
    expect(
      screen.queryByText(/keep what matters|authentication and mfa|authorized members only/i),
    ).not.toBeInTheDocument();
  });

  it("explains an authenticated but unprovisioned denial", () => {
    render(<AccessNotProvisionedScreen />);

    expect(screen.getByRole("heading", { name: /don’t have access yet/i })).toBeVisible();
    expect(screen.getByText(/has not been added to this memoryos tenant/i)).toBeVisible();
    expect(screen.getByRole("link", { name: /try another account/i })).toHaveAttribute(
      "href",
      "/oauth2/authorization/memoryos",
    );
  });
});
