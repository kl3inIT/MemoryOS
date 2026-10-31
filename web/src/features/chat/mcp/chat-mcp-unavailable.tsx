import { use, useState } from "react";
import { useAuiState } from "@assistant-ui/react";
import {
  Item,
  ItemActions,
  ItemContent,
  ItemGroup,
  ItemMedia,
  ItemTitle,
} from "@/components/ui/item";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { McpConnection } from "@/lib/hey-api/types.gen";
import type { UnavailableApp } from "@/features/chat/activity/chat-activity";
import { ChatEditingContext } from "@/features/chat/thread/chat-editing-context";
import { ConnectAction, McpApiKeyDialog } from "@/features/mcp/mcp-connect-actions";
import { useMcpConnections } from "@/features/mcp/mcp-connections";
import { McpServerMark } from "@/features/mcp/mcp-server-mark";
import { needsUserAction } from "@/features/mcp/mcp-status";

/**
 * MEM-273: the reply's apps the person must connect first, under the answer with the composer's Connect action, as
 * Onyx answers a custom tool without credentials with a connect card. The line follows the app's state now, so an
 * app connected since, or no longer offered to the person, shows nothing.
 */
export function ChatMcpUnavailable() {
  const ui = useAppTranslation();
  const sessionId = use(ChatEditingContext)?.sessionId;
  const apps = useAuiState(
    (state) => state.message.metadata.custom.unavailableApps as UnavailableApp[] | undefined,
  );
  const connections = useMcpConnections();
  const [apiKeyFor, setApiKeyFor] = useState<McpConnection | null>(null);
  const waiting = (apps ?? []).flatMap((app) => {
    const connection = connections.data?.find((candidate) => candidate.id === app.serverId);
    return connection && needsUserAction(connection) ? [connection] : [];
  });
  if (waiting.length === 0) return null;
  return (
    <ItemGroup className="mt-3">
      {waiting.map((connection) => (
        <Item key={connection.id} variant="outline" size="sm">
          <ItemMedia>
            <McpServerMark
              size="sm"
              slug={connection.slug}
              url={connection.url}
              name={connection.name}
            />
          </ItemMedia>
          <ItemContent>
            <ItemTitle>
              {connection.connectionState === "REAUTH_REQUIRED"
                ? ui("{{name}} đã hết hạn đăng nhập", { name: connection.name })
                : ui("{{name}} chưa kết nối", { name: connection.name })}
            </ItemTitle>
          </ItemContent>
          <ItemActions>
            <ConnectAction
              connection={connection}
              sessionId={sessionId}
              // A conversation not saved yet (an assistant's test chat) returns to the page it is on.
              returnPath={
                sessionId === undefined
                  ? `${window.location.pathname}${window.location.search}`
                  : undefined
              }
              onApiKey={setApiKeyFor}
            />
          </ItemActions>
        </Item>
      ))}
      {apiKeyFor ? (
        <McpApiKeyDialog connection={apiKeyFor} onClose={() => setApiKeyFor(null)} />
      ) : null}
    </ItemGroup>
  );
}
