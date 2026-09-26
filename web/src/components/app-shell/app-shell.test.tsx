import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  Link,
  Outlet,
  RouterProvider,
} from "@tanstack/react-router";
import { expect, it, vi } from "vitest";
import type {
  ApplicationCapability,
  ApplicationSession,
} from "@/features/identity/application-session-context";
import { ApplicationSessionProvider } from "@/features/identity/application-session-provider";
import { ThemeProvider } from "@/features/theme/theme-provider";
import { AppShell } from "./app-shell";

function session(capabilities: ApplicationCapability[]): ApplicationSession {
  return {
    actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
    authorizationVersion: 1,
    uiLanguage: "en",
    tenant: { displayName: "Tasco", role: "MEMBER" },
    capabilities,
    scopedCapabilities: [],
  };
}

async function renderShell(path: string, capabilities: ApplicationCapability[]) {
  vi.stubGlobal("scrollTo", vi.fn());
  const root = createRootRoute();
  const authenticated = createRoute({
    getParentRoute: () => root,
    id: "_authenticated",
    component: () => (
      <ApplicationSessionProvider session={session(capabilities)}>
        <ThemeProvider>
          <AppShell>
            <Outlet />
          </AppShell>
        </ThemeProvider>
      </ApplicationSessionProvider>
    ),
  });
  const admin = createRoute({ getParentRoute: () => authenticated, path: "admin" });
  const page = (parent: typeof admin, pagePath: string, text: string) =>
    createRoute({
      getParentRoute: () => parent,
      path: pagePath,
      component: () => (
        <>
          <p>{text}</p>
          <Link to="/admin/audit">Open audit</Link>
        </>
      ),
    });
  const router = createRouter({
    routeTree: root.addChildren([
      authenticated.addChildren([
        admin.addChildren([
          page(admin, "audit", "Audit page"),
          page(admin, "chat-history", "History page"),
          page(admin, "users", "Users page"),
        ]),
      ]),
    ]),
    history: createMemoryHistory({ initialEntries: [path] }),
  });
  await router.load();
  render(
    <QueryClientProvider client={new QueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

it("shows only the administration pages the session may open, in their sections", async () => {
  await renderShell("/admin/chat-history", ["AUDIT_READ", "CHAT_HISTORY_READ"]);

  const navigation = await screen.findByRole("navigation", { name: "Administration navigation" });
  expect(
    within(navigation)
      .getAllByRole("link")
      .map((link) => link.textContent),
  ).toEqual(["Conversation history", "Audit log"]);
  expect(within(navigation).getByRole("link", { name: "Conversation history" })).toHaveClass(
    "bg-surface-raised",
  );
  expect(screen.getByText("History page")).toBeInTheDocument();
});

it("keeps the sidebar mounted while moving between administration pages", async () => {
  const user = userEvent.setup();
  await renderShell("/admin/chat-history", ["AUDIT_READ", "CHAT_HISTORY_READ"]);

  await user.click(await screen.findByRole("button", { name: "Collapse sidebar" }));
  await user.click(screen.getByRole("link", { name: "Open audit" }));

  expect(await screen.findByText("Audit page")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Expand sidebar" })).toBeInTheDocument();
});

it("refuses an administration page the session may not open, without the administration frame", async () => {
  await renderShell("/admin/users", ["AUDIT_READ"]);

  expect(await screen.findByRole("heading", { level: 1 })).toBeInTheDocument();
  expect(screen.queryByText("Users page")).not.toBeInTheDocument();
  expect(
    screen.queryByRole("navigation", { name: "Administration navigation" }),
  ).not.toBeInTheDocument();
});
