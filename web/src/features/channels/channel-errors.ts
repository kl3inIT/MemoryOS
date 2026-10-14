import { ApiError, problemCode } from "@/lib/api";

const messages: Readonly<Record<string, string>> = {
  CHANNEL_APP_REJECTED: "Lark từ chối App ID hoặc App Secret, hoặc app chưa bật tính năng bot.",
  CHANNEL_APP_IN_USE: "App này đã gắn với một bot khác.",
  CHANNEL_PROVIDER_UNAVAILABLE: "Lark không phản hồi. Hãy thử lại sau ít phút.",
  CHANNEL_NOT_CONFIGURED: "Máy chủ chưa có khoá mã hoá cho bot. Hãy báo người vận hành.",
  CHANNEL_CHANGED: "Bot vừa được thay đổi ở nơi khác. Hãy thử lại.",
  CHANNEL_NOT_FOUND: "Bot này không còn nữa.",
  CHANNEL_INVALID: "Kiểm tra lại các trường rồi thử lại.",
};

/** Safe copy for a failed bot change, read from the problem's code; never the backend's text. */
export function channelError(failure: unknown): string | undefined {
  const code = failure instanceof ApiError ? problemCode(failure) : undefined;
  return code ? messages[code] : undefined;
}
