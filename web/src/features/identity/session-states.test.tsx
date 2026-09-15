import { act, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { redirectToSignIn } from "@/features/identity/sign-in-redirect";
import { whenBootSplashDone } from "@/lib/boot-splash";
import { AccessNotProvisionedScreen, SignInRedirect, SignInScreen } from "./session-states";

vi.mock("@/features/identity/sign-in-redirect", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/identity/sign-in-redirect")>()),
  redirectToSignIn: vi.fn(),
}));

vi.mock("@/lib/boot-splash", () => ({ whenBootSplashDone: vi.fn() }));

beforeEach(() => {
  vi.mocked(whenBootSplashDone).mockResolvedValue(undefined);
});

afterEach(() => {
  window.sessionStorage.clear();
  vi.mocked(redirectToSignIn).mockClear();
});

describe("browser authentication states", () => {
  it("sends a signed-out browser straight to the company sign-in flow", async () => {
    render(<SignInRedirect />);

    expect(screen.getByRole("status", { name: /redirecting to sign in/i })).toBeVisible();
    await waitFor(() => expect(redirectToSignIn).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole("heading", { name: /sign in to memoryos/i })).not.toBeInTheDocument();
  });

  it("waits for the boot splash to finish before redirecting", async () => {
    let finishSplash: () => void = () => {};
    vi.mocked(whenBootSplashDone).mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finishSplash = resolve;
      }),
    );

    render(<SignInRedirect />);
    await act(async () => {});
    expect(redirectToSignIn).not.toHaveBeenCalled();

    await act(async () => finishSplash());
    expect(redirectToSignIn).toHaveBeenCalledTimes(1);
  });

  it("offers the manual sign-in gate instead of redirecting again right after a redirect", () => {
    window.sessionStorage.setItem("memoryos.signInRedirectAt", String(Date.now()));

    render(<SignInRedirect />);

    expect(screen.getByRole("link", { name: /continue with company account/i })).toHaveAttribute(
      "href",
      "/oauth2/authorization/memoryos",
    );
    expect(redirectToSignIn).not.toHaveBeenCalled();
  });

  it("renders the signed-out state as a direct authentication gate", () => {
    render(<SignInScreen />);

    expect(screen.getByRole("heading", { name: /sign in to memoryos/i })).toBeVisible();
    expect(screen.getByRole("link", { name: /continue with company account/i })).toHaveAttribute(
      "href",
      "/oauth2/authorization/memoryos",
    );
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
