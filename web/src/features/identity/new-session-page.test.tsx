import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@/features/theme/theme-provider";
import { NewSessionPage } from "./new-session-page";

afterEach(() => {
  window.localStorage.clear();
  document.documentElement.classList.remove("dark");
  document.documentElement.style.removeProperty("color-scheme");
  vi.unstubAllGlobals();
});

describe("NewSessionPage", () => {
  it("renders New Session inside the authenticated application shell", () => {
    render(
      <ThemeProvider>
        <NewSessionPage />
      </ThemeProvider>,
    );

    expect(screen.getByRole("navigation", { name: "Primary navigation" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "New Session" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect(screen.getByRole("link", { name: "Admin Panel" })).toHaveAttribute("href", "/admin");
    expect(screen.getByRole("heading", { name: "How can I help?" })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Ask MemoryOS" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Workspace owner" })).toBeInTheDocument();
  });

  it("collapses and expands the desktop sidebar", async () => {
    const user = userEvent.setup();
    render(
      <ThemeProvider>
        <NewSessionPage />
      </ThemeProvider>,
    );

    await user.click(screen.getByRole("button", { name: "Collapse sidebar" }));
    expect(screen.getByRole("button", { name: "Expand sidebar" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Expand sidebar" }));
    expect(screen.getByRole("button", { name: "Collapse sidebar" })).toBeInTheDocument();
  });

  it("persists a real dark theme preference", async () => {
    const user = userEvent.setup();
    render(
      <ThemeProvider>
        <NewSessionPage />
      </ThemeProvider>,
    );

    await user.click(screen.getByRole("button", { name: "Workspace owner" }));
    await user.click(screen.getByRole("button", { name: "Use dark theme" }));

    expect(document.documentElement).toHaveClass("dark");
    expect(window.localStorage.getItem("memoryos-theme")).toBe("dark");
    expect(screen.getByRole("button", { name: "Use light theme" })).toBeInTheDocument();
  });

  it("sends a guarded same-origin sign-out request", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn(() => new Promise<Response>(() => undefined));
    vi.stubGlobal("fetch", fetchMock);
    render(
      <ThemeProvider>
        <NewSessionPage />
      </ThemeProvider>,
    );

    await user.click(screen.getByRole("button", { name: "Workspace owner" }));
    await user.click(screen.getByRole("button", { name: "Sign out" }));

    expect(fetchMock).toHaveBeenCalledWith("/logout", {
      method: "POST",
      credentials: "same-origin",
      headers: { "X-MemoryOS-CSRF": "1" },
    });
    expect(screen.getByRole("button", { name: "Signing out…" })).toBeDisabled();
  });

  it("keeps the account menu actionable when sign-out fails", async () => {
    const user = userEvent.setup();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 500 })));
    render(
      <ThemeProvider>
        <NewSessionPage />
      </ThemeProvider>,
    );

    await user.click(screen.getByRole("button", { name: "Workspace owner" }));
    await user.click(screen.getByRole("button", { name: "Sign out" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "We couldn't sign you out. Try again.",
    );
    expect(screen.getByRole("button", { name: "Sign out" })).toBeEnabled();
  });
});
