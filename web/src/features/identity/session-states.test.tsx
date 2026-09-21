import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { redirectToSignIn } from "@/features/identity/sign-in-redirect";
import { AccessNotProvisionedScreen, SignInRedirect } from "./session-states";

vi.mock("@/features/identity/sign-in-redirect", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/identity/sign-in-redirect")>()),
  redirectToSignIn: vi.fn(),
}));

afterEach(() => {
  window.sessionStorage.clear();
  vi.mocked(redirectToSignIn).mockClear();
});

describe("browser authentication states", () => {
  it("sends a signed-out browser straight to the company sign-in flow", async () => {
    render(<SignInRedirect />);

    expect(screen.getByRole("status", { name: /redirecting to sign in/i })).toBeVisible();
    await waitFor(() => expect(redirectToSignIn).toHaveBeenCalled());
    expect(screen.queryByRole("heading", { name: /sign in to memoryos/i })).not.toBeInTheDocument();
  });

  it("explains an authenticated but unprovisioned denial", () => {
    render(<AccessNotProvisionedScreen />);

    expect(screen.getByRole("heading", { name: /don’t have access yet/i })).toBeVisible();
    expect(screen.getByRole("link", { name: /try another account/i })).toHaveAttribute(
      "href",
      "/oauth2/authorization/memoryos",
    );
  });
});
