import { Copy, Link2 } from "lucide-react";
import { useRef, useState, type RefObject } from "react";
import { Dialog } from "radix-ui";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { formatInvitationDate } from "@/features/invitations/invitation-presentation";
import type { IssuedInvitation } from "@/lib/hey-api/types.gen";
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

export function InvitationDialog({
  open,
  pending,
  issuedInvitation,
  returnFocusRef,
  fallbackFocusRef,
  onOpenChange,
  onCreate,
}: InvitationDialogProps) {
  const [inviteeEmail, setInviteeEmail] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const [linkCopied, setLinkCopied] = useState(false);
  const creationInFlight = useRef(false);

  function changeOpen(nextOpen: boolean) {
    if (!nextOpen && (creationInFlight.current || pending)) return;
    if (!nextOpen) {
      setInviteeEmail("");
      setFormError(null);
      setLinkCopied(false);
    }
    onOpenChange(nextOpen);
  }

  async function submitInvitation() {
    const email = inviteeEmail.trim();
    if (!email || creationInFlight.current) return;

    creationInFlight.current = true;
    setFormError(null);
    try {
      await onCreate(email);
      setInviteeEmail("");
    } catch (error) {
      setFormError(invitationError(error));
    } finally {
      creationInFlight.current = false;
    }
  }

  async function copyInvitationLink() {
    if (!issuedInvitation) return;
    try {
      await navigator.clipboard.writeText(
        new URL(issuedInvitation.invitationUrl, window.location.origin).toString(),
      );
      setLinkCopied(true);
      setFormError(null);
    } catch {
      setLinkCopied(false);
      setFormError("The invitation link could not be copied. Select and copy it from the field.");
    }
  }

  return (
    <Dialog.Root open={open} onOpenChange={changeOpen}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-content-primary/20 backdrop-blur-[2px] data-[state=closed]:animate-out data-[state=open]:animate-in data-[state=closed]:fade-out data-[state=open]:fade-in motion-reduce:animate-none" />
        <Dialog.Content
          className="fixed top-1/2 left-1/2 z-50 max-h-[calc(100dvh-2rem)] w-[min(31rem,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 overflow-y-auto rounded-2xl border border-border-default bg-surface-overlay p-5 shadow-md outline-none sm:p-6"
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
            if (creationInFlight.current) event.preventDefault();
          }}
        >
          <form
            aria-busy={pending}
            onSubmit={(event) => {
              event.preventDefault();
              void submitInvitation();
            }}
          >
            <Dialog.Title className="font-heading-h3 text-content-primary">
              {issuedInvitation ? issuedTitle(issuedInvitation) : "Invite a member"}
            </Dialog.Title>
            <Dialog.Description className="mt-2 max-w-md font-main-ui-body text-content-secondary">
              {issuedInvitation
                ? issuedDescription(issuedInvitation)
                : "Invite someone to join this Tenant as a member."}
            </Dialog.Description>

            {issuedInvitation ? (
              <div className="mt-6">
                <div className="rounded-xl border border-border-subtle bg-surface-subtle p-4">
                  <p className="font-secondary-action text-content-primary">
                    One-time recovery link
                  </p>
                  <p className="mt-1 font-secondary-body text-content-muted">
                    Copy this link now. MemoryOS cannot show it again after this dialog closes.
                  </p>
                  <label
                    htmlFor="issued-invitation-link"
                    className="mt-4 block font-secondary-action text-content-secondary"
                  >
                    Secure invitation link
                  </label>
                  <div className="mt-2 flex flex-col gap-2 sm:flex-row">
                    <Input
                      id="issued-invitation-link"
                      readOnly
                      value={new URL(
                        issuedInvitation.invitationUrl,
                        window.location.origin,
                      ).toString()}
                      className="min-w-0 flex-1 bg-surface-base font-mono text-xs"
                      onFocus={(event) => event.currentTarget.select()}
                    />
                    <Button type="button" onClick={() => void copyInvitationLink()}>
                      {linkCopied ? <Link2 /> : <Copy />}
                      {linkCopied ? "Copied" : "Copy"}
                    </Button>
                  </div>
                </div>
                <p className="mt-3 font-secondary-body text-content-muted">
                  Expires {formatInvitationDate(issuedInvitation.invitation.expiresAt)}.
                </p>
              </div>
            ) : (
              <label className="mt-6 grid gap-2 font-secondary-action text-content-secondary">
                Email address
                <Input
                  type="email"
                  autoComplete="email"
                  required
                  maxLength={254}
                  value={inviteeEmail}
                  onChange={(event) => setInviteeEmail(event.target.value)}
                  placeholder="name@company.com"
                  size="lg"
                />
              </label>
            )}

            {formError ? (
              <p
                role="alert"
                className="mt-4 rounded-lg bg-status-danger-surface px-4 py-3 font-secondary-body text-status-danger-content"
              >
                {formError}
              </p>
            ) : linkCopied ? (
              <p role="status" className="mt-4 font-secondary-body text-status-success-content">
                Recovery link copied.
              </p>
            ) : null}

            <div className="mt-7 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
              <Button
                type="button"
                prominence={issuedInvitation ? "primary" : "secondary"}
                onClick={() => changeOpen(false)}
                disabled={pending}
              >
                {issuedInvitation ? "Done" : "Cancel"}
              </Button>
              {!issuedInvitation ? (
                <Button type="submit" pending={pending} disabled={!inviteeEmail.trim() || pending}>
                  {pending ? "Sending invitation…" : "Send invitation"}
                </Button>
              ) : null}
            </div>
          </form>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
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
