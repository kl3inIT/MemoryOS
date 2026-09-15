import { MoreHorizontal, Pencil, ShieldCheck, Trash2 } from "lucide-react";
import { type RefObject, useRef } from "react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { IconButton } from "@/components/ui/icon-button";
import { useAppTranslation } from "@/i18n/use-app-translation";

/**
 * Source-level actions. Each one opens a dialog, which takes focus over from the menu and
 * returns it to the trigger when it closes.
 */
export function SourceActionsMenu({
  triggerRef,
  disabled,
  onRename,
  onChangeAccess,
  onDelete,
}: {
  triggerRef: RefObject<HTMLButtonElement | null>;
  disabled: boolean;
  onRename?: () => void;
  onChangeAccess?: () => void;
  onDelete?: () => void;
}) {
  const ui = useAppTranslation();
  const openingDialog = useRef(false);
  if (!onRename && !onChangeAccess && !onDelete) return null;

  const openDialog = (open: () => void) => () => {
    openingDialog.current = true;
    open();
  };

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <IconButton
          ref={triggerRef}
          prominence="secondary"
          disabled={disabled}
          aria-label={ui("Source actions")}
        >
          <MoreHorizontal aria-hidden="true" />
        </IconButton>
      </DropdownMenuTrigger>
      <DropdownMenuContent
        align="end"
        className="w-auto min-w-48"
        onCloseAutoFocus={(event) => {
          if (!openingDialog.current) return;
          openingDialog.current = false;
          event.preventDefault();
        }}
      >
        {onRename ? (
          <DropdownMenuItem onSelect={openDialog(onRename)}>
            <Pencil aria-hidden="true" />
            {ui("Rename source")}
          </DropdownMenuItem>
        ) : null}
        {onChangeAccess ? (
          <DropdownMenuItem onSelect={openDialog(onChangeAccess)}>
            <ShieldCheck aria-hidden="true" />
            {ui("Change visibility")}
          </DropdownMenuItem>
        ) : null}
        {onDelete && (onRename || onChangeAccess) ? <DropdownMenuSeparator /> : null}
        {onDelete ? (
          <DropdownMenuItem variant="destructive" onSelect={openDialog(onDelete)}>
            <Trash2 aria-hidden="true" />
            {ui("Delete source")}
          </DropdownMenuItem>
        ) : null}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
