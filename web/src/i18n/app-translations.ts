// The whole catalog, for the i18n audit and tests. The application bundles one language: `vi.ts` holds
// `englishUi` and `en.ts`, loaded on demand, holds `vietnameseUi`. A key missing from the active language
// renders as its own text, so Vietnamese keys read as themselves in Vietnamese and English keys in English.
import { englishUi } from "./app-translations.vi.ts";
import { vietnameseUi } from "./app-translations.en.ts";

const unchanged = [
  "Account ID",
  "Google Sheets",
  "Google Docs",
  "Google Slides",
  "PDF",
  "CSV",
  "JSON",
  "Markdown",
  "Word",
  "Excel",
  "PowerPoint",
  "MemoryOS",
  "Google Drive",
  "Tiếng Việt",
  "English",
  "name@company.com",
  "{{v1}} B",
  "{{v1}} KiB",
  " {{v1}}%",
  " ({{v1}})",
  " +{{v1}}",
  "{{percent}}%",
  "{{width}} × {{height}} px",
  "*/Archive/*",
  "https://contoso.sharepoint.com/sites/Archive*",
];
export const appEn: Record<string, string> = Object.fromEntries([
  ...Object.keys(englishUi).map((key) => [key, key]),
  ...Object.entries(vietnameseUi),
  ...unchanged.map((key) => [key, key]),
]);
export const appVi: Record<string, string> = Object.fromEntries([
  ...Object.entries(englishUi),
  ...Object.keys(vietnameseUi).map((key) => [key, key]),
  ...unchanged.map((key) => [key, key]),
]);
