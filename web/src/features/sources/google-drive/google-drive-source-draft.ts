import type { GetGoogleDriveConfigurationResponse, SourceSummary } from "@/lib/hey-api/types.gen";

/** What the person has entered for the new Source so far; it outlives a step back. */
export type GoogleDriveSourceDraft = {
  sourceName: string;
  access: SourceSummary["access"];
  groupIds: Set<string>;
  scopeMode: GetGoogleDriveConfigurationResponse["scopeMode"];
  linksText: string;
  linksTouched: boolean;
};

export const emptyGoogleDriveSourceDraft: GoogleDriveSourceDraft = {
  sourceName: "",
  access: "SYNC",
  groupIds: new Set(),
  scopeMode: "SPECIFIC",
  linksText: "",
  linksTouched: false,
};
