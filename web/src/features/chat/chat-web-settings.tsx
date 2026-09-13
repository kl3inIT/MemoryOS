import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, Globe } from "lucide-react";
import { SettingsLayout, PageHeader } from "@/components/ui/settings-layout";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  listChatWebConnections,
  saveChatWebConnection,
  selectChatWebProvider,
  testChatWebConnection,
} from "@/lib/hey-api/sdk.gen";
import type { WebConnectionResponse } from "@/lib/hey-api/types.gen";
import { useAppTranslation } from "@/i18n/use-app-translation";
import SvgBrave from "@/components/provider-logos/brave";
import SvgTavily from "@/components/provider-logos/tavily";
import SvgExa from "@/components/provider-logos/exa";
import SvgSerper from "@/components/provider-logos/serper";
import SvgSearxng from "@/components/provider-logos/searxng";
import SvgFirecrawl from "@/components/provider-logos/firecrawl";
import { cn } from "@/lib/utils";

const providers = [
  "BRAVE",
  "TAVILY",
  "EXA",
  "SERPER",
  "GOOGLE_PSE",
  "SEARXNG",
  "FIRECRAWL",
] as const;
const names = {
  BRAVE: "Brave",
  TAVILY: "Tavily",
  EXA: "Exa",
  SERPER: "Serper",
  GOOGLE_PSE: "Google PSE",
  SEARXNG: "SearXNG",
  FIRECRAWL: "Firecrawl",
};
type Provider = (typeof providers)[number];
const providerDetails = {
  BRAVE: { logo: SvgBrave, description: "Brave Search API" },
  TAVILY: { logo: SvgTavily, description: "Tavily AI" },
  EXA: { logo: SvgExa, description: "Exa.ai" },
  SERPER: { logo: SvgSerper, description: "Serper.dev" },
  GOOGLE_PSE: {
    logo: () => <img src="/provider-logos/google.svg" className="size-6" alt="" />,
    description: "Google",
  },
  SEARXNG: { logo: SvgSearxng, description: "SearXNG" },
  FIRECRAWL: { logo: SvgFirecrawl, description: "Firecrawl" },
};
const searchProviders: Provider[] = ["EXA", "SERPER", "BRAVE", "GOOGLE_PSE", "SEARXNG", "TAVILY"];
const readerProviders: Provider[] = ["FIRECRAWL", "EXA", "TAVILY"];

