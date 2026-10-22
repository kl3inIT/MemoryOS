import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n";
import type { McpServerInput, McpServerView } from "@/lib/hey-api/types.gen";
import { McpServerEditor } from "./mcp-server-editor";
import { slugFromName } from "./mcp-servers";

const saved = (overrides: Partial<McpServerView> = {}): McpServerView => ({
  id: "s1",
  slug: "drive",
  name: "Drive",
  description: null,
  url: "https://drivemcp.googleapis.com/mcp/v1",
  authType: "OAUTH",
  authPerformer: "PER_USER",
  oauthProviderMode: "AUTO_DISCOVERY",
  oauthScopes: [],
  oauthAdditionalParameters: {},
  headerNames: [],
  sharedCredentialConfigured: false,
  tenantWide: true,
  groupIds: [],
  personaIds: [],
  publicBots: false,
  status: "CREATED",
  lastRefreshedAt: null,
  toolCount: 0,
  enabledToolCount: 0,
  revision: 3,
  ...overrides,
});

function edit(server?: McpServerView) {
  const onSave = vi.fn<(input: McpServerInput, revision?: number) => void>();
  render(
    <QueryClientProvider client={new QueryClient()}>
      <McpServerEditor server={server} saving={false} onSave={onSave} onClose={() => undefined} />
    </QueryClientProvider>,
  );
  return onSave;
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
});

it("derives the short code from the name", () => {
  expect(slugFromName("Google Drive")).toBe("googledrive");
  expect(slugFromName("Đơn hàng Miền Bắc 2026")).toBe("donhangmienbac20");
  expect(slugFromName("!!!")).toBe("mcp");
});

it("adds a server from its address and name alone", async () => {
  const onSave = edit();
  await userEvent.type(screen.getByLabelText("Địa chỉ máy chủ"), "https://mcp.linear.app/mcp");
  await userEvent.type(screen.getByLabelText("Tên"), "Linear");
  await userEvent.click(screen.getByRole("button", { name: "Thêm" }));
  await waitFor(() => expect(onSave).toHaveBeenCalledOnce());
  expect(onSave.mock.lastCall?.[0]).toMatchObject({
    slug: "linear",
    name: "Linear",
    url: "https://mcp.linear.app/mcp",
    authType: "OAUTH",
    authPerformer: "PER_USER",
    oauthProviderMode: "AUTO_DISCOVERY",
    oauthScopes: [],
    oauthAdditionalParameters: {},
    headers: { action: "KEEP" },
    tenantWide: true,
  });
});

it("adds the Google parameters a Google API server needs and sends scopes and headers", async () => {
  const onSave = edit(saved());
  await userEvent.click(await screen.findByRole("button", { name: "Thêm tham số Google" }));
  await userEvent.type(screen.getByLabelText(/Scope/), "drive.readonly openid");
  await userEvent.click(screen.getAllByRole("button", { name: "Thêm dòng" }).at(-1)!);
  const names = screen.getAllByLabelText("Tên");
  const values = screen.getAllByLabelText("Giá trị");
  await userEvent.type(names.at(-1)!, "X-Team");
  await userEvent.type(values.at(-1)!, "north");
  await userEvent.click(screen.getByRole("button", { name: "Lưu" }));
  await waitFor(() => expect(onSave).toHaveBeenCalledOnce());
  expect(onSave.mock.lastCall).toEqual([
    expect.objectContaining({
      oauthScopes: ["drive.readonly", "openid"],
      oauthAdditionalParameters: { access_type: "offline", prompt: "consent" },
      headers: { action: "REPLACE", values: { "X-Team": "north" } },
    }),
    3,
  ]);
});

it("keeps saved header values unless they are replaced or removed", async () => {
  const onSave = edit(saved({ authType: "API_TOKEN", headerNames: ["Authorization"] }));
  expect(await screen.findByText("Authorization")).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Xóa header" }));
  await userEvent.click(screen.getByRole("button", { name: "Lưu" }));
  await waitFor(() => expect(onSave).toHaveBeenCalledOnce());
  expect(onSave.mock.lastCall?.[0].headers).toEqual({ action: "REMOVE" });
});

it("shows the chosen sign-in method as a checked radio", () => {
  edit();
  expect(screen.getByRole("radio", { name: "OAuth" })).toBeChecked();
  expect(screen.getByRole("radio", { name: "Mỗi người dùng tài khoản của mình" })).toBeChecked();
  expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
});

it("adds a catalog entry with its address, short code, scopes and parameters filled in", async () => {
  const onSave = vi.fn<(input: McpServerInput, revision?: number) => void>();
  render(
    <QueryClientProvider client={new QueryClient()}>
      <McpServerEditor
        preset={{
          name: "Gmail",
          url: "https://gmailmcp.googleapis.com/mcp/v1",
          slug: "gmail",
          oauthScopes: ["https://www.googleapis.com/auth/gmail.readonly"],
          oauthAdditionalParameters: { access_type: "offline", prompt: "consent" },
        }}
        saving={false}
        onSave={onSave}
        onClose={() => undefined}
      />
    </QueryClientProvider>,
  );
  expect(screen.getByRole("heading", { name: "Thêm Gmail" })).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Thêm" }));
  await waitFor(() => expect(onSave).toHaveBeenCalledOnce());
  expect(onSave.mock.lastCall?.[0]).toMatchObject({
    slug: "gmail",
    url: "https://gmailmcp.googleapis.com/mcp/v1",
    oauthScopes: ["https://www.googleapis.com/auth/gmail.readonly"],
    oauthAdditionalParameters: { access_type: "offline", prompt: "consent" },
  });
});
