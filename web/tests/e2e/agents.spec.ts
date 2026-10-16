import { expect, test, type Page } from "@playwright/test";

const me = "e62a621f-41d2-4853-aa76-b600dafd8e34";
const hr = "70000000-0000-4000-8000-000000000001";
const finance = "70000000-0000-4000-8000-000000000002";
const kpi = "70000000-0000-4000-8000-000000000003";
const board = "70000000-0000-4000-8000-000000000010";
const sources = [
  {
    id: "70000000-0000-4000-8000-000000000020",
    name: "Báo cáo KPI tháng 8 – Khối Năng lượng",
    type: "GOOGLE_DRIVE",
  },
  { id: "70000000-0000-4000-8000-000000000021", name: "Báo cáo tài chính Q2/2026", type: "FILE" },
];

function identity(capabilities: string[]) {
  return {
    actorId: me,
    authorizationVersion: 1,
    uiLanguage: "vi",
    tenant: { displayName: "Tasco", role: "MEMBER" },
    capabilities: ["SYSTEM_BASIC", "SEARCH_READ", "CHAT_READ", "CHAT_WRITE", ...capabilities],
    scopedCapabilities: [],
  };
}

function agent(overrides: Record<string, unknown>) {
  return {
    builtin: false,
    permissions: {
      edit: false,
      share: false,
      setPublic: false,
      delete: false,
      transfer: false,
      leave: false,
      manage: false,
    },
    revision: 0,
    description: "",
    instructions: "",
    starterPrompts: [],
    sourceIds: [],
    sources: [],
    tools: ["search"],
    mcpServers: [],
    fileIds: [],
    avatarSeed: "tasco",
    hasAvatar: false,
    labels: [],
    owner: { actor: { actorId: me, name: "Đạt Phan", email: "dat@tasco.vn" }, group: null },
    vacant: false,
    userShares: [],
    groupShares: [],
    isPublic: false,
    publicPermission: "VIEWER",
    listed: true,
    featured: false,
    pinned: false,
    ...overrides,
  };
}

