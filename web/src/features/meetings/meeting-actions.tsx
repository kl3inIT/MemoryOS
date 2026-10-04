import { useRef, useState } from "react";
import { MoreHorizontal, Pencil, Trash2 } from "lucide-react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { IconButton } from "@/components/ui/icon-button";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { presentProblem } from "@/lib/problem-presentation";
import { MeetingDetailsDialog } from "./meeting-details-dialog";
import type { MeetingDetail } from "./meetings-api";

/**
 * What the owner does to the meeting itself, less often than sharing it: renaming it and deleting it. Both open
 * from one menu in the header, so neither waits below a transcript as long as the meeting was.
 */
export function MeetingActions({
  meeting,
  deletable,
  onDelete,
}: {
  meeting: MeetingDetail;
  /** Not while this page is recording the meeting: it is ended first. */
  deletable: boolean;
  onDelete: () => Promise<unknown>;
}) {
  const ui = useAppTranslation();
  const trigger = useRef<HTMLButtonElement>(null);
  const [dialog, setDialog] = useState<"details" | "delete">();
  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <IconButton
            ref={trigger}
            aria-label={ui("Thao tác khác cho {{v1}}", { v1: meeting.title })}
          >
            <MoreHorizontal />
          </IconButton>
        </DropdownMenuTrigger>
        <DropdownMenuContent
          align="end"
          onCloseAutoFocus={(event) => {
            // The dialog an item opened takes the focus; the menu must not take it back.
            if (dialog) event.preventDefault();
          }}
        >
          <DropdownMenuGroup>
            <DropdownMenuItem onSelect={() => setDialog("details")}>
              <Pencil aria-hidden="true" />
              {ui("Sửa thông tin")}
            </DropdownMenuItem>
            {deletable && (
              <DropdownMenuItem variant="destructive" onSelect={() => setDialog("delete")}>
                <Trash2 aria-hidden="true" />
                {ui("Xóa cuộc họp")}
              </DropdownMenuItem>
            )}
          </DropdownMenuGroup>
        </DropdownMenuContent>
      </DropdownMenu>
      <MeetingDetailsDialog
        meeting={meeting}
        open={dialog === "details"}
        onOpenChange={(open) => setDialog(open ? "details" : undefined)}
      />
      <ConfirmDialog
        open={dialog === "delete"}
        onOpenChange={(open) => setDialog(open ? "delete" : undefined)}
        restoreFocusRef={trigger}
        title={ui("Xóa {{v1}}?", { v1: meeting.title })}
        description={ui(
          "Transcript, tên người nói và ghi chú của cuộc họp này sẽ bị xóa vĩnh viễn.",
        )}
        confirmLabel={ui("Xóa")}
        pendingLabel={ui("Đang xóa…")}
        confirmTone="danger"
        errorMessage={(error) => presentProblem(error, "mutation").message}
        onConfirm={async () => {
          await onDelete();
        }}
      />
    </>
  );
}
