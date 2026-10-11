import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse } from "msw";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n/index";
import {
  handleListOcrConnections,
  handleListOcrProviders,
  handleSaveOcrConnection,
  handleSelectOcrProvider,
  handleTestOcrConnection,
} from "@/lib/hey-api/msw.gen";
import type { OcrConnectionResponse, OcrProviderResponse } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { OcrSettings } from "./ocr-settings";

type Call = { path: { provider: string }; body: Record<string, unknown> };
const listOcrProviders = vi.fn();
const saveOcrConnection = vi.fn<(call: Call) => void>();
const selectOcrProvider = vi.fn<(call: { body: Record<string, unknown> }) => void>();
const testOcrConnection = vi.fn<(call: Call) => void>();

const session = vi.hoisted(() => ({ capabilities: ["MODELS_MANAGE"] }));
vi.mock("@/features/identity/application-session-context", () => ({
  useApplicationSession: () => ({
    actorId: "actor",
    authorizationVersion: 1,
    capabilities: session.capabilities,
  }),
}));

const paddle: OcrProviderResponse = {
  provider: "PADDLEOCR_VL",
  requiresKey: false,
  dataBoundary: "INTERNAL",
};
const connection = (overrides: Partial<OcrConnectionResponse> = {}): OcrConnectionResponse => ({
  provider: "PADDLEOCR_VL",
  endpoint: "https://ocr.vadan.app",
  credentialConfigured: false,
  dataBoundary: "INTERNAL",
  active: false,
  revision: 1,
  ...overrides,
});

function show(connections: OcrConnectionResponse[] = []) {
  server.use(
    handleListOcrProviders(() => {
      listOcrProviders();
      return HttpResponse.json([paddle]);
    }),
    handleListOcrConnections({ body: connections }),
    handleSelectOcrProvider(async ({ request }) => {
      const body = (await request.json()) as Record<string, unknown>;
      selectOcrProvider({ body: { provider: body.provider } });
      return new HttpResponse(null, { status: 204 });
    }),
    handleSaveOcrConnection(async ({ request, params }) => {
      saveOcrConnection({
        path: { provider: String(params.provider) },
        body: (await request.json()) as Record<string, unknown>,
      });
      return HttpResponse.json(connections[0] ?? connection());
    }),
    handleTestOcrConnection(async ({ request, params }) => {
      testOcrConnection({
        path: { provider: String(params.provider) },
        body: (await request.json()) as Record<string, unknown>,
      });
      return new HttpResponse(null, { status: 204 });
    }),
  );
  const queries = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queries}>
      <OcrSettings />
    </QueryClientProvider>,
  );
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  session.capabilities = ["MODELS_MANAGE"];
  vi.clearAllMocks();
});
afterEach(cleanup);

it("refuses non-managers before loading any OCR settings", () => {
  session.capabilities = [];
  show();
  expect(screen.getByText("Bạn không có quyền quản lý mô hình.")).toBeInTheDocument();
  expect(listOcrProviders).not.toHaveBeenCalled();
});

it("reads with Docling until a connection is chosen", async () => {
  show();
  const docling = await screen.findByRole("region", { name: "Docling" });
  expect(within(docling).getByText("Đang dùng")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Kết nối" })).toBeInTheDocument();
});

it("saves a PaddleOCR-VL connection with its address and an internal boundary", async () => {
  show();
  await userEvent.click(await screen.findByRole("button", { name: "Kết nối" }));
  await userEvent.type(screen.getByLabelText(/Địa chỉ dịch vụ/), "https://ocr.vadan.app");
  expect(screen.queryByLabelText(/Khóa API/)).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Kiểm tra kết nối" }));
  await waitFor(() =>
    expect(testOcrConnection).toHaveBeenCalledWith({
      path: { provider: "PADDLEOCR_VL" },
      body: { endpoint: "https://ocr.vadan.app" },
    }),
  );
  await userEvent.click(screen.getByRole("button", { name: "Lưu" }));
  await waitFor(() =>
    expect(saveOcrConnection).toHaveBeenCalledWith({
      path: { provider: "PADDLEOCR_VL" },
      body: {
        endpoint: "https://ocr.vadan.app",
        dataBoundary: "INTERNAL",
        revision: 0,
        credentialAction: "KEEP",
      },
    }),
  );
});

it("chooses a saved connection as the reader and returns to Docling", async () => {
  show([connection()]);
  await userEvent.click(await screen.findByRole("button", { name: "Đặt làm mặc định" }));
  await waitFor(() =>
    expect(selectOcrProvider).toHaveBeenCalledWith({ body: { provider: "PADDLEOCR_VL" } }),
  );
  cleanup();
  vi.clearAllMocks();
  show([connection({ active: true })]);
  await userEvent.click(await screen.findByRole("button", { name: "Dùng Docling" }));
  await waitFor(() =>
    expect(selectOcrProvider).toHaveBeenCalledWith({ body: { provider: undefined } }),
  );
});
