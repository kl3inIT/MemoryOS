import * as React from "react";
import { AlertDialog } from "radix-ui";

import { Button } from "@/components/ui/button";

const fallbackErrorMessage = "The action could not be completed. Try again.";

type ConfirmDialogProps = {
  trigger: React.ReactElement;
  title: React.ReactNode;
  description: React.ReactNode;
  confirmLabel: string;
  pendingLabel: string;
  onConfirm: () => Promise<void>;
  errorMessage?: (error: unknown) => string;
};

function ConfirmDialog({
  trigger,
  title,
  description,
  confirmLabel,
  pendingLabel,
  onConfirm,
  errorMessage,
}: ConfirmDialogProps) {
  const [open, setOpen] = React.useState(false);
  const [pending, setPending] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  const pendingRef = React.useRef(false);
  const cancelRef = React.useRef<HTMLButtonElement>(null);

  function changeOpen(nextOpen: boolean) {
    if (!nextOpen && pendingRef.current) return;
    if (nextOpen) setError(null);
    setOpen(nextOpen);
  }

  async function confirm(event: React.MouseEvent<HTMLButtonElement>) {
    event.preventDefault();
    if (pendingRef.current) return;

    pendingRef.current = true;
    setPending(true);
    setError(null);
    try {
      await onConfirm();
      setOpen(false);
    } catch (cause) {
      setError(errorMessage?.(cause) ?? fallbackErrorMessage);
    } finally {
      pendingRef.current = false;
      setPending(false);
    }
  }

  return (
    <AlertDialog.Root open={open} onOpenChange={changeOpen}>
      <AlertDialog.Trigger asChild>{trigger}</AlertDialog.Trigger>
      <AlertDialog.Portal>
        <AlertDialog.Overlay className="fixed inset-0 z-40 bg-content-primary/20 backdrop-blur-[2px] data-[state=closed]:animate-out data-[state=open]:animate-in data-[state=closed]:fade-out data-[state=open]:fade-in motion-reduce:animate-none" />
        <AlertDialog.Content
          className="fixed top-1/2 left-1/2 z-50 w-[min(30rem,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 rounded-2xl border border-border-default bg-surface-overlay p-5 shadow-md outline-none sm:p-6"
          onOpenAutoFocus={(event) => {
            event.preventDefault();
            cancelRef.current?.focus();
          }}
          onEscapeKeyDown={(event) => {
            if (pendingRef.current) event.preventDefault();
          }}
        >
          <AlertDialog.Title className="font-heading-h3 text-content-primary">
            {title}
          </AlertDialog.Title>
          <AlertDialog.Description className="mt-2 font-main-ui-body text-content-secondary">
            {description}
          </AlertDialog.Description>

          {error ? (
            <p
              role="alert"
              className="mt-4 rounded-lg bg-status-danger-surface px-4 py-3 font-secondary-body text-status-danger-content"
            >
              {error}
            </p>
          ) : null}

          <div className="mt-6 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
            <AlertDialog.Cancel asChild>
              <Button ref={cancelRef} prominence="secondary" disabled={pending}>
                Cancel
              </Button>
            </AlertDialog.Cancel>
            <AlertDialog.Action asChild>
              <Button tone="danger" pending={pending} onClick={confirm}>
                {pending ? pendingLabel : confirmLabel}
              </Button>
            </AlertDialog.Action>
          </div>
        </AlertDialog.Content>
      </AlertDialog.Portal>
    </AlertDialog.Root>
  );
}

export { ConfirmDialog, type ConfirmDialogProps };