async function mockAgents(page: Page, capabilities: string[], initial: ReturnType<typeof agent>[]) {
  const state = {
    agents: initial,
    shared: undefined as unknown,
    created: undefined as unknown,
    updated: undefined as unknown,
    pins: undefined as unknown,
    deleted: undefined as string | undefined,
    listings: [] as { id: string; body: unknown }[],
  };
  await page.route("**/api/identity/me", (route) =>
    route.fulfill({ json: identity(capabilities) }),
  );
  await page.route("**/api/chat/persona-labels**", (route) =>
    route.fulfill({ json: [{ id: "70000000-0000-4000-8000-000000000030", name: "Tài chính" }] }),
  );
  await page.route("**/api/chat/persona-pins**", (route) => {
    if (route.request().method() === "PUT") {
      const { personaIds } = route.request().postDataJSON() as { personaIds: string[] };
      state.pins = personaIds;
      state.agents = state.agents.map((item) => ({
        ...item,
        pinned: personaIds.includes(item.id as string),
      }));
      return route.fulfill({
        json: personaIds.map((id) => state.agents.find((item) => item.id === id)),
      });
    }
    return route.fulfill({ json: state.agents.filter((item) => item.pinned) });
  });
  // The sidebar's assistants follow the pins, in their order.
  await page.route("**/api/chat/assistants", (route) =>
    route.fulfill({
      json: state.agents
        .filter((item) => item.pinned)
        .toSorted(
          (a, b) =>
            ((state.pins as string[] | undefined)?.indexOf(a.id as string) ?? 0) -
            ((state.pins as string[] | undefined)?.indexOf(b.id as string) ?? 0),
        )
        .map((item) => ({
          id: item.id,
          name: item.name,
          description: item.description,
          avatarSeed: item.avatarSeed,
          hasAvatar: false,
          builtin: false,
          ownerName: "Đạt Phan",
          usable: true,
          latestTopic: null,
        })),
    }),
  );
  await page.route("**/api/identity/principals**", (route) =>
    route.fulfill({
      json: {
        people: [
          {
            actorId: "70000000-0000-4000-8000-000000000040",
            name: "Nguyễn Thị Thảo",
            email: "thao@tasco.vn",
          },
        ],
        groups: [{ id: board, name: "Ban điều hành" }],
      },
    }),
  );
  await page.route("**/api/mcp/connections**", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/chat/document-sets**", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/chat/personas**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path.endsWith("/sources")) return route.fulfill({ json: sources });
    if (path.endsWith("/models")) return route.fulfill({ json: [] });
    const detail = /\/api\/chat\/personas\/([0-9a-f-]{36})$/.exec(path)?.[1];
    if (detail && request.method() === "GET")
      return route.fulfill({ json: state.agents.find((item) => item.id === detail) });
    if (detail && request.method() === "DELETE") {
      expect(request.headers()["x-memoryos-csrf"]).toBe("1");
      state.deleted = detail;
      state.agents = state.agents.filter((item) => item.id !== detail);
      return route.fulfill({ status: 204 });
    }
    if (detail && request.method() === "PUT") {
      expect(request.headers()["x-memoryos-csrf"]).toBe("1");
      const body = request.postDataJSON() as Record<string, unknown>;
      state.updated = body;
      state.agents = state.agents.map((item) =>
        item.id === detail
          ? {
              ...item,
              ...body,
              title: body.title ?? undefined,
              greeting: body.greeting ?? undefined,
              revision: (item.revision as number) + 1,
            }
          : item,
      );
      return route.fulfill({ json: state.agents.find((item) => item.id === detail) });
    }
    const listing = /\/api\/chat\/personas\/([0-9a-f-]{36})\/listing$/.exec(path)?.[1];
    if (listing) {
      const body = request.postDataJSON() as Record<string, unknown>;
      state.listings.push({ id: listing, body });
      state.agents = state.agents.map((item) =>
        item.id === listing ? { ...item, ...body } : item,
      );
      return route.fulfill({ json: state.agents.find((item) => item.id === listing) });
    }
    const sharing = /\/api\/chat\/personas\/([0-9a-f-]{36})\/sharing$/.exec(path)?.[1];
    if (sharing && request.method() === "PUT") {
      state.shared = request.postDataJSON();
      state.agents = state.agents.map((item) =>
        item.id === sharing ? { ...item, revision: (item.revision as number) + 1 } : item,
      );
      return route.fulfill({ json: state.agents.find((item) => item.id === sharing) });
    }
    if (request.method() === "POST") {
      expect(request.headers()["x-memoryos-csrf"]).toBe("1");
      const body = request.postDataJSON();
      state.created = body;
      const created = agent({
        ...body,
        id: "70000000-0000-4000-8000-000000000099",
        sources: sources.filter((source) => body.sourceIds.includes(source.id)),
        permissions: {
          edit: true,
          share: true,
          setPublic: true,
          delete: true,
          transfer: true,
          leave: false,
          manage: false,
        },
      });
      state.agents = [...state.agents, created];
      return route.fulfill({ status: 201, json: created });
    }
    const view = new URL(request.url()).searchParams.get("view");
    const listed =
      view === "MINE"
        ? state.agents.filter((item) => item.owner.actor?.actorId === me)
        : view === "SHARED"
          ? state.agents.filter((item) => item.owner.actor?.actorId !== me)
          : state.agents;
    return route.fulfill({ json: listed });
  });
  return state;
}

const tascoAgents = () => [
  agent({
    id: kpi,
    name: "OKR/KPI hằng tháng",
    description: "Xếp loại A/B/C và kết quả KPI của các đơn vị theo tháng, kèm link báo cáo gốc.",
    featured: true,
    isPublic: true,
    avatarSeed: "chart",
    labels: [{ id: "70000000-0000-4000-8000-000000000030", name: "Tài chính" }],
    owner: {
      actor: {
        actorId: "70000000-0000-4000-8000-000000000041",
        name: "Lê Thu Hà",
        email: "ha@tasco.vn",
      },
      group: null,
    },
    sources: [sources[0]],
    sourceIds: [sources[0]!.id],
    starterPrompts: [
      "Xếp loại KPI tháng 8 của các đơn vị",
      "So sánh KPI tháng 7 và tháng 8 của khối Năng lượng",
    ],
    instructions: "Chỉ trả lời từ báo cáo KPI đã ban hành. Luôn nêu tháng và đơn vị.",
  }),
  agent({
    id: finance,
    name: "Báo cáo tài chính",
    description: "Tra cứu số liệu trong báo cáo tài chính đã ban hành theo quý.",
    avatarSeed: "finance",
    groupShares: [{ group: { id: board, name: "Ban điều hành" }, permission: "VIEWER" }],
    owner: { actor: null, group: { id: board, name: "Ban điều hành" } },
    permissions: {
      edit: false,
      share: false,
      setPublic: false,
      delete: false,
      transfer: false,
      leave: false,
      manage: false,
    },
  }),
  agent({
    id: hr,
    name: "Chính sách nhân sự",
    description: "Giải đáp chính sách lương, nghỉ phép và mẫu hợp đồng đang có hiệu lực.",
    avatarSeed: "people",
    pinned: true,
    permissions: {
      edit: true,
      share: true,
      setPublic: true,
      delete: true,
      transfer: true,
      leave: false,
      manage: false,
    },
    userShares: [
      {
        person: {
          actorId: "70000000-0000-4000-8000-000000000040",
          name: "Nguyễn Thị Thảo",
          email: "thao@tasco.vn",
        },
        permission: "VIEWER",
      },
    ],
  }),
];

