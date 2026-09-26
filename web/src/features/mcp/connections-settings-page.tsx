import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Blocks } from "lucide-react";
import { EmptyState } from "@/components/composites/empty-state";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { StatusBadge } from "@/components/ui/status-badge";
import { invalidateMcpConnections, useMcpConnections } from "./mcp-connections";
import { ConnectAction, McpApiKeyDialog } from "./mcp-connect-actions";
import { connectionStatus, needsUserAction } from "./mcp-status";
import { appText } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { disconnectMcpConnectionMutation } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { McpConnection } from "@/lib/hey-api/types.gen";

/**
 * Onyx Settings › Connectors: the MCP servers this member may use, connected with their own account. The actions
 * are the ones the Chat composer offers, returning here after an OAuth sign-in.
 */
export function ConnectionsSettingsPage() {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const connections = useMcpConnections();
  const disconnect = useMutation({
    ...disconnectMcpConnectionMutation(),
    onSuccess: () => invalidateMcpConnections(cache),
  });
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
        <EmptyState icon={<Blocks />} title={ui("No connectors set up for your organization.")} />
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
                        await disconnect.mutateAsync({ path: { serverId: connection.id } });
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
