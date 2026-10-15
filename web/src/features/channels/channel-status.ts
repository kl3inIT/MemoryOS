import type { ChannelResponse } from "@/lib/hey-api/types.gen";

type Line = { line: string; tone: "muted" | "warning" | "danger" };

const LARK = {
  failed: "Không kết nối được tới Lark",
  connecting: "Đang kết nối tới Lark…",
  answering: "Đang trả lời trên Lark",
};

const ZALO = {
  failed: "Không kết nối được tới Zalo",
  connecting: "Đang kết nối tới Zalo…",
  answering: "Đang trả lời trên Zalo",
};

/** Why a Zalo bot failed, when the failure has a line of its own; otherwise the bot cannot connect. */
const ZALO_FAILURES: Readonly<Record<string, string>> = {
  CHANNEL_WEBHOOK_UNREACHABLE: "Zalo không gọi được tới webhook",
  CHANNEL_WEB_URL_MISSING: "Máy chủ chưa có địa chỉ công khai cho webhook",
  CHANNEL_APP_REJECTED: "Zalo từ chối token của bot",
};

/**
 * A bot's one line, as an MCP server's: what is wrong when something is, else what it is doing. A connection that
 * fails says so before a missing scope, which only limits what an open connection can read.
 */
export function channelLine(channel: ChannelResponse): Line {
  const words = channel.provider === "ZALO_BOT" ? ZALO : LARK;
  switch (channel.status) {
    case "FAILED": {
      const reason =
        channel.provider === "ZALO_BOT" && channel.failureCode
          ? ZALO_FAILURES[channel.failureCode]
          : undefined;
      return { line: reason ?? words.failed, tone: "danger" };
    }
    case "NOT_RUNNING":
      return { line: "Dịch vụ bot đang không chạy", tone: "warning" };
    case "CONNECTING":
      return { line: words.connecting, tone: "muted" };
    case "OFF":
      return { line: "Đang tắt", tone: "muted" };
    case "CONNECTED":
      return channel.missingScopes.length > 0
        ? { line: "Thiếu {{count}} quyền trên Lark", tone: "warning" }
        : { line: words.answering, tone: "muted" };
  }
}

/** The status in a word or two, for the facts of a bot's page. */
export function channelState(status: ChannelResponse["status"]): string {
  switch (status) {
    case "CONNECTED":
      return "Đã kết nối";
    case "CONNECTING":
      return "Đang kết nối";
    case "FAILED":
      return "Không kết nối được";
    case "NOT_RUNNING":
      return "Dịch vụ bot không chạy";
    case "OFF":
      return "Đã tắt";
  }
}

/** A bot switched on that is not connected yet is read again soon; nothing else is polled. */
export function settling(channels: readonly ChannelResponse[] | undefined) {
  return Boolean(channels?.some((channel) => channel.enabled && channel.status !== "CONNECTED"));
}
