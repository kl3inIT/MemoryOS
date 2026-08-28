import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from "@tanstack/react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ApplicationSession } from "@/features/identity/application-session-context";
import { ApplicationSessionProvider } from "@/features/identity/application-session-provider";
import { ThemeProvider } from "@/features/theme/theme-provider";
import { NewSessionPage } from "./new-session-page";

const OWNER_SESSION: ApplicationSession = {
  actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
  organization: {
    displayName: "Tasco",
    role: "OWNER",
  },
  capabilities: ["INVITATIONS_MANAGE", "SOURCES_MANAGE"],
};

async function renderNewSession(session: ApplicationSession = OWNER_SESSION) {
  vi.stubGlobal("scrollTo", vi.fn());
  const rootRoute = createRootRoute();
  const indexRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: "/",
    component: () => (
      <ApplicationSessionProvider session={session}>
        <ThemeProvider>
          <NewSessionPage />
        </ThemeProvider>
      </ApplicationSessionProvider>
    ),
  });
  const router = createRouter({
    routeTree: rootRoute.addChildren([indexRoute]),
    history: createMemoryHistory({ initialEntries: ["/"] }),
  });
  await router.load();
  return render(<RouterProvider router={router} />);
}

afterEach(() => {
  window.localStorage.clear();
  document.documentElement.classList.remove("dark");
  document.documentElement.style.removeProperty("color-scheme");
  vi.unstubAllGlobals();
});

describe("NewSessionPage", () => {
  it("renders New Session inside the authenticated application shell", async () => {
    await renderNewSession();

    expect(screen.getByRole("navigation", { name: "Primary navigation" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "New Session" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect(screen.getByRole("link", { name: "Admin Panel" })).toHaveAttribute("href", "/admin");
    expect(screen.getByRole("heading", { name: "How can I help?" })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Ask MemoryOS" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Organization owner" })).toBeInTheDocument();
  });

  it("removes owner administration affordances for a member", async () => {
    await renderNewSession({
      ...OWNER_SESSION,
      organization: { ...OWNER_SESSION.organization, role: "MEMBER" },
      capabilities: [],
    });

    expect(screen.getByRole("button", { name: "Organization member" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Admin Panel" })).not.toBeInTheDocument();
  });

  it("collapses and expands the desktop sidebar", async () => {
    const user = userEvent.setup();
    await renderNewSession();

    await user.click(screen.getByRole("button", { name: "Collapse sidebar" }));
    expect(screen.getByRole("button", { name: "Expand sidebar" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Expand sidebar" }));
    expect(screen.getByRole("button", { name: "Collapse sidebar" })).toBeInTheDocument();
  });

  it("persists a real dark theme preference", async () => {
    const user = userEvent.setup();
    await renderNewSession();

    await user.click(screen.getByRole("button", { name: "Organization owner" }));
    await user.click(screen.getByRole("button", { name: "Use dark theme" }));

    expect(document.documentElement).toHaveClass("dark");
    expect(window.localStorage.getItem("memoryos-theme")).toBe("dark");
    expect(screen.getByRole("button", { name: "Use light theme" })).toBeInTheDocument();
  });

  it("sends a guarded same-origin sign-out request", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn(() => new Promise<Response>(() => undefined));
    vi.stubGlobal("fetch", fetchMock);
    await renderNewSession();

    await user.click(screen.getByRole("button", { name: "Organization owner" }));
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
    await renderNewSession();

    await user.click(screen.getByRole("button", { name: "Organization owner" }));
    await user.click(screen.getByRole("button", { name: "Sign out" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "We couldn't sign you out. Try again.",
    );
    expect(screen.getByRole("button", { name: "Sign out" })).toBeEnabled();
  });
});
