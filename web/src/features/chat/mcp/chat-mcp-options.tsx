import { useState } from "react";
import { ArrowLeft, Blocks, ChevronRight } from "lucide-react";
import { Button } from "@/components/ui/button";
import { StatusBadge } from "@/components/ui/status-badge";
import { Switch } from "@/components/ui/switch";
import { connectionStatus, needsUserAction, usableInTurn } from "@/features/mcp/mcp-status";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { McpConnection } from "@/lib/hey-api/types.gen";
import { menuRow } from "@/components/composites/menu-row";
import { useMcpConnections } from "@/features/mcp/mcp-connections";
import { ConnectAction, McpApiKeyDialog } from "@/features/mcp/mcp-connect-actions";

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
    <button type="button" className={menuRow} onClick={onOpen}>
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
