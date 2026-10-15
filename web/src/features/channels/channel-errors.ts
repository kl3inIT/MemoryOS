import { ApiError, problemCode } from "@/lib/api";
import type { ChannelResponse } from "@/lib/hey-api/types.gen";

type Provider = ChannelResponse["provider"];

const messages: Readonly<Record<string, string>> = {
  CHANNEL_APP_REJECTED: "Lark từ chối App ID hoặc App Secret, hoặc app chưa bật tính năng bot.",
  CHANNEL_APP_IN_USE: "App này đã gắn với một bot khác.",
  CHANNEL_PROVIDER_UNAVAILABLE: "Lark không phản hồi. Hãy thử lại sau ít phút.",
  CHANNEL_NOT_CONFIGURED: "Máy chủ chưa có khóa mã hóa cho bot. Hãy báo người vận hành.",
  CHANNEL_CHANGED: "Bot vừa được thay đổi ở nơi khác. Hãy thử lại.",
  CHANNEL_NOT_FOUND: "Bot này không còn nữa.",
  CHANNEL_INVALID: "Kiểm tra lại các trường rồi thử lại.",
  CHANNEL_WEB_URL_MISSING:
    "Máy chủ chưa có địa chỉ công khai, nên Zalo không gọi được webhook. Hãy chọn Polling.",
  CHANNEL_WEBHOOK_UNREACHABLE: "Zalo không gọi được tới webhook của MemoryOS. Hãy chọn Polling.",
  CHANNEL_PROVIDER_QUOTA: "Bot đã dùng hết quota tin nhắn của Zalo trong tháng.",
};

/** Zalo's own words where Lark's would be wrong. */
const zaloMessages: Readonly<Record<string, string>> = {
  CHANNEL_APP_REJECTED: "Zalo từ chối token này. Hãy sao chép lại token từ Zalo Bot Creator.",
  CHANNEL_APP_IN_USE: "Bot Zalo này đã được thêm.",
  CHANNEL_PROVIDER_UNAVAILABLE: "Zalo không phản hồi. Hãy thử lại sau ít phút.",
};

/** Safe copy for a failed bot change, read from the problem's code; never the backend's text. */
export function channelError(failure: unknown, provider: Provider = "LARK"): string | undefined {
  const code = failure instanceof ApiError ? problemCode(failure) : undefined;
  if (!code) return undefined;
  return (provider === "ZALO_BOT" ? zaloMessages[code] : undefined) ?? messages[code];
}
