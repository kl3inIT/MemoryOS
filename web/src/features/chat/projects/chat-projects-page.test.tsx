import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from "@tanstack/react-router";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse } from "msw";
import { afterEach, beforeEach, expect, it } from "vitest";
import { ActionNotifications } from "@/components/ui/action-notifications";
import { i18n } from "@/i18n/index";
import {
  handleDeleteChatProject,
  handleListChatProjects,
  handleReorderChatProjects,
} from "@/lib/hey-api/msw.gen";
import type { ProjectView } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { stackRows } from "@/test/row-layout";
import { ChatProjectsPage } from "./chat-projects-page";
import { projectsSearchSchema } from "./projects-filter";

const planning: ProjectView = {
  id: "44444444-4444-4444-8444-444444444444",
  name: "Kế hoạch",
  description: "Mục tiêu quý 4",
  instructions: "",
  revision: 3,
  updatedAt: "2026-10-03T08:00:00Z",
  fileIds: ["66666666-6666-4666-8666-666666666666", "77777777-7777-4777-8777-777777777777"],
};
const finance: ProjectView = {
  ...planning,
  id: "55555555-5555-4555-8555-555555555555",
  name: "Tài chính",
  description: "",
};

async function show(path = "/projects") {
  const rootRoute = createRootRoute();
  const authenticatedRoute = createRoute({ getParentRoute: () => rootRoute, id: "_authenticated" });
  const router = createRouter({
    routeTree: rootRoute.addChildren([
      authenticatedRoute.addChildren([
        createRoute({
          getParentRoute: () => authenticatedRoute,
          path: "projects/",
          validateSearch: projectsSearchSchema,
          component: ChatProjectsPage,
        }),
        createRoute({
          getParentRoute: () => authenticatedRoute,
          path: "projects/$projectId",
          component: () => null,
        }),
      ]),
    ]),
    history: createMemoryHistory({ initialEntries: [path] }),
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
  return router;
}

beforeEach(() => i18n.changeLanguage("vi"));
afterEach(cleanup);

it("lists each project as a link with its description and a file count and update day", async () => {
  server.use(handleListChatProjects({ body: [planning, finance] }));
  await show();

  const link = await screen.findByRole("link", { name: /Kế hoạch/ });
  expect(link).toHaveAttribute("href", `/projects/${planning.id}`);
  expect(link).toHaveTextContent("Mục tiêu quý 4");
  expect(link).toHaveTextContent("2 tệp · Cập nhật");
  // A project with no description shows no empty line for it.
  expect(screen.getByRole("link", { name: /Tài chính/ })).not.toHaveTextContent("Mục tiêu");
  expect(screen.getByRole("heading", { level: 1, name: "Dự án" })).toBeInTheDocument();
  // The menu is a sibling of the link, not inside it.
  expect(link).not.toContainElement(screen.getByRole("button", { name: "Thao tác với Kế hoạch" }));
});

it("shows the brand loader as a status while the list loads", async () => {
  server.use(handleListChatProjects(() => new Promise(() => {})));
  await show();

  expect(await screen.findByRole("status")).toHaveTextContent("Đang tải dự án…");
});

it("announces a failed load and retries it", async () => {
  server.use(handleListChatProjects(() => new HttpResponse(null, { status: 500 })));
  await show();
  const user = userEvent.setup();

  const alert = await screen.findByRole("alert");
  expect(alert).toHaveTextContent("Không tải được dự án.");
  server.use(handleListChatProjects({ body: [planning] }));
  await user.click(within(alert).getByRole("button", { name: "Tải lại" }));

  expect(await screen.findByRole("link", { name: /Kế hoạch/ })).toBeInTheDocument();
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});

it("keeps the empty state when there are no projects", async () => {
  server.use(handleListChatProjects({ body: [] }));
  await show();

  expect(await screen.findByText("Một nơi cho công việc của bạn")).toBeInTheDocument();
});

it("opens the editor for the chosen project from its menu", async () => {
  server.use(handleListChatProjects({ body: [planning] }));
  await show();
  const user = userEvent.setup();

  await user.click(await screen.findByRole("button", { name: "Thao tác với Kế hoạch" }));
  await user.click(await screen.findByRole("menuitem", { name: "Sửa" }));

  const dialog = await screen.findByRole("dialog");
  expect(within(dialog).getByRole("textbox", { name: /Tên/ })).toHaveValue("Kế hoạch");
});

it("deletes a project at its revision after confirmation and says the conversations stay", async () => {
  let projects = [planning, finance];
  const deleted: { projectId: string; revision: string | null }[] = [];
  server.use(
    handleListChatProjects(() => HttpResponse.json(projects)),
    handleDeleteChatProject(({ params, request }) => {
      deleted.push({
        projectId: params.projectId,
        revision: new URL(request.url).searchParams.get("revision"),
      });
      projects = projects.filter((project) => project.id !== params.projectId);
      return new HttpResponse(null, { status: 204 });
    }),
  );
  await show();
  const user = userEvent.setup();

  await user.click(await screen.findByRole("button", { name: "Thao tác với Kế hoạch" }));
  await user.click(await screen.findByRole("menuitem", { name: "Xóa" }));
  const dialog = await screen.findByRole("alertdialog");
  expect(dialog).toHaveTextContent("Xóa dự án “Kế hoạch”?");
  expect(dialog).toHaveTextContent("vẫn giữ nguyên lịch sử");
  expect(deleted).toEqual([]);
  await user.click(within(dialog).getByRole("button", { name: "Xóa dự án" }));

  await waitFor(() =>
    expect(screen.queryByRole("link", { name: /Kế hoạch/ })).not.toBeInTheDocument(),
  );
  expect(deleted).toEqual([{ projectId: planning.id, revision: "3" }]);
  expect(screen.getByRole("link", { name: /Tài chính/ })).toBeInTheDocument();
});

const reviews: ProjectView = {
  ...planning,
  id: "88888888-8888-4888-8888-888888888888",
  name: "Rà soát",
};

/** The projects' names in the order the page lists them; a row's link also holds its description and counts. */
function listedNames() {
  const names = [planning, finance, reviews].map((project) => project.name ?? "");
  return screen
    .getAllByRole("link")
    .map((link) => names.find((name) => link.textContent?.startsWith(name)));
}

/** Lifts the project by its handle and moves it one place with the keyboard. */
async function moveWithKeyboard(
  user: ReturnType<typeof userEvent.setup>,
  name: string,
  key: string,
) {
  screen.getByRole("button", { name: `Kéo để sắp xếp ${name}` }).focus();
  await user.keyboard(" ");
  await user.keyboard(`{${key}}`);
  await user.keyboard(" ");
}

it("shows a reordered list at once and sends every project in the chosen order", async () => {
  stackRows("li");
  let release = () => {};
  const saved: unknown[] = [];
  server.use(
    handleListChatProjects({ body: [planning, finance, reviews] }),
    handleReorderChatProjects(async ({ request }) => {
      saved.push(await request.json());
      await new Promise<void>((resolve) => {
        release = resolve;
      });
      return new HttpResponse(null, { status: 204 });
    }),
  );
  await show();
  const user = userEvent.setup();
  await screen.findByRole("link", { name: /Kế hoạch/ });

  await moveWithKeyboard(user, "Kế hoạch", "ArrowDown");

  // The write is still open, and the order already shows.
  await waitFor(() => expect(listedNames()).toEqual(["Tài chính", "Kế hoạch", "Rà soát"]));
  await waitFor(() =>
    expect(saved).toEqual([{ projectIds: [finance.id, planning.id, reviews.id] }]),
  );
  release();
  await waitFor(() => expect(listedNames()).toEqual(["Tài chính", "Kế hoạch", "Rà soát"]));
});

it("restores the order, reloads the list and says so when the list changed elsewhere", async () => {
  stackRows("li");
  let reads = 0;
  server.use(
    handleListChatProjects(() => {
      reads += 1;
      return HttpResponse.json([planning, finance, reviews]);
    }),
    handleReorderChatProjects(() => new HttpResponse(null, { status: 409 })),
  );
  await show();
  const user = userEvent.setup();
  await screen.findByRole("link", { name: /Kế hoạch/ });

  await moveWithKeyboard(user, "Kế hoạch", "ArrowDown");

  expect(await screen.findByText(/Danh sách dự án đã thay đổi ở nơi khác/)).toBeInTheDocument();
  expect(listedNames()).toEqual(["Kế hoạch", "Tài chính", "Rà soát"]);
  await waitFor(() => expect(reads).toBe(2));
});

it("restores the order and says the result is unconfirmed when the write fails", async () => {
  stackRows("li");
  server.use(
    handleListChatProjects({ body: [planning, finance, reviews] }),
    handleReorderChatProjects(() => new HttpResponse(null, { status: 500 })),
  );
  await show();
  const user = userEvent.setup();
  await screen.findByRole("link", { name: /Kế hoạch/ });

  await moveWithKeyboard(user, "Rà soát", "ArrowUp");

  expect(await screen.findByText(/Chưa xác nhận được kết quả/)).toBeInTheDocument();
  expect(listedNames()).toEqual(["Kế hoạch", "Tài chính", "Rà soát"]);
});

it("reorders by dragging a row from anywhere and does not open the project when the pointer is released", async () => {
  stackRows("li");
  const saved: unknown[] = [];
  server.use(
    handleListChatProjects({ body: [planning, finance, reviews] }),
    handleReorderChatProjects(async ({ request }) => {
      saved.push(await request.json());
      return new HttpResponse(null, { status: 204 });
    }),
  );
  const router = await show();
  const user = userEvent.setup();
  const link = await screen.findByRole("link", { name: /Kế hoạch/ });

  await user.pointer([
    { keys: "[MouseLeft>]", target: link, coords: { x: 20, y: 10 } },
    { coords: { x: 20, y: 30 } },
    { coords: { x: 20, y: 55 } },
    { coords: { x: 20, y: 65 } },
    { keys: "[/MouseLeft]" },
  ]);

  await waitFor(() => expect(saved).toHaveLength(1));
  expect(router.state.location.pathname).toBe("/projects");
});

it("does not drag the row behind the editor when text is selected by dragging inside it", async () => {
  stackRows("li");
  const saved: unknown[] = [];
  server.use(
    handleListChatProjects({ body: [planning, finance, reviews] }),
    handleReorderChatProjects(async ({ request }) => {
      saved.push(await request.json());
      return new HttpResponse(null, { status: 204 });
    }),
  );
  await show();
  const user = userEvent.setup();
  await user.click(await screen.findByRole("button", { name: "Thao tác với Kế hoạch" }));
  await user.click(await screen.findByRole("menuitem", { name: "Sửa" }));
  const name = within(await screen.findByRole("dialog")).getByRole("textbox", { name: /Tên/ });

  await user.pointer([
    { keys: "[MouseLeft>]", target: name, coords: { x: 20, y: 10 } },
    { coords: { x: 20, y: 30 } },
    { coords: { x: 20, y: 55 } },
    { coords: { x: 20, y: 65 } },
    { keys: "[/MouseLeft]" },
  ]);

  await new Promise((resolve) => setTimeout(resolve, 150));
  expect(saved).toHaveLength(0);
  expect(screen.getByRole("dialog")).toBeInTheDocument();
});

it("narrows the list by name or description without the marks and keeps the words in the address", async () => {
  server.use(handleListChatProjects({ body: [planning, finance, reviews] }));
  const router = await show();
  const user = userEvent.setup();
  await screen.findByRole("link", { name: /Kế hoạch/ });
  const box = screen.getByRole("textbox", { name: "Tìm dự án" });

  await user.type(box, "muc tieu");

  // "Kế hoạch" and "Rà soát" share the description "Mục tiêu quý 4"; "Tài chính" has none.
  expect(listedNames()).toEqual(["Kế hoạch", "Rà soát"]);
  await waitFor(() => expect(router.state.location.search).toEqual({ q: "muc tieu" }));
  await user.clear(box);
  await user.type(box, "TAI");
  expect(listedNames()).toEqual(["Tài chính"]);
});

it("opens narrowed by the address and offers no reordering while narrowed", async () => {
  server.use(handleListChatProjects({ body: [planning, finance, reviews] }));
  await show("/projects?q=ra%20soat");

  expect(await screen.findByRole("textbox", { name: "Tìm dự án" })).toHaveValue("ra soat");
  await waitFor(() => expect(listedNames()).toEqual(["Rà soát"]));
  expect(screen.queryByRole("button", { name: /^Kéo để sắp xếp/ })).not.toBeInTheDocument();
  expect(screen.getByText("Xóa từ khóa để sắp xếp lại dự án.")).toBeInTheDocument();
});

it("says nothing matches and clears the search from the empty state", async () => {
  server.use(handleListChatProjects({ body: [planning, finance] }));
  const router = await show("/projects?q=khong%20co");
  const user = userEvent.setup();

  expect(await screen.findByRole("heading", { name: "Không có dự án nào khớp" })).toBeVisible();
  await user.click(screen.getByRole("button", { name: "Xóa bộ lọc" }));
  expect(listedNames()).toEqual(["Kế hoạch", "Tài chính"]);
  await waitFor(() => expect(router.state.location.search).toEqual({}));
  expect(screen.getByRole("button", { name: "Kéo để sắp xếp Kế hoạch" })).toBeInTheDocument();
});
