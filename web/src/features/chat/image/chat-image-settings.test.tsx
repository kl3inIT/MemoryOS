import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse } from "msw";
import { afterEach, assert, beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n/index";
import {
  handleListChatImageConnections,
  handleListChatImageProviders,
  handleSaveChatImageConnection,
  handleSelectChatImageProvider,
  handleTestChatImageConnection,
} from "@/lib/hey-api/msw.gen";
import type { ImageConnectionResponse, ImageProviderResponse } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { ChatImageSettings } from "./chat-image-settings";

type Call = { path: { provider: string }; body: Record<string, unknown> };
/** The requests each write received, as the SDK call that sent it: its provider path and its body. */
const listChatImageProviders = vi.fn();
const saveChatImageConnection = vi.fn<(call: Call) => void>();
const selectChatImageProvider = vi.fn<(call: { body: Record<string, unknown> }) => void>();
const testChatImageConnection = vi.fn<(call: Call) => void>();

const session = vi.hoisted(() => ({ capabilities: ["MODELS_MANAGE"] }));
vi.mock("@/features/identity/application-session-context", () => ({
  useApplicationSession: () => ({
    actorId: "actor",
    authorizationVersion: 1,
    capabilities: session.capabilities,
  }),
}));

const openai: ImageProviderResponse = {
  provider: "OPENAI_IMAGE",
  credentialRequired: true,
  defaultEndpoint: "https://api.openai.com/v1",
  endpointRequired: false,
  knownModels: [
    {
      modelName: "gpt-image-1",
      displayName: "GPT Image 1",
      outputMediaType: "image/png",
      sizes: ["1024x1024", "1536x1024", "1024x1536"],
      edit: true,
      deprecated: false,
    },
    {
      modelName: "dall-e-3",
      displayName: "DALL·E 3",
      outputMediaType: "image/png",
      sizes: ["1024x1024"],
      edit: false,
      deprecated: true,
    },
  ],
};
const cloudflare: ImageProviderResponse = {
  provider: "CLOUDFLARE_WORKERS_AI",
  credentialRequired: true,
  endpointRequired: true,
  editModel: {
    modelName: "@cf/black-forest-labs/flux-2-klein-9b",
    displayName: "FLUX.2 Klein 9B",
    outputMediaType: "image/jpeg",
    sizes: [],
    edit: true,
    deprecated: false,
  },
  knownModels: [
    {
      modelName: "@cf/black-forest-labs/flux-1-schnell",
      displayName: "FLUX.1 Schnell",
      outputMediaType: "image/jpeg",
      sizes: [],
      edit: false,
      deprecated: false,
    },
  ],
};
const catalog = [openai, cloudflare];

const connection = (overrides: Partial<ImageConnectionResponse> = {}): ImageConnectionResponse => ({
  provider: "OPENAI_IMAGE",
  endpoint: "https://api.openai.com/v1",
  model: "gpt-image-1",
  credentialConfigured: true,
  active: false,
  revision: 1,
  ...overrides,
});

function show(
  providers: ImageProviderResponse[] = catalog,
  connections: ImageConnectionResponse[] = [],
) {
  server.use(
    handleListChatImageProviders(() => {
      listChatImageProviders();
      return HttpResponse.json(providers);
    }),
    handleListChatImageConnections({ body: connections }),
    handleSelectChatImageProvider(async ({ request }) => {
      const body = (await request.json()) as Record<string, unknown>;
      selectChatImageProvider({ body: { provider: body.provider } });
      return new HttpResponse(null, { status: 204 });
    }),
    handleSaveChatImageConnection(async ({ request, params }) => {
      saveChatImageConnection({
        path: { provider: String(params.provider) },
        body: (await request.json()) as Record<string, unknown>,
      });
      return HttpResponse.json(connections[0] ?? connection());
    }),
    handleTestChatImageConnection(async ({ request, params }) => {
      testChatImageConnection({
        path: { provider: String(params.provider) },
        body: (await request.json()) as Record<string, unknown>,
      });
      return new HttpResponse(null, { status: 204 });
    }),
  );
  const queries = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queries}>
      <ChatImageSettings />
    </QueryClientProvider>,
  );
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  session.capabilities = ["MODELS_MANAGE"];
  vi.clearAllMocks();
});
afterEach(cleanup);

