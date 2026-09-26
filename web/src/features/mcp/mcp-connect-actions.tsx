import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { CatalogDialog } from "@/components/composites/catalog-dialog";
import { Button } from "@/components/ui/button";
import { Field, FieldError, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { StatusBadge } from "@/components/ui/status-badge";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  saveMcpConnectionApiKeyMutation,
  startMcpConnectionAuthorizationMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { McpConnection } from "@/lib/hey-api/types.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { invalidateMcpConnections } from "./mcp-connections";

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
    ...startMcpConnectionAuthorizationMutation(),
    onSuccess: ({ authorizationUrl }) => {
      // The authorization server owns the next navigation; the callback returns to this chat.
      if (authorizationUrl) window.location.assign(authorizationUrl);
    },
  });
  const start = (oauthClientId: string) =>
    authorize.mutate({
      path: { serverId: connection.id },
      body: {
        oauthClientId,
        returnPath: returnPath ?? (sessionId === undefined ? "/" : `/chat/${sessionId}`),
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
    <FieldError>{problem(presentProblem(authorize.error, "mutation").message)}</FieldError>
  ) : null;
  if (clients.length === 1 || !choosing) {
    return (
      <div className="flex flex-col items-end gap-1">
        <Button
          size="sm"
          prominence="secondary"
          pending={authorize.isPending}
          onClick={() => (clients.length === 1 ? start(clients[0].id) : setChoosing(true))}
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
          onClick={() => start(client.id)}
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
  const cache = useQueryClient();
  const [value, setValue] = useState("");
  const save = useMutation({
    ...saveMcpConnectionApiKeyMutation(),
    // The request carries the key, so the finished mutation is not kept in the cache.
    gcTime: 0,
    onSuccess: async () => {
      await invalidateMcpConnections(cache);
      onClose();
    },
  });

  return (
    <CatalogDialog
      title={ui("Kết nối {{name}}", { name: connection.name })}
      description={ui("Khoá được thử với máy chủ trước khi lưu, và chỉ bạn dùng được.")}
      onClose={onClose}
    >
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          if (value.trim() !== "")
            save.mutate({ path: { serverId: connection.id }, body: { apiKey: value } });
        }}
      >
        <Field data-invalid={save.isError || undefined}>
          <FieldLabel htmlFor="mcp-user-key">{ui("Khoá API")}</FieldLabel>
          <Input
            id="mcp-user-key"
            type="password"
            autoComplete="off"
            value={value}
            aria-invalid={save.isError || undefined}
            onChange={(event) => {
              setValue(event.target.value);
              save.reset();
            }}
          />
          {save.isError ? (
            <FieldError>{ui("Máy chủ từ chối khoá này. Khoá chưa được lưu.")}</FieldError>
          ) : null}
        </Field>
        <div className="flex justify-end gap-2">
          <Button prominence="secondary" onClick={onClose}>
            {ui("Huỷ")}
          </Button>
          <Button type="submit" disabled={value.trim() === ""} pending={save.isPending}>
            {ui("Kết nối")}
          </Button>
        </div>
      </form>
    </CatalogDialog>
  );
}
