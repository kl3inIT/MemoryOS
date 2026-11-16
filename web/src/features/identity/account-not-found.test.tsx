import { QueryClientProvider } from "@tanstack/react-query";
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from "@tanstack/react-router";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, expect, it } from "vitest";
import { i18n } from "@/i18n";
import { handleGetCurrentIdentity } from "@/lib/hey-api/msw.gen";
import type { CurrentIdentity } from "@/lib/hey-api/types.gen";
import { createMemoryOsQueryClient } from "@/lib/query-client";
import { server } from "@/test/msw";
import { AccountNotFound } from "./account-not-found";

const ENGLISH_MEMBER: CurrentIdentity = {
  actorId: "97c41cb9-55ae-4a52-94ab-7aad59be91e5",
  displayName: null,
  authorizationVersion: 1,
  uiLanguage: "en",
  tenant: { displayName: "Tasco", role: "MEMBER" },
  capabilities: ["SYSTEM_BASIC", "CHAT_READ"],
  scopedCapabilities: [],
};

async function openUnknownAddress() {
  const rootRoute = createRootRoute({ notFoundComponent: AccountNotFound });
  const router = createRouter({
    routeTree: rootRoute.addChildren([
      createRoute({ getParentRoute: () => rootRoute, path: "/", component: () => null }),
    ]),
    history: createMemoryHistory({ initialEntries: ["/no-such-page"] }),
    notFoundMode: "root",
  });
  await router.load();
  render(
    <QueryClientProvider client={createMemoryOsQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

beforeEach(() => i18n.changeLanguage("vi"));
afterEach(async () => {
  cleanup();
  await i18n.changeLanguage("vi");
});

it("shows a direct load of an unknown address in the account language", async () => {
  server.use(handleGetCurrentIdentity({ body: ENGLISH_MEMBER }));
  await openUnknownAddress();

  expect(
    await screen.findByRole("heading", { name: "This path isn’t part of your organization." }),
  ).toBeInTheDocument();
  expect(document.documentElement.lang).toBe("en");
  expect(screen.queryByText("Đường dẫn này không thuộc tổ chức của bạn.")).not.toBeInTheDocument();
});

it("shows the page in the default language to a visitor who is not signed in", async () => {
  server.use(handleGetCurrentIdentity(() => Response.json({}, { status: 401 })));
  await openUnknownAddress();

  expect(
    await screen.findByRole("heading", { name: "Đường dẫn này không thuộc tổ chức của bạn." }),
  ).toBeInTheDocument();
  expect(document.documentElement.lang).toBe("vi");
  expect(screen.getByRole("link", { name: "Về trang chủ" })).toBeInTheDocument();
});