const moreAgents = () => [
  agent({
    id: "70000000-0000-4000-8000-000000000050",
    name: "Pháp chế hợp đồng",
    description: "Rà soát điều khoản theo mẫu hợp đồng đã ban hành và chỉ ra điểm khác biệt.",
    avatarSeed: "legal",
    isPublic: true,
    labels: [{ id: "70000000-0000-4000-8000-000000000031", name: "Pháp chế" }],
    owner: {
      actor: {
        actorId: "70000000-0000-4000-8000-000000000042",
        name: "Trần Minh Quân",
        email: "quan@tasco.vn",
      },
      group: null,
    },
  }),
  agent({
    id: "70000000-0000-4000-8000-000000000051",
    name: "Quy trình mua sắm",
    description: "Hướng dẫn các bước đề xuất, phê duyệt và thanh toán theo quy chế mua sắm 2026.",
    avatarSeed: "document",
    labels: [{ id: "70000000-0000-4000-8000-000000000032", name: "Vận hành" }],
    groupShares: [{ group: { id: board, name: "Ban điều hành" }, permission: "VIEWER" }],
    owner: { actor: null, group: { id: board, name: "Ban điều hành" } },
  }),
  agent({
    id: "70000000-0000-4000-8000-000000000052",
    name: "Dự toán công trình",
    description: "Tính nhanh khối lượng và đơn giá từ bộ định mức đã duyệt.",
    avatarSeed: "calculator",
    labels: [
      { id: "70000000-0000-4000-8000-000000000030", name: "Tài chính" },
      { id: "70000000-0000-4000-8000-000000000032", name: "Vận hành" },
    ],
    owner: {
      actor: {
        actorId: "70000000-0000-4000-8000-000000000041",
        name: "Lê Thu Hà",
        email: "ha@tasco.vn",
      },
      group: null,
    },
    isPublic: true,
  }),
];

test("browses, filters and pins agents with visibility and owners at desktop and mobile widths", async ({
  page,
}) => {
  await mockAgents(page, ["AGENTS_CREATE"], [...tascoAgents(), ...moreAgents()]);
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/assistants");
  await expect(page).toHaveURL(/\/agents$/);
  const featured = page.getByRole("article").first();
  await expect(featured).toContainText("OKR/KPI hằng tháng");
  await expect(featured).toContainText("Nổi bật");
  await expect(featured).toContainText("Công khai");
  await expect(featured).toContainText("Lê Thu Hà");
  await expect(page.getByRole("article").filter({ hasText: "Báo cáo tài chính" })).toContainText(
    "Ban điều hành",
  );
  await expect(page.getByText("6 trợ lý")).toBeVisible();
  await page.screenshot({
    path: "../output/playwright/agents-gallery-desktop.png",
    fullPage: true,
  });
  await page.getByRole("article").filter({ hasText: "Chính sách nhân sự" }).hover();
  await page.screenshot({
    path: "../output/playwright/agents-gallery-hover.png",
    clip: { x: 240, y: 180, width: 1200, height: 360 },
  });

  await page.getByPlaceholder("Tìm theo tên, mô tả hoặc nhãn").fill("hợp đồng");
  await expect(page.getByRole("article")).toHaveCount(2);
  await page.getByPlaceholder("Tìm theo tên, mô tả hoặc nhãn").fill("");
  const labels = page.getByRole("group", { name: "Lọc theo nhãn" });
  await labels.getByRole("button", { name: /Tài chính/ }).click();
  await expect(page.getByRole("article")).toHaveCount(2);
  await labels.getByRole("button", { name: /Tài chính/ }).click();
  await expect(page.getByRole("article")).toHaveCount(6);

  await page.getByRole("button", { name: "Mọi người tạo" }).click();
  await page.getByRole("checkbox", { name: "Lê Thu Hà" }).click();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("article")).toHaveCount(2);
  await page.getByRole("button", { name: "Xóa bộ lọc người tạo" }).click();

  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole("article")).toHaveCount(6);
  await page.screenshot({ path: "../output/playwright/agents-gallery-mobile.png", fullPage: true });
});

