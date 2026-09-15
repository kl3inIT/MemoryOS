import { useState, type ReactNode } from "react";
import { useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, Cpu, Globe, Settings2 } from "lucide-react";
import { Dialog } from "radix-ui";
import { Switch } from "@/components/ui/switch";
import { SettingsLayout, PageHeader } from "@/components/ui/settings-layout";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { StatusBadge } from "@/components/ui/status-badge";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  listChatProviderAdapters,
  listChatProviders,
  listChatWebConnections,
  listConfiguredChatModels,
  saveChatWebConnection,
  selectChatWebProvider,
  testChatWebConnection,
  updateChatModel,
} from "@/lib/hey-api/sdk.gen";
import type { Model, WebConnectionResponse } from "@/lib/hey-api/types.gen";
import { presentProblem, type ErrorMessage } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { ProviderCard } from "@/components/provider-logos/provider-card";
import { ProviderLogo } from "@/components/provider-logos/provider-logo";
import { hasProviderMark } from "@/components/provider-logos/provider-marks";

const webProblem = (error: unknown): ErrorMessage =>
  presentProblem(error, "mutation", {
    CHAT_PROVIDER_UNAVAILABLE: { key: "webProviderUnavailable" },
  }).message;

const providers = [
  "BRAVE",
  "TAVILY",
  "EXA",
  "SERPER",
  "GOOGLE_PSE",
  "SEARXNG",
  "NINEROUTER",
  "FIRECRAWL",
] as const;
const names = {
  BRAVE: "Brave",
  TAVILY: "Tavily",
  EXA: "Exa",
  SERPER: "Serper",
  GOOGLE_PSE: "Google PSE",
  SEARXNG: "SearXNG",
  NINEROUTER: "9Router",
  FIRECRAWL: "Firecrawl",
};
type Provider = (typeof providers)[number];
const providerDetails = {
  BRAVE: { description: "brave.com", endpoint: "https://api.search.brave.com" },
  TAVILY: { description: "tavily.com", endpoint: "https://api.tavily.com" },
  EXA: { description: "exa.ai", endpoint: "https://api.exa.ai" },
  SERPER: { description: "serper.dev", endpoint: "https://google.serper.dev" },
  GOOGLE_PSE: {
    description: "programmablesearchengine.google.com",
    endpoint: "https://customsearch.googleapis.com",
  },
  SEARXNG: { description: "searxng.org", endpoint: "" },
  NINEROUTER: { description: "9router.com", endpoint: "" },
  FIRECRAWL: { description: "firecrawl.dev", endpoint: "https://api.firecrawl.dev" },
};
const searchProviders: Provider[] = [
  "EXA",
  "SERPER",
  "BRAVE",
  "GOOGLE_PSE",
  "SEARXNG",
  "NINEROUTER",
  "TAVILY",
];
const readerProviders: Provider[] = ["FIRECRAWL", "EXA", "TAVILY"];
const notice =
  "rounded-xl border border-border-default bg-surface-sunken px-4 py-3 text-sm text-content-secondary";

function InUseBadge({ children }: { children: ReactNode }) {
  return (
    <StatusBadge tone="success" className="gap-1">
      <CheckCircle2 className="size-3.5" aria-hidden="true" />
      {children}
    </StatusBadge>
  );
}

