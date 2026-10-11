import { revalidateLogic } from "@tanstack/react-form";
import { z } from "zod";
import { useAppForm } from "@/components/form/app-form";
import { zCreateLarkSourceRequest } from "@/lib/hey-api/zod.gen";
import { emptyLarkScopeDraft, type LarkScopeDraft } from "./lark-scope";

type LarkSourceValues = {
  sourceName: string;
  access: "PUBLIC" | "PRIVATE";
  groupIds: ReadonlySet<string>;
  scope: LarkScopeDraft;
};

/**
 * The new Lark Source form. It lives with the setup page rather than a step, so what was entered
 * survives moving between steps; `onEdit` runs on every change, which starts a new request after a
 * settled attempt.
 */
export function useLarkSourceForm({
  scoped,
  onEdit,
  onSubmit,
}: {
  /** A scoped manager can only create group-access Sources. */
  scoped: boolean;
  onEdit: () => void;
  onSubmit: () => void;
}) {
  const defaultValues: LarkSourceValues = {
    sourceName: "",
    access: scoped ? "PRIVATE" : "PUBLIC",
    groupIds: new Set(),
    scope: emptyLarkScopeDraft(),
  };
  return useAppForm({
    defaultValues,
    validationLogic: revalidateLogic(),
    validators: {
      onDynamic: z.object({
        sourceName: zCreateLarkSourceRequest.shape.name,
        access: z.enum(["PUBLIC", "PRIVATE"]),
        groupIds: z.custom<ReadonlySet<string>>(),
        scope: z.custom<LarkScopeDraft>(),
      }),
    },
    listeners: { onChange: onEdit },
    onSubmit: () => onSubmit(),
  });
}

export type LarkSourceFormApi = ReturnType<typeof useLarkSourceForm>;