test("shows the full snapshot to a use-only reader without edit or share controls", async ({
  page,
}) => {
  await mockAgents(page, [], tascoAgents());
  await page.goto("/agents");
  await expect(page.getByRole("button", { name: "Tạo trợ lý" })).toBeDisabled();
  const card = page.getByRole("article").filter({ hasText: "OKR/KPI hằng tháng" });
  await expect(card.getByRole("button", { name: /Chia sẻ/ })).toHaveCount(0);
  await card.getByRole("button", { name: "OKR/KPI hằng tháng" }).click();
  const viewer = page.getByRole("dialog", { name: "OKR/KPI hằng tháng" });
  await expect(viewer.getByRole("button", { name: "Sửa" })).toHaveCount(0);
  await viewer.getByRole("button", { name: /Cấu hình trợ lý/ }).click();
  await expect(viewer).toContainText("Báo cáo KPI tháng 8 – Khối Năng lượng");
  await expect(viewer).toContainText("Chỉ trả lời từ báo cáo KPI đã ban hành.");
  await page.screenshot({ path: "../output/playwright/agents-viewer.png" });

  // A copied share link opens the same detail view.
  await page.goto(`/agents?agent=${kpi}`);
  await expect(page.getByRole("dialog", { name: "OKR/KPI hằng tháng" })).toBeVisible();
});

test("creates an agent from the short dialog, configures it in place and shares from its tab", async ({
  page,
}) => {
  const state = await mockAgents(page, ["AGENTS_CREATE"], tascoAgents());
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/agents");
  await page.getByRole("button", { name: "Tạo trợ lý" }).click();
  const dialog = page.getByRole("dialog", { name: "Tạo trợ lý" });
  await dialog.getByLabel("Tên trợ lý").fill("Pháp chế hợp đồng");
  await dialog.getByLabel("Chức danh").fill("Chuyên viên pháp chế");
  await dialog.getByLabel("Mô tả").fill("Rà soát điều khoản theo mẫu hợp đồng đã ban hành.");
  await page.screenshot({ path: "../output/playwright/agents-create-dialog.png" });
  await dialog.getByRole("button", { name: "Tạo trợ lý" }).click();
  await expect(page).toHaveURL(/\/agents\/70000000-0000-4000-8000-000000000099\/edit$/);
  expect(state.created).toMatchObject({
    name: "Pháp chế hợp đồng",
    title: "Chuyên viên pháp chế",
    avatarSeed: expect.stringMatching(/^[A-Za-z0-9_-]{1,64}$/),
    tools: ["search", "web_search"],
  });
  expect(state.created).not.toHaveProperty("reasoningEffort");

  // One header: the way back, the state and Lưu, with no second bar under it.
  const save = page.getByRole("button", { name: "Lưu", exact: true });
  await expect(save).toBeDisabled();
  await expect(page.getByRole("tab", { name: "Cấu hình" })).toHaveAttribute(
    "aria-selected",
    "true",
  );
  await page.getByRole("button", { name: "Đổi ảnh đại diện" }).click();
  const studio = page.getByRole("dialog", { name: "Ảnh đại diện" });
  await studio.getByRole("radio", { name: "Khuôn mặt 3" }).click();
  await studio.getByRole("button", { name: "Xong" }).click();
  await page
    .getByLabel("Lời chào")
    .fill("Chào bạn, gửi mình điều khoản cần rà soát và số hiệu mẫu hợp đồng nhé.");
  await page.getByLabel("Câu hỏi gợi ý 1").fill("Điều khoản phạt vi phạm trong mẫu HĐ-02 là gì?");
  await page.getByRole("button", { name: "Thêm tài liệu" }).click();
  await page.getByRole("option", { name: "Báo cáo tài chính Q2/2026" }).click();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("switch", { name: "Tạo ảnh" })).toHaveAttribute(
    "aria-checked",
    "false",
  );
  await expect(page.getByRole("switch", { name: "Sandbox" })).toHaveAttribute(
    "aria-checked",
    "false",
  );
  await save.click();
  await expect(page.getByRole("status").filter({ hasText: "Đã lưu" })).toBeVisible();
  await expect(page).toHaveURL(/\/edit$/);
  expect(state.updated).toMatchObject({
    greeting: "Chào bạn, gửi mình điều khoản cần rà soát và số hiệu mẫu hợp đồng nhé.",
    starterPrompts: ["Điều khoản phạt vi phạm trong mẫu HĐ-02 là gì?"],
    sourceIds: [sources[1]!.id],
    tools: ["search", "web_search"],
  });

  // The card's share action opens the editor's Chia sẻ tab, where each change saves at once.
  await page.goto("/agents");
  const hrCard = page.getByRole("article").filter({ hasText: "Chính sách nhân sự" });
  await hrCard.hover();
  await hrCard.getByRole("button", { name: "Chia sẻ Chính sách nhân sự" }).click();
  await expect(page).toHaveURL(new RegExp(`/agents/${hr}/edit\\?tab=share$`));
  const panel = page.getByRole("tabpanel", { name: "Chia sẻ" });
  await expect(panel).toContainText("Nguyễn Thị Thảo");
  await panel.getByPlaceholder("Thêm người hoặc Group").fill("Ban");
  await panel.getByRole("option", { name: "Ban điều hành" }).click();
  await expect
    .poll(() => state.shared)
    .toMatchObject({ groups: [{ groupId: board, permission: "VIEWER" }] });
  await panel.getByLabel("Quyền của Ban điều hành").selectOption("EDITOR");
  await expect
    .poll(() => state.shared)
    .toMatchObject({ groups: [{ groupId: board, permission: "EDITOR" }] });
  await panel.getByRole("combobox", { name: "Quyền truy cập chung" }).selectOption("VIEWER");
  await expect
    .poll(() => state.shared)
    .toEqual({
      users: [{ actorId: "70000000-0000-4000-8000-000000000040", permission: "VIEWER" }],
      groups: [{ groupId: board, permission: "EDITOR" }],
      isPublic: true,
      publicPermission: "VIEWER",
    });
  await page.screenshot({
    path: "../output/playwright/agents-share-tab.png",
    animations: "disabled",
  });
});

