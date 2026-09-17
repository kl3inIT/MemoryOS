import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Blocks, ChevronRight } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { StatusBadge } from "@/components/ui/status-badge";
import { Switch } from "@/components/ui/switch";
import { CatalogDialog } from "@/features/models/catalog-dialog";
import { connectionStatus, needsUserAction, usableInTurn } from "@/features/mcp/mcp-status";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { sameOriginMutationHeaders } from "@/lib/api";
import { saveMcpConnectionApiKey, startMcpConnectionAuthorization } from "@/lib/hey-api/sdk.gen";
import type { McpConnection } from "@/lib/hey-api/types.gen";
import { composerMenuRow } from "./chat-composer-menu-row";
import { mcpConnectionsKey, useMcpConnections } from "./chat-mcp-connections";

/** The composer row that opens the MCP submenu, with the count selected for this turn. */
export function ChatMcpToggle({
  selected,
  available,
  onOpen,
}: {
  selected: string[];
  available: number;
  onOpen: () => void;
}) {
  const ui = useAppTranslation();
  if (available === 0) return null;
  return (
    <button type="button" className={composerMenuRow} onClick={onOpen}>
      <Blocks aria-hidden="true" />
      <span className="flex-1">{ui("Công cụ MCP")}</span>
      {selected.length > 0 ? (
        <span className="text-content-secondary">{selected.length}</span>
      ) : null}
      <ChevronRight aria-hidden="true" />
    </button>
  );
}

/**
 * One row per MCP server the User may use. A server they have not connected offers Connect instead of a
 * switch, because selecting it would only produce a connect message from the model.
 */
export function ChatMcpServers({
  selected,
  onChange,
  onBack,
  sessionId,
  allowedIds,
}: {
  selected: string[];
  onChange: (ids: string[]) => void;
  onBack: () => void;
  sessionId?: string;
  /** Servers attached to the conversation's agent; null means every server the User may use. */
  allowedIds?: string[] | null;
}) {
  const ui = useAppTranslation();
  const connections = useMcpConnections();
  const list = {
    data: connections.data?.filter(
      (connection) => !allowedIds || allowedIds.includes(connection.id),
    ),
  };
  const [apiKeyFor, setApiKeyFor] = useState<McpConnection | null>(null);

  return (
    <div className="flex flex-col gap-2">
      <Button
        size="sm"
        prominence="internal"
        aria-label={ui("Quay lại")}
        className="self-start"
        onClick={onBack}
      >
        <ArrowLeft className="size-4" aria-hidden="true" />
        {ui("Công cụ MCP")}
      </Button>
      {list.data && list.data.length === 0 ? (
        <p className="px-2 py-1 font-secondary-body text-content-muted">
          {ui("Chưa có máy chủ MCP nào dành cho bạn.")}
        </p>
      ) : null}
      <ul className="flex flex-col">
        {(list.data ?? []).map((connection) => {
          const status = connectionStatus(connection.connectionState);
          const actionable = needsUserAction(connection);
          return (
            <li key={connection.id} className="flex items-center gap-2 px-2 py-1.5">
              <div className="min-w-0 flex-1">
                <p className="truncate font-main-ui-body text-content-primary">{connection.name}</p>
                <p className="font-secondary-body text-content-muted">
                  <StatusBadge tone={status.tone}>{ui(status.label)}</StatusBadge>{" "}
                  {ui("{{count}} công cụ", { count: connection.enabledToolCount })}
                </p>
              </div>
              {actionable ? (
                <ConnectAction
                  connection={connection}
                  sessionId={sessionId}
                  onApiKey={setApiKeyFor}
                />
              ) : (
                <Switch
                  checked={selected.includes(connection.id)}
                  disabled={!usableInTurn(connection)}
                  aria-label={connection.name}
                  onCheckedChange={(on) =>
                    onChange(
                      on
                        ? [...selected, connection.id]
                        : selected.filter((id) => id !== connection.id),
                    )
                  }
                />
              )}
            </li>
          );
        })}
      </ul>
      {apiKeyFor ? (
        <McpApiKeyDialog connection={apiKeyFor} onClose={() => setApiKeyFor(null)} />
      ) : null}
    </div>
  );
}

