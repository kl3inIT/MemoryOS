import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
  RouterProvider,
} from "@tanstack/react-router";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse } from "msw";
import { afterEach, beforeEach, expect, it } from "vitest";
import { ActionNotifications } from "@/components/ui/action-notifications";
import { i18n } from "@/i18n/index";
import { handleListProjectChatSessions } from "@/lib/hey-api/msw.gen";
import { server } from "@/test/msw";
import type { Project } from "./chat-projects-api";
import { ProjectTabs } from "./project-tabs";

const project: Project = {
  id: "44444444-4444-4444-8444-444444444444",
  name: "Kế hoạch",
  description: "",
  instructions: "",
  revision: 1,
  updatedAt: "2026-10-01T00:00:00Z",
  iconName: undefined,
  fileIds: [],
};

async function show() {
  const rootRoute = createRootRoute({ component: Outlet });
  const page = createRoute({
    getParentRoute: () => rootRoute,
    path: "/",
    component: () => <ProjectTabs project={project} />,
  });
  const router = createRouter({
    routeTree: rootRoute.addChildren([page]),
    history: createMemoryHistory({ initialEntries: ["/"] }),
  });
  await router.load();
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <ActionNotifications>
        <RouterProvider router={router} />
      </ActionNotifications>
    </QueryClientProvider>,
  );
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  server.use(handleListProjectChatSessions(() => HttpResponse.json([])));
});
afterEach(cleanup);

it("shows the conversations first and moves the files to their own tab", async () => {
  await show();
  const user = userEvent.setup();
  const chats = screen.getByRole("tab", { name: "Trò chuyện" });
  const files = screen.getByRole("tab", { name: "Tệp" });

  expect(chats).toHaveAttribute("aria-selected", "true");
  expect(await screen.findByText(/Chưa có hội thoại/)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Thêm tệp" })).toBeNull();

  await user.click(files);

  expect(files).toHaveAttribute("aria-selected", "true");
  expect(screen.getByRole("button", { name: "Thêm tệp" })).toBeInTheDocument();
  expect(screen.queryByText(/Chưa có hội thoại/)).toBeNull();
});