test("edits an agent in place, saving only changes and guarding unsaved edits", async ({
  page,
}) => {
  const state = await mockAgents(page, ["AGENTS_CREATE"], tascoAgents());
  await page.goto(`/agents/${hr}/edit`);
  const save = page.getByRole("button", { name: "Lưu", exact: true });
  await expect(page.getByLabel("Tên trợ lý", { exact: true })).toHaveValue("Chính sách nhân sự");
  await expect(save).toBeDisabled();
  await page.getByLabel("Mô tả").fill("Giải đáp chính sách lương và nghỉ phép năm 2026.");
  await expect(save).toBeEnabled();
  const addStarter = page.getByRole("button", { name: "Thêm câu gợi ý" });
  for (let index = 1; index < 4; index++) await addStarter.click();
  await expect(page.getByLabel(/^Câu hỏi gợi ý \d$/)).toHaveCount(4);
  await expect(addStarter).toBeHidden();
  await page.getByLabel("Câu hỏi gợi ý 1", { exact: true }).fill("Chế độ nghỉ phép năm 2026?");
  await page.getByLabel("Chức danh").fill("Chuyên viên nhân sự");

  // Switching tabs keeps the unsaved changes and does not ask; leaving the page does.
  await page.getByRole("tab", { name: "Chia sẻ" }).click();
  await expect(page).toHaveURL(/\/edit\?tab=share$/);
  await page.getByRole("tab", { name: "Cấu hình" }).click();
  await expect(page.getByLabel("Chức danh")).toHaveValue("Chuyên viên nhân sự");
  await page
    .getByRole("navigation", { name: "Đường dẫn phân cấp" })
    .getByRole("link", { name: "Trợ lý" })
    .click();
  const guard = page.getByRole("alertdialog", { name: "Bỏ thay đổi chưa lưu?" });
  await expect(guard).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page).toHaveURL(/\/edit$/);

  await save.click();
  await expect(page.getByRole("status").filter({ hasText: "Đã lưu" })).toBeVisible();
  await expect(save).toBeDisabled();
  expect(state.updated).toMatchObject({
    name: "Chính sách nhân sự",
    description: "Giải đáp chính sách lương và nghỉ phép năm 2026.",
    starterPrompts: ["Chế độ nghỉ phép năm 2026?"],
    title: "Chuyên viên nhân sự",
  });

  await page
    .getByRole("navigation", { name: "Đường dẫn phân cấp" })
    .getByRole("link", { name: "Trợ lý" })
    .click();
  await expect(page).toHaveURL(/\/agents$/);
  const card = page.getByRole("article").filter({ hasText: "Chính sách nhân sự" });
  await card.hover();
  await card.getByRole("button", { name: "Thao tác khác cho Chính sách nhân sự" }).click();
  await page.getByRole("menuitem", { name: "Xóa trợ lý" }).click();
  await page.getByRole("alertdialog").getByRole("button", { name: "Xóa trợ lý" }).click();
  await expect.poll(() => state.deleted).toBe(hr);
});

