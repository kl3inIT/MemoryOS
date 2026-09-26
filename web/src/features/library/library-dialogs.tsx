import { revalidateLogic } from "@tanstack/react-form";
import { z } from "zod";
import { useAppForm, useProblemErrors } from "@/components/form/app-form";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { FieldGroup } from "@/components/ui/field";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { LibraryFile } from "./library";

/** Longest name the dialog accepts; the server keeps the extension on top of it. */
const filenameLimit = 200;

/** Renaming keeps the extension, so the dialog says what the file's new name will be before it is saved. */
export function RenameDialog({
  file,
  onOpenChange,
  onRename,
}: {
  file: LibraryFile;
  onOpenChange: (open: boolean) => void;
  onRename: (filename: string) => Promise<unknown>;
}) {
  const ui = useAppTranslation();
  const problemErrors = useProblemErrors();
  const form = useAppForm({
    defaultValues: { filename: file.filename },
    // Errors appear on submit and then follow each change until the name is valid.
    validationLogic: revalidateLogic(),
    validators: {
      onDynamic: z.object({
        filename: z.string().trim().min(1, ui("Nhập tên tệp.")).max(filenameLimit),
      }),
    },
    onSubmit: async ({ value, formApi }) => {
      formApi.setErrorMap({ onSubmit: { form: undefined, fields: {} } });
      try {
        await onRename(value.filename.trim());
      } catch (cause) {
        formApi.setErrorMap({ onSubmit: problemErrors(cause) });
        return;
      }
      onOpenChange(false);
    },
  });
  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent showCloseButton={false}>
        <form
          noValidate
          className="flex flex-col gap-5"
          onSubmit={(event) => {
            event.preventDefault();
            void form.handleSubmit();
          }}
        >
          <DialogHeader>
            <DialogTitle>{ui("Đổi tên tệp")}</DialogTitle>
            <DialogDescription>
              {ui("Tên mới hiển thị ở mọi nơi và khi tải về. Phần đuôi tệp được giữ nguyên.")}
            </DialogDescription>
          </DialogHeader>
          <FieldGroup>
            <form.AppField name="filename">
              {(field) => <field.TextField label={ui("Tên tệp")} maxLength={filenameLimit} />}
            </form.AppField>
          </FieldGroup>
          <form.AppForm>
            <form.FormError />
            <DialogFooter>
              <DialogClose asChild>
                <Button type="button" prominence="secondary">
                  {ui("Đóng")}
                </Button>
              </DialogClose>
              <form.SubmitButton>{ui("Đổi tên")}</form.SubmitButton>
            </DialogFooter>
          </form.AppForm>
        </form>
      </DialogContent>
    </Dialog>
  );
}

/** Deleting a selection: where the files go depends on how long this server keeps the trash. */
export function DeleteFilesDialog({
  files,
  trashDays,
  onOpenChange,
  onConfirm,
}: {
  files: readonly LibraryFile[] | undefined;
  trashDays: number | undefined;
  onOpenChange: (open: boolean) => void;
  onConfirm: () => Promise<void>;
}) {
  const ui = useAppTranslation();
  const count = files?.length ?? 0;
  return (
    <ConfirmDialog
      open={files !== undefined}
      onOpenChange={onOpenChange}
      title={ui("Xoá tệp?")}
      description={
        trashDays === 0
          ? ui(
              "Tệp sẽ bị xoá khỏi mọi cuộc hội thoại và không thể khôi phục. Đã chọn {{count}} tệp.",
              { count },
            )
          : ui(
              "Tệp sẽ rời khỏi mọi cuộc hội thoại và nằm trong thùng rác {{days}} ngày, khôi phục được trong thời gian đó. Đã chọn {{count}} tệp.",
              { days: trashDays ?? 30, count },
            )
      }
      confirmLabel={ui("Xoá")}
      pendingLabel={ui("Đang xoá…")}
      confirmTone="danger"
      onConfirm={onConfirm}
    />
  );
}

/** Ending a trashed selection for good. */
export function PurgeFilesDialog({
  files,
  onOpenChange,
  onConfirm,
}: {
  files: readonly LibraryFile[] | undefined;
  onOpenChange: (open: boolean) => void;
  onConfirm: () => Promise<void>;
}) {
  const ui = useAppTranslation();
  return (
    <ConfirmDialog
      open={files !== undefined}
      onOpenChange={onOpenChange}
      title={ui("Xoá vĩnh viễn?")}
      description={ui("Tệp sẽ bị xoá vĩnh viễn và không thể khôi phục. Đã chọn {{count}} tệp.", {
        count: files?.length ?? 0,
      })}
      confirmLabel={ui("Xoá vĩnh viễn")}
      pendingLabel={ui("Đang xoá…")}
      confirmTone="danger"
      onConfirm={onConfirm}
    />
  );
}
