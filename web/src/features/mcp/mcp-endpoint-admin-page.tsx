import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Cable } from "lucide-react";
import { SectionHeader } from "@/components/composites/section-header";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { ProviderLogo } from "@/components/provider-logos/provider-logo";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  getMcpEndpointSettingsOptions,
  getMcpEndpointSettingsQueryKey,
  updateMcpEndpointSettingsMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { McpCopyField } from "./mcp-copy-field";

/**
 * MEM-114 administration of the MemoryOS MCP endpoint, the direction in which Claude and ChatGPT read MemoryOS: the
 * Tenant switch, the URL, and what a ChatGPT workspace administrator enters once. Máy chủ MCP is the other direction.
 */
export function McpEndpointAdminPage() {
  const ui = useAppTranslation();
  const problemMessage = useProblemMessage();
  const cache = useQueryClient();
  // The answer carries ChatGPT's client secret, so neither the read nor a save outlives the page.
  const settings = useQuery({ ...getMcpEndpointSettingsOptions(), gcTime: 0, retry: false });
  const update = useMutation({
    ...updateMcpEndpointSettingsMutation(),
    gcTime: 0,
    // A rejected change also reloads, so the switch shows the stored state.
    onSettled: () => cache.invalidateQueries({ queryKey: getMcpEndpointSettingsQueryKey() }),
  });

  return (
    <SettingsLayout>
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
        <div className="flex max-w-2xl flex-col gap-8">
          <SettingRows>
            <SettingRow
              title={ui("Bật MemoryOS MCP")}
              htmlFor="mcp-endpoint-enabled"
              control={
                <Switch
                  id="mcp-endpoint-enabled"
                  checked={settings.data.enabled}
                  disabled={update.isPending}
                  onCheckedChange={(enabled) =>
                    update.mutate({ body: { enabled, revision: settings.data.revision } })
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
          {settings.data.url ? (
            <McpCopyField
              id="mcp-endpoint-url"
              label={ui("Địa chỉ MCP")}
              value={settings.data.url}
            />
          ) : null}
          {settings.data.chatGpt ? (
            <section aria-labelledby="mcp-endpoint-chatgpt" className="flex flex-col gap-4">
              <SectionHeader
                id="mcp-endpoint-chatgpt"
                icon={<ProviderLogo mark="OPENAI" className="size-4" />}
                title={ui("ChatGPT")}
              />
              <McpCopyField
                id="mcp-endpoint-chatgpt-client-id"
                label={ui("Client ID")}
                value={settings.data.chatGpt.clientId}
              />
              <McpCopyField
                id="mcp-endpoint-chatgpt-client-secret"
                label={ui("Client secret")}
                value={settings.data.chatGpt.clientSecret}
                secret
              />
            </section>
          ) : null}
        </div>
      )}
    </SettingsLayout>
  );
}