it("refuses non-managers before loading any image settings", () => {
  session.capabilities = [];
  show();
  expect(screen.getByText("Bạn không có quyền quản lý mô hình.")).toBeInTheDocument();
  expect(listChatImageProviders).not.toHaveBeenCalled();
});

it("shows disconnected providers with a connect action and no in-use provider", async () => {
  show();
  expect(
    await screen.findByText("Chọn một nhà cung cấp để bật tạo ảnh trong Chat."),
  ).toBeInTheDocument();
  const cards = screen.getAllByRole("button", { name: "Kết nối" });
  expect(cards).toHaveLength(2);
  expect(screen.getByText("OpenAI Images")).toBeInTheDocument();
  expect(screen.getByText("Cloudflare Workers AI")).toBeInTheDocument();
});

it("marks a configured provider connected and lets it become the active one", async () => {
  show(catalog, [connection()]);
  expect(await screen.findByText("Đã kết nối")).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Đặt làm mặc định" }));
  await waitFor(() =>
    expect(selectChatImageProvider).toHaveBeenCalledWith({ body: { provider: "OPENAI_IMAGE" } }),
  );
});

it("shows the in-use provider in the banner and turns generation off", async () => {
  show(catalog, [connection({ active: true })]);
  const banner = await screen.findAllByText("Đang dùng");
  expect(banner.length).toBeGreaterThanOrEqual(2);
  await userEvent.click(screen.getByRole("button", { name: "Tắt tạo ảnh" }));
  await waitFor(() =>
    expect(selectChatImageProvider).toHaveBeenCalledWith({ body: { provider: undefined } }),
  );
});

it("saves a catalog model and keeps the stored key when none is typed", async () => {
  show(catalog, [connection()]);
  await userEvent.click(await screen.findByRole("button", { name: "Cấu hình" }));
  await userEvent.click(await screen.findByRole("button", { name: "Lưu" }));
  await waitFor(() => expect(saveChatImageConnection).toHaveBeenCalled());
  expect(saveChatImageConnection).toHaveBeenCalledWith(
    expect.objectContaining({
      path: { provider: "OPENAI_IMAGE" },
      body: expect.objectContaining({
        model: "gpt-image-1",
        credentialAction: "KEEP",
        revision: 1,
      }),
    }),
  );
  const [request] = saveChatImageConnection.mock.calls[0] ?? [];
  assert.isDefined(request);
  expect(request.body.credentialValue).toBeUndefined();
});

it("replaces the key and accepts a manually entered model", async () => {
  show(catalog, [connection()]);
  await userEvent.click(await screen.findByRole("button", { name: "Cấu hình" }));
  await userEvent.click(await screen.findByRole("radio", { name: "Mô hình khác…" }));
  await userEvent.type(screen.getByLabelText("Tên mô hình"), "custom-model");
  await userEvent.type(screen.getByLabelText("Khóa API"), "sk-new");
  await userEvent.click(screen.getByRole("button", { name: "Lưu" }));
  await waitFor(() => expect(saveChatImageConnection).toHaveBeenCalled());
  expect(saveChatImageConnection).toHaveBeenCalledWith(
    expect.objectContaining({
      body: expect.objectContaining({
        model: "custom-model",
        credentialAction: "REPLACE",
        credentialValue: "sk-new",
      }),
    }),
  );
});

it("hides deprecated models unless the connection already uses one", async () => {
  show(catalog, [connection({ model: "dall-e-3" })]);
  await userEvent.click(await screen.findByRole("button", { name: "Cấu hình" }));
  expect(await screen.findByRole("radio", { name: /DALL·E 3/ })).toBeInTheDocument();
  expect(screen.getByText("Ngừng hỗ trợ")).toBeInTheDocument();
  cleanup();
  vi.clearAllMocks();
  show(catalog, [connection({ model: "gpt-image-1" })]);
  await userEvent.click(await screen.findByRole("button", { name: "Cấu hình" }));
  expect(screen.queryByRole("radio", { name: /DALL·E 3/ })).not.toBeInTheDocument();
});

it("requires an account id for providers that declare an endpoint", async () => {
  show(catalog, []);
  const [, card] = await screen.findAllByRole("button", { name: "Kết nối" });
  assert.isDefined(card);
  await userEvent.click(card);
  expect(await screen.findByLabelText("Account ID")).toBeInTheDocument();
  expect(
    screen.getByText(/flux-2-klein-9b|FLUX\.2 Klein 9B/, { selector: "p" }),
  ).toBeInTheDocument();
});

