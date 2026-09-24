import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { StatusBadge } from "@/components/ui/status-badge";
import { CatalogDialog } from "@/features/models/catalog-dialog";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { saveMcpConnectionApiKey, startMcpConnectionAuthorization } from "@/lib/hey-api/sdk.gen";
import type { McpConnection } from "@/lib/hey-api/types.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { mcpConnectionsKey } from "./mcp-connections";

export function ConnectAction({
  connection,
  sessionId,
  returnPath,
  onApiKey,
}: {
  connection: McpConnection;
  sessionId?: string;
  /** Where the OAuth callback returns; the conversation by default. */
  returnPath?: string;
  onApiKey: (connection: McpConnection) => void;
}) {
  const ui = useAppTranslation();
  const [choosing, setChoosing] = useState(false);
  const problem = useProblemMessage();
  const authorize = useMutation({
    mutationFn: async (oauthClientId: string) =>
      await startMcpConnectionAuthorization({
        path: { serverId: connection.id },
        body: {
          oauthClientId,
          returnPath: returnPath ?? (sessionId === undefined ? "/" : `/chat/${sessionId}`),
        },
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
  const failure = authorize.isError ? (
    <p role="alert" className="font-secondary-body text-status-danger-content">
      {problem(presentProblem(authorize.error, "mutation").message)}
    </p>
  ) : null;
  if (clients.length === 1 || !choosing) {
    return (
      <div className="flex flex-col items-end gap-1">
        <Button
          size="sm"
          prominence="secondary"
          pending={authorize.isPending}
          onClick={() =>
            clients.length === 1 ? authorize.mutate(clients[0].id) : setChoosing(true)
          }
        >
          {ui("Kết nối")}
        </Button>
        {failure}
      </div>
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
      {failure}
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
