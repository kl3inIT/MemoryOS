import { FileSearch, Globe, ImagePlus, SquareTerminal } from "lucide-react";
import type { AgentTool } from "@/features/chat/chat-personas-api";
import { useAppTranslation } from "@/i18n/use-app-translation";

export const toolIcons = {
  search: FileSearch,
  web_search: Globe,
  image_generation: ImagePlus,
  code_interpreter: SquareTerminal,
};

/** The name and description of each agent tool, as the editor and its preview show them. */
export function useToolNames(): Record<AgentTool, { label: string; hint: string }> {
  const ui = useAppTranslation();
  return {
    search: {
      label: ui("Tìm tài liệu nội bộ"),
      hint: ui("Tìm trong nguồn đã chọn, theo quyền đọc của từng người dùng."),
    },
    web_search: { label: ui("Tìm kiếm Web"), hint: ui("Tìm và đọc trang Web công khai.") },
    image_generation: {
      label: ui("Tạo ảnh"),
      hint: ui("Tạo và chỉnh sửa ảnh khi người dùng yêu cầu."),
    },
    code_interpreter: {
      label: ui("Chạy Python"),
      hint: ui(
        "Tính toán, xử lý tệp và vẽ biểu đồ bằng Python khi quản trị viên bật Code Interpreter.",
      ),
    },
  };
}
