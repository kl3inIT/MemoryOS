import { X } from "lucide-react";
import { useRef, type ReactNode } from "react";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { IconButton } from "@/components/ui/icon-button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

export function CatalogDialog({
  title,
  description,
  onClose,
  children,
}: {
  title: string;
  description?: ReactNode;
  onClose: () => void;
  children: ReactNode;
}) {
  const ui = useAppTranslation();
  const content = useRef<HTMLDivElement>(null);
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <DialogContent
        ref={content}
        showCloseButton={false}
        className="sm:max-w-2xl"
        onOpenAutoFocus={(event) => {
          const field = content.current?.querySelector<HTMLInputElement>("input:not([disabled])");
          if (field) {
            event.preventDefault();
            field.focus();
          }
        }}
      >
        {/* The editor's own close, named for what it closes: an editor body often has its own Close. */}
        <DialogClose asChild>
          <IconButton size="sm" aria-label={ui("Close editor")} className="absolute top-4 right-4">
            <X />
          </IconButton>
        </DialogClose>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          {description === undefined ? (
            <DialogDescription className="sr-only">{title}</DialogDescription>
          ) : (
            <DialogDescription>{description}</DialogDescription>
          )}
        </DialogHeader>
        <div>{children}</div>
      </DialogContent>
    </Dialog>
  );
}
