import { revalidateLogic } from "@tanstack/react-form";
import { z } from "zod";
import { useAppForm } from "@/components/form/app-form";
import { zCreateSharePointSourceRequest } from "@/lib/hey-api/zod.gen";
import { emptySharePointScopeDraft, type SharePointScopeDraft } from "./sharepoint-scope";

type SharePointSourceValues = {
  sourceName: string;
  access: "PUBLIC" | "PRIVATE";
  groupIds: ReadonlySet<string>;
  scope: SharePointScopeDraft;
};

/**
 * The new SharePoint Source form. It lives with the setup page rather than a step, so what was
 * entered survives moving between steps; `onEdit` runs on every change, which starts a new request
 * after a settled attempt.
 */
export function useSharePointSourceForm({
  scoped,
  onEdit,
  onSubmit,
}: {
  /** A scoped manager can only create group-access Sources. */
  scoped: boolean;
  onEdit: () => void;
  onSubmit: () => void;
}) {
  const defaultValues: SharePointSourceValues = {
    sourceName: "",
    access: scoped ? "PRIVATE" : "PUBLIC",
    groupIds: new Set(),
    scope: emptySharePointScopeDraft(),
  };
  return useAppForm({
    defaultValues,
    validationLogic: revalidateLogic(),
    validators: {
      onDynamic: z.object({
        sourceName: zCreateSharePointSourceRequest.shape.name,
        access: z.enum(["PUBLIC", "PRIVATE"]),
        groupIds: z.custom<ReadonlySet<string>>(),
        scope: z.custom<SharePointScopeDraft>(),
      }),
    },
    listeners: { onChange: onEdit },
    onSubmit: () => onSubmit(),
  });
}

export type SharePointSourceFormApi = ReturnType<typeof useSharePointSourceForm>;
