import { MoreHorizontal, RefreshCw, Trash2 } from "lucide-react";
import { useRef, useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { IconButton } from "@/components/ui/icon-button";
import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { ErrorMessage } from "@/lib/problem-presentation";

/**
 * Actions on one Source file. Reindexing and removing conflict with each other, so either one
 * in flight blocks the whole menu.
 */
export function SourceFileActions({
  filename,
  pending,
  disabled,
  onReindex,
  onRemove,
  removeError,
}: {
  filename: string | null;
  pending: boolean;
  disabled: boolean;
  onReindex?: () => void;
  onRemove?: () => Promise<void>;
  removeError: (cause: unknown) => AppCopy | ErrorMessage;
}) {
  const ui = useAppTranslation();
  const trigger = useRef<HTMLButtonElement>(null);
  const openingDialog = useRef(false);
  const [confirmingRemoval, setConfirmingRemoval] = useState(false);
  if (!onReindex && !onRemove) return null;

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <IconButton
            ref={trigger}
            size="sm"
            pending={pending}
            disabled={disabled}
            aria-label={ui("Actions for {{v1}}", { v1: filename ?? ui("uploaded file") })}
          >
            <MoreHorizontal aria-hidden="true" />
          </IconButton>
        </DropdownMenuTrigger>
        <DropdownMenuContent
          align="end"
          className="w-auto min-w-40"
          onCloseAutoFocus={(event) => {
            if (!openingDialog.current) return;
            openingDialog.current = false;
            event.preventDefault();
          }}
        >
          {onReindex ? (
            <DropdownMenuGroup>
              <DropdownMenuItem onSelect={onReindex}>
                <RefreshCw aria-hidden="true" />
                {ui("Reindex")}
              </DropdownMenuItem>
            </DropdownMenuGroup>
          ) : null}
          {onReindex && onRemove ? <DropdownMenuSeparator /> : null}
          {onRemove ? (
            <DropdownMenuGroup>
              <DropdownMenuItem
                variant="destructive"
                onSelect={() => {
                  openingDialog.current = true;
                  setConfirmingRemoval(true);
                }}
              >
                <Trash2 aria-hidden="true" />
                {ui("Remove")}
              </DropdownMenuItem>
            </DropdownMenuGroup>
          ) : null}
        </DropdownMenuContent>
      </DropdownMenu>
      {onRemove ? (
        <ConfirmDialog
          open={confirmingRemoval}
          onOpenChange={setConfirmingRemoval}
          restoreFocusRef={trigger}
          title={ui("Remove {{v1}}?", { v1: filename ?? ui("uploaded file") })}
          description={ui(
            "Removing “{{v1}}” makes its indexed document unavailable. Cleanup continues asynchronously.",
            { v1: filename ?? ui("this file") },
          )}
          confirmLabel={ui("Remove file")}
          pendingLabel={ui("Removing file")}
          onConfirm={onRemove}
          errorMessage={removeError}
        />
      ) : null}
    </>
  );
}
