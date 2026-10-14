import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
  RouterProvider,
} from "@tanstack/react-router";
import { cleanup, createEvent, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse } from "msw";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { ActionNotifications } from "@/components/ui/action-notifications";
import { i18n } from "@/i18n/index";
import {
  handleListChatProjects,
  handleListProjectChatSessions,
  handleMoveChatProject,
  handleReorderChatProjects,
} from "@/lib/hey-api/msw.gen";
import type { ChatSession, ProjectView } from "@/lib/hey-api/types.gen";
import { getChatSessionQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import { server } from "@/test/msw";
import { stackRows } from "@/test/row-layout";
import { ChatProjectsSection } from "./chat-projects-section";
import { CHAT_DRAG_TYPE } from "./chat-session-row";

// The sidebar's Projects section, exercised through the routes that make a project "viewed".

vi.mock("@/features/identity/application-session-context", () => ({
  useApplicationSession: () => ({ actorId: "actor", authorizationVersion: 1, capabilities: [] }),
  useGlobalCapability: () => false,
}));

const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, "0")}`;

const project = (n: number): ProjectView => ({
  id: id(n),
  name: `Dự án ${n}`,
  description: "",
  instructions: "",
  revision: 1,
  updatedAt: "2026-10-01T00:00:00Z",
  fileIds: [],
});

const session = (n: number, projectId: string | null = null): ChatSession => ({
  id: id(100 + n),
  personaId: id(900),
  rootMessageId: id(800 + n),
  title: `Hội thoại ${n}`,
  createdAt: "2026-10-01T00:00:00Z",
  updatedAt: "2026-10-01T00:00:00Z",
  projectId,
  reasoningEffort: null,
  archivedAt: null,
  branchedFromSessionId: null,
  branchedFromMessageId: null,
  temporary: false,
});

/** Records each request to a project's conversations, with its limit. */
function serveSessions(byProject: Record<string, ChatSession[]>) {
  const requests: { projectId: string; limit: string | null }[] = [];
  server.use(
    handleListProjectChatSessions(({ params, request }) => {
      requests.push({
        projectId: params.projectId,
        limit: new URL(request.url).searchParams.get("limit"),
      });
      return HttpResponse.json(byProject[params.projectId] ?? []);
    }),
  );
  return requests;
}

/** `stored` is the conversation the runtime has put in the cache, which is where the open one's Project is read. */
async function show(projects: ProjectView[], path = "/", stored?: ChatSession) {
  server.use(handleListChatProjects({ body: projects }));
  const rootRoute = createRootRoute({ component: Outlet });
  const page = (routePath: string) =>
    createRoute({
      getParentRoute: () => rootRoute,
      path: routePath,
      component: () => <ChatProjectsSection />,
    });
  const router = createRouter({
    routeTree: rootRoute.addChildren([
      page("/"),
      page("/projects"),
      page("/projects/$projectId"),
      page("/chat/$sessionId"),
    ]),
    history: createMemoryHistory({ initialEntries: [path] }),
  });
  await router.load();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  if (stored)
    client.setQueryData(getChatSessionQueryKey({ path: { sessionId: stored.id } }), stored);
  render(
    <QueryClientProvider client={client}>
      <ActionNotifications>
        <RouterProvider router={router} />
      </ActionNotifications>
    </QueryClientProvider>,
  );
  await screen.findByRole("button", { name: "Danh sách dự án" });
  return router;
}

beforeEach(async () => {
  window.localStorage.clear();
  await i18n.changeLanguage("vi");
});
afterEach(cleanup);

it("lists the first five projects and links to the rest", async () => {
  const projects = [1, 2, 3, 4, 5, 6, 7].map(project);
  await show(projects);

  for (const shown of projects.slice(0, 5))
    expect(
      await screen.findByRole("button", { name: `Hội thoại trong ${shown.name}` }),
    ).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Hội thoại trong Dự án 6" })).not.toBeInTheDocument();
  expect(screen.getByRole("link", { name: "Xem tất cả dự án" })).toHaveAttribute(
    "href",
    "/projects",
  );
});

it("links to the Projects page even while every project fits", async () => {
  await show([1, 2].map(project));

  await screen.findByRole("button", { name: "Hội thoại trong Dự án 2" });
  expect(screen.getByRole("link", { name: "Xem tất cả dự án" })).toHaveAttribute(
    "href",
    "/projects",
  );
});

it("opens the project page from its row action menu", async () => {
  const user = userEvent.setup();
  const router = await show([project(1)]);

  await user.click(await screen.findByRole("button", { name: "Thao tác với Dự án 1" }));
  await user.click(screen.getByRole("menuitem", { name: "Xem dự án" }));

  await waitFor(() => expect(router.state.location.pathname).toBe(`/projects/${id(1)}`));
});

it("opens the project editor from its row action menu", async () => {
  const user = userEvent.setup();
  await show([project(1)]);

  await user.click(await screen.findByRole("button", { name: "Thao tác với Dự án 1" }));
  await user.click(screen.getByRole("menuitem", { name: "Sửa" }));

  expect(await screen.findByRole("dialog", { name: "Chỉnh sửa dự án" })).toBeInTheDocument();
});

it("requires confirmation before deleting a project from its row action menu", async () => {
  const user = userEvent.setup();
  await show([project(1)]);

  await user.click(await screen.findByRole("button", { name: "Thao tác với Dự án 1" }));
  await user.click(screen.getByRole("menuitem", { name: "Xóa" }));

  expect(await screen.findByRole("alertdialog")).toHaveTextContent(
    "Các hội thoại được chuyển ra ngoài dự án và vẫn giữ nguyên lịch sử.",
  );
  expect(screen.getByRole("button", { name: "Xóa dự án" })).toBeInTheDocument();
});

it("lists the project being viewed even when it is beyond the first five", async () => {
  await show([1, 2, 3, 4, 5, 6, 7].map(project), `/projects/${id(7)}`);

  const viewed = await screen.findByRole("button", { name: "Hội thoại trong Dự án 7" });
  expect(viewed).toHaveAttribute("aria-current", "page");
  expect(screen.queryByRole("button", { name: "Hội thoại trong Dự án 6" })).not.toBeInTheDocument();
});

it("opens and closes the section and remembers it", async () => {
  const user = userEvent.setup();
  await show([project(1)]);
  await screen.findByRole("button", { name: "Hội thoại trong Dự án 1" });

  const toggle = screen.getByRole("button", { name: "Danh sách dự án" });
  expect(toggle).toHaveAttribute("aria-expanded", "true");
  await user.click(toggle);
  expect(screen.queryByRole("button", { name: "Hội thoại trong Dự án 1" })).not.toBeInTheDocument();
  expect(toggle).toHaveAttribute("aria-expanded", "false");

  cleanup();
  await show([project(1)]);
  expect(screen.getByRole("button", { name: "Danh sách dự án" })).toHaveAttribute(
    "aria-expanded",
    "false",
  );
});

it("fetches a project's five latest conversations only once it is opened", async () => {
  const user = userEvent.setup();
  const first = project(1);
  const requests = serveSessions({
    [id(1)]: [1, 2, 3, 4, 5].map((n) => session(n, id(1))),
  });
  await show([first]);
  await screen.findByRole("button", { name: "Hội thoại trong Dự án 1" });
  expect(requests).toEqual([]);

  await user.click(screen.getByRole("button", { name: "Hội thoại trong Dự án 1" }));

  expect(await screen.findByRole("link", { name: "Hội thoại 5" })).toHaveAttribute(
    "href",
    `/chat/${id(105)}`,
  );
  expect(requests).toEqual([{ projectId: id(1), limit: "5" }]);
  // A full page may have more behind it, so the row leads to the project page.
  expect(screen.getByRole("link", { name: "Xem thêm" })).toHaveAttribute(
    "href",
    `/projects/${id(1)}`,
  );
});

it("shows no 'Xem thêm' for a short page, and a muted line for an empty project", async () => {
  const user = userEvent.setup();
  serveSessions({ [id(1)]: [session(1, id(1))] });
  await show([project(1), project(2)]);

  await user.click(await screen.findByRole("button", { name: "Hội thoại trong Dự án 1" }));
  await screen.findByRole("link", { name: "Hội thoại 1" });
  expect(screen.queryByRole("link", { name: "Xem thêm" })).not.toBeInTheDocument();

  await user.click(screen.getByRole("button", { name: "Hội thoại trong Dự án 2" }));
  expect(await screen.findByText("Chưa có hội thoại.")).toBeInTheDocument();
});

it("loads a project's conversations behind skeleton rows announced once", async () => {
  const user = userEvent.setup();
  let release = () => {};
  server.use(
    handleListProjectChatSessions(async () => {
      await new Promise<void>((resolve) => {
        release = resolve;
      });
      return HttpResponse.json([]);
    }),
  );
  await show([project(1)]);

  await user.click(await screen.findByRole("button", { name: "Hội thoại trong Dự án 1" }));

  // dnd-kit keeps a status region of its own, so the loading one is found by its text.
  const status = (await screen.findByText("Đang tải hội thoại…")).closest("[role=status]");
  expect(status).toHaveTextContent("Đang tải hội thoại…");
  expect(status?.querySelectorAll("[data-slot=skeleton]")).toHaveLength(3);
  release();
  await screen.findByText("Chưa có hội thoại.");
});

it("offers a retry when a project's conversations fail to load", async () => {
  const user = userEvent.setup();
  let failing = true;
  server.use(
    handleListProjectChatSessions(() =>
      failing ? new HttpResponse(null, { status: 500 }) : HttpResponse.json([session(1, id(1))]),
    ),
  );
  await show([project(1)]);

  await user.click(await screen.findByRole("button", { name: "Hội thoại trong Dự án 1" }));
  await screen.findByText("Không tải được hội thoại.");
  failing = false;
  await user.click(screen.getByRole("button", { name: "Tải lại" }));

  expect(await screen.findByRole("link", { name: "Hội thoại 1" })).toBeInTheDocument();
});

it("opens the project of the open conversation by itself", async () => {
  const requests = serveSessions({ [id(2)]: [session(1, id(2))] });
  await show([project(1), project(2)], `/chat/${id(101)}`, session(1, id(2)));

  expect(await screen.findByRole("link", { name: "Hội thoại 1" })).toBeInTheDocument();
  expect(requests).toEqual([{ projectId: id(2), limit: "5" }]);
  expect(screen.getByRole("button", { name: "Hội thoại trong Dự án 2" })).toHaveAttribute(
    "aria-expanded",
    "true",
  );
  expect(screen.getByRole("button", { name: "Hội thoại trong Dự án 1" })).toHaveAttribute(
    "aria-expanded",
    "false",
  );
});

it("reads an open project's conversations again after a conversation is moved", async () => {
  const user = userEvent.setup();
  let rows = [session(1, id(1))];
  server.use(
    handleListProjectChatSessions(({ params }) =>
      HttpResponse.json(params.projectId === id(1) ? rows : []),
    ),
  );
  const moves: { sessionId: string; projectId: string | null | undefined }[] = [];
  server.use(
    handleMoveChatProject(async ({ params, request }) => {
      const body = await request.json();
      moves.push({ sessionId: params.sessionId, projectId: body.projectId });
      rows = [];
      return HttpResponse.json(session(1, id(2)));
    }),
  );
  await show([project(1), project(2)]);
  await user.click(await screen.findByRole("button", { name: "Hội thoại trong Dự án 1" }));
  await screen.findByRole("link", { name: "Hội thoại 1" });

  // A conversation from the thread list dropped on another project.
  const target = screen.getByRole("button", { name: "Hội thoại trong Dự án 2" });
  const drop = createEvent.drop(target);
  Object.defineProperty(drop, "dataTransfer", {
    value: { types: [CHAT_DRAG_TYPE], getData: () => id(101) },
  });
  fireEvent.dragOver(target, { dataTransfer: { types: [CHAT_DRAG_TYPE] } });
  fireEvent(target, drop);

  await waitFor(() => expect(moves).toEqual([{ sessionId: id(101), projectId: id(2) }]));
  await waitFor(() => expect(screen.queryByRole("link", { name: "Hội thoại 1" })).toBeNull());
  expect(await screen.findByText("Chưa có hội thoại.")).toBeInTheDocument();
});

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

/** The projects' names in the order the sidebar lists them. */
function listedNames() {
  return screen
    .getAllByRole("button", { name: /^Hội thoại trong Dự án \d/ })
    .map((link) => link.textContent);
}

/** The ids the order endpoint received, one entry per request. */
function serveOrder() {
  const saved: unknown[] = [];
  server.use(
    handleReorderChatProjects(async ({ request }) => {
      saved.push(await request.json());
      return new HttpResponse(null, { status: 204 });
    }),
  );
  return saved;
}

it("sends every project, not only the five shown, when the shown ones are reordered", async () => {
  stackRows("[data-project-id]");
  const saved = serveOrder();
  const user = userEvent.setup();
  await show([1, 2, 3, 4, 5, 6, 7].map(project));
  await screen.findByRole("button", { name: "Hội thoại trong Dự án 1" });

  await moveWithKeyboard(user, "Dự án 1", "ArrowDown");

  await waitFor(() => expect(saved).toEqual([{ projectIds: [2, 1, 3, 4, 5, 6, 7].map(id) }]));
  expect(listedNames()).toEqual(["Dự án 2", "Dự án 1", "Dự án 3", "Dự án 4", "Dự án 5"]);
});

it("keeps the projects that are not listed in their places when the viewed one moves", async () => {
  stackRows("[data-project-id]");
  const saved = serveOrder();
  const user = userEvent.setup();
  await show([1, 2, 3, 4, 5, 6, 7].map(project), `/projects/${id(7)}`);
  await screen.findByRole("button", { name: "Hội thoại trong Dự án 7" });

  await moveWithKeyboard(user, "Dự án 7", "ArrowUp");

  // Listed: 1..5 and 7, in slots 0-4 and 6; project 6 keeps slot 5.
  await waitFor(() => expect(saved).toEqual([{ projectIds: [1, 2, 3, 4, 7, 6, 5].map(id) }]));
});

it("restores the order and says so when the order cannot be saved", async () => {
  stackRows("[data-project-id]");
  server.use(handleReorderChatProjects(() => new HttpResponse(null, { status: 500 })));
  const user = userEvent.setup();
  await show([1, 2, 3].map(project));
  await screen.findByRole("button", { name: "Hội thoại trong Dự án 1" });

  await moveWithKeyboard(user, "Dự án 1", "ArrowDown");

  expect(await screen.findByText(/Chưa xác nhận được kết quả/)).toBeInTheDocument();
  expect(listedNames()).toEqual(["Dự án 1", "Dự án 2", "Dự án 3"]);
});
