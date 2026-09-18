import { act, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { redirectToSignIn } from "@/features/identity/sign-in-redirect";
import { whenBootSplashDone } from "@/lib/boot-splash";
import { AccessNotProvisionedScreen, SignInRedirect } from "./session-states";

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

  it("explains an authenticated but unprovisioned denial", () => {
    render(<AccessNotProvisionedScreen />);

    expect(screen.getByRole("heading", { name: /don’t have access yet/i })).toBeVisible();
    expect(screen.getByRole("link", { name: /try another account/i })).toHaveAttribute(
      "href",
      "/oauth2/authorization/memoryos",
    );
  });
});