export function ChatWebSettings() {
  const ui = useAppTranslation();
  const session = useApplicationSession();
  const manager = session.capabilities.includes("MODELS_MANAGE");
  const cache = useQueryClient();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState(false);
  const query = useQuery({
    queryKey: ["web-connections", session.actorId, session.authorizationVersion],
    enabled: manager,
    queryFn: async ({ signal }) =>
      (await listChatWebConnections({ signal, throwOnError: true })).data,
    retry: false,
  });
  async function changed() {
    await Promise.all([query.refetch(), cache.invalidateQueries({ queryKey: ["chat-web"] })]);
  }
  async function select(search: boolean, provider: Provider | null) {
    setPending(true);
    setError(false);
    try {
      await selectChatWebProvider({
        body: { search, provider: provider ?? undefined },
        headers: sameOriginMutationHeaders,
        throwOnError: true,
      });
      await changed();
    } catch {
      setError(true);
    } finally {
      setPending(false);
    }
  }
  if (!manager)
    return (
      <p role="alert" className="p-6">
        {ui("Bạn không có quyền quản lý mô hình.")}
      </p>
    );
  return (
    <SettingsLayout>
      <PageHeader
        title={ui("Tìm kiếm Web")}
        icon={<Globe />}
        description={ui(
          "Kết nối công cụ tìm kiếm và đọc trang. Exa và Tavily dùng chung một kết nối cho cả hai chức năng.",
        )}
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
            {!query.data?.some((c) => c.searchActive) && (
              <p className="rounded-xl border border-border-default bg-surface-sunken px-4 py-3 text-sm text-content-secondary">
                {ui("Chưa kết nối công cụ tìm kiếm.")}
              </p>
            )}
            <div className="space-y-3">
              {searchProviders.map((provider) => {
                const connection = query.data?.find((c) => c.provider === provider);
                return (
                  <ConnectionCard
                    key={`${provider}:${connection?.revision ?? "new"}`}
                    provider={provider}
                    search
                    connection={connection}
                    disabled={pending}
                    onChanged={changed}
                    onSelect={select}
                  />
                );
              })}
            </div>
          </section>
          <section aria-label={ui("Trình đọc trang Web")} className="mt-8 space-y-3">
            <div>
              <h2 className="text-lg font-semibold">{ui("Trình đọc trang Web")}</h2>
              <p className="mt-1 text-sm text-content-muted">
                {ui("Đọc nội dung đầy đủ của trang từ kết quả tìm kiếm hoặc đường dẫn bạn gửi.")}
              </p>
            </div>
            <div
              className={cn(
                "flex items-center gap-3 rounded-xl border p-4",
                !query.data?.some((c) => c.contentActive)
                  ? "border-border-strong bg-surface-sunken"
                  : "border-border-default",
              )}
            >
              <Globe className="size-6 shrink-0" aria-hidden="true" />
              <div className="min-w-0 flex-1">
                <h3 className="font-main-ui-action">{ui("Trình đọc MemoryOS")}</h3>
                <p className="mt-1 text-sm text-content-muted">
                  {ui("Tích hợp sẵn, không cần khóa API.")}
                </p>
              </div>
              {!query.data?.some((c) => c.contentActive) ? (
                <span className="flex items-center gap-1 text-xs">
                  <CheckCircle2 className="size-4" />
                  {ui("Đang dùng")}
                </span>
              ) : (
                <Button
                  size="sm"
                  prominence="secondary"
                  disabled={pending}
                  onClick={() => void select(false, null)}
                >
                  {ui("Dùng trình đọc tích hợp")}
                </Button>
              )}
            </div>
            {readerProviders.map((provider) => {
              const connection = query.data?.find((c) => c.provider === provider);
              return (
                <ConnectionCard
                  key={`${provider}:${connection?.revision ?? "new"}`}
                  provider={provider}
                  search={false}
                  connection={connection}
                  disabled={pending}
                  onChanged={changed}
                  onSelect={select}
                />
              );
            })}
          </section>
        </>
      )}
      {error && (
        <p role="alert">{ui("Không cập nhật được kết nối Web. Hãy tải lại và thử lại.")}</p>
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
  const [expanded, setExpanded] = useState(false);
  const [key, setKey] = useState("");
  const [endpoint, setEndpoint] = useState(connection?.endpoint ?? "");
  const [engineId, setEngineId] = useState(connection?.engineId ?? "");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState(false);
  const [tested, setTested] = useState(false);
  const canSearch = search;
  const canRead = !search;
  const active = search ? connection?.searchActive : connection?.contentActive;
  const Logo = providerDetails[provider].logo;
  const configured = !!connection && (provider === "SEARXNG" || connection.credentialConfigured);
  async function save() {
    setPending(true);
    setError(false);
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
      setExpanded(false);
    } catch {
      setError(true);
    } finally {
      setPending(false);
    }
  }
  async function test(search: boolean) {
    setPending(true);
    setError(false);
    setTested(false);
    try {
      await testChatWebConnection({
        path: { provider },
        body: { search },
        headers: sameOriginMutationHeaders,
        throwOnError: true,
      });
      setTested(true);
    } catch {
      setError(true);
    } finally {
      setPending(false);
    }
  }
  return (
    <section
      aria-label={names[provider]}
      className={cn(
        "rounded-xl border p-4",
        active ? "border-border-strong bg-surface-sunken" : "border-border-default",
      )}
    >
      <div className="flex flex-wrap items-center gap-3">
        <span aria-hidden="true" className="flex size-7 shrink-0 items-center justify-center">
          <Logo className="size-6" />
        </span>
        <div className="mr-auto min-w-0">
          <h3 className="font-main-ui-action">{names[provider]}</h3>
          <p className="mt-1 text-sm text-content-muted">{providerDetails[provider].description}</p>
        </div>
        {active && (
          <span className="flex items-center gap-1 text-xs">
            <CheckCircle2 className="size-4" />
            {ui("Đang dùng")}
          </span>
        )}
        <Button
          size="sm"
          prominence="secondary"
          disabled={disabled || pending}
          aria-expanded={expanded}
          onClick={() => setExpanded(!expanded)}
        >
          {configured ? ui("Cấu hình") : ui("Kết nối")}
        </Button>
      </div>
      {expanded && (
        <form
          className="mt-4 space-y-3"
          onSubmit={(e) => {
            e.preventDefault();
            void save();
          }}
        >
          {provider === "SEARXNG" && (
            <label className="block space-y-1">
              <span>{ui("Địa chỉ SearXNG")}</span>
              <Input
                value={endpoint}
                onChange={(e) => setEndpoint(e.target.value)}
                required
                maxLength={2048}
              />
            </label>
          )}
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
          <label className="block space-y-1">
            <span>{ui("Khóa API")}</span>
            <Input
              type="password"
              autoComplete="new-password"
              value={key}
              maxLength={8192}
              onChange={(e) => setKey(e.target.value)}
              placeholder={
                connection?.credentialConfigured ? ui("Đã lưu khóa; để trống để giữ nguyên") : ""
              }
            />
          </label>
          <Button type="submit" size="sm" disabled={disabled || pending}>
            {ui("Lưu")}
          </Button>
        </form>
      )}
      {configured && (
        <div className="mt-3 flex flex-wrap gap-2">
          {canSearch && (
            <Button
              size="sm"
              prominence="internal"
              disabled={disabled || pending || connection.searchActive}
              onClick={() => void onSelect(true, provider)}
            >
              {ui("Dùng để tìm kiếm")}
            </Button>
          )}
          {canRead && (
            <Button
              size="sm"
              prominence="internal"
              disabled={disabled || pending || connection.contentActive}
              onClick={() => void onSelect(false, provider)}
            >
              {ui("Dùng để đọc trang")}
            </Button>
          )}
          {canSearch && (
            <Button
              size="sm"
              prominence="internal"
              disabled={disabled || pending}
              onClick={() => void test(true)}
            >
              {ui("Kiểm tra tìm kiếm")}
            </Button>
          )}
          {canRead && (
            <Button
              size="sm"
              prominence="internal"
              disabled={disabled || pending}
              onClick={() => void test(false)}
            >
              {ui("Kiểm tra đọc trang")}
            </Button>
          )}
        </div>
      )}
      {configured && (
        <p className="mt-2 text-xs text-content-muted">
          {ui("Kiểm tra kết nối có thể phát sinh phí từ nhà cung cấp.")}
        </p>
      )}
      {pending && (
        <p role="status" className="mt-2 text-sm">
          {ui("Đang xử lý…")}
        </p>
      )}
      {tested && (
        <p role="status" className="mt-2 flex items-center gap-1 text-sm">
          <CheckCircle2 className="size-4" />
          {ui("Kiểm tra kết nối thành công")}
        </p>
      )}
      {error && (
        <p role="alert" className="mt-2 text-sm">
          {ui("Không cập nhật hoặc kiểm tra được kết nối Web.")}
        </p>
      )}
    </section>
  );
}
