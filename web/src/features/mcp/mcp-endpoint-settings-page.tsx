import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Cable } from "lucide-react";
import { EmptyState } from "@/components/composites/empty-state";
import { SectionHeader } from "@/components/composites/section-header";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { formatUiDay } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { ApiError } from "@/lib/api";
import {
  getMcpEndpointConnectionOptions,
  listMcpClientGrantsOptions,
  listMcpClientGrantsQueryKey,
  revokeMcpClientGrantMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { McpClientMark } from "./mcp-client-mark";
import { McpCopyField } from "./mcp-copy-field";

/**
 * MEM-114 Settings › MemoryOS MCP: how a member connects the apps their administrator trusts (MEM-207) to MemoryOS,
 * and the assistants they allowed.
 * Connections is the other direction, the tools the assistant uses on the member's behalf.
 */
export function McpEndpointSettingsPage() {
  const ui = useAppTranslation();
  return (
    <SettingsLayout>
      <PageHeader
        title={ui("MemoryOS MCP")}
        icon={<Cable />}
        description={ui(
          "Dùng tài liệu của tổ chức trong Claude và ChatGPT, với đúng quyền của bạn.",
        )}
      />
      <div className="flex max-w-2xl flex-col gap-10">
        <ConnectSection />
        <GrantsSection />
      </div>
    </SettingsLayout>
  );
}

function ConnectSection() {
  const ui = useAppTranslation();
  const connection = useQuery({ ...getMcpEndpointConnectionOptions(), retry: false });

  if (connection.isPending) return <p role="status">{ui("Đang tải…")}</p>;
  if (connection.isError) {
    const forbidden = connection.error instanceof ApiError && connection.error.status === 403;
    return forbidden ? (
      <Alert role="note">
        <AlertDescription>{ui("Bạn chưa được dùng tìm kiếm tài liệu.")}</AlertDescription>
      </Alert>
    ) : (
      <Alert variant="destructive">
        <AlertTitle>{ui("Không tải được địa chỉ MemoryOS MCP.")}</AlertTitle>
        <div>
          <Button onClick={() => void connection.refetch()}>{ui("Tải lại")}</Button>
        </div>
      </Alert>
    );
  }
  if (!connection.data.available || !connection.data.url) {
    return (
      <Alert role="note">
        <AlertDescription>{ui("Quản trị viên chưa bật MemoryOS MCP.")}</AlertDescription>
      </Alert>
    );
  }
  const apps = connection.data.apps;
  if (apps.length === 0) {
    return (
      <Alert role="note">
        <AlertDescription>{ui("Quản trị viên chưa tin cậy ứng dụng nào.")}</AlertDescription>
      </Alert>
    );
  }
  return (
    <section aria-label={ui("Kết nối Claude hoặc ChatGPT")} className="flex flex-col gap-6">
      <McpCopyField id="mcp-endpoint-url" label={ui("Địa chỉ MCP")} value={connection.data.url} />
      {/* A guide for each app the administrator trusts, in the order Claude, ChatGPT, then any other. */}
      <Tabs defaultValue={apps[0]}>
        <TabsList>
          {apps.includes("CLAUDE") ? (
            <TabsTrigger value="CLAUDE">
              <McpClientMark client="CLAUDE" className="size-4" />
              {ui("Claude")}
            </TabsTrigger>
          ) : null}
          {apps.includes("CHATGPT") ? (
            <TabsTrigger value="CHATGPT">
              <McpClientMark client="CHATGPT" className="size-4" />
              {ui("ChatGPT")}
            </TabsTrigger>
          ) : null}
          {apps.includes("CUSTOM") ? (
            <TabsTrigger value="CUSTOM">
              <McpClientMark client="CUSTOM" className="size-4" />
              {ui("Ứng dụng khác")}
            </TabsTrigger>
          ) : null}
        </TabsList>
        <TabsContent value="CLAUDE">
          <Steps
            steps={[
              ui("Trong Claude, mở Settings › Connectors và chọn Add custom connector."),
              ui("Dán địa chỉ MCP ở trên rồi bấm Add."),
              ui("Bấm Connect, đăng nhập MemoryOS và chọn Cho phép."),
            ]}
          />
        </TabsContent>
        <TabsContent value="CHATGPT">
          <Steps
            steps={[
              ui("Trong ChatGPT, mở Plugin và chọn Plugin mới."),
              ui("Dán địa chỉ MCP ở trên, giữ Xác thực là OAuth rồi bấm Tạo."),
              ui("Bấm Connect, đăng nhập MemoryOS và chọn Cho phép."),
            ]}
          />
        </TabsContent>
        <TabsContent value="CUSTOM">
          <Steps
            steps={[
              ui("Trong ứng dụng, thêm một máy chủ MCP từ xa."),
              ui("Dán địa chỉ MCP ở trên và chọn xác thực OAuth."),
              ui("Đăng nhập MemoryOS và chọn Cho phép."),
            ]}
          />
        </TabsContent>
      </Tabs>
    </section>
  );
}

/** The steps arrive translated; each is its own sentence, so it is also its key. */
function Steps({ steps }: { steps: readonly string[] }) {
  return (
    <ol className="flex list-decimal flex-col gap-2 pt-3 pl-5 font-main-ui-body text-content-primary marker:text-content-muted">
      {steps.map((step) => (
        <li key={step}>{step}</li>
      ))}
    </ol>
  );
}

function GrantsSection() {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const grants = useQuery({ ...listMcpClientGrantsOptions(), retry: false });
  const revoke = useMutation({
    ...revokeMcpClientGrantMutation(),
    onSettled: () => cache.invalidateQueries({ queryKey: listMcpClientGrantsQueryKey() }),
  });

  return (
    <section aria-labelledby="mcp-client-grants" className="flex flex-col gap-4">
      <SectionHeader id="mcp-client-grants" title={ui("Ứng dụng đã cấp quyền")} />
      {grants.isPending ? (
        <p role="status">{ui("Đang tải…")}</p>
      ) : grants.isError ? (
        <Alert variant="destructive">
          <AlertTitle>{ui("Không tải được ứng dụng đã cấp quyền.")}</AlertTitle>
          <div>
            <Button onClick={() => void grants.refetch()}>{ui("Tải lại")}</Button>
          </div>
        </Alert>
      ) : grants.data.length === 0 ? (
        <EmptyState icon={<Cable />} title={ui("Chưa có ứng dụng nào được cấp quyền.")} />
      ) : (
        <SettingRows>
          {grants.data.map((grant) => (
            <SettingRow
              key={grant.clientId}
              icon={<McpClientMark client={grant.client} />}
              title={grant.name}
              description={
                // One line with a separator where it fits; on a phone, two lines and no dangling separator.
                <>
                  <span className="block sm:inline">{ui("Đọc tri thức")}</span>
                  <span aria-hidden="true" className="hidden sm:inline">
                    {" · "}
                  </span>
                  <span className="block whitespace-nowrap sm:inline">
                    {ui("Cấp ngày {{date}}", {
                      date: formatUiDay(grant.grantedAt),
                    })}
                  </span>
                </>
              }
              control={
                <ConfirmDialog
                  trigger={<Button prominence="secondary">{ui("Thu hồi")}</Button>}
                  title={ui("Thu hồi quyền của {{name}}?", { name: grant.name })}
                  description={ui("{{name}} sẽ không đọc được tài liệu của bạn nữa.", {
                    name: grant.name,
                  })}
                  confirmLabel={ui("Thu hồi")}
                  pendingLabel={ui("Đang thu hồi…")}
                  confirmTone="danger"
                  onConfirm={async () => {
                    await revoke.mutateAsync({ query: { clientId: grant.clientId } });
                  }}
                />
              }
            />
          ))}
        </SettingRows>
      )}
    </section>
  );
}