export function ChatWebSettings() {
  const ui = useAppTranslation();
  const session = useApplicationSession();
  const manager = session.capabilities.includes("MODELS_MANAGE");
  const cache = useQueryClient();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ErrorMessage>();
  const problemMessage = useProblemMessage();
  const query = useQuery({
    queryKey: ["web-connections", session.actorId, session.authorizationVersion],
    enabled: manager,
    queryFn: async ({ signal }) =>
      (await listChatWebConnections({ signal, throwOnError: true })).data,
    retry: false,
  });
  async function changed() {
    await Promise.all([
      query.refetch(),
      cache.invalidateQueries({ queryKey: ["chat-web"] }),
      cache.invalidateQueries({ queryKey: ["chat-provider-models"] }),
    ]);
  }
  async function select(search: boolean, provider: Provider | null) {
    setPending(true);
    setError(undefined);
    try {
      await selectChatWebProvider({
        body: { search, provider: provider ?? undefined },
        headers: sameOriginMutationHeaders,
        throwOnError: true,
      });
      await changed();
    } catch (failed) {
      setError(webProblem(failed));
    } finally {
      setPending(false);
    }
  }
  function card(provider: Provider, search: boolean) {
    const connection = query.data?.find((c) => c.provider === provider);
    return (
      <ConnectionCard
        key={`${provider}:${connection?.revision ?? "new"}`}
        provider={provider}
        search={search}
        connection={connection}
        disabled={pending}
        onChanged={changed}
        onSelect={select}
      />
    );
  }
  if (!manager)
    return (
      <p role="alert" className="p-6">
        {ui("Bạn không có quyền quản lý mô hình.")}
      </p>
    );
  const builtInReader = !query.data?.some((c) => c.contentActive);
  return (
    <SettingsLayout>
      <PageHeader
        title={ui("Tìm kiếm Web")}
        icon={<Globe />}
        description={ui("Cài đặt tìm kiếm bên ngoài trên internet.")}
      />
      {query.isError ? (
        <div role="alert">
          <p>{ui("Không tải được kết nối Web.")}</p>
          <Button onClick={() => void query.refetch()}>{ui("Tải lại")}</Button>
        </div>
      ) : query.isPending ? (
        <p role="status">{ui("Đang tải…")}</p>
      ) : (
        <>
          <section aria-label={ui("Công cụ tìm kiếm")} className="space-y-3">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <h2 className="text-lg font-semibold">{ui("Công cụ tìm kiếm")}</h2>
              <Button
                size="sm"
                prominence="secondary"
                disabled={pending || !query.data?.some((c) => c.searchActive)}
                onClick={() => void select(true, null)}
              >
                {ui("Tắt công cụ tìm kiếm")}
              </Button>
            </div>
            <p className="text-sm text-content-muted">
              {ui(
                "API tìm kiếm bên ngoài trả về đường dẫn, trích đoạn và siêu dữ liệu cho kết quả Web.",
              )}
            </p>
            {!query.data?.some((c) => c.searchActive) && (
              <p className={notice}>{ui("Chọn một công cụ tìm kiếm để bật tìm kiếm Web.")}</p>
            )}
            <div className="space-y-3">
              {searchProviders.map((provider) => card(provider, true))}
            </div>
          </section>
          <section aria-label={ui("Trình đọc trang Web")} className="mt-8 space-y-3">
            <h2 className="text-lg font-semibold">{ui("Trình đọc trang Web")}</h2>
            <p className="text-sm text-content-muted">
              {ui("Dùng để đọc toàn bộ nội dung của trang trong kết quả tìm kiếm.")}
            </p>
            <ProviderCard
              logo={<Globe />}
              name={ui("Trình đọc MemoryOS")}
              description={ui("Tích hợp sẵn, không cần khóa API.")}
              selected={builtInReader}
              actions={
                builtInReader ? (
                  <InUseBadge>{ui("Đang dùng")}</InUseBadge>
                ) : (
                  <Button
                    size="sm"
                    prominence="secondary"
                    disabled={pending}
                    onClick={() => void select(false, null)}
                  >
                    {ui("Dùng trình đọc tích hợp")}
                  </Button>
                )
              }
            />
            {readerProviders.map((provider) => card(provider, false))}
          </section>
          <NativeSearchSection onChanged={changed} />
        </>
      )}
      {error && (
        <p role="alert" className="mt-4 text-sm text-status-danger-content">
          {problemMessage(error)}
        </p>
      )}
    </SettingsLayout>
  );
}

