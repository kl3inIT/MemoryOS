import { Dialog } from "radix-ui";
import { useRef, useState, type ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { chatActionError } from "./chat-action-utils";

export function ChatDialog({
  title,
  description,
  trigger,
  children,
  onSubmit,
  submitLabel = "Lưu",
  open,
  onOpenChange,
}: {
  title: string;
  description: string;
  trigger?: ReactNode;
  children: ReactNode;
  onSubmit?: () => Promise<void>;
  submitLabel?: string;
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
}) {
  const [internalOpen, setOpen] = useState(false);
  const [pending, setPending] = useState(false);
  const busy = useRef(false);
  const [error, setError] = useState<string>();
  function change(next: boolean) {
    if (busy.current) return;
    setError(undefined);
    setOpen(next);
    onOpenChange?.(next);
  }
  return (
    <Dialog.Root open={open ?? internalOpen} onOpenChange={change}>
      {trigger && <Dialog.Trigger asChild>{trigger}</Dialog.Trigger>}
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-content-primary/20 backdrop-blur-[2px]" />
        <Dialog.Content
          className="fixed top-1/2 left-1/2 z-50 max-h-[calc(100dvh-2rem)] w-[min(40rem,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 overflow-y-auto rounded-2xl border border-border-default bg-surface-overlay p-6 shadow-md outline-none"
          onEscapeKeyDown={(event) => {
            if (busy.current) event.preventDefault();
          }}
          onInteractOutside={(event) => {
            if (busy.current) event.preventDefault();
          }}
        >
          <form
            onSubmit={(event) => {
              event.preventDefault();
              if (!onSubmit || busy.current) return;
              busy.current = true;
              setPending(true);
              setError(undefined);
              void onSubmit()
                .then(() => {
                  busy.current = false;
                  change(false);
                })
                .catch((cause: unknown) => setError(chatActionError(cause)))
                .finally(() => {
                  busy.current = false;
                  setPending(false);
                });
            }}
          >
            <Dialog.Title className="text-xl font-semibold">{title}</Dialog.Title>
            <Dialog.Description className="mt-2 text-sm text-content-secondary">
              {description}
            </Dialog.Description>
            <fieldset disabled={pending} className="mt-5 space-y-4">
              {children}
            </fieldset>
            {error && (
              <p role="alert" className="mt-4 text-sm">
                {error}
              </p>
            )}
            <div className="mt-6 flex justify-end gap-2">
              <Button
                type="button"
                prominence="secondary"
                disabled={pending}
                onClick={() => change(false)}
              >
                Đóng
              </Button>
              {onSubmit && (
                <Button type="submit" pending={pending}>
                  {submitLabel}
                </Button>
              )}
            </div>
          </form>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
