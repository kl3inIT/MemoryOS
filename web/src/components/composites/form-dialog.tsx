import { Dialog } from "radix-ui";
import { useRef, useState, type ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { actionProblem } from "@/lib/action-errors";
import { useTranslation } from "react-i18next";
import type { ErrorMessage } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { ErrorState } from "@/components/assistant-ui/elements/error-state";
import { cn } from "@/lib/utils";

/** A dialog around one form: it submits once, keeps open with the failure shown, and closes on success. */
export function FormDialog({
  title,
  description,
  trigger,
  children,
  onSubmit,
  submitLabel,
  open,
  onOpenChange,
  closeOnSuccess = true,
  submitDisabled = false,
  wide = false,
  fill = false,
}: {
  title: string;
  description: string;
  trigger?: ReactNode;
  children: ReactNode;
  onSubmit?: () => Promise<void>;
  submitLabel?: string;
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
  closeOnSuccess?: boolean;
  submitDisabled?: boolean;
  /** Wider layout for multi-section editors. */
  wide?: boolean;
  /**
   * The body owns the scrolling: the dialog keeps to the viewport and its content stretches inside it, so a
   * long list scrolls on its own instead of putting a second scrollbar on the dialog.
   */
  fill?: boolean;
}) {
  const { t } = useTranslation("common");
  const { t: statusText } = useTranslation("chatStatus");
  const message = useProblemMessage();
  const [internalOpen, setOpen] = useState(false);
  const [pending, setPending] = useState(false);
  const busy = useRef(false);
  const [error, setError] = useState<ErrorMessage>();
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
        <Dialog.Overlay className="fixed inset-0 z-40 bg-surface-scrim backdrop-blur-[2px]" />
        <Dialog.Content
          className={cn(
            "fixed top-1/2 left-1/2 z-50 max-h-[calc(100dvh-2rem)] w-[min(40rem,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 rounded-2xl border border-border-default bg-surface-overlay p-6 shadow-md outline-none data-[wide=true]:w-[min(56rem,calc(100vw-2rem))]",
            // A definite height is what lets the body own the scrolling: percentage-free flex children cannot
            // resolve against `max-height` alone, and the footer would be clipped instead of staying in view.
            fill
              ? "flex h-[min(44rem,calc(100dvh-2rem))] flex-col overflow-hidden"
              : "overflow-y-auto",
          )}
          data-wide={wide}
          onEscapeKeyDown={(event) => {
            if (busy.current) event.preventDefault();
          }}
          onInteractOutside={(event) => {
            if (busy.current) event.preventDefault();
          }}
        >
          <form
            className={cn(fill && "flex min-h-0 flex-1 flex-col")}
            onSubmit={(event) => {
              event.preventDefault();
              if (!onSubmit || busy.current || submitDisabled) return;
              busy.current = true;
              setPending(true);
              setError(undefined);
              void onSubmit()
                .then(() => {
                  busy.current = false;
                  if (closeOnSuccess) change(false);
                })
                .catch((cause: unknown) => setError(actionProblem(cause)))
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
            <fieldset
              disabled={pending}
              className={cn("mt-5 space-y-4", fill && "flex min-h-0 flex-1 flex-col")}
            >
              {children}
            </fieldset>
            {error && (
              <ErrorState
                className="mt-4"
                title={statusText("actionFailed")}
                detail={message(error)}
              />
            )}
            <div className="mt-6 flex justify-end gap-2">
              <Button
                type="button"
                prominence="secondary"
                disabled={pending}
                onClick={() => change(false)}
              >
                {t("close")}
              </Button>
              {onSubmit && (
                <Button type="submit" pending={pending} disabled={submitDisabled}>
                  {submitLabel ?? t("save")}
                </Button>
              )}
            </div>
          </form>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
