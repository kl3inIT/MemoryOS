import type { ChannelResponse } from "@/lib/hey-api/types.gen";
import { providerWords } from "./channel-provider";

type Line = { line: string; tone: "muted" | "warning" | "danger" };

/** Why a bot failed, when the failure has a line of its own; otherwise the bot cannot connect. */
const FAILURES: Readonly<Partial<Record<ChannelResponse["provider"], Record<string, string>>>> = {
  ZALO_BOT: {
    CHANNEL_WEBHOOK_UNREACHABLE: "Zalo không gọi được tới webhook",
    CHANNEL_WEB_URL_MISSING: "Máy chủ chưa có địa chỉ công khai cho webhook",
    CHANNEL_APP_REJECTED: "Zalo từ chối token của bot",
  },
  WHATSAPP: {
    CHANNEL_WHATSAPP_LOGGED_OUT: "WhatsApp đã đăng xuất thiết bị",
    CHANNEL_WHATSAPP_BANNED: "WhatsApp đang tạm khóa số này",
    CHANNEL_WHATSAPP_REPLACED: "Phiên WhatsApp đã mở ở nơi khác",
    CHANNEL_WHATSAPP_WRONG_NUMBER: "Mã được quét bằng một số khác",
    CHANNEL_WHATSAPP_NUMBER_IN_USE: "Số này đã liên kết với bot khác",
  },
};

/** A failure that waits for the bot to be linked again rather than another try. */
const RELINK = new Set([
  "CHANNEL_WHATSAPP_LOGGED_OUT",
  "CHANNEL_WHATSAPP_BANNED",
  "CHANNEL_WHATSAPP_REPLACED",
  "CHANNEL_WHATSAPP_WRONG_NUMBER",
  "CHANNEL_WHATSAPP_NUMBER_IN_USE",
]);

function reason(channel: ChannelResponse) {
  return channel.failureCode ? FAILURES[channel.provider]?.[channel.failureCode] : undefined;
}

/**
 * A bot's one line, as an MCP server's: what is wrong when something is, else what it is doing. A connection that
 * fails says so before a missing scope, which only limits what an open connection can read.
 */
export function channelLine(channel: ChannelResponse): Line {
  const words = providerWords(channel.provider);
  switch (channel.status) {
    case "FAILED":
      return { line: reason(channel) ?? words.failed, tone: "danger" };
    case "NOT_RUNNING":
      return { line: "Dịch vụ bot đang không chạy", tone: "warning" };
    case "CONNECTING":
      return { line: words.connecting, tone: "muted" };
    case "PAIRING":
      return { line: "Chờ quét mã QR", tone: "warning" };
    case "OFF":
      return { line: "Đang tắt", tone: "muted" };
    case "CONNECTED":
      return channel.missingScopes.length > 0
        ? { line: "Thiếu {{count}} quyền trên Lark", tone: "warning" }
        : { line: words.answering, tone: "muted" };
  }
}

/** The last failure in words where it has its own, else its code. */
export function channelFailure(channel: ChannelResponse): string | undefined {
  if (!channel.failureCode) return undefined;
  return reason(channel) ?? channel.failureCode;
}

/** Whether the bot stopped until it is linked again: its number was logged out, banned, replaced or refused. */
export function needsRelink(channel: ChannelResponse) {
  return (
    channel.enabled &&
    channel.status === "FAILED" &&
    Boolean(channel.failureCode && RELINK.has(channel.failureCode))
  );
}

/** The status in a word or two, for the facts of a bot's page. */
export function channelState(status: ChannelResponse["status"]): string {
  switch (status) {
    case "CONNECTED":
      return "Đã kết nối";
    case "CONNECTING":
      return "Đang kết nối";
    case "PAIRING":
      return "Chờ liên kết";
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
