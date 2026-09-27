import * as React from "react";
import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useTranslation } from "react-i18next";
import { useProblemMessage } from "@/lib/use-problem-message";
import type { ErrorMessage } from "@/lib/problem-presentation";
import type { ActionTone } from "@/components/ui/action-styles";
import { Alert, AlertDescription } from "@/components/ui/alert";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";

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
    <AlertDialog open={open} onOpenChange={changeOpen}>
      {trigger ? <AlertDialogTrigger asChild>{trigger}</AlertDialogTrigger> : null}
      <AlertDialogContent
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
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription>{description}</AlertDialogDescription>
        </AlertDialogHeader>

        {error ? (
          <Alert variant="destructive">
            <AlertDescription>
              {typeof error.message === "object" && "key" in error.message
                ? translateError(error.message)
                : ui(error.message)}
            </AlertDescription>
          </Alert>
        ) : null}

        <AlertDialogFooter>
          <AlertDialogCancel ref={cancelRef} disabled={pending}>
            {t("cancel")}
          </AlertDialogCancel>
          <AlertDialogAction tone={confirmTone} pending={pending} onClick={confirm}>
            {pending ? pendingLabel : confirmLabel}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

export { ConfirmDialog, type ConfirmDialogProps };