test("tests the unsaved assistant beside the editor without saving it", async ({ page }) => {
  const state = await mockAgents(page, ["AGENTS_CREATE"], tascoAgents());
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(`/agents/${hr}/edit`);
  const chat = page.getByRole("region", { name: "Chat thử" });
  await expect(chat.getByRole("heading", { name: "Chính sách nhân sự" })).toBeVisible();
  await expect(chat.getByText("Đang dùng bản chưa lưu")).toHaveCount(0);

  await page.getByLabel("Lời chào").fill("Chào bạn, hỏi mình về nghỉ phép nhé.");
  await page
    .getByRole("textbox", { name: "Hướng dẫn", exact: true })
    .fill("Trả lời ngắn gọn, ghi rõ điều khoản.");
  await expect(chat.getByText("Chào bạn, hỏi mình về nghỉ phép nhé.")).toBeVisible();
  await expect(chat.getByText("Đang dùng bản chưa lưu")).toBeVisible();

  const created = page.waitForRequest(
    (request) =>
      request.method() === "POST" && new URL(request.url()).pathname === "/api/chat/sessions",
  );
  const sent = page.waitForRequest(
    (request) => request.method() === "POST" && request.url().endsWith("/messages"),
  );
  await chat.getByRole("textbox", { name: "Câu hỏi", exact: true }).fill("Nghỉ phép năm mấy ngày?");
  await chat.getByRole("button", { name: "Gửi câu hỏi" }).click();
  expect((await created).postDataJSON()).toMatchObject({ personaId: hr, temporary: true });
  expect((await sent).postDataJSON()).toMatchObject({
    text: "Nghỉ phép năm mấy ngày?",
    draftPersona: {
      instructions: "Trả lời ngắn gọn, ghi rõ điều khoản.",
      greeting: "Chào bạn, hỏi mình về nghỉ phép nhé.",
    },
  });
  await expect(chat.getByText("Hello 👋")).toBeVisible();
  expect(state.updated).toBeUndefined();

  await chat.getByRole("button", { name: "Làm mới" }).click();
  await expect(chat.getByText("Nghỉ phép năm mấy ngày?")).toHaveCount(0);
  await expect(chat.getByText("Chào bạn, hỏi mình về nghỉ phép nhé.")).toBeVisible();
});

test("manages prompt shortcuts inline and inserts them from /", async ({ page }) => {
  await mockAgents(page, ["AGENTS_CREATE"], tascoAgents());
  const shortcuts = [
    {
      id: "70000000-0000-4000-8000-000000000061",
      name: "Tóm tắt hợp đồng",
      content: "Tóm tắt các điều khoản chính, nghĩa vụ và mức phạt trong hợp đồng này.",
      active: true,
      isPublic: true,
      hidden: false,
      revision: 0,
    },
  ];
  let created: unknown;
  await page.route("**/api/chat/prompt-shortcuts**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path.endsWith("/preferences")) return route.fulfill({ json: { enabled: true } });
    if (request.method() === "POST") {
      created = request.postDataJSON();
      const shortcut = {
        ...(created as { name: string; content: string }),
        id: "70000000-0000-4000-8000-000000000062",
        active: true,
        isPublic: false,
        hidden: false,
        revision: 0,
      };
      shortcuts.push(shortcut);
      return route.fulfill({ status: 201, json: shortcut });
    }
    return route.fulfill({ json: shortcuts });
  });

  await page.goto("/settings/chat");
  await expect(page.getByRole("heading", { name: "Lệnh tắt", exact: true })).toBeVisible({
    timeout: 15000,
  });
  const shared = page.getByLabel("Tên lệnh tắt").and(page.locator("[readonly]"));
  await expect(shared).toHaveValue("Tóm tắt hợp đồng");
  await expect(shared).toHaveAttribute("readonly", "");
  await page.getByLabel("Tên lệnh tắt").first().fill("KPI tháng");
  await page
    .getByLabel("Nội dung lệnh tắt")
    .first()
    .fill("Tổng hợp xếp loại KPI tháng này của các đơn vị.");
  await page.getByRole("heading", { name: "Lệnh tắt", exact: true }).click();
  await expect
    .poll(() => created)
    .toEqual({
      name: "KPI tháng",
      content: "Tổng hợp xếp loại KPI tháng này của các đơn vị.",
    });

  await page.goto("/");
  const composer = page.getByRole("textbox", { name: "Câu hỏi" });
  await composer.click();
  await composer.pressSequentially("/tom tat");
  const option = page.getByRole("option", { name: /Tóm tắt hợp đồng/ });
  await expect(option).toBeVisible();
  await expect(page.getByRole("option", { name: /KPI tháng/ })).toHaveCount(0);
  await option.click();
  await expect(composer).toHaveValue(
    "Tóm tắt các điều khoản chính, nghĩa vụ và mức phạt trong hợp đồng này.",
  );
});

