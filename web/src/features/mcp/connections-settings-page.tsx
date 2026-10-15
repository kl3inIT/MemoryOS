import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Blocks, MoreHorizontal, Unplug } from "lucide-react";
import { EmptyState } from "@/components/composites/empty-state";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { IconButton } from "@/components/ui/icon-button";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { disconnectMcpConnectionMutation } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { McpConnection } from "@/lib/hey-api/types.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { AppGrid, AppRow } from "@/components/composites/app-row";
import { ConnectAction, McpApiKeyDialog } from "./mcp-connect-actions";
import { invalidateMcpConnections, useMcpConnections } from "./mcp-connections";
import { McpServerMark } from "./mcp-server-mark";
import { connectionStatus, needsUserAction } from "./mcp-status";

/**
 * Onyx Settings › Connectors, laid out as ChatGPT's plugin list: the MCP servers this member may use, connected
 * with their own account. The actions are the ones the Chat composer offers, returning here after an OAuth sign-in.
 */
export function ConnectionsSettingsPage() {
  const ui = useAppTranslation();
  const problem = useProblemMessage();
  const cache = useQueryClient();
  const connections = useMcpConnections();
  const disconnect = useMutation({
    ...disconnectMcpConnectionMutation(),
    onSuccess: () => invalidateMcpConnections(cache),
  });
  const [apiKeyFor, setApiKeyFor] = useState<McpConnection | null>(null);
  const [disconnecting, setDisconnecting] = useState<McpConnection | null>(null);
  const list = connections.data ?? [];
  return (
    <SettingsLayout>
      <PageHeader icon={<Blocks />} title={ui("Ứng dụng")} />
      {connections.isError ? (
        <Alert variant="destructive" role="alert">
          <AlertDescription>
            {problem(presentProblem(connections.error, "initialLoad").message)}
          </AlertDescription>
        </Alert>
      ) : null}
      {connections.isSuccess && list.length === 0 ? (
        <EmptyState icon={<Blocks />} title={ui("Tổ chức chưa bật ứng dụng nào.")} />
      ) : (
        <AppGrid>
          {list.map((connection) => {
            const status = connectionStatus(connection.connectionState);
            const waiting =
              connection.authType === "OAUTH" &&
              needsUserAction(connection) &&
              connection.oauthClients.length === 0;
            const own =
              connection.authPerformer === "PER_USER" && connection.connectionState === "CONNECTED";
            return (
              <AppRow
                key={connection.id}
                mark={
                  <McpServerMark
                    slug={connection.slug}
                    url={connection.url}
                    name={connection.name}
                  />
                }
                title={connection.name}
                line={
                  needsUserAction(connection)
                    ? ui(waiting ? "Chờ quản trị viên" : status.label)
                    : (connection.description ?? ui(status.label))
                }
                tone={needsUserAction(connection) && !waiting ? "warning" : "muted"}
                action={
                  needsUserAction(connection) && !waiting ? (
                    <ConnectAction
                      connection={connection}
                      returnPath="/settings/connections"
                      onApiKey={setApiKeyFor}
                    />
                  ) : own ? (
                    <DropdownMenu>
                      <DropdownMenuTrigger asChild>
                        <IconButton
                          size="sm"
                          prominence="internal"
                          aria-label={ui("Thao tác với {{name}}", { name: connection.name })}
                        >
                          <MoreHorizontal />
                        </IconButton>
                      </DropdownMenuTrigger>
                      <DropdownMenuContent align="end">
                        <DropdownMenuItem
                          variant="destructive"
                          onSelect={() => setDisconnecting(connection)}
                        >
                          <Unplug /> {ui("Ngắt kết nối")}
                        </DropdownMenuItem>
                      </DropdownMenuContent>
                    </DropdownMenu>
                  ) : null
                }
              />
            );
          })}
        </AppGrid>
      )}
      {disconnecting ? (
        <ConfirmDialog
          open
          title={ui("Ngắt kết nối {{name}}?", { name: disconnecting.name })}
          description={ui(
            "Trợ lý sẽ không dùng {{name}} bằng tài khoản của bạn nữa. Các hội thoại cũ giữ nguyên.",
            { name: disconnecting.name },
          )}
          confirmLabel={ui("Ngắt kết nối")}
          pendingLabel={ui("Đang ngắt")}
          confirmTone="danger"
          onConfirm={async () => {
            await disconnect.mutateAsync({ path: { serverId: disconnecting.id } });
          }}
          errorMessage={(failure) => presentProblem(failure, "mutation").message}
          onOpenChange={(open) => {
            if (!open) setDisconnecting(null);
          }}
        />
      ) : null}
      {apiKeyFor ? (
        <McpApiKeyDialog connection={apiKeyFor} onClose={() => setApiKeyFor(null)} />
      ) : null}
    </SettingsLayout>
  );
}