export function ConnectAction({
  connection,
  sessionId,
  onApiKey,
}: {
  connection: McpConnection;
  sessionId?: string;
  onApiKey: (connection: McpConnection) => void;
}) {
  const ui = useAppTranslation();
  const [choosing, setChoosing] = useState(false);
  const authorize = useMutation({
    mutationFn: async (oauthClientId: string) =>
      await startMcpConnectionAuthorization({
        path: { serverId: connection.id },
        body: {
          oauthClientId,
          returnPath: sessionId === undefined ? "/" : `/chat/${sessionId}`,
        },
        headers: sameOriginMutationHeaders,
      }),
    onSuccess: (response) => {
      const url = response.data?.authorizationUrl;
      // The authorization server owns the next navigation; the callback returns to this chat.
      if (url) window.location.assign(url);
    },
  });

  if (connection.authType === "API_TOKEN") {
    return (
      <Button size="sm" prominence="secondary" onClick={() => onApiKey(connection)}>
        {ui("Kết nối")}
      </Button>
    );
  }
  const clients = connection.oauthClients;
  if (clients.length === 0) {
    return <StatusBadge tone="neutral">{ui("Chờ quản trị viên")}</StatusBadge>;
  }
  if (clients.length === 1 || !choosing) {
    return (
      <Button
        size="sm"
        prominence="secondary"
        pending={authorize.isPending}
        onClick={() => (clients.length === 1 ? authorize.mutate(clients[0].id) : setChoosing(true))}
      >
        {ui("Kết nối")}
      </Button>
    );
  }
  return (
    <div className="flex flex-col items-end gap-1">
      {clients.map((client) => (
        <Button
          key={client.id}
          size="sm"
          prominence="secondary"
          pending={authorize.isPending}
          onClick={() => authorize.mutate(client.id)}
        >
          {client.label}
        </Button>
      ))}
    </div>
  );
}

export function McpApiKeyDialog({
  connection,
  onClose,
}: {
  connection: McpConnection;
  onClose: () => void;
}) {
  const ui = useAppTranslation();
  const client = useQueryClient();
  const [value, setValue] = useState("");
  const [failed, setFailed] = useState(false);
  const save = useMutation({
    mutationFn: async () =>
      await saveMcpConnectionApiKey({
        path: { serverId: connection.id },
        body: { apiKey: value },
        headers: sameOriginMutationHeaders,
      }),
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: mcpConnectionsKey });
      onClose();
    },
    onError: () => setFailed(true),
  });

  return (
    <CatalogDialog
      title={ui("Kết nối {{name}}", { name: connection.name })}
      description={ui("Khoá được thử với máy chủ trước khi lưu, và chỉ bạn dùng được.")}
      onClose={onClose}
    >
      <div className="flex flex-col gap-4">
        <div className="flex flex-col gap-2">
          <Label htmlFor="mcp-user-key">{ui("Khoá API")}</Label>
          <Input
            id="mcp-user-key"
            type="password"
            autoComplete="off"
            value={value}
            onChange={(event) => {
              setValue(event.target.value);
              setFailed(false);
            }}
          />
        </div>
        {failed ? (
          <p role="alert" className="font-secondary-body text-status-danger-content">
            {ui("Máy chủ từ chối khoá này. Khoá chưa được lưu.")}
          </p>
        ) : null}
        <div className="flex justify-end gap-2">
          <Button prominence="secondary" onClick={onClose}>
            {ui("Huỷ")}
          </Button>
          <Button
            disabled={value.trim() === ""}
            pending={save.isPending}
            onClick={() => save.mutate()}
          >
            {ui("Kết nối")}
          </Button>
        </div>
      </div>
    </CatalogDialog>
  );
}
