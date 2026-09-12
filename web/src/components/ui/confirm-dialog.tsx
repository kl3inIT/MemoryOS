import * as React from "react";
import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useTranslation } from "react-i18next";
import { useProblemMessage } from "@/lib/use-problem-message";
import type { ErrorMessage } from "@/lib/problem-presentation";
import { AlertDialog } from "radix-ui";

import { Button } from "@/components/ui/button";
import type { ActionTone } from "@/components/ui/action-styles";

type ConfirmDialogProps = {
  trigger?: React.ReactElement;
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
  restoreFocusRef?: React.RefObject<HTMLElement | null>;
  successFocusRef?: React.RefObject<HTMLElement | null>;
  fallbackFocusRef?: React.RefObject<HTMLElement | null>;
  title: React.ReactNode;
  description: React.ReactNode;
  confirmLabel: string;
  pendingLabel: string;
  confirmTone?: ActionTone;
  onConfirm: () => Promise<void>;
  errorMessage?: (error: unknown) => AppCopy | ErrorMessage;
};

function ConfirmDialog({
  trigger,
  open: controlledOpen,
  onOpenChange,
  restoreFocusRef,
  successFocusRef,
  fallbackFocusRef,
  title,
  description,
  confirmLabel,
  pendingLabel,
  confirmTone = "danger",
  onConfirm,
  errorMessage,
}: ConfirmDialogProps) {
  const { t } = useTranslation("common");
  const ui = useAppTranslation();
  const translateError = useProblemMessage();
  const [internalOpen, setInternalOpen] = React.useState(false);
  const [pending, setPending] = React.useState(false);
  const [error, setError] = React.useState<{ message: AppCopy | ErrorMessage } | null>(null);
  const pendingRef = React.useRef(false);
  const cancelRef = React.useRef<HTMLButtonElement>(null);
  const confirmedRef = React.useRef(false);
  const confirmedFocusTargetRef = React.useRef<HTMLElement | null>(null);
  const controlled = controlledOpen !== undefined;
  const open = controlledOpen ?? internalOpen;

  function publishOpen(nextOpen: boolean) {
    if (!controlled) setInternalOpen(nextOpen);
    onOpenChange?.(nextOpen);
  }

  function changeOpen(nextOpen: boolean) {
    if (!nextOpen && pendingRef.current) return;
    if (nextOpen) setError(null);
    publishOpen(nextOpen);
  }

  async function confirm(event: React.MouseEvent<HTMLButtonElement>) {
    event.preventDefault();
    if (pendingRef.current) return;

    pendingRef.current = true;
    setPending(true);
    setError(null);
    try {
      await onConfirm();
      confirmedRef.current = true;
      confirmedFocusTargetRef.current = successFocusRef?.current ?? null;
      publishOpen(false);
    } catch (cause) {
      setError({ message: errorMessage?.(cause) ?? { key: "actionFailed" } });
    } finally {
      pendingRef.current = false;
      setPending(false);
    }
  }

  return (
    <AlertDialog.Root open={open} onOpenChange={changeOpen}>
      {trigger ? <AlertDialog.Trigger asChild>{trigger}</AlertDialog.Trigger> : null}
      <AlertDialog.Portal>
        <AlertDialog.Overlay className="fixed inset-0 z-40 bg-surface-scrim backdrop-blur-[2px] data-[state=closed]:animate-out data-[state=open]:animate-in data-[state=closed]:fade-out data-[state=open]:fade-in motion-reduce:animate-none" />
        <AlertDialog.Content
          className="fixed top-1/2 left-1/2 z-50 max-h-[calc(100dvh-2rem)] w-[min(30rem,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 overflow-y-auto rounded-2xl border border-border-subtle bg-surface-overlay p-6 shadow-md outline-none"
          onOpenAutoFocus={(event) => {
            confirmedRef.current = false;
            confirmedFocusTargetRef.current = null;
            setError(null);
            event.preventDefault();
            cancelRef.current?.focus();
          }}
          onCloseAutoFocus={(event) => {
            const preferredTarget = confirmedRef.current
              ? confirmedFocusTargetRef.current
              : restoreFocusRef?.current;
            const focusTarget = preferredTarget?.isConnected
              ? preferredTarget
              : restoreFocusRef?.current?.isConnected
                ? restoreFocusRef.current
                : fallbackFocusRef?.current;
            if (!focusTarget?.isConnected) return;
            event.preventDefault();
            focusTarget.focus();
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
              {typeof error.message === "object" && "key" in error.message
                ? translateError(error.message)
                : ui(error.message)}
            </p>
          ) : null}

          <div className="mt-6 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
            <AlertDialog.Cancel asChild>
              <Button ref={cancelRef} prominence="secondary" disabled={pending}>
                {t("cancel")}
              </Button>
            </AlertDialog.Cancel>
            <AlertDialog.Action asChild>
              <Button tone={confirmTone} pending={pending} onClick={confirm}>
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
