import { useState } from "react";
import { Link } from "@tanstack/react-router";
import { ArrowLeft, Blocks, ChevronRight, Settings2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import { connectionStatus, needsUserAction, usableInTurn } from "@/features/mcp/mcp-status";
import { McpServerMark } from "@/features/mcp/mcp-server-mark";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { McpConnection } from "@/lib/hey-api/types.gen";
import { menuRow } from "@/components/composites/menu-row";
import { cn } from "@/lib/utils";
import { useMcpConnections } from "@/features/mcp/mcp-connections";
import { ConnectAction, McpApiKeyDialog } from "@/features/mcp/mcp-connect-actions";

/**
 * The composer row that opens the apps submenu, as Claude and Mistral keep connectors in the `+` menu. The marks of
 * the apps on for this conversation show beside it, so what the turn may use is visible without opening it.
 */
export function ChatMcpToggle({
  selected,
  connections,
  onOpen,
}: {
  selected: string[];
  /** The servers the person may use in this conversation. */
  connections: McpConnection[];
  onOpen: () => void;
}) {
  const ui = useAppTranslation();
  if (connections.length === 0) return null;
  const on = connections.filter((connection) => selected.includes(connection.id));
  return (
    <button type="button" className={menuRow} onClick={onOpen}>
      <Blocks aria-hidden="true" />
      <span className="flex-1">{ui("Ứng dụng")}</span>
      {on.length > 0 ? (
        <span
          className="flex items-center -space-x-1"
          aria-label={ui("{{count}} đang bật", { count: on.length })}
        >
          {on.slice(0, 3).map((connection) => (
            <McpServerMark
              key={connection.id}
              size="xs"
              slug={connection.slug}
              url={connection.url}
              name={connection.name}
              className="ring-2 ring-surface-raised"
            />
          ))}
          {on.length > 3 ? (
            <span className="pl-2 text-content-secondary">+{on.length - 3}</span>
          ) : null}
        </span>
      ) : null}
      <ChevronRight aria-hidden="true" />
    </button>
  );
}

/**
 * One row per app the person may use: its logo, name and one line, then a switch for this conversation. An app
 * they have not connected offers Connect instead, because selecting it would only produce a connect message.
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
  const usable = connections.data?.filter(
    (connection) => !allowedIds || allowedIds.includes(connection.id),
  );
  const [apiKeyFor, setApiKeyFor] = useState<McpConnection | null>(null);

  return (
    <div className="flex flex-col gap-1">
      <Button
        size="sm"
        prominence="internal"
        aria-label={ui("Quay lại")}
        className="self-start"
        onClick={onBack}
      >
        <ArrowLeft data-icon="inline-start" aria-hidden="true" />
        {ui("Ứng dụng")}
      </Button>
      {usable?.length === 0 ? (
        <p className="px-2 py-1 font-secondary-body text-content-muted">
          {ui("Chưa có ứng dụng nào dành cho bạn.")}
        </p>
      ) : null}
      <ul className="flex flex-col">
        {(usable ?? []).map((connection) => {
          const actionable = needsUserAction(connection);
          const status = connectionStatus(connection.connectionState);
          return (
            <li key={connection.id} className="flex items-center gap-2.5 rounded-lg px-2 py-1.5">
              <McpServerMark
                size="sm"
                slug={connection.slug}
                url={connection.url}
                name={connection.name}
              />
              <div className="min-w-0 flex-1">
                <p className="truncate font-main-ui-body text-content-primary">{connection.name}</p>
                <p
                  className={cn(
                    "truncate font-secondary-body",
                    actionable ? "text-status-warning-content" : "text-content-muted",
                  )}
                >
                  {actionable
                    ? ui(status.label)
                    : ui("{{count}} công cụ", { count: connection.enabledToolCount })}
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
      <Link to="/settings/connections" className={menuRow}>
        <Settings2 aria-hidden="true" />
        <span className="flex-1">{ui("Quản lý kết nối")}</span>
      </Link>
      {apiKeyFor ? (
        <McpApiKeyDialog connection={apiKeyFor} onClose={() => setApiKeyFor(null)} />
      ) : null}
    </div>
  );
}
