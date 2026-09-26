import { Globe } from "lucide-react";
import { ConnectionStatusBadge } from "@/components/composites/connection-form";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { ProviderCard } from "@/components/provider-logos/provider-card";
import { Alert, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useProblemMessage } from "@/lib/use-problem-message";
import {
  ConversationHistorySection,
  DeepResearchSection,
  NativeSearchSection,
  WebNotice,
  WebSection,
} from "./chat-web-sections";
import { useWebConnections, webProblem, type WebProvider } from "./use-web-connections";
import { WebConnectionCard } from "./web-connection-card";

const searchProviders: WebProvider[] = [
  "EXA",
  "SERPER",
  "BRAVE",
  "GOOGLE_PSE",
  "SEARXNG",
  "NINEROUTER",
  "TAVILY",
];
const readerProviders: WebProvider[] = ["FIRECRAWL", "EXA", "TAVILY"];

/** The Web search administration: the search engine and page reader in use and how Chat reaches the Web. */
export function ChatWebSettings() {
  const ui = useAppTranslation();
  const manager = useApplicationSession().capabilities.includes("MODELS_MANAGE");
  const problemMessage = useProblemMessage();
  const { connections, select } = useWebConnections(manager);
  if (!manager)
    return (
      <p role="alert" className="p-6">
        {ui("Bạn không có quyền quản lý mô hình.")}
      </p>
    );
  const choose = (search: boolean, provider: WebProvider | null) =>
    select.mutate({ body: { search, provider: provider ?? undefined } });
  const card = (provider: WebProvider, search: boolean) => {
    const connection = connections.data?.find((candidate) => candidate.provider === provider);
    return (
      <WebConnectionCard
        key={`${provider}:${connection?.revision ?? "new"}`}
        provider={provider}
        search={search}
        connection={connection}
        disabled={select.isPending}
        onSelect={choose}
      />
    );
  };
  const searchActive = connections.data?.some((connection) => connection.searchActive) ?? false;
  const builtInReader = !connections.data?.some((connection) => connection.contentActive);
  return (
    <SettingsLayout>
      <PageHeader
        title={ui("Tìm kiếm Web")}
        icon={<Globe />}
        description={ui("Cài đặt tìm kiếm bên ngoài trên internet.")}
      />
      {connections.isError ? (
        <Alert variant="destructive">
          <AlertTitle>{ui("Không tải được kết nối Web.")}</AlertTitle>
          <div>
            <Button onClick={() => void connections.refetch()}>{ui("Tải lại")}</Button>
          </div>
        </Alert>
      ) : connections.isPending ? (
        <p role="status">{ui("Đang tải…")}</p>
      ) : (
        <div className="flex flex-col gap-8">
          <WebSection
            title={ui("Công cụ tìm kiếm")}
            action={
              <Button
                size="sm"
                prominence="secondary"
                disabled={select.isPending || !searchActive}
                onClick={() => choose(true, null)}
              >
                {ui("Tắt công cụ tìm kiếm")}
              </Button>
            }
          >
            {!searchActive && (
              <WebNotice>{ui("Chọn một công cụ tìm kiếm để bật tìm kiếm Web.")}</WebNotice>
            )}
            {searchProviders.map((provider) => card(provider, true))}
          </WebSection>
          <WebSection title={ui("Trình đọc trang Web")}>
            <ProviderCard
              logo={<Globe />}
              name={ui("Trình đọc MemoryOS")}
              description={ui("Tích hợp sẵn, không cần khóa API.")}
              selected={builtInReader}
              actions={
                builtInReader ? (
                  <ConnectionStatusBadge>{ui("Đang dùng")}</ConnectionStatusBadge>
                ) : (
                  <Button
                    size="sm"
                    prominence="secondary"
                    disabled={select.isPending}
                    onClick={() => choose(false, null)}
                  >
                    {ui("Dùng trình đọc tích hợp")}
                  </Button>
                )
              }
            />
            {readerProviders.map((provider) => card(provider, false))}
          </WebSection>
          <NativeSearchSection />
          <DeepResearchSection />
          <ConversationHistorySection />
        </div>
      )}
      {select.error ? (
        <Alert variant="destructive">
          <AlertTitle>{problemMessage(webProblem(select.error))}</AlertTitle>
        </Alert>
      ) : null}
    </SettingsLayout>
  );
}