test("reorders and unpins sidebar agents and features agents from administration", async ({
  page,
}) => {
  const pinned = tascoAgents().map((item) => ({ ...item, pinned: true }));
  const state = await mockAgents(page, ["AGENTS_CREATE", "AGENTS_MANAGE"], pinned);
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/agents");
  const pins = page.locator("section[aria-labelledby=sidebar-assistants]");
  await expect(pins.getByRole("button", { name: /^Kéo để sắp xếp/ })).toHaveCount(3);

  const handle = pins.getByRole("button", { name: "Kéo để sắp xếp OKR/KPI hằng tháng" });
  await handle.focus();
  await page.keyboard.press("Space");
  await page.waitForTimeout(150);
  await page.keyboard.press("ArrowDown");
  await page.waitForTimeout(300);
  await page.keyboard.press("Space");
  await expect.poll(() => state.pins).toEqual([finance, kpi, hr]);

  await pins.getByRole("link", { name: /Báo cáo tài chính/ }).hover();
  await pins.getByRole("button", { name: "Bỏ ghim Báo cáo tài chính" }).click();
  await expect.poll(() => state.pins).toEqual([kpi, hr]);

  await page.goto("/admin/agents");
  const row = page.getByRole("listitem").filter({ hasText: "Chính sách nhân sự" });
  await row.hover();
  await row.getByRole("button", { name: "Đặt Chính sách nhân sự nổi bật" }).click();
  await expect
    .poll(() => state.listings.find((item) => item.id === hr)?.body)
    .toMatchObject({ featured: true, listed: true });
});

test("sends a starter prompt from the agent's detail view into a new topic", async ({ page }) => {
  await mockAgents(page, [], tascoAgents());
  await page.goto(`/agents?agent=${kpi}`);
  const viewer = page.getByRole("dialog", { name: "OKR/KPI hằng tháng" });
  await viewer.getByRole("button", { name: "Xếp loại KPI tháng 8 của các đơn vị" }).click();
  await expect(page).toHaveURL(/\/chat\/[0-9a-f-]{36}$/);
  await expect(page.getByText("Xếp loại KPI tháng 8 của các đơn vị").first()).toBeVisible();
  await expect(page.getByRole("textbox", { name: "Câu hỏi" })).toHaveValue("");
});

test.describe("on a phone", () => {
  // A touch screen cannot hover, so the card's actions are always shown there.
  test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });

  test("keeps a card's actions clear of its name and description", async ({ page }) => {
    await mockAgents(page, ["AGENTS_CREATE"], tascoAgents());
    await page.goto("/agents");
    const card = page.getByRole("article").filter({ hasText: "Chính sách nhân sự" });
    const edit = card.getByRole("button", { name: "Sửa Chính sách nhân sự" });
    await expect(edit).toBeVisible();
    const actions = (await edit.boundingBox())!;
    for (const text of [
      card.getByRole("heading", { name: "Chính sách nhân sự" }),
      card.getByText("Giải đáp chính sách lương, nghỉ phép và mẫu hợp đồng đang có hiệu lực."),
    ]) {
      const box = (await text.boundingBox())!;
      expect(box.y + box.height).toBeLessThanOrEqual(actions.y);
    }
    await card.scrollIntoViewIfNeeded();
    await page.screenshot({ path: "../output/playwright/agents-card-phone.png" });
  });
});

test("the editor keeps the shell header in view and Lưu says why it is off", async ({ page }) => {
  await mockAgents(page, ["AGENTS_CREATE"], tascoAgents());
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(`/agents/${hr}/edit`);
  await expect(page.getByRole("region", { name: "Chat thử" })).toBeVisible();
  const banner = page.getByRole("banner");
  const save = page.getByRole("button", { name: "Lưu", exact: true });
  // The switches' hidden inputs sit low in the form; they overflow the form's scroller, never the frame around the
  // header, so bringing a header control into view (as a hover retry or a screen reader does) cannot move it.
  await page.getByRole("switch", { name: "Sandbox" }).focus();
  expect(
    await page.evaluate(() => {
      const frame = document.querySelector("[data-slot=sidebar-inset]")!;
      return frame.scrollHeight - frame.clientHeight;
    }),
  ).toBe(0);
  await save.evaluate((element) => element.scrollIntoView({ block: "center" }));
  expect((await banner.boundingBox())?.y).toBe(0);

  await save.locator("..").hover();
  await expect(page.getByRole("tooltip", { name: "Chưa có thay đổi" })).toBeVisible();
  expect((await banner.boundingBox())?.y).toBe(0);
  await page.keyboard.press("Escape");
  await expect(page.getByRole("tooltip", { name: "Chưa có thay đổi" })).toHaveCount(0);
  await page.mouse.move(700, 600);
  await page.getByRole("link", { name: "Hủy" }).focus();
  await page.keyboard.press("Tab");
  await expect(page.getByRole("tooltip", { name: "Chưa có thay đổi" })).toBeVisible();
});

