import { useAppTranslation } from "@/i18n/use-app-translation";
import { revalidateLogic } from "@tanstack/react-form";
import { Copy, Link2 } from "lucide-react";
import { useState, type RefObject } from "react";
import { z } from "zod";
import { setServerErrors, useAppForm } from "@/components/form/app-form";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Field, FieldDescription, FieldError, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { formatInvitationDate } from "@/features/invitations/invitation-presentation";
import type { IssuedInvitation } from "@/lib/hey-api/types.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { invitationError } from "./user-action-errors";

type InvitationDialogProps = {
  open: boolean;
  pending: boolean;
  issuedInvitation: IssuedInvitation | null;
  returnFocusRef: RefObject<HTMLElement | null>;
  fallbackFocusRef: RefObject<HTMLElement | null>;
  onOpenChange: (open: boolean) => void;
  onCreate: (email: string) => Promise<void>;
};

/** Invites a member by email, then shows the issued (or rotated) one-time recovery link. */
export function InvitationDialog({
  open,
  pending,
  issuedInvitation,
  returnFocusRef,
  fallbackFocusRef,
  onOpenChange,
  onCreate,
}: InvitationDialogProps) {
  function changeOpen(nextOpen: boolean) {
    if (!nextOpen && pending) return;
    onOpenChange(nextOpen);
  }

  return (
    <Dialog open={open} onOpenChange={changeOpen}>
      <DialogContent
        onCloseAutoFocus={(event) => {
          const target = returnFocusRef.current?.isConnected
            ? returnFocusRef.current
            : fallbackFocusRef.current;
          if (target?.isConnected) {
            event.preventDefault();
            target.focus();
          }
          returnFocusRef.current = null;
        }}
        onEscapeKeyDown={(event) => {
          if (pending) event.preventDefault();
        }}
      >
        {issuedInvitation ? (
          <IssuedInvitationView invitation={issuedInvitation} onDone={() => changeOpen(false)} />
        ) : (
          <InvitationForm
            pending={pending}
            onCreate={onCreate}
            onCancel={() => changeOpen(false)}
          />
        )}
      </DialogContent>
    </Dialog>
  );
}

function InvitationForm({
  pending,
  onCreate,
  onCancel,
}: {
  pending: boolean;
  onCreate: (email: string) => Promise<void>;
  onCancel: () => void;
}) {
  const ui = useAppTranslation();
  const problemMessage = useProblemMessage();
  const form = useAppForm({
    defaultValues: { email: "" },
    validationLogic: revalidateLogic(),
    validators: {
      onDynamic: z.object({
        email: z
          .string()
          .trim()
          .min(1, ui("Enter an email address."))
          .pipe(z.email(problemMessage({ key: "email" })).max(254)),
      }),
    },
    onSubmit: async ({ value, formApi }) => {
      try {
        await onCreate(value.email.trim());
      } catch (cause) {
        const message = problemMessage(invitationError(cause));
        setServerErrors(
          formApi,
          presentProblem(cause, "mutation").fields.email
            ? { fields: { email: { message } } }
            : { form: message, fields: {} },
        );
      }
    },
  });

  return (
    <form
      noValidate
      aria-busy={pending}
      className="flex flex-col gap-6"
      onSubmit={(event) => {
        event.preventDefault();
        void form.handleSubmit();
      }}
    >
      <DialogHeader>
        <DialogTitle>{ui("Invite a member")}</DialogTitle>
        <DialogDescription>
          {ui("Invite someone to join this Tenant as a member.")}
        </DialogDescription>
      </DialogHeader>
      <form.AppField name="email">
        {(field) => (
          <field.TextField
            label={ui("Email address")}
            type="email"
            autoComplete="email"
            maxLength={254}
            placeholder={ui("name@company.com")}
            size="lg"
            disabled={pending}
          />
        )}
      </form.AppField>
      <form.AppForm>
        <form.FormError />
        <DialogFooter>
          <Button type="button" prominence="secondary" onClick={onCancel} disabled={pending}>
            {ui("Cancel")}
          </Button>
          <form.SubmitButton disabled={pending}>
            {pending ? ui("Sending invitation…") : ui("Send invitation")}
          </form.SubmitButton>
        </DialogFooter>
      </form.AppForm>
    </form>
  );
}

function IssuedInvitationView({
  invitation,
  onDone,
}: {
  invitation: IssuedInvitation;
  onDone: () => void;
}) {
  const ui = useAppTranslation();
  const problemMessage = useProblemMessage();
  const [copy, setCopy] = useState<"copied" | "failed">();
  const link = new URL(invitation.invitationUrl, window.location.origin).toString();

  async function copyLink() {
    try {
      await navigator.clipboard.writeText(link);
      setCopy("copied");
    } catch {
      setCopy("failed");
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <DialogHeader>
        <DialogTitle>{ui(issuedTitle(invitation))}</DialogTitle>
        <DialogDescription>{ui(issuedDescription(invitation))}</DialogDescription>
      </DialogHeader>
      <Card size="sm">
        <CardHeader>
          <CardTitle>{ui("One-time recovery link")}</CardTitle>
          <CardDescription>
            {ui("Copy this link now. MemoryOS cannot show it again after this dialog closes.")}
          </CardDescription>
        </CardHeader>
        <CardContent>
          <Field data-invalid={copy === "failed" || undefined}>
            <FieldLabel htmlFor="issued-invitation-link">{ui("Secure invitation link")}</FieldLabel>
            <div className="flex flex-col gap-2 sm:flex-row">
              <Input
                id="issued-invitation-link"
                readOnly
                value={link}
                className="min-w-0 flex-1"
                onFocus={(event) => event.currentTarget.select()}
              />
              <Button type="button" onClick={() => void copyLink()}>
                {copy === "copied" ? (
                  <Link2 data-icon="inline-start" />
                ) : (
                  <Copy data-icon="inline-start" />
                )}
                {copy === "copied" ? ui("Copied") : ui("Copy")}
              </Button>
            </div>
            {copy === "failed" ? (
              <FieldError>{problemMessage({ key: "copyInvitation" })}</FieldError>
            ) : copy === "copied" ? (
              <FieldDescription role="status">{ui("Recovery link copied.")}</FieldDescription>
            ) : null}
          </Field>
        </CardContent>
      </Card>
      <p className="font-secondary-body text-content-muted">
        {ui("Expires")} {formatInvitationDate(invitation.invitation.expiresAt)}.
      </p>
      <DialogFooter>
        <Button type="button" onClick={onDone}>
          {ui("Done")}
        </Button>
      </DialogFooter>
    </div>
  );
}

function issuedTitle(invitation: IssuedInvitation) {
  if (invitation.delivery === "ACTIVATION_EMAIL_SENT") return "Activation email sent";
  if (invitation.delivery === "EXISTING_ACCOUNT") return "Invitation ready";
  return "Recovery link rotated";
}

function issuedDescription(invitation: IssuedInvitation) {
  if (invitation.delivery === "ACTIVATION_EMAIL_SENT") {
    return "They can verify the invited email and choose a password. Keep the recovery link in case they need help.";
  }
  if (invitation.delivery === "EXISTING_ACCOUNT") {
    return "They already have a verified account and can sign in to accept. Share the recovery link only if needed.";
  }
  return "The previous recovery link no longer works. Share this replacement only if needed.";
}
