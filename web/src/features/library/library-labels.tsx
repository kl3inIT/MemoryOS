import { Mic } from "lucide-react";
import type { AppTranslate, useAppTranslation } from "@/i18n/use-app-translation";
import type { LibraryCategory, LibraryFile, LibrarySource } from "./library";
import type { LibraryEntry, LibraryEntryKind } from "./library-entries";
import { CATEGORY_ICONS } from "./library-icons";

/** How a file is named on screen: where it came from, what kind it is, and how far along it is. */
export function sourceLabels(ui: ReturnType<typeof useAppTranslation>) {
  return {
    UPLOAD: ui("Đã tải lên"),
    GENERATED: ui("Do mã tạo"),
    IMAGE: ui("Ảnh AI"),
  } satisfies Record<LibrarySource, string>;
}

/** What an entry is, as its row names it; an owned file reads as its source does in Tệp của tôi. */
export function entryKindLabels(ui: AppTranslate) {
  return {
    ...sourceLabels(ui),
    MEETING: ui("Cuộc họp"),
    AGENT_FILE: ui("Tệp của trợ lý"),
    DOCUMENT: ui("Tài liệu tổ chức"),
  } satisfies Record<LibraryEntryKind, string>;
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
  const Icon = CATEGORY_ICONS[file.category];
  return <Icon className={className} aria-hidden="true" />;
}

/** A meeting is a recording, not a file; everything else wears the icon of its category. */
export function entryIcon(entry: LibraryEntry, className = "size-4 text-content-muted") {
  const Icon = entry.kind === "MEETING" ? Mic : CATEGORY_ICONS[entry.category ?? "OTHER"];
  return <Icon className={className} aria-hidden="true" />;
}
