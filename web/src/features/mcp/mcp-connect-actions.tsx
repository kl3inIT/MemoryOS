import { useState } from "react";
import { ChevronDown } from "lucide-react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { CatalogDialog } from "@/components/composites/catalog-dialog";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
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
  label,
  primary = false,
}: {
  connection: McpConnection;
  sessionId?: string;
  /** Where the OAuth callback returns; the conversation by default. */
  returnPath?: string;
  onApiKey: (connection: McpConnection) => void;
  /** Replaces "Kết nối" / "Kết nối lại", e.g. for the administrator's own account. */
  label?: string;
  primary?: boolean;
}) {
  const ui = useAppTranslation();
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
  const text =
    label ?? ui(connection.connectionState === "REAUTH_REQUIRED" ? "Kết nối lại" : "Kết nối");
  const prominence = primary ? "primary" : "secondary";

  if (connection.authType === "API_TOKEN") {
    return (
      <Button size="sm" prominence={prominence} onClick={() => onApiKey(connection)}>
        {text}
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
  const only = clients.length === 1 ? clients[0] : undefined;
  return (
    <div className="flex flex-col items-end gap-1">
      {only ? (
        <Button
          size="sm"
          prominence={prominence}
          pending={authorize.isPending}
          onClick={() => start(only.id)}
        >
          {text}
        </Button>
      ) : (
        // Each organization signs in through its own application, named by the administrator.
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button size="sm" prominence={prominence} pending={authorize.isPending}>
              {text}
              <ChevronDown data-icon="inline-end" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            {clients.map((client) => (
              <DropdownMenuItem key={client.id} onSelect={() => start(client.id)}>
                {client.label}
              </DropdownMenuItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>
      )}
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
  const problem = useProblemMessage();
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
      description={ui("Khóa được thử với máy chủ trước khi lưu, và chỉ bạn dùng được.")}
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
          <FieldLabel htmlFor="mcp-user-key">{ui("Khóa API")}</FieldLabel>
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
            <FieldError>
              {presentProblem(save.error, "mutation").code === "MCP_AUTHORIZATION_REQUIRED"
                ? ui("Máy chủ từ chối khóa này. Khóa chưa được lưu.")
                : problem(presentProblem(save.error, "mutation").message)}
            </FieldError>
          ) : null}
        </Field>
        <div className="flex justify-end gap-2">
          <Button prominence="secondary" onClick={onClose}>
            {ui("Hủy")}
          </Button>
          <Button type="submit" disabled={value.trim() === ""} pending={save.isPending}>
            {ui("Kết nối")}
          </Button>
        </div>
      </form>
    </CatalogDialog>
  );
}
