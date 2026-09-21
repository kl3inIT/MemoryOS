import { FileSpreadsheet, FileText, Image as ImageIcon, Presentation } from "lucide-react";
import type { useAppTranslation } from "@/i18n/use-app-translation";
import type { LibraryCategory, LibraryFile, LibrarySource } from "./chat-library";

/** How a file is named on screen: where it came from, what kind it is, and how far along it is. */
export function sourceLabels(ui: ReturnType<typeof useAppTranslation>) {
  return {
    UPLOAD: ui("Đã tải lên"),
    GENERATED: ui("Do mã tạo"),
    IMAGE: ui("Ảnh AI"),
  } satisfies Record<LibrarySource, string>;
}

export function categoryLabels(ui: ReturnType<typeof useAppTranslation>) {
  return {
    DOCUMENT: ui("Tài liệu"),
    SPREADSHEET: ui("Bảng tính"),
    IMAGE: ui("Ảnh"),
    PRESENTATION: ui("Trình chiếu"),
    OTHER: ui("Khác"),
  } satisfies Record<LibraryCategory, string>;
}

export function statusLabel(file: LibraryFile, ui: ReturnType<typeof useAppTranslation>) {
  switch (file.status) {
    case "UPLOADING":
      return ui("Chưa xác nhận tải lên");
    case "PROCESSING":
      return ui("Đang xử lý…");
    case "FAILED":
      return file.errorCode === "UPLOAD_EXPIRED"
        ? ui("Tải lên hết hạn · hãy tải lại tệp")
        : ui("Xử lý lỗi");
    default:
      return ui("Sẵn sàng");
  }
}

export function categoryIcon(file: LibraryFile, className = "size-4 text-content-muted") {
  switch (file.category) {
    case "SPREADSHEET":
      return <FileSpreadsheet className={className} aria-hidden="true" />;
    case "IMAGE":
      return <ImageIcon className={className} aria-hidden="true" />;
    case "PRESENTATION":
      return <Presentation className={className} aria-hidden="true" />;
    default:
      return <FileText className={className} aria-hidden="true" />;
  }
}
