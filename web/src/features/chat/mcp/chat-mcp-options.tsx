import { useState } from "react";
import { Link } from "@tanstack/react-router";
import { ArrowLeft, Blocks, Check, ChevronRight, Settings2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { connectionStatus, needsUserAction, usableInTurn } from "@/features/mcp/mcp-status";
import { McpServerMark } from "@/features/mcp/mcp-server-mark";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { McpConnection } from "@/lib/hey-api/types.gen";
import { menuRow } from "@/components/composites/menu-row";
import { useMcpConnections } from "@/features/mcp/mcp-connections";
import { ConnectAction, McpApiKeyDialog } from "@/features/mcp/mcp-connect-actions";

/**
 * The composer row that opens the apps submenu, as Claude and Mistral keep connectors in the `+` menu. The logos of
 * the apps on for this conversation show beside it without their tiles, so what the turn may use is visible without
 * opening it and the row stays quiet.
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
          className="flex items-center gap-1"
          aria-label={ui("{{count}} đang bật", { count: on.length })}
        >
          {on.slice(0, 3).map((connection) => (
            <McpServerMark
              key={connection.id}
              size="xs"
              bare
              slug={connection.slug}
              url={connection.url}
              name={connection.name}
            />
          ))}
          {on.length > 3 ? <span className="text-content-secondary">+{on.length - 3}</span> : null}
        </span>
      ) : null}
      <ChevronRight aria-hidden="true" />
    </button>
  );
}

/**
 * The apps the person may use. A connected app is one row that turns it on or off for this conversation with a check,
 * as the `+` menu marks Web, and the menu stays open for the next pick. Apps still to connect follow under their own
 * heading with Connect, because selecting one would only produce a connect message.
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
  const usable = (connections.data ?? []).filter(
    (connection) => !allowedIds || allowedIds.includes(connection.id),
  );
  const ready = usable.filter((connection) => !needsUserAction(connection));
  const waiting = usable.filter(needsUserAction);
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
      {connections.data && usable.length === 0 ? (
        <p className="px-2 py-1 font-secondary-body text-content-muted">
          {ui("Chưa có ứng dụng nào dành cho bạn.")}
        </p>
      ) : null}
      {ready.length > 0 ? (
        <div className="flex flex-col">
          {ready.map((connection) => {
            const on = selected.includes(connection.id);
            const usableNow = usableInTurn(connection);
            return (
              <button
                key={connection.id}
                type="button"
                aria-pressed={on && usableNow}
                disabled={!usableNow}
                className={menuRow}
                onClick={() =>
                  onChange(
                    on
                      ? selected.filter((id) => id !== connection.id)
                      : [...selected, connection.id],
                  )
                }
              >
                <McpServerMark
                  size="xs"
                  slug={connection.slug}
                  url={connection.url}
                  name={connection.name}
                />
                <span className="min-w-0 flex-1 truncate">{connection.name}</span>
                {usableNow ? null : (
                  <span className="shrink-0 text-content-muted">{ui("Chưa bật công cụ nào")}</span>
                )}
                {on && usableNow ? <Check aria-hidden="true" /> : null}
              </button>
            );
          })}
        </div>
      ) : null}
      {waiting.length > 0 ? (
        <>
          <Separator className="my-1" />
          <p className="px-2 font-secondary-body text-content-muted">{ui("Chưa kết nối")}</p>
          <ul className="flex flex-col">
            {waiting.map((connection) => (
              <li key={connection.id} className="flex items-center gap-2 rounded-lg px-2 py-1.5">
                <McpServerMark
                  size="xs"
                  slug={connection.slug}
                  url={connection.url}
                  name={connection.name}
                />
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm text-content-primary">{connection.name}</p>
                  {connection.connectionState === "REAUTH_REQUIRED" ? (
                    <p className="truncate font-secondary-body text-status-warning-content">
                      {ui(connectionStatus(connection.connectionState).label)}
                    </p>
                  ) : null}
                </div>
                <ConnectAction
                  connection={connection}
                  sessionId={sessionId}
                  onApiKey={setApiKeyFor}
                />
              </li>
            ))}
          </ul>
        </>
      ) : null}
      <Separator className="my-1" />
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
