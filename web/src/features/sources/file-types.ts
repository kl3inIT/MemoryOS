import type { IconifyIcon } from "@iconify/react";
import googleDocsIcon from "@iconify-icons/simple-icons/googledocs";
import googleSheetsIcon from "@iconify-icons/simple-icons/googlesheets";
import googleSlidesIcon from "@iconify-icons/simple-icons/googleslides";
import fileIcon from "@iconify-icons/vscode-icons/default-file";
import folderIcon from "@iconify-icons/vscode-icons/default-folder";
import audioIcon from "@iconify-icons/vscode-icons/file-type-audio";
import databaseIcon from "@iconify-icons/vscode-icons/file-type-db";
import epubIcon from "@iconify-icons/vscode-icons/file-type-epub";
import excelIcon from "@iconify-icons/vscode-icons/file-type-excel";
import fontIcon from "@iconify-icons/vscode-icons/file-type-font";
import htmlIcon from "@iconify-icons/vscode-icons/file-type-html";
import imageIcon from "@iconify-icons/vscode-icons/file-type-image";
import jsonIcon from "@iconify-icons/vscode-icons/file-type-json";
import logIcon from "@iconify-icons/vscode-icons/file-type-log";
import markdownIcon from "@iconify-icons/vscode-icons/file-type-markdown";
import emailIcon from "@iconify-icons/vscode-icons/file-type-outlook";
import pdfIcon from "@iconify-icons/vscode-icons/file-type-pdf2";
import powerpointIcon from "@iconify-icons/vscode-icons/file-type-powerpoint";
import sqlIcon from "@iconify-icons/vscode-icons/file-type-sql";
import svgIcon from "@iconify-icons/vscode-icons/file-type-svg";
import textIcon from "@iconify-icons/vscode-icons/file-type-text";
import videoIcon from "@iconify-icons/vscode-icons/file-type-video";
import wordIcon from "@iconify-icons/vscode-icons/file-type-word";
import xmlIcon from "@iconify-icons/vscode-icons/file-type-xml";
import yamlIcon from "@iconify-icons/vscode-icons/file-type-yaml";
import archiveIcon from "@iconify-icons/vscode-icons/file-type-zip";

/**
 * A file type's name and icon: the vscode-icons logos for Office, PDF and other formats, and
 * Google's single-colour product marks, tinted with their brand colour, for Google-native files.
 */
export type FileType = { label: string; icon: IconifyIcon; color?: string };

const type = (label: string, icon: IconifyIcon, color?: string): FileType => ({
  label,
  icon,
  color,
});

const googleBrand = { docs: "#4285F4", sheets: "#34A853", slides: "#FBBC04" } as const;

const genericFile = type("File", fileIcon);
const image = type("Image", imageIcon);
const audio = type("Audio", audioIcon);
const video = type("Video", videoIcon);
const text = type("Text", textIcon);

/** Google-native types have no extension, so their MIME type names them. */
const byMimeType = new Map<string, FileType>([
  ["application/vnd.google-apps.folder", type("Folder", folderIcon)],
  ["application/vnd.google-apps.document", type("Google Docs", googleDocsIcon, googleBrand.docs)],
  [
    "application/vnd.google-apps.spreadsheet",
    type("Google Sheets", googleSheetsIcon, googleBrand.sheets),
  ],
  [
    "application/vnd.google-apps.presentation",
    type("Google Slides", googleSlidesIcon, googleBrand.slides),
  ],
  ["application/pdf", type("PDF", pdfIcon)],
  ["application/msword", type("Word", wordIcon)],
  [
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    type("Word", wordIcon),
  ],
  ["application/vnd.ms-excel", type("Excel", excelIcon)],
  ["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", type("Excel", excelIcon)],
  ["application/vnd.ms-powerpoint", type("PowerPoint", powerpointIcon)],
  [
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    type("PowerPoint", powerpointIcon),
  ],
  ["text/csv", type("CSV", excelIcon)],
  ["text/markdown", type("Markdown", markdownIcon)],
  ["text/html", type("HTML", htmlIcon)],
  ["application/json", type("JSON", jsonIcon)],
  ["application/x-ndjson", type("JSON Lines", jsonIcon)],
  ["text/plain", text],
]);

const byExtension = new Map<string, FileType>(
  (
    [
      [["doc", "docx", "docm", "dotx"], type("Word", wordIcon)],
      [["odt", "rtf"], type("Document", wordIcon)],
      [["xls", "xlsx", "xlsm", "xlsb"], type("Excel", excelIcon)],
      [["ods"], type("Spreadsheet", excelIcon)],
      [["csv"], type("CSV", excelIcon)],
      [["tsv"], type("TSV", excelIcon)],
      [["ppt", "pptx", "pptm"], type("PowerPoint", powerpointIcon)],
      [["odp", "key"], type("Presentation", powerpointIcon)],
      [["pdf"], type("PDF", pdfIcon)],
      [["md", "markdown", "mdx"], type("Markdown", markdownIcon)],
      [["txt", "text"], text],
      [["log"], type("Log", logIcon)],
      [["html", "htm", "xhtml"], type("HTML", htmlIcon)],
      [["json", "json5"], type("JSON", jsonIcon)],
      [["jsonl", "ndjson"], type("JSON Lines", jsonIcon)],
      [["xml"], type("XML", xmlIcon)],
      [["yaml", "yml"], type("YAML", yamlIcon)],
      [["png", "jpg", "jpeg", "gif", "webp", "bmp", "tif", "tiff", "heic", "avif", "ico"], image],
      [["svg"], type("SVG", svgIcon)],
      [["mp3", "wav", "m4a", "aac", "ogg", "flac", "opus"], audio],
      [["mp4", "mov", "webm", "mkv", "avi", "m4v"], video],
      [["zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz"], type("Archive", archiveIcon)],
      [["eml", "msg", "mbox"], type("Email", emailIcon)],
      [["epub"], type("EPUB", epubIcon)],
      [["sql"], type("Database", sqlIcon)],
      [["db", "sqlite", "sqlite3"], type("Database", databaseIcon)],
      [["ttf", "otf", "woff", "woff2"], type("Font", fontIcon)],
    ] satisfies Array<[string[], FileType]>
  ).flatMap(([extensions, fileType]) =>
    extensions.map((extension) => [extension, fileType] as const),
  ),
);

/**
 * Names a file's type from its Google-native MIME type, its extension, then any other MIME type,
 * so a Drive item and an uploaded file of the same format share an icon.
 */
export function fileTypeOf({
  name,
  mimeType,
}: {
  name?: string | null;
  mimeType?: string | null;
}): FileType {
  const media = mimeType?.split(";", 1)[0]?.trim().toLowerCase() ?? "";
  if (media.startsWith("application/vnd.google-apps.")) return byMimeType.get(media) ?? genericFile;
  const dot = name?.lastIndexOf(".") ?? -1;
  const extension = name && dot > 0 ? name.slice(dot + 1).toLowerCase() : "";
  return (
    byExtension.get(extension) ??
    byMimeType.get(media) ??
    (media.startsWith("image/")
      ? image
      : media.startsWith("audio/")
        ? audio
        : media.startsWith("video/")
          ? video
          : media.startsWith("text/")
            ? text
            : genericFile)
  );
}