function ConnectionCard({
  provider,
  search,
  connection,
  disabled,
  onChanged,
  onSelect,
}: {
  provider: Provider;
  search: boolean;
  connection?: WebConnectionResponse;
  disabled: boolean;
  onChanged: () => Promise<void>;
  onSelect: (search: boolean, provider: Provider) => Promise<void>;
}) {
  const ui = useAppTranslation();
  const problemMessage = useProblemMessage();
  const [open, setOpen] = useState(false);
  const [key, setKey] = useState("");
  const [endpoint, setEndpoint] = useState(connection?.endpoint ?? "");
  const [engineId, setEngineId] = useState(connection?.engineId ?? "");
  const [pending, setPending] = useState(false);
  const [saveError, setSaveError] = useState<ErrorMessage>();
  const [testError, setTestError] = useState<ErrorMessage>();
  const [tested, setTested] = useState(false);
  function changeOpen(next: boolean) {
    if (pending) return;
    if (!next) {
      setSaveError(undefined);
      setTestError(undefined);
      setTested(false);
      setKey("");
    }
    setOpen(next);
  }
  const active = search ? connection?.searchActive : connection?.contentActive;
  const configured = !!connection && (provider === "SEARXNG" || connection.credentialConfigured);
  async function save() {
    setPending(true);
    setSaveError(undefined);
    setTestError(undefined);
    setTested(false);
    try {
      await saveChatWebConnection({
        path: { provider },
        body: {
          endpoint,
          engineId,
          revision: connection?.revision ?? 0,
          credentialAction: key ? "REPLACE" : "KEEP",
          credentialValue: key || undefined,
        },
        headers: sameOriginMutationHeaders,
        throwOnError: true,
      });
      setKey("");
      await onChanged();
      setOpen(false);
    } catch (failed) {
      setSaveError(webProblem(failed));
    } finally {
      setPending(false);
    }
  }
  async function test(search: boolean) {
    setPending(true);
    setSaveError(undefined);
    setTestError(undefined);
    setTested(false);
    try {
      await testChatWebConnection({
        path: { provider },
        body: { search },
        headers: sameOriginMutationHeaders,
        throwOnError: true,
      });
      setTested(true);
    } catch (failed) {
      setTestError(webProblem(failed));
    } finally {
      setPending(false);
    }
  }
  return (
    <ProviderCard
      as="section"
      aria-label={names[provider]}
      logo={<ProviderLogo mark={provider} />}
      name={names[provider]}
      description={providerDetails[provider].description}
      selected={!!active}
      actions={
        <>
          {active ? (
            <InUseBadge>{ui("Đang dùng")}</InUseBadge>
          ) : configured ? (
            <InUseBadge>{ui("Đã kết nối")}</InUseBadge>
          ) : null}
          {configured && !active && (
            <Button
              size="sm"
              prominence="secondary"
              disabled={disabled || pending}
              onClick={() => void onSelect(search, provider)}
            >
              {ui("Đặt làm mặc định")}
            </Button>
          )}
          {configured ? (
            <Button
              size="sm"
              prominence="tertiary"
              disabled={disabled || pending}
              onClick={() => setOpen(true)}
            >
              <Settings2 aria-hidden="true" /> {ui("Cấu hình")}
            </Button>
          ) : (
            <Button
              size="sm"
              prominence="secondary"
              disabled={disabled || pending}
              onClick={() => setOpen(true)}
            >
              {ui("Kết nối")}
            </Button>
          )}
        </>
      }
    >
      <Dialog.Root open={open} onOpenChange={changeOpen}>
        <Dialog.Portal>
          <Dialog.Overlay className="fixed inset-0 z-40 bg-content-primary/20 backdrop-blur-[2px]" />
          <Dialog.Content className="fixed top-1/2 left-1/2 z-50 max-h-[calc(100dvh-2rem)] w-[min(34rem,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 overflow-y-auto rounded-2xl border border-border-default bg-surface-overlay p-6 shadow-md outline-none">
            <form
              onSubmit={(e) => {
                e.preventDefault();
                void save();
              }}
            >
              <Dialog.Title className="text-xl font-semibold">{names[provider]}</Dialog.Title>
              <Dialog.Description className="mt-2 text-sm text-content-secondary">
                {providerDetails[provider].description}
              </Dialog.Description>
              <fieldset disabled={disabled || pending} className="mt-5 space-y-4">
                <label className="block space-y-1">
                  <span>
                    {provider === "SEARXNG"
                      ? ui("Địa chỉ SearXNG")
                      : provider === "NINEROUTER"
                        ? ui("Địa chỉ 9Router")
                        : ui("Địa chỉ tùy chỉnh (để trống dùng mặc định)")}
                  </span>
                  <Input
                    value={endpoint}
                    onChange={(e) => setEndpoint(e.target.value)}
                    required={provider === "SEARXNG" || provider === "NINEROUTER"}
                    maxLength={2048}
                    placeholder={
                      provider === "NINEROUTER"
                        ? "https://9router.example.com/v1"
                        : providerDetails[provider].endpoint || "https://searx.example.com"
                    }
                  />
                </label>
                {provider === "GOOGLE_PSE" && (
                  <label className="block space-y-1">
                    <span>{ui("Mã công cụ tìm kiếm")}</span>
                    <Input
                      value={engineId}
                      onChange={(e) => setEngineId(e.target.value)}
                      required
                      maxLength={200}
                    />
                  </label>
                )}
                {provider === "NINEROUTER" && (
                  <label className="block space-y-1">
                    <span>{ui("Engine tìm kiếm")}</span>
                    <Input
                      value={engineId}
                      onChange={(e) => setEngineId(e.target.value)}
                      required
                      maxLength={200}
                    />
                  </label>
                )}
                <label className="block space-y-1">
                  <span>{ui("Khóa API")}</span>
                  <Input
                    type="password"
                    autoComplete="new-password"
                    value={key}
                    maxLength={8192}
                    onChange={(e) => setKey(e.target.value)}
                    placeholder={
                      connection?.credentialConfigured
                        ? ui("Đã lưu khóa; để trống để giữ nguyên")
                        : ""
                    }
                  />
                </label>
              </fieldset>
              {tested && (
                <p role="status" className="mt-4 flex items-center gap-1 text-sm">
                  <CheckCircle2 className="size-4" />
                  {ui("Kiểm tra kết nối thành công")}
                </p>
              )}
              {(saveError || testError) && (
                <p role="alert" className="mt-4 text-sm text-status-danger-content">
                  {problemMessage(saveError ?? testError!)}
                </p>
              )}
              <div className="mt-6 flex justify-end gap-2">
                {configured && (
                  <Button
                    type="button"
                    size="sm"
                    prominence="internal"
                    disabled={disabled || pending}
                    onClick={() => void test(search)}
                  >
                    {ui("Kiểm tra kết nối")}
                  </Button>
                )}
                <Button
                  type="button"
                  size="sm"
                  prominence="secondary"
                  disabled={pending}
                  onClick={() => changeOpen(false)}
                >
                  {ui("Đóng")}
                </Button>
                <Button type="submit" size="sm" pending={pending} disabled={disabled}>
                  {ui("Lưu")}
                </Button>
              </div>
            </form>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
    </ProviderCard>
  );
}

