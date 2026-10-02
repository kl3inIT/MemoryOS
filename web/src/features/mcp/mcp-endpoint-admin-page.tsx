import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import { Cable } from "lucide-react";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  getMcpEndpointSettingsOptions,
  getMcpEndpointSettingsQueryKey,
  updateMcpEndpointSettingsMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { McpEndpointSettingsResponse } from "@/lib/hey-api/types.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { McpCopyField } from "./mcp-copy-field";
import { McpEndpointActivity } from "./mcp-endpoint-activity";
import type { McpEndpointTab } from "./mcp-endpoint-admin-search";
import { McpEndpointInsights } from "./mcp-endpoint-insights";
import { McpTrustedApps } from "./mcp-trusted-apps";

const endpointRoute = getRouteApi("/_authenticated/admin/mcp-endpoint");

/**
 * MEM-114 administration of the MemoryOS MCP endpoint, the direction in which Claude and ChatGPT read MemoryOS: the
 * Tenant switch, the URL and the apps it trusts (MEM-207), then each tool call and its totals (MEM-209), as Glean
 * keeps them for its MCP servers. Máy chủ MCP is the other direction.
 */
export function McpEndpointAdminPage() {
  const ui = useAppTranslation();
  const settings = useQuery({ ...getMcpEndpointSettingsOptions(), retry: false });
  const { tab } = endpointRoute.useSearch();
  const navigate = endpointRoute.useNavigate();

  return (
    <SettingsLayout wide>
      <PageHeader
        title={ui("MemoryOS MCP")}
        icon={<Cable />}
        description={ui(
          "Cho Claude và ChatGPT tìm và đọc tài liệu của tổ chức bằng quyền của từng người.",
        )}
      />
      {settings.isError ? (
        <Alert variant="destructive">
          <AlertTitle>{ui("Không tải được cài đặt MemoryOS MCP.")}</AlertTitle>
          <div>
            <Button onClick={() => void settings.refetch()}>{ui("Tải lại")}</Button>
          </div>
        </Alert>
      ) : settings.isPending ? (
        <p role="status">{ui("Đang tải…")}</p>
      ) : !settings.data.configured ? (
        <Alert role="note">
          <AlertDescription>{ui("Máy chủ này chưa cấu hình địa chỉ MCP.")}</AlertDescription>
        </Alert>
      ) : (
        <Tabs
          value={tab}
          onValueChange={(value) =>
            void navigate({
              search: (current) => ({ ...current, tab: value as McpEndpointTab }),
              replace: true,
              resetScroll: false,
            })
          }
        >
          <div className="border-b border-border-subtle pb-1">
            <TabsList variant="line" aria-label={ui("MemoryOS MCP")}>
              <TabsTrigger value="settings" className="flex-none">
                {ui("Cài đặt")}
              </TabsTrigger>
              <TabsTrigger value="activity" className="flex-none">
                {ui("Hoạt động")}
              </TabsTrigger>
              <TabsTrigger value="insights" className="flex-none">
                {ui("Thống kê")}
              </TabsTrigger>
            </TabsList>
          </div>
          <TabsContent value="settings" className="mt-4">
            <EndpointSettings settings={settings.data} />
          </TabsContent>
          <TabsContent value="activity" className="mt-4">
            <div className="flex min-w-0 flex-col gap-4">
              <McpEndpointActivity />
            </div>
          </TabsContent>
          <TabsContent value="insights" className="mt-4">
            <div className="flex min-w-0 flex-col gap-6">
              <McpEndpointInsights />
            </div>
          </TabsContent>
        </Tabs>
      )}
    </SettingsLayout>
  );
}

function EndpointSettings({ settings }: { settings: McpEndpointSettingsResponse }) {
  const ui = useAppTranslation();
  const problemMessage = useProblemMessage();
  const cache = useQueryClient();
  const update = useMutation({
    ...updateMcpEndpointSettingsMutation(),
    // A rejected change also reloads, so the switch shows the stored state.
    onSettled: () => cache.invalidateQueries({ queryKey: getMcpEndpointSettingsQueryKey() }),
  });

  return (
    <div className="flex max-w-2xl flex-col gap-8">
      <SettingRows>
        <SettingRow
          title={ui("Bật MemoryOS MCP")}
          htmlFor="mcp-endpoint-enabled"
          control={
            <Switch
              id="mcp-endpoint-enabled"
              checked={settings.enabled}
              disabled={update.isPending}
              onCheckedChange={(enabled) =>
                update.mutate({ body: { enabled, revision: settings.revision } })
              }
            />
          }
        />
      </SettingRows>
      {update.error ? (
        <Alert variant="destructive">
          <AlertTitle>
            {problemMessage(presentProblem(update.error, "mutation", {}).message)}
          </AlertTitle>
        </Alert>
      ) : null}
      {settings.url ? (
        <McpCopyField id="mcp-endpoint-url" label={ui("Địa chỉ MCP")} value={settings.url} />
      ) : null}
      <McpTrustedApps />
    </div>
  );
}
