import { useRef, useState, type ReactNode } from "react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
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
    <Dialog open={open ?? internalOpen} onOpenChange={change}>
      {trigger && <DialogTrigger asChild>{trigger}</DialogTrigger>}
      <DialogContent
        showCloseButton={false}
        className={cn(
          wide ? "sm:max-w-4xl" : "sm:max-w-2xl",
          // A definite height is what lets the body own the scrolling: percentage-free flex children cannot
          // resolve against `max-height` alone, and the footer would be clipped instead of staying in view.
          fill && "flex h-[min(44rem,calc(100dvh-2rem))] flex-col overflow-hidden",
        )}
        onEscapeKeyDown={(event) => {
          if (busy.current) event.preventDefault();
        }}
        onInteractOutside={(event) => {
          if (busy.current) event.preventDefault();
        }}
      >
        <form
          className={cn("flex flex-col gap-5", fill && "min-h-0 flex-1")}
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
          <DialogHeader>
            <DialogTitle>{title}</DialogTitle>
            <DialogDescription>{description}</DialogDescription>
          </DialogHeader>
          <fieldset
            disabled={pending}
            className={cn("flex min-w-0 flex-col gap-4", fill && "min-h-0 flex-1")}
          >
            {children}
          </fieldset>
          {error && <ErrorState title={statusText("actionFailed")} detail={message(error)} />}
          <DialogFooter>
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
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