/** Provider-hosted search is a per-model option on adapters that support it; it reuses the model's own credential. */
function NativeSearchSection({ onChanged }: { onChanged: () => Promise<void> }) {
  const ui = useAppTranslation();
  const problemMessage = useProblemMessage();
  const session = useApplicationSession();
  const [pending, setPending] = useState<string>();
  const [error, setError] = useState<ErrorMessage>();
  const adapters = useQuery({
    queryKey: ["chat-provider-adapters", session.actorId, session.authorizationVersion],
    queryFn: async ({ signal }) =>
      (await listChatProviderAdapters({ signal, throwOnError: true })).data,
    retry: false,
  });
  const providers = useQuery({
    queryKey: ["chat-providers", session.actorId, session.authorizationVersion],
    queryFn: async ({ signal }) => (await listChatProviders({ signal, throwOnError: true })).data,
    retry: false,
  });
  const nativeProviders = (providers.data ?? []).filter((provider) =>
    adapters.data?.some(
      (adapter) => adapter.type === provider.adapterType && adapter.nativeWebSearch,
    ),
  );
  const models = useQueries({
    queries: nativeProviders.map((provider) => ({
      queryKey: ["chat-provider-models", provider.id, provider.revision],
      queryFn: async ({ signal }: { signal: AbortSignal }) =>
        (
          await listConfiguredChatModels({
            path: { providerId: provider.id! },
            signal,
            throwOnError: true,
          })
        ).data,
      retry: false,
    })),
  });
  const rows = nativeProviders.flatMap((provider, index) =>
    (models[index]?.data ?? []).map((model) => ({ provider, model })),
  );
  async function toggle(model: Model, enable: boolean) {
    if (!model.id || model.revision === undefined || !model.settings) return;
    setPending(model.id);
    setError(undefined);
    try {
      const options = { ...(model.settings.options ?? {}) };
      if (enable) options.webSearch = "native";
      else delete options.webSearch;
      await updateChatModel({
        path: { modelId: model.id },
        query: { revision: model.revision },
        body: {
          modelName: model.modelName,
          displayName: model.displayName,
          visible: model.visible,
          settings: { ...model.settings, options },
        },
        headers: sameOriginMutationHeaders,
        throwOnError: true,
      });
      await onChanged();
    } catch (failed) {
      setError(webProblem(failed));
    } finally {
      setPending(undefined);
    }
  }
  if (adapters.isPending || providers.isPending) return null;
  if (nativeProviders.length === 0) return null;
  return (
    <section aria-label={ui("Tìm kiếm của nhà cung cấp mô hình")} className="mt-8 space-y-3">
      <h2 className="text-lg font-semibold">{ui("Tìm kiếm của nhà cung cấp mô hình")}</h2>
      {rows.length === 0 ? (
        <p className={notice}>{ui("Chưa có mô hình nào trên nhà cung cấp hỗ trợ tìm kiếm.")}</p>
      ) : (
        <ul className="space-y-3">
          {rows.map(({ provider, model }) => {
            const enabled = model.settings?.options?.webSearch === "native";
            const toolCalling = !!model.settings?.capabilities?.toolCalling;
            const adapter = (provider.adapterType ?? "").toUpperCase();
            const mark = hasProviderMark(adapter) ? adapter : undefined;
            return (
              <ProviderCard
                key={model.id}
                as="li"
                logo={mark ? <ProviderLogo mark={mark} /> : <Cpu />}
                name={model.displayName || model.modelName}
                description={provider.name}
                selected={enabled}
                actions={
                  <>
                    {enabled && <InUseBadge>{ui("Đang dùng")}</InUseBadge>}
                    {!toolCalling && (
                      <StatusBadge tone="neutral">{ui("Không hỗ trợ công cụ")}</StatusBadge>
                    )}
                    <Switch
                      checked={enabled}
                      disabled={!toolCalling || pending !== undefined}
                      aria-label={ui("Tìm kiếm Web của nhà cung cấp cho {{name}}", {
                        name: model.displayName || model.modelName,
                      })}
                      onCheckedChange={(checked) => void toggle(model, checked)}
                    />
                  </>
                }
              />
            );
          })}
        </ul>
      )}
      {error && (
        <p role="alert" className="text-sm text-status-danger-content">
          {problemMessage(error)}
        </p>
      )}
    </section>
  );
}