it("shows a stored cloudflare endpoint as its account id", async () => {
  show(catalog, [
    connection({
      provider: "CLOUDFLARE_WORKERS_AI",
      endpoint: "https://api.cloudflare.com/client/v4/accounts/b73a9841898f88f7cc2b731d7776f265",
      model: "@cf/black-forest-labs/flux-1-schnell",
    }),
  ]);
  await userEvent.click(await screen.findByRole("button", { name: "Cấu hình" }));
  const field = await screen.findByLabelText("Account ID");
  expect(field).toHaveValue("b73a9841898f88f7cc2b731d7776f265");
  await userEvent.click(screen.getByRole("button", { name: "Lưu" }));
  await waitFor(() => expect(saveChatImageConnection).toHaveBeenCalled());
  expect(saveChatImageConnection).toHaveBeenCalledWith(
    expect.objectContaining({
      path: { provider: "CLOUDFLARE_WORKERS_AI" },
      body: expect.objectContaining({
        endpoint: "b73a9841898f88f7cc2b731d7776f265",
      }),
    }),
  );
});

it("tests a configured connection and reports success", async () => {
  show(catalog, [connection()]);
  await userEvent.click(await screen.findByRole("button", { name: "Cấu hình" }));
  await userEvent.click(await screen.findByRole("button", { name: "Kiểm tra kết nối" }));
  await waitFor(() => expect(testChatImageConnection).toHaveBeenCalled());
  expect(testChatImageConnection).toHaveBeenCalledWith(
    expect.objectContaining({
      path: { provider: "OPENAI_IMAGE" },
      body: {
        endpoint: "https://api.openai.com/v1",
        model: "gpt-image-1",
        credentialValue: undefined,
      },
    }),
  );
  expect(await screen.findByText("Kiểm tra kết nối thành công")).toBeInTheDocument();
});

it("tests unsaved values before the first save", async () => {
  show(catalog, []);
  const [connect] = await screen.findAllByRole("button", { name: "Kết nối" });
  assert.isDefined(connect);
  await userEvent.click(connect);
  expect(await screen.findByRole("button", { name: "Kiểm tra kết nối" })).toBeDisabled();
  await userEvent.type(screen.getByLabelText("Khóa API"), "sk-new");
  await userEvent.click(screen.getByRole("button", { name: "Kiểm tra kết nối" }));
  await waitFor(() => expect(testChatImageConnection).toHaveBeenCalled());
  expect(testChatImageConnection).toHaveBeenCalledWith(
    expect.objectContaining({
      path: { provider: "OPENAI_IMAGE" },
      body: { endpoint: "", model: "gpt-image-1", credentialValue: "sk-new" },
    }),
  );
  expect(await screen.findByText("Kiểm tra kết nối thành công")).toBeInTheDocument();
});

it("disconnects the active provider after choosing a replacement", async () => {
  const active = connection({ active: true });
  const other = connection({
    provider: "CLOUDFLARE_WORKERS_AI",
    endpoint: "https://api.cloudflare.com/client/v4/accounts/x",
    model: "@cf/black-forest-labs/flux-1-schnell",
  });
  show(catalog, [active, other]);
  const [configure] = await screen.findAllByRole("button", { name: "Cấu hình" });
  assert.isDefined(configure);
  await userEvent.click(configure);
  await userEvent.click(await screen.findByRole("button", { name: "Ngắt kết nối" }));
  expect(
    await screen.findByText(
      "Nhà cung cấp này đang dùng. Chọn nhà cung cấp thay thế hoặc tắt tạo ảnh.",
    ),
  ).toBeInTheDocument();
  await userEvent.click(screen.getByRole("radio", { name: "Cloudflare Workers AI" }));
  await userEvent.click(screen.getByRole("button", { name: "Ngắt kết nối" }));
  await waitFor(() =>
    expect(selectChatImageProvider).toHaveBeenCalledWith({
      body: { provider: "CLOUDFLARE_WORKERS_AI" },
    }),
  );
  await waitFor(() => expect(saveChatImageConnection).toHaveBeenCalled());
  expect(saveChatImageConnection).toHaveBeenCalledWith(
    expect.objectContaining({
      path: { provider: "OPENAI_IMAGE" },
      body: expect.objectContaining({ credentialAction: "REMOVE" }),
    }),
  );
});
