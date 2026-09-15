import archiveIcon from "material-icon-theme/icons/zip.svg";
import audioIcon from "material-icon-theme/icons/audio.svg";
import databaseIcon from "material-icon-theme/icons/database.svg";
import documentIcon from "material-icon-theme/icons/document.svg";
import emailIcon from "material-icon-theme/icons/email.svg";
import epubIcon from "material-icon-theme/icons/epub.svg";
import fileIcon from "material-icon-theme/icons/file.svg";
import folderIcon from "material-icon-theme/icons/folder.svg";
import fontIcon from "material-icon-theme/icons/font.svg";
import htmlIcon from "material-icon-theme/icons/html.svg";
import imageIcon from "material-icon-theme/icons/image.svg";
import jsonIcon from "material-icon-theme/icons/json.svg";
import logIcon from "material-icon-theme/icons/log.svg";
import markdownIcon from "material-icon-theme/icons/markdown.svg";
import pdfIcon from "material-icon-theme/icons/pdf.svg";
import powerpointIcon from "material-icon-theme/icons/powerpoint.svg";
import svgIcon from "material-icon-theme/icons/svg.svg";
import tableIcon from "material-icon-theme/icons/table.svg";
import videoIcon from "material-icon-theme/icons/video.svg";
import wordIcon from "material-icon-theme/icons/word.svg";
import xmlIcon from "material-icon-theme/icons/xml.svg";
import yamlIcon from "material-icon-theme/icons/yaml.svg";

/** A file type's name and its Material Icon Theme glyph, the icons VS Code shows per extension. */
export type FileType = { label: string; icon: string };

const type = (label: string, icon: string): FileType => ({ label, icon });

const genericFile = type("File", fileIcon);
const image = type("Image", imageIcon);
const audio = type("Audio", audioIcon);
const video = type("Video", videoIcon);
const text = type("Text", documentIcon);

/** Google-native types have no extension, so their MIME type names them. */
const byMimeType = new Map<string, FileType>([
  ["application/vnd.google-apps.folder", type("Folder", folderIcon)],
  ["application/vnd.google-apps.document", type("Google Docs", documentIcon)],
  ["application/vnd.google-apps.spreadsheet", type("Google Sheets", tableIcon)],
  ["application/vnd.google-apps.presentation", type("Google Slides", powerpointIcon)],
  ["application/pdf", type("PDF", pdfIcon)],
  ["application/msword", type("Word", wordIcon)],
  [
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    type("Word", wordIcon),
  ],
  ["application/vnd.ms-excel", type("Excel", tableIcon)],
  ["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", type("Excel", tableIcon)],
  ["application/vnd.ms-powerpoint", type("PowerPoint", powerpointIcon)],
  [
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    type("PowerPoint", powerpointIcon),
  ],
  ["text/csv", type("CSV", tableIcon)],
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
      [["odt", "rtf"], type("Document", documentIcon)],
      [["xls", "xlsx", "xlsm", "xlsb"], type("Excel", tableIcon)],
      [["ods"], type("Spreadsheet", tableIcon)],
      [["csv"], type("CSV", tableIcon)],
      [["tsv"], type("TSV", tableIcon)],
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
      [["sql", "db", "sqlite", "sqlite3"], type("Database", databaseIcon)],
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
