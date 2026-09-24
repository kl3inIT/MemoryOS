import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Blocks } from "lucide-react";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import { StatusBadge } from "@/components/ui/status-badge";
import { mcpConnectionsKey, useMcpConnections } from "@/features/chat/mcp/chat-mcp-connections";
import { ConnectAction, McpApiKeyDialog } from "@/features/chat/mcp/chat-mcp-options";
import { connectionStatus, needsUserAction } from "@/features/mcp/mcp-status";
import { appText } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { disconnectMcpConnection } from "@/lib/hey-api/sdk.gen";
import type { McpConnection } from "@/lib/hey-api/types.gen";

/**
 * Onyx Settings › Connectors: the MCP servers this member may use, connected with their own account. The actions
 * are the Chat composer's, returning here after an OAuth sign-in.
 */
export function ConnectionsSettingsPage() {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const connections = useMcpConnections();
  const [apiKeyFor, setApiKeyFor] = useState<McpConnection | null>(null);
  const list = connections.data ?? [];
  return (
    <SettingsLayout>
      <PageHeader
        icon={<Blocks />}
        title={ui("Connections")}
        description={ui(
          "Tools the assistant uses on your behalf. Each connection uses your own account.",
        )}
      />
      {connections.isSuccess && list.length === 0 ? (
        <p className="max-w-2xl text-content-muted">
          {ui("No connectors set up for your organization.")}
        </p>
      ) : (
        <SettingRows className="max-w-2xl">
          {list.map((connection) => {
            const status = connectionStatus(connection.connectionState);
            const own =
              connection.authPerformer === "PER_USER" && connection.connectionState === "CONNECTED";
            return (
              <SettingRow
                key={connection.id}
                icon={<Blocks />}
                title={connection.name}
                description={
                  <span className="flex flex-wrap items-center gap-2">
                    <StatusBadge tone={status.tone} size="sm">
                      {ui(status.label)}
                    </StatusBadge>
                    {connection.description ??
                      ui("{{count}} công cụ", { count: connection.enabledToolCount })}
                  </span>
                }
                className="flex-col items-stretch sm:flex-row sm:items-center"
                control={
                  needsUserAction(connection) ? (
                    <ConnectAction
                      connection={connection}
                      returnPath="/settings/connections"
                      onApiKey={setApiKeyFor}
                    />
                  ) : own ? (
                    <ConfirmDialog
                      trigger={<Button prominence="secondary">{ui("Disconnect")}</Button>}
                      title={ui(appText("Disconnect {{name}}", { name: connection.name }))}
                      description={ui(
                        appText(
                          "The assistant will no longer be able to use {{name}} with your account. Existing conversations stay as they are.",
                          { name: connection.name },
                        ),
                      )}
                      confirmLabel={ui("Disconnect")}
                      pendingLabel={ui("Disconnecting…")}
                      confirmTone="danger"
                      onConfirm={async () => {
                        await disconnectMcpConnection({
                          path: { serverId: connection.id },
                        });
                        await cache.invalidateQueries({ queryKey: mcpConnectionsKey });
                      }}
                    />
                  ) : null
                }
              />
            );
          })}
        </SettingRows>
      )}
      {apiKeyFor ? (
        <McpApiKeyDialog connection={apiKeyFor} onClose={() => setApiKeyFor(null)} />
      ) : null}
    </SettingsLayout>
  );
}
