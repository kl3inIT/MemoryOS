import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
  RouterProvider,
} from "@tanstack/react-router";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse } from "msw";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n/index";
import { handleListChatAssistants, handleReplaceChatPersonaPins } from "@/lib/hey-api/msw.gen";
import type { ChatAssistant } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { stackRows } from "@/test/row-layout";
import { AssistantsSection } from "./chat-assistants-section";

// The home sidebar's assistants: five rows, then more on request, as LobeHub's agent list.

const capabilities = vi.hoisted(() => ({ create: false }));
vi.mock("@/features/identity/application-session-context", () => ({
  useApplicationSession: () => ({ actorId: "actor", authorizationVersion: 1, capabilities: [] }),
  useGlobalCapability: (capability: string) =>
    capability === "AGENTS_CREATE" && capabilities.create,
}));

const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, "0")}`;
const names = ["MemoryOS", ...[1, 2, 3, 4, 5, 6, 7].map((n) => `Trợ lý ${n}`)];

const assistant = (n: number, running = false): ChatAssistant => ({
  id: id(n),
  name: names[n]!,
  description: "",
  avatarSeed: names[n]!,
  hasAvatar: false,
  builtin: n === 0,
  ownerName: n === 0 ? null : "Phòng Nhân sự",
  usable: true,
  latestTopic: {
    sessionId: id(100 + n),
    title: `Chủ đề ${n}`,
    lastMessageAt: "2026-10-16T08:00:00Z",
    preview: `Câu hỏi ${n}`,
    replyRunning: running,
  },
});

/** MemoryOS and seven pins, in pin order. */
const roster = (running?: number) =>
  [0, 1, 2, 3, 4, 5, 6, 7].map((n) => assistant(n, n === running));

function show(assistants: ChatAssistant[], openAssistant?: string) {
  server.use(handleListChatAssistants({ body: assistants }));
  const rootRoute = createRootRoute({ component: Outlet });
  const page = (path: string) =>
    createRoute({
      getParentRoute: () => rootRoute,
      path,
      component: () => <AssistantsSection openAssistant={openAssistant} />,
    });
  const router = createRouter({
    routeTree: rootRoute.addChildren([
      page("/"),
      page("/chat/$sessionId"),
      page("/agents"),
      page("/agents/create"),
    ]),
    history: createMemoryHistory({ initialEntries: ["/"] }),
  });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

/** The assistants' names in the order the section lists them. */
async function listed() {
  await screen.findByText("MemoryOS");
  return screen
    .getAllByRole("link")
    .map((link) => within(link).queryByText(/^(MemoryOS|Trợ lý \d)$/)?.textContent)
    .filter(Boolean);
}

beforeEach(async () => {
  capabilities.create = false;
  window.localStorage.clear();
  await i18n.changeLanguage("vi");
});
afterEach(cleanup);

it("lists MemoryOS and the first four pins, then opens the rest in place and folds them again", async () => {
  const user = userEvent.setup();
  show(roster());

  expect(await listed()).toEqual(names.slice(0, 5));
  await user.click(screen.getByRole("button", { name: "Xem thêm (3)" }));
  expect(await listed()).toEqual(names);
  await user.click(screen.getByRole("button", { name: "Thu gọn" }));
  expect(await listed()).toEqual(names.slice(0, 5));
});

it("lists as many rows as the menu says and remembers the choice", async () => {
  const user = userEvent.setup();
  const view = show(roster());
  await listed();

  await user.click(screen.getByRole("button", { name: "Tùy chọn danh sách trợ lý" }));
  await user.click(await screen.findByRole("menuitemradio", { name: "10 trợ lý" }));

  expect(await listed()).toEqual(names);
  expect(screen.queryByRole("button", { name: /Xem thêm/ })).not.toBeInTheDocument();
  expect(window.localStorage.getItem("memoryos:chat-assistants-rows")).toBe("10");
  view.unmount();
  show(roster());
  expect(await listed()).toEqual(names);
});

it("starts from five rows when storage holds no known choice", async () => {
  window.localStorage.setItem("memoryos:chat-assistants-rows", "7");
  show(roster());

  expect(await listed()).toEqual(names.slice(0, 5));
});

it("always lists the open assistant and one that is answering", async () => {
  show(roster(6), id(7));

  expect(await listed()).toEqual([...names.slice(0, 5), "Trợ lý 6", "Trợ lý 7"]);
  expect(screen.getByRole("button", { name: "Xem thêm (1)" })).toBeInTheDocument();
});

it("saves every pin, with the hidden ones in their places, when a shown one moves", async () => {
  stackRows("[data-assistant-id]");
  const saved: unknown[] = [];
  server.use(
    handleReplaceChatPersonaPins(async ({ request }) => {
      const body = (await request.json()) as { personaIds: string[] };
      saved.push(body);
      return HttpResponse.json(body.personaIds);
    }),
  );
  const user = userEvent.setup();
  show(roster(), id(7));
  await listed();

  // Shown pins: 1..4 and the open 7, in slots 0-3 and 6; pins 5 and 6 keep slots 4 and 5.
  screen.getByRole("button", { name: "Kéo để sắp xếp Trợ lý 7" }).focus();
  await user.keyboard(" ");
  await user.keyboard("{ArrowUp}");
  await user.keyboard(" ");

  await waitFor(() => expect(saved).toEqual([{ personaIds: [1, 2, 3, 7, 5, 6, 4].map(id) }]));
});

it("heads the section with the rows menu, explore and, for who may, create", async () => {
  capabilities.create = true;
  show(roster());
  await listed();

  expect(screen.getByRole("link", { name: "Khám phá trợ lý" })).toHaveAttribute("href", "/agents");
  expect(screen.getByRole("link", { name: "Tạo trợ lý" })).toHaveAttribute(
    "href",
    "/agents/create",
  );
  // Explore lives in the header, as LobeHub's "view all agents"; no row repeats it.
  expect(screen.queryByText("Khám phá trợ lý")).not.toBeInTheDocument();
});

it("offers no create entry to who may not create an assistant", async () => {
  show(roster());
  await listed();

  expect(screen.getByRole("link", { name: "Khám phá trợ lý" })).toBeInTheDocument();
  expect(screen.queryByRole("link", { name: "Tạo trợ lý" })).not.toBeInTheDocument();
});
