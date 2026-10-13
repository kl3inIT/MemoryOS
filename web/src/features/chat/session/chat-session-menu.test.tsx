import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  createMemoryHistory,
  createRootRoute,
  createRouter,
  RouterProvider,
} from "@tanstack/react-router";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse } from "msw";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { ActionNotifications } from "@/components/ui/action-notifications";
import { i18n } from "@/i18n/index";
import {
  handleCreateChatProject,
  handleListChatProjects,
  handleMoveChatProject,
} from "@/lib/hey-api/msw.gen";
import type { ChatSession, ProjectView } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { ChatSessionMenu } from "./chat-session-menu";

const planning: ProjectView = {
  id: "44444444-4444-4444-8444-444444444444",
  name: "Kế hoạch",
  description: "",
  instructions: "",
  revision: 0,
  updatedAt: "2026-10-03T08:00:00Z",
  fileIds: [],
};
const finance: ProjectView = {
  ...planning,
  id: "55555555-5555-4555-8555-555555555555",
  name: "Tài chính",
};

const session: ChatSession = {
  id: "22222222-2222-4222-8222-222222222222",
  personaId: "33333333-3333-4333-8333-333333333333",
  rootMessageId: "66666666-6666-4666-8666-666666666666",
  title: "Báo cáo quý",
  createdAt: "2026-10-01T08:00:00Z",
  updatedAt: "2026-10-01T08:00:00Z",
  projectId: null,
  reasoningEffort: null,
  archivedAt: null,
  branchedFromSessionId: null,
  branchedFromMessageId: null,
  temporary: false,
};

/** The projectId bodies the conversation was moved with, in order. */
let moves: unknown[] = [];

async function show(overrides: Partial<ChatSession> = {}, onChange = vi.fn(async () => {})) {
  const rootRoute = createRootRoute({
    component: () => <ChatSessionMenu session={{ ...session, ...overrides }} onChange={onChange} />,
  });
  const router = createRouter({
    routeTree: rootRoute,
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
  const user = userEvent.setup();
  await user.click(await screen.findByRole("button", { name: "Thao tác hội thoại Báo cáo quý" }));
  return { user, onChange };
}

type User = ReturnType<typeof userEvent.setup>;

/** The submenu opens from the keyboard: jsdom has no geometry for the pointer's grace area to hold it open. */
async function openProjects(user: User) {
  (await screen.findByRole("menuitem", { name: "Chuyển đến dự án" })).focus();
  await user.keyboard("{ArrowRight}");
}

async function choose(user: User, name: string) {
  (await screen.findByRole("menuitem", { name })).focus();
  await user.keyboard("{Enter}");
}

function answerMoves(status = 200) {
  server.use(
    handleMoveChatProject(async ({ request }) => {
      moves.push(await request.json());
      return status === 200
        ? HttpResponse.json(session)
        : HttpResponse.json({ title: "Conflict", status }, { status });
    }),
  );
}

beforeEach(async () => {
  moves = [];
  await i18n.changeLanguage("vi");
  server.use(handleListChatProjects({ body: [planning, finance] }));
});
afterEach(cleanup);

it("moves the conversation into the chosen project with one click and refreshes", async () => {
  answerMoves();
  const { user, onChange } = await show();

  await openProjects(user);
  await choose(user, "Tài chính");

  await waitFor(() => expect(moves).toEqual([{ projectId: finance.id }]));
  await waitFor(() => expect(onChange).toHaveBeenCalledOnce());
  expect(screen.queryByRole("menu")).not.toBeInTheDocument();
});

it("lists the projects in the order the API returns them under New project", async () => {
  const { user } = await show();

  await openProjects(user);

  const submenu = (await screen.findByRole("menuitem", { name: "Kế hoạch" })).closest(
    '[role="menu"]',
  ) as HTMLElement;
  expect(
    within(submenu)
      .getAllByRole("menuitem")
      .map((item) => item.textContent),
  ).toEqual(["Dự án mới", "Kế hoạch", "Tài chính"]);
});

it("marks the current project and does not move into it", async () => {
  answerMoves();
  const { user } = await show({ projectId: planning.id });

  await openProjects(user);
  const current = await screen.findByRole("menuitem", { name: "Kế hoạch" });
  expect(current).toHaveAttribute("aria-disabled", "true");
  current.focus();
  await user.keyboard("{Enter}");

  expect(moves).toEqual([]);
});

it("offers to leave a project only for a conversation that is in one, and moves it out", async () => {
  answerMoves();
  await show();
  expect(screen.queryByRole("menuitem", { name: "Gỡ khỏi dự án" })).not.toBeInTheDocument();
  cleanup();

  const { user, onChange } = await show({ projectId: planning.id });
  await choose(user, "Gỡ khỏi dự án");

  await waitFor(() => expect(moves).toEqual([{ projectId: null }]));
  await waitFor(() => expect(onChange).toHaveBeenCalledOnce());
});

it("shows a refused move after the menu has closed", async () => {
  answerMoves(409);
  const { user, onChange } = await show();

  await openProjects(user);
  await choose(user, "Tài chính");

  expect(await screen.findByText(/hội thoại đang trả lời/)).toBeInTheDocument();
  expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  expect(onChange).not.toHaveBeenCalled();
});

it("says the projects did not load and loads them again on retry", async () => {
  server.use(handleListChatProjects(() => new HttpResponse(null, { status: 500 })));
  const { user } = await show();

  await openProjects(user);
  expect(await screen.findByText("Không tải được dự án.")).toBeInTheDocument();
  server.use(handleListChatProjects({ body: [planning] }));
  await choose(user, "Tải lại");

  expect(await screen.findByRole("menuitem", { name: "Kế hoạch" })).toBeInTheDocument();
  expect(screen.queryByText("Không tải được dự án.")).not.toBeInTheDocument();
});

it("creates a project from the menu and moves the conversation into it", async () => {
  answerMoves();
  const created: ProjectView = {
    ...planning,
    id: "77777777-7777-4777-8777-777777777777",
    name: "Mới",
  };
  server.use(handleCreateChatProject({ body: created }));
  const { user, onChange } = await show();

  await openProjects(user);
  await choose(user, "Dự án mới");
  const dialog = await screen.findByRole("dialog");
  await user.type(within(dialog).getByRole("textbox", { name: /Tên dự án/ }), "Mới");
  await user.click(within(dialog).getByRole("button", { name: "Tạo dự án" }));

  await waitFor(() => expect(moves).toEqual([{ projectId: created.id }]));
  await waitFor(() => expect(onChange).toHaveBeenCalledOnce());
  await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
});