test("opens the test chat's sources over it instead of beside it", async ({ page }) => {
  await mockAgents(page, ["AGENTS_CREATE"], tascoAgents());
  await page.setViewportSize({ width: 1440, height: 900 });
  // The fixture server answers a "grounded" conversation with one cited source.
  await page.route("**/api/chat/sessions", async (route) => {
    const request = route.request();
    if (request.method() !== "POST") return route.fallback();
    return route.fallback({
      postData: JSON.stringify({ ...request.postDataJSON(), mode: "grounded" }),
    });
  });
  await page.goto(`/agents/${hr}/edit`);
  const chat = page.getByRole("region", { name: "Chat thử" });
  await chat.getByRole("textbox", { name: "Câu hỏi", exact: true }).fill("Nghỉ phép năm mấy ngày?");
  await chat.getByRole("button", { name: "Gửi câu hỏi" }).click();
  await expect(chat.getByText("17 days")).toBeVisible();
  const width = (await chat.boundingBox())!.width;
  await chat.getByRole("button", { name: /^Nguồn/ }).click();
  const sheet = page.getByRole("dialog");
  await expect(sheet).toBeVisible();
  await expect(sheet.locator('[data-slot="source-row"]')).toHaveCount(1);
  await expect(page.getByRole("complementary", { name: "Nguồn" })).toHaveCount(0);
  expect((await chat.boundingBox())!.width).toBe(width);
  await page.keyboard.press("Escape");
  await expect(sheet).toHaveCount(0);
});

test("uploads a file from the editor, attaches it once ready and says where the assistant searches", async ({
  page,
}) => {
  const state = await mockAgents(page, ["AGENTS_CREATE"], tascoAgents());
  const uploaded = {
    id: "70000000-0000-4000-8000-000000000060",
    filename: "Quy trình kê khai.pdf",
    mediaType: "application/pdf",
    sizeBytes: 2048,
  };
  let reads = 0;
  await page.route("**/api/chat/files/policy", (route) =>
    route.fulfill({ json: { maxSizeBytes: 104857600 } }),
  );
  await page.route("**/api/chat/files/uploads", (route) =>
    route.fulfill({ json: { file: { ...uploaded, status: "READY" } } }),
  );
  await page.route(`**/api/chat/files/${uploaded.id}`, (route) => {
    reads += 1;
    // The first reads find it still being extracted, as a fresh upload is.
    return route.fulfill({ json: { ...uploaded, status: reads < 3 ? "PROCESSING" : "READY" } });
  });
  await page.goto(`/agents/${hr}/edit`);
  await expect(page.getByText("Tìm trong mọi tài liệu được phép đọc")).toBeVisible();

  await page.getByRole("button", { name: "Thêm tài liệu" }).click();
  const chooser = page.waitForEvent("filechooser");
  await page.getByRole("option", { name: "Tải tệp lên" }).click();
  await (
    await chooser
  ).setFiles({
    name: uploaded.filename,
    mimeType: uploaded.mediaType,
    buffer: Buffer.from("%PDF-1.7"),
  });

  await expect(page.getByText("Đang xử lý")).toBeVisible();
  await expect(page.getByText("Tìm thêm trong mọi tài liệu được phép đọc")).toBeVisible();
  await expect(page.getByText("Đang xử lý")).toHaveCount(0);
  await page.getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: "Đã lưu" })).toBeVisible();
  expect(state.updated).toMatchObject({ fileIds: [uploaded.id] });

  // Turning the switch off greys the list out and the state line goes with it.
  await page.getByRole("switch", { name: "Tìm trong tài liệu nội bộ" }).click();
  await expect(page.getByText(/mọi tài liệu được phép đọc/)).toHaveCount(0);
  await expect(page.getByText(uploaded.filename)).toBeVisible();
});
