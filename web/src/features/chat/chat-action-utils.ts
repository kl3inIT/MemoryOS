import { ApiError } from "@/lib/api";

export const chatField =
  "w-full rounded-lg border border-border-default bg-surface-raised p-3 outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-60";

export function chatActionError(error: unknown) {
  if (error instanceof ApiError) {
    if (error.status === 409)
      return "Dữ liệu đã thay đổi hoặc hội thoại đang trả lời. Tải lại rồi thử lại.";
    if ([401, 403, 404].includes(error.status ?? 0))
      return "Nội dung này không còn khả dụng hoặc bạn không có quyền truy cập.";
    if (error.status === 400)
      return "Thông tin chưa hợp lệ. Kiểm tra các giới hạn và quyền truy cập model, nguồn tài liệu.";
  }
  return "Chưa xác nhận được kết quả. Tải lại để kiểm tra trước khi thử lại.";
}
