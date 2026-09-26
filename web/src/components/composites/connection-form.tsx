import type { FormEvent, ReactNode } from "react";
import { CheckCircle2, Settings2 } from "lucide-react";
import { ProviderCard } from "@/components/provider-logos/provider-card";
import { Alert, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { FieldGroup } from "@/components/ui/field";
import { StatusBadge } from "@/components/ui/status-badge";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { ErrorMessage } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { cn } from "@/lib/utils";

/** The success badge of a provider that is connected or in use. */
export function ConnectionStatusBadge({ children }: { children: ReactNode }) {
  return (
    <StatusBadge tone="success">
      <CheckCircle2 aria-hidden="true" />
      {children}
    </StatusBadge>
  );
}

/**
 * One connectable provider on an administration page (Web search, image generation): its card with the
 * in-use or connected state, the action that makes a configured provider the default, and the action that
 * opens its connection dialog, which the page renders as `children`.
 */
export function ConnectionCard({
  logo,
  name,
  description,
  active,
  configured,
  disabled,
  onSelect,
  onConfigure,
  children,
}: {
  logo: ReactNode;
  /** The provider's name; it also names the card's region. */
  name: string;
  description: ReactNode;
  /** The provider in use for its capability. */
  active: boolean;
  /** A stored connection the provider can be used with. */
  configured: boolean;
  disabled: boolean;
  onSelect: () => void;
  onConfigure: () => void;
  children: ReactNode;
}) {
  const ui = useAppTranslation();
  return (
    <ProviderCard
      as="section"
      aria-label={name}
      logo={logo}
      name={name}
      description={description}
      selected={active}
      actions={
        <>
          {active ? (
            <ConnectionStatusBadge>{ui("Đang dùng")}</ConnectionStatusBadge>
          ) : configured ? (
            <ConnectionStatusBadge>{ui("Đã kết nối")}</ConnectionStatusBadge>
          ) : null}
          {configured && !active && (
            <Button size="sm" prominence="secondary" disabled={disabled} onClick={onSelect}>
              {ui("Đặt làm mặc định")}
            </Button>
          )}
          {configured ? (
            <Button size="sm" prominence="tertiary" disabled={disabled} onClick={onConfigure}>
              <Settings2 data-icon="inline-start" aria-hidden="true" />
              {ui("Cấu hình")}
            </Button>
          ) : (
            <Button size="sm" prominence="secondary" disabled={disabled} onClick={onConfigure}>
              {ui("Kết nối")}
            </Button>
          )}
        </>
      }
    >
      {children}
    </ProviderCard>
  );
}

/** The dialog around a connection; it cannot be closed while a request of it runs. */
export function ConnectionDialog({
  open,
  onOpenChange,
  busy,
  wide = false,
  children,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  busy: boolean;
  wide?: boolean;
  children: ReactNode;
}) {
  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!busy) onOpenChange(next);
      }}
    >
      <DialogContent showCloseButton={false} className={cn(wide && "sm:max-w-xl")}>
        {children}
      </DialogContent>
    </Dialog>
  );
}

/**
 * The connection form inside a {@link ConnectionDialog}: the provider's fields, the result of a connection
 * test, the failure of the last request, and Test, Close and Save. `start` holds an action placed apart from
 * the others, such as disconnecting.
 */
export function ConnectionForm({
  title,
  description,
  children,
  onSubmit,
  saving,
  saveDisabled = false,
  submitLabel,
  pendingLabel,
  onTest,
  testDisabled = false,
  tested,
  error,
  busy,
  onClose,
  notice,
  start,
}: {
  title: string;
  description: ReactNode;
  /** The provider's fields. */
  children: ReactNode;
  onSubmit: () => void;
  saving: boolean;
  saveDisabled?: boolean;
  /** The submit action's name; Save by default. */
  submitLabel?: string;
  /** What the submit action says while it runs, such as a key being checked. */
  pendingLabel?: string;
  /** Tests the connection; without it no test is offered. */
  onTest?: () => void;
  testDisabled?: boolean;
  tested: boolean;
  error?: ErrorMessage;
  /** A request of the form runs: the fields and actions wait. */
  busy: boolean;
  onClose: () => void;
  /** A line under the result, such as what a test costs. */
  notice?: ReactNode;
  start?: ReactNode;
}) {
  const ui = useAppTranslation();
  const problemMessage = useProblemMessage();
  return (
    <form
      className="flex flex-col gap-5"
      onSubmit={(event: FormEvent) => {
        event.preventDefault();
        onSubmit();
      }}
    >
      <DialogHeader>
        <DialogTitle>{title}</DialogTitle>
        <DialogDescription>{description}</DialogDescription>
      </DialogHeader>
      <fieldset disabled={busy} className="min-w-0">
        <FieldGroup>{children}</FieldGroup>
      </fieldset>
      {tested && (
        <Alert variant="success" role="status">
          <CheckCircle2 aria-hidden="true" />
          <AlertTitle>{ui("Kiểm tra kết nối thành công")}</AlertTitle>
        </Alert>
      )}
      {error && (
        <Alert variant="destructive">
          <AlertTitle>{problemMessage(error)}</AlertTitle>
        </Alert>
      )}
      {notice}
      <DialogFooter>
        {start && <div className="sm:mr-auto">{start}</div>}
        {onTest && (
          <Button
            type="button"
            prominence="internal"
            disabled={busy || testDisabled}
            onClick={onTest}
          >
            {ui("Kiểm tra kết nối")}
          </Button>
        )}
        <Button type="button" prominence="secondary" disabled={busy} onClick={onClose}>
          {ui("Đóng")}
        </Button>
        <Button type="submit" pending={saving} disabled={busy || saveDisabled}>
          {saving && pendingLabel ? pendingLabel : (submitLabel ?? ui("Lưu"))}
        </Button>
      </DialogFooter>
    </form>
  );
}
