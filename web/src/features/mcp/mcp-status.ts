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
