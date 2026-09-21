import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n";
import { ChatStorageQuotaPage } from "./chat-storage-quota-page";

const getChatStorageQuota = vi.hoisted(() => vi.fn());
const setChatStorageQuota = vi.hoisted(() => vi.fn());
const setChatStoragePersonQuota = vi.hoisted(() => vi.fn());
const capabilities = vi.hoisted(() => ({ value: ["MODELS_MANAGE"] as string[] }));

vi.mock("@/lib/hey-api/sdk.gen", () => ({
  getChatStorageQuota: (...args: unknown[]) => getChatStorageQuota(...args),
  setChatStorageQuota: (...args: unknown[]) => setChatStorageQuota(...args),
  setChatStoragePersonQuota: (...args: unknown[]) => setChatStoragePersonQuota(...args),
}));

vi.mock("@/features/identity/application-session-context", () => ({
  useApplicationSession: () => ({
    actorId: "actor",
    authorizationVersion: 1,
    capabilities: capabilities.value,
  }),
}));

const PERSON = "44444444-4444-4444-8444-444444444444";

function show() {
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <ChatStorageQuotaPage />
    </QueryClientProvider>,
  );
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  vi.clearAllMocks();
  capabilities.value = ["MODELS_MANAGE"];
  getChatStorageQuota.mockResolvedValue({
    data: {
      tenantLimitBytes: 5242880,
      people: [{ actorId: PERSON, name: "Lan", maxBytes: 10485760 }],
    },
  });
  setChatStorageQuota.mockResolvedValue({ data: { tenantLimitBytes: 8388608, people: [] } });
  setChatStoragePersonQuota.mockResolvedValue({ data: { tenantLimitBytes: 5242880, people: [] } });
});
afterEach(cleanup);

it("saves the Tenant limit in whole MiB and reports what is in force", async () => {
  show();
  const user = userEvent.setup();

  const field = await screen.findByRole("textbox", { name: "Hạn mức mỗi người (MiB)" });
  expect(field).toHaveValue("5");
  expect(screen.getByText("Đang áp dụng 5 MB mỗi người")).toBeInTheDocument();

  await user.clear(field);
  await user.type(field, "8");
  await user.click(screen.getByRole("button", { name: "Lưu hạn mức" }));

  await waitFor(() =>
    expect(setChatStorageQuota).toHaveBeenCalledWith(
      expect.objectContaining({ body: { maxBytes: 8 * 1024 * 1024 } }),
    ),
  );
});

it("sets and removes one person's own limit", async () => {
  show();
  const user = userEvent.setup();

  const row = await screen.findByRole("row", { name: /Lan/ });
  expect(within(row).getByText("10 MB")).toBeInTheDocument();
  await user.click(within(row).getByRole("button", { name: "Bỏ hạn mức riêng của Lan" }));
  await waitFor(() =>
    expect(setChatStoragePersonQuota).toHaveBeenCalledWith(
      expect.objectContaining({ path: { actorId: PERSON }, body: { maxBytes: null } }),
    ),
  );

  await user.type(screen.getByRole("textbox", { name: "Actor ID" }), PERSON);
  await user.type(screen.getByRole("textbox", { name: "Hạn mức (MiB)" }), "20");
  await user.click(screen.getByRole("button", { name: "Đặt hạn mức riêng" }));
  await waitFor(() =>
    expect(setChatStoragePersonQuota).toHaveBeenLastCalledWith(
      expect.objectContaining({ path: { actorId: PERSON }, body: { maxBytes: 20 * 1024 * 1024 } }),
    ),
  );
});

it("refuses the page to anyone who cannot manage models", async () => {
  capabilities.value = [];
  show();

  expect(screen.getByRole("alert")).toHaveTextContent("Bạn không có quyền quản lý mô hình.");
  expect(getChatStorageQuota).not.toHaveBeenCalled();
});
