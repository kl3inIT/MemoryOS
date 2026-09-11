import { Dialog } from "radix-ui";
import { X } from "lucide-react";
import { useRef, type ReactNode } from "react";
import { IconButton } from "@/components/ui/icon-button";

export function CatalogDialog({
  title,
  description,
  onClose,
  children,
}: {
  title: string;
  description: ReactNode;
  onClose: () => void;
  children: ReactNode;
}) {
  const content = useRef<HTMLDivElement>(null);
  return (
    <Dialog.Root
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-surface-scrim backdrop-blur-[2px]" />
        <Dialog.Content
          ref={content}
          onOpenAutoFocus={(event) => {
            const field = content.current?.querySelector<HTMLInputElement>("input:not([disabled])");
            if (field) {
              event.preventDefault();
              field.focus();
            }
          }}
          className="fixed top-1/2 left-1/2 z-50 max-h-[calc(100dvh-2rem)] w-[min(44rem,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 overflow-y-auto rounded-2xl border border-border-subtle bg-surface-overlay p-6 shadow-md outline-none"
        >
          <div className="flex items-start justify-between gap-4">
            <Dialog.Title className="font-heading-h3 text-content-primary">{title}</Dialog.Title>
            <Dialog.Close asChild>
              <IconButton prominence="tertiary" size="sm" aria-label="Close editor">
                <X />
              </IconButton>
            </Dialog.Close>
          </div>
          <Dialog.Description className="mt-2 font-main-ui-body text-content-muted">
            {description}
          </Dialog.Description>
          <div className="mt-6">{children}</div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
