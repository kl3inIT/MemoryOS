import type { AppCopy } from "@/i18n/app-text";

export type SourceSection = { value: string; label: AppCopy };

const fileContentSections: readonly SourceSection[] = [
  { value: "content", label: "Files" },
  { value: "history", label: "Indexing history" },
];

export const googleDriveSections: readonly SourceSection[] = [
  { value: "content", label: "Content" },
  { value: "history", label: "Sync history" },
  { value: "settings", label: "Connection and settings" },
];

/**
 * Sections of a file or SharePoint Source. The settings tab holds group associations and the
 * administrator's manager appointment; without either it is dropped.
 */
export function fileSourceSections(showGroups: boolean, isAdministrator: boolean) {
  return showGroups
    ? [...fileContentSections, { value: "settings", label: "Groups" }]
    : isAdministrator
      ? [...fileContentSections, { value: "settings", label: "Manager" }]
      : fileContentSections;
}
