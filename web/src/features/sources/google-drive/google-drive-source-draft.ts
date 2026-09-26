import { revalidateLogic } from "@tanstack/react-form";
import { z } from "zod";
import { useAppForm } from "@/components/form/app-form";
import type { GetGoogleDriveConfigurationResponse, SourceSummary } from "@/lib/hey-api/types.gen";
import { zCreateGoogleDriveSourceRequest } from "@/lib/hey-api/zod.gen";

/** What the person has entered for the new Source so far; it outlives a step back. */
type GoogleDriveSourceDraft = {
  sourceName: string;
  access: SourceSummary["access"];
  groupIds: ReadonlySet<string>;
  scopeMode: GetGoogleDriveConfigurationResponse["scopeMode"];
  linksText: string;
};

const emptyDraft: GoogleDriveSourceDraft = {
  sourceName: "",
  access: "SYNC",
  groupIds: new Set(),
  scopeMode: "SPECIFIC",
  linksText: "",
};

/**
 * The new Google Drive Source form. It lives with the setup page rather than the step, so its
 * values survive a step back to the credentials; `onEdit` runs on every change, which starts a new
 * request after a settled attempt.
 */
export function useGoogleDriveSourceForm({
  onEdit,
  onSubmit,
}: {
  onEdit: () => void;
  onSubmit: () => void;
}) {
  return useAppForm({
    defaultValues: emptyDraft,
    validationLogic: revalidateLogic(),
    validators: {
      onDynamic: z.object({
        sourceName: zCreateGoogleDriveSourceRequest.shape.name,
        access: zCreateGoogleDriveSourceRequest.shape.access.unwrap(),
        groupIds: z.custom<ReadonlySet<string>>(),
        scopeMode: zCreateGoogleDriveSourceRequest.shape.scopeMode,
        linksText: z.string(),
      }),
    },
    listeners: { onChange: onEdit },
    onSubmit: () => onSubmit(),
  });
}

export type GoogleDriveSourceFormApi = ReturnType<typeof useGoogleDriveSourceForm>;
