import {
  AuiConfig,
  AuiProvider,
  McpAppRenderer,
  McpAppsRemoteHost,
  Tools,
  useAui,
  type McpAppHostContext,
} from "@assistant-ui/react";
import { useMemo, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { useMcpConnections } from "@/features/mcp/mcp-connections";
import { useTheme } from "@/features/theme/theme-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { appFetch, json, type Approval } from "./chat-mcp-app-fetch";

/** The tallest a view grows before it scrolls inside its frame. */
const MAX_HEIGHT = 640;
const HOST_INFO = { name: "MemoryOS", version: "1" };
/** The renderer posts here; `appFetch` sends each request to the route of the answer and call instead. */
const HOST_URL = "/api/chat/apps";

/**
 * Renders the MCP Apps views of the conversation's answers (MEM-275) with assistant-ui's `McpAppRenderer`, inline as
 * Claude shows them. Every request a view makes goes to the API route of the answer and call that showed it, which
 * resolves the server and its tool policy; a tool that asks first runs only after the person allows it here, as Claude
 * asks before a view's tool call. Messages to the chat and other display modes are not offered, as in Goose.
 */
export function ChatMcpApps({ children }: { children: ReactNode }) {
  const aui = useAui();
  const ui = useAppTranslation();
  const { resolvedTheme } = useTheme();
  const { i18n } = useTranslation();
  const connections = useMcpConnections();
  const [approval, setApproval] = useState<Approval | null>(null);

  const host = useMemo(
    () => McpAppsRemoteHost({ url: HOST_URL, fetch: (_, init) => appFetch(init, setApproval) }),
    [],
  );
  const handlers = useMemo(
    () => ({
      sendMessage: () => ({ isError: true, ok: false, reason: "not supported" }),
      requestDisplayMode: () => ({ mode: "inline" as const }),
    }),
    [],
  );
  const hostContext = useMemo<McpAppHostContext>(
    () => ({
      theme: resolvedTheme === "dark" ? "dark" : "light",
      locale: i18n.language,
      displayMode: "inline",
      availableDisplayModes: ["inline"],
    }),
    [resolvedTheme, i18n.language],
  );
  const config = AuiConfig({
    tools: Tools({
      mcpApp: McpAppRenderer({
        host,
        hostInfo: HOST_INFO,
        hostContext,
        handlers,
        maxHeight: MAX_HEIGHT,
      }),
    }),
  });

  const server = approval
    ? (connections.data?.find((candidate) => candidate.slug === approval.slug)?.name ??
      approval.slug)
    : "";
  return (
    <AuiProvider extends={aui} config={config}>
      {children}
      <ConfirmDialog
        open={approval !== null}
        onOpenChange={(open) => {
          if (open || !approval) return;
          approval.settle(json({ error: "declined" }, 403));
          setApproval(null);
        }}
        title={ui("Cho phép {{tool}} trên {{server}}?", { tool: approval?.tool ?? "", server })}
        description={
          <code className="block max-h-40 overflow-auto font-mono text-xs whitespace-pre-wrap">
            {JSON.stringify(approval?.arguments ?? {}, null, 2)}
          </code>
        }
        confirmLabel={ui("Cho phép")}
        pendingLabel={ui("Đang chạy…")}
        confirmTone="default"
        onConfirm={async () => {
          if (!approval) return;
          approval.settle(await approval.run());
          setApproval(null);
        }}
      />
    </AuiProvider>
  );
}
