import { Dialog } from "radix-ui";
import { useRef, useState, type ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { chatActionProblem } from "./chat-action-utils";
import { useTranslation } from "react-i18next";
import type { ErrorMessage } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { ErrorState } from "@/components/assistant-ui/elements/error-state";

export function ChatDialog({
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
              if (!onSubmit || busy.current || submitDisabled) return;
              busy.current = true;
              setPending(true);
              setError(undefined);
              void onSubmit()
                .then(() => {
                  busy.current = false;
                  if (closeOnSuccess) change(false);
                })
                .catch((cause: unknown) => setError(chatActionProblem(cause)))
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
