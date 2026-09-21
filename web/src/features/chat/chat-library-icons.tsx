import { File, FileChartColumn, FileImage, FileSpreadsheet, FileText } from "lucide-react";
import type { LibraryCategory } from "./chat-library";

/**
 * One icon per library category. They are all of the `File` family on purpose: a row of files should read as a
 * row of files, with the kind as the difference, rather than mixing a document, a picture frame and a
 * presentation board at three different weights.
 */
export const CATEGORY_ICONS = {
  DOCUMENT: FileText,
  SPREADSHEET: FileSpreadsheet,
  IMAGE: FileImage,
  PRESENTATION: FileChartColumn,
  OTHER: File,
} satisfies Record<LibraryCategory, typeof File>;
