import type { McpConnection, McpServerView } from "@/lib/hey-api/types.gen";
import type { StatusTone } from "@/components/ui/status-badge";
import type { AppCopy } from "@/i18n/app-text";

/** One product vocabulary for MCP state, so the admin list, the composer and the timeline agree. */
export function serverStatus(status: McpServerView["status"]): {
  tone: StatusTone;
  label: AppCopy;
} {
  switch (status) {
    case "CONNECTED":
      return { tone: "success", label: "Đã kết nối" };
    case "AWAITING_AUTH":
      return { tone: "warning", label: "Cần kết nối" };
    case "FETCHING_TOOLS":
      return { tone: "info", label: "Đang lấy công cụ" };
    case "DISCONNECTED":
      return { tone: "danger", label: "Mất kết nối" };
    default:
      return { tone: "neutral", label: "Chưa lấy công cụ" };
  }
}

/** What an administrator must do next before a server's tools reach Chat; null when nothing is missing. */
type ServerNextStep = "ADD_OAUTH_APP" | "CONNECT_SHARED" | "CONNECT_OWN" | "FETCH_TOOLS" | null;

/**
 * The first missing piece, in the order a server becomes usable: an OAuth application to sign in with, the shared
 * account, the administrator's own account (listing a per-person server's tools uses it), then the tool list.
 * `oauthApps` is undefined while the applications load and `own` when the administrator may not use the server.
 */
export function serverNextStep(
  server: McpServerView,
  oauthApps: number | undefined,
  own: McpConnection | undefined,
): ServerNextStep {
  if (server.authType === "OAUTH" && oauthApps === 0) return "ADD_OAUTH_APP";
  if (
    server.authType !== "NONE" &&
    server.authPerformer === "ADMIN" &&
    !server.sharedCredentialConfigured
  )
    return "CONNECT_SHARED";
  if (
    server.authType !== "NONE" &&
    server.authPerformer === "PER_USER" &&
    own &&
    needsUserAction(own)
  )
    return "CONNECT_OWN";
  if (server.status !== "CONNECTED" || server.toolCount === 0) return "FETCH_TOOLS";
  return null;
}

/** The badge for a server: what is missing when something is, otherwise its status. */
export function serverBadge(
  server: McpServerView,
  step: ServerNextStep,
): { tone: StatusTone; label: AppCopy } {
  switch (step) {
    case "ADD_OAUTH_APP":
      return { tone: "warning", label: "Chưa có ứng dụng OAuth" };
    case "CONNECT_SHARED":
      return { tone: "warning", label: "Chưa kết nối tài khoản dùng chung" };
    case "CONNECT_OWN":
      return { tone: "warning", label: "Bạn chưa kết nối" };
    default:
      return serverStatus(server.status);
  }
}

/** The one line under a server's name: what is missing when something is, otherwise how many tools are on. */
export function serverLine(
  server: McpServerView,
  step: ServerNextStep,
): { line: AppCopy; tone: "muted" | "warning" | "danger" } {
  const badge = serverBadge(server, step);
  if (step !== null && step !== "FETCH_TOOLS") return { line: badge.label, tone: "warning" };
  if (server.status === "DISCONNECTED") return { line: badge.label, tone: "danger" };
  if (server.toolCount === 0) return { line: badge.label, tone: "muted" };
  return { line: "{{enabled}}/{{total}} công cụ đang bật", tone: "muted" };
}

export function connectionStatus(state: McpConnection["connectionState"]): {
  tone: StatusTone;
  label: AppCopy;
} {
  switch (state) {
    case "CONNECTED":
      return { tone: "success", label: "Đã kết nối" };
    case "SHARED":
      return { tone: "success", label: "Dùng kết nối chung" };
    case "REAUTH_REQUIRED":
      return { tone: "warning", label: "Cần kết nối lại" };
    case "NOT_CONNECTED":
      return { tone: "warning", label: "Chưa kết nối" };
    default:
      return { tone: "neutral", label: "Không cần đăng nhập" };
  }
}

/** A User can act on a server only when their own credential is missing or rejected. */
export function needsUserAction(connection: McpConnection): boolean {
  return (
    connection.connectionState === "NOT_CONNECTED" ||
    connection.connectionState === "REAUTH_REQUIRED"
  );
}

/** Tools reach the model only from a server that is both connected and has enabled tools. */
export function usableInTurn(connection: McpConnection): boolean {
  return !needsUserAction(connection) && connection.enabledToolCount > 0;
}
