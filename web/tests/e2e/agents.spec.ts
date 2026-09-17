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
    taskPrompt: "",
    starterPrompts: [],
    sourceIds: [],
    sources: [],
    tools: ["search"],
    mcpServers: [],
    fileIds: [],
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
    replaceBaseSystemPrompt: false,
    datetimeAware: true,
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
  await page.route("**/api/chat/persona-share-options**", (route) =>
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
      state.updated = request.postDataJSON();
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
    if (path.endsWith("/sharing") && request.method() === "PUT") {
      state.shared = request.postDataJSON();
      return route.fulfill({ json: state.agents[0] });
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
    iconName: "chart",
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
    iconName: "finance",
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
    iconName: "people",
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
    iconName: "legal",
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
    iconName: "document",
    labels: [{ id: "70000000-0000-4000-8000-000000000032", name: "Vận hành" }],
    groupShares: [{ group: { id: board, name: "Ban điều hành" }, permission: "VIEWER" }],
    owner: { actor: null, group: { id: board, name: "Ban điều hành" } },
  }),
  agent({
    id: "70000000-0000-4000-8000-000000000052",
    name: "Dự toán công trình",
    description: "Tính nhanh khối lượng và đơn giá từ bộ định mức đã duyệt.",
    iconName: "calculator",
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
  await page.getByRole("button", { name: "Xoá bộ lọc người tạo" }).click();

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

test("creates an agent on its own page and shares it with a Group and the organization", async ({
  page,
}) => {
  const state = await mockAgents(page, ["AGENTS_CREATE"], tascoAgents());
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/agents");
  await page.getByRole("button", { name: "Tạo trợ lý" }).click();
  await expect(page).toHaveURL(/\/agents\/create$/);
  await page.getByLabel("Tên trợ lý", { exact: true }).fill("Pháp chế hợp đồng");
  await page.getByLabel("Mô tả").fill("Rà soát điều khoản theo mẫu hợp đồng đã ban hành.");
  await page.getByRole("button", { name: "Đổi biểu tượng" }).click();
  await page.getByRole("radio", { name: "Pháp chế" }).click();
  await page.keyboard.press("Escape");
  await page.getByLabel("Nhắc việc mỗi lượt").fill("Luôn trích số hiệu mẫu hợp đồng.");
  await page.getByLabel("Câu hỏi gợi ý 1").fill("Điều khoản phạt vi phạm trong mẫu HĐ-02 là gì?");

  // The unsaved new agent survives a reload as a browser draft.
  await page.reload();
  await expect(page.getByText("Đã khôi phục bản nháp bạn đang tạo dở.")).toBeVisible();
  await expect(page.getByLabel("Tên trợ lý", { exact: true })).toHaveValue("Pháp chế hợp đồng");

  await page.getByRole("button", { name: "Thêm nguồn" }).click();
  await page.getByRole("option", { name: "Báo cáo tài chính Q2/2026" }).click();
  await page.keyboard.press("Escape");
  await page.getByLabel("Chỉ dùng tài liệu cập nhật từ ngày").fill("2026-01-01");
  await page.getByRole("switch", { name: "Tạo ảnh" }).click();
  await expect(page.getByRole("switch", { name: "Tạo ảnh" })).toHaveAttribute(
    "aria-checked",
    "false",
  );
  await page.getByRole("button", { name: "Tạo trợ lý", exact: true }).click();
  await expect(page).toHaveURL(/\/agents$/);
  expect(state.created).toMatchObject({
    name: "Pháp chế hợp đồng",
    iconName: "legal",
    taskPrompt: "Luôn trích số hiệu mẫu hợp đồng.",
    starterPrompts: ["Điều khoản phạt vi phạm trong mẫu HĐ-02 là gì?"],
    sourceIds: [sources[1]!.id],
    tools: ["search", "web_search", "code_interpreter"],
    knowledgeCutoff: "2026-01-01T00:00:00Z",
  });

  const hrCard = page.getByRole("article").filter({ hasText: "Chính sách nhân sự" });
  await hrCard.getByRole("button", { name: "Chia sẻ Chính sách nhân sự" }).click();
  const share = page.getByRole("dialog", { name: "Chia sẻ Chính sách nhân sự" });
  await expect(share).toContainText("Nguyễn Thị Thảo");
  await expect(share.getByRole("listbox")).toHaveCount(0);
  await expect(share.getByRole("button", { name: "Lưu chia sẻ" })).toBeDisabled();
  await share.getByPlaceholder("Thêm người hoặc Group").fill("Ban");
  await share.getByRole("option", { name: "Ban điều hành" }).click();
  await expect(share.getByPlaceholder("Thêm người hoặc Group")).toHaveValue("");
  await share.getByLabel("Quyền của Ban điều hành").selectOption("EDITOR");
  await share.getByRole("combobox", { name: "Quyền truy cập chung" }).selectOption("VIEWER");
  await page.screenshot({
    path: "../output/playwright/agents-share-dialog.png",
    animations: "disabled",
  });
  await share.getByRole("button", { name: "Lưu chia sẻ" }).click();
  await expect(share).toHaveCount(0);
  expect(state.shared).toEqual({
    users: [{ actorId: "70000000-0000-4000-8000-000000000040", permission: "VIEWER" }],
    groups: [{ groupId: board, permission: "EDITOR" }],
    isPublic: true,
    publicPermission: "VIEWER",
  });
});

test("edits an agent on its own page, saving only changes and guarding unsaved edits", async ({
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
  for (let index = 1; index < 8; index++) await addStarter.click();
  await expect(page.getByLabel(/^Câu hỏi gợi ý \d$/)).toHaveCount(8);
  await expect(addStarter).toBeDisabled();
  await page.getByLabel("Câu hỏi gợi ý 1", { exact: true }).fill("Chế độ nghỉ phép năm 2026?");
  await page.getByLabel("Max output (token)").fill("2048");

  await page.getByRole("link", { name: "Trợ lý", exact: true }).first().click();
  const guard = page.getByRole("alertdialog", { name: "Bỏ thay đổi chưa lưu?" });
  await expect(guard).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page).toHaveURL(/\/edit$/);

  await save.click();
  await expect(page).toHaveURL(/\/agents$/);
  expect(state.updated).toMatchObject({
    name: "Chính sách nhân sự",
    description: "Giải đáp chính sách lương và nghỉ phép năm 2026.",
    starterPrompts: ["Chế độ nghỉ phép năm 2026?"],
    outputTokenLimit: 2048,
  });

  const card = page.getByRole("article").filter({ hasText: "Chính sách nhân sự" });
  await card.hover();
  await card.getByRole("button", { name: "Thao tác khác cho Chính sách nhân sự" }).click();
  await page.getByRole("menuitem", { name: "Xóa trợ lý" }).click();
  await page.getByRole("alertdialog").getByRole("button", { name: "Xóa trợ lý" }).click();
  await expect.poll(() => state.deleted).toBe(hr);
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

  await page.goto("/settings/general");
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
  const pins = page
    .getByRole("region", { name: "Trợ lý đã ghim" })
    .or(page.locator("section[aria-labelledby=pinned-agents]"));
  await expect(pins.getByRole("listitem")).toHaveCount(3);

  const handle = pins.getByRole("button", { name: "Kéo để sắp xếp OKR/KPI hằng tháng" });
  await handle.focus();
  await page.keyboard.press("Space");
  await page.waitForTimeout(150);
  await page.keyboard.press("ArrowDown");
  await page.waitForTimeout(300);
  await page.keyboard.press("Space");
  await expect.poll(() => state.pins).toEqual([finance, kpi, hr]);

  await pins.getByRole("listitem").filter({ hasText: "Báo cáo tài chính" }).hover();
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

test("sends a starter prompt from the agent's detail view into a new conversation", async ({
  page,
}) => {
  await mockAgents(page, [], tascoAgents());
  await page.goto(`/agents?agent=${kpi}`);
  const viewer = page.getByRole("dialog", { name: "OKR/KPI hằng tháng" });
  await viewer.getByRole("button", { name: "Xếp loại KPI tháng 8 của các đơn vị" }).click();
  await expect(page).toHaveURL(/\/chat\/[0-9a-f-]{36}$/);
  await expect(page.getByText("Xếp loại KPI tháng 8 của các đơn vị").first()).toBeVisible();
  await expect(page.getByRole("textbox", { name: "Câu hỏi" })).toHaveValue("");
});
