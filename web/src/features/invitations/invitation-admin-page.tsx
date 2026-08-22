import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Copy, Link2, MailPlus, RefreshCw, Trash2, UserRoundPlus, Users } from "lucide-react";
import { useState } from "react";
import { Dialog } from "radix-ui";
import { AppShell } from "@/components/app-shell/app-shell";
import { Button } from "@/components/ui/button";
import { ApiError } from "@/lib/api";
import {
  createInvitationMutation,
  listInvitationsOptions,
  listInvitationsQueryKey,
  revokeInvitationMutation,
  rotateInvitationMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { Invitation, IssuedInvitation } from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";

const browserMutationHeaders = { "X-MemoryOS-CSRF": "1" as const };

const statusStyles: Record<Invitation["status"], string> = {
  PENDING: "bg-status-warning-surface text-status-warning-content",
  ACCEPTED: "bg-status-success-surface text-status-success-content",
  EXPIRED: "bg-status-info-surface text-status-info-content",
  REVOKED: "bg-status-info-surface text-content-muted",
};

export function InvitationAdminPage() {
  const queryClient = useQueryClient();
  const invitations = useQuery({ ...listInvitationsOptions(), retry: false });
  const [dialogOpen, setDialogOpen] = useState(false);
  const [email, setEmail] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const [issued, setIssued] = useState<IssuedInvitation | null>(null);
  const [copied, setCopied] = useState(false);

  const create = useMutation(createInvitationMutation());
  const rotate = useMutation(rotateInvitationMutation());
  const revoke = useMutation(revokeInvitationMutation());

  async function refreshInvitations() {
    await queryClient.invalidateQueries({ queryKey: listInvitationsQueryKey() });
  }

  async function createInvite() {
    setFormError(null);
    try {
      const result = await create.mutateAsync({
        body: { email },
        headers: browserMutationHeaders,
      });
      setIssued(result);
      setEmail("");
      await refreshInvitations();
    } catch (error) {
      setFormError(invitationError(error));
    }
  }

  async function rotateInvite(invitation: Invitation) {
    setFormError(null);
    try {
      const result = await rotate.mutateAsync({
        path: { invitationId: invitation.id },
        headers: browserMutationHeaders,
      });
      setIssued(result);
      setDialogOpen(true);
      await refreshInvitations();
    } catch (error) {
      setFormError(invitationError(error));
    }
  }

  async function revokeInvite(invitation: Invitation) {
    setFormError(null);
    try {
      await revoke.mutateAsync({
        path: { invitationId: invitation.id },
        headers: browserMutationHeaders,
      });
      await refreshInvitations();
    } catch (error) {
      setFormError(invitationError(error));
    }
  }

  function closeDialog() {
    if (create.isPending || rotate.isPending) return;
    setDialogOpen(false);
    setEmail("");
    setFormError(null);
    setIssued(null);
    setCopied(false);
  }

  async function copyLink() {
    if (!issued) return;
    try {
      await navigator.clipboard.writeText(
        new URL(issued.invitationUrl, window.location.origin).toString(),
      );
      setCopied(true);
    } catch {
      setFormError("The invitation link could not be copied. Copy it from the field.");
    }
  }

  const rows = invitations.data ?? [];

  return (
    <AppShell area="admin" adminPage="people" pageTitle="People">
      <section className="mx-auto w-full max-w-5xl px-5 py-8 sm:px-8 sm:py-12">
        <header className="mb-8 flex flex-col gap-5 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <p className="mb-2 font-secondary-action text-content-muted">Organization</p>
            <h1 className="font-heading-h2 text-content-primary">People</h1>
            <p className="mt-2 max-w-xl font-main-content-body text-content-secondary">
              Invite someone into this workspace with a secure, expiring link.
            </p>
          </div>
          <Button
            size="lg"
            onClick={() => {
              setIssued(null);
              setFormError(null);
              setDialogOpen(true);
            }}
          >
            <UserRoundPlus />
            Invite member
          </Button>
        </header>

        {formError && !dialogOpen && (
          <div
            role="alert"
            className="mb-5 rounded-xl border border-status-danger-content/20 bg-status-danger-surface px-4 py-3 font-main-ui-body text-status-danger-content"
          >
            {formError}
          </div>
        )}

        <div className="overflow-hidden rounded-2xl border border-border-subtle bg-surface-raised">
          <div className="flex items-center justify-between border-b border-border-subtle px-4 py-3 sm:px-5">
            <div className="flex items-center gap-2.5">
              <Users className="size-4 text-content-secondary" aria-hidden="true" />
              <h2 className="font-main-ui-action text-content-primary">Invitations</h2>
            </div>
            <span className="font-secondary-body text-content-muted">
              {rows.length} {rows.length === 1 ? "record" : "records"}
            </span>
          </div>

          {invitations.isPending ? (
            <div className="px-5 py-12 text-center font-main-ui-body text-content-muted">
              Loading invitations…
            </div>
          ) : invitations.isError ? (
            <div className="px-5 py-12 text-center">
              <p className="font-main-ui-body text-content-secondary">
                Invitations could not be loaded.
              </p>
              <Button variant="outline" className="mt-4" onClick={() => void invitations.refetch()}>
                Try again
              </Button>
            </div>
          ) : rows.length === 0 ? (
            <div className="px-6 py-14 text-center">
              <span className="mx-auto mb-5 grid size-11 place-items-center rounded-xl border border-border-subtle bg-surface-subtle text-content-secondary">
                <MailPlus className="size-5" aria-hidden="true" />
              </span>
              <h2 className="font-heading-h3 text-content-primary">No invitations yet</h2>
              <p className="mx-auto mt-2 max-w-md font-main-ui-body text-content-muted">
                Create a link when you are ready to bring someone into MemoryOS.
              </p>
            </div>
          ) : (
            <ul className="divide-y divide-border-subtle">
              {rows.map((invitation) => {
                const pending = invitation.status === "PENDING";
                return (
                  <li
                    key={invitation.id}
                    className="flex flex-col gap-4 px-4 py-4 sm:flex-row sm:items-center sm:px-5"
                  >
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-2.5">
                        <p className="truncate font-main-ui-action text-content-primary">
                          {invitation.email}
                        </p>
                        <span
                          className={cn(
                            "rounded-full px-2 py-0.5 font-figure-small-label tracking-wide",
                            statusStyles[invitation.status],
                          )}
                        >
                          {statusLabel(invitation.status)}
                        </span>
                      </div>
                      <p className="mt-1 font-secondary-body text-content-muted">
                        {invitation.status === "ACCEPTED" && invitation.acceptedAt
                          ? `Joined ${formatDate(invitation.acceptedAt)}`
                          : invitation.status === "REVOKED" && invitation.revokedAt
                            ? `Revoked ${formatDate(invitation.revokedAt)}`
                            : `Expires ${formatDate(invitation.expiresAt)}`}
                      </p>
                    </div>
                    {pending && (
                      <div className="flex shrink-0 gap-2">
                        <Button
                          variant="outline"
                          onClick={() => void rotateInvite(invitation)}
                          disabled={rotate.isPending || revoke.isPending}
                        >
                          <RefreshCw />
                          Rotate link
                        </Button>
                        <Button
                          variant="ghost"
                          className="text-status-danger-content"
                          onClick={() => void revokeInvite(invitation)}
                          disabled={rotate.isPending || revoke.isPending}
                        >
                          <Trash2 />
                          Revoke
                        </Button>
                      </div>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      </section>

      <Dialog.Root
        open={dialogOpen}
        onOpenChange={(open) => (open ? setDialogOpen(true) : closeDialog())}
      >
        <Dialog.Portal>
          <Dialog.Overlay className="fixed inset-0 z-40 bg-content-primary/20 backdrop-blur-[2px] data-[state=closed]:animate-out data-[state=open]:animate-in data-[state=closed]:fade-out data-[state=open]:fade-in motion-reduce:animate-none" />
          <Dialog.Content className="fixed top-1/2 left-1/2 z-50 w-[min(30rem,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 rounded-2xl border border-border-default bg-surface-overlay p-5 shadow-md outline-none sm:p-6">
            <Dialog.Title className="font-heading-h3 text-content-primary">
              {issued ? "Invitation link ready" : "Invite a member"}
            </Dialog.Title>
            <Dialog.Description className="mt-2 font-main-ui-body text-content-secondary">
              {issued
                ? "Share this link now. MemoryOS stores only its digest, so this exact link cannot be shown again."
                : "They will join as a member of this Organization and its default workspace."}
            </Dialog.Description>

            {issued ? (
              <div className="mt-6">
                <label
                  className="font-secondary-action text-content-secondary"
                  htmlFor="invitation-link"
                >
                  Secure invitation link
                </label>
                <div className="mt-2 flex gap-2">
                  <input
                    id="invitation-link"
                    readOnly
                    value={new URL(issued.invitationUrl, window.location.origin).toString()}
                    className="h-10 min-w-0 flex-1 rounded-lg border border-border-default bg-surface-subtle px-3 font-main-ui-body text-content-primary outline-none focus:border-focus-ring focus:ring-3 focus:ring-ring/30"
                  />
                  <Button size="lg" onClick={() => void copyLink()}>
                    {copied ? <Link2 /> : <Copy />}
                    {copied ? "Copied" : "Copy"}
                  </Button>
                </div>
                <p className="mt-3 font-secondary-body text-content-muted">
                  Expires {formatDate(issued.invitation.expiresAt)}.
                </p>
              </div>
            ) : (
              <div className="mt-6">
                <label
                  className="font-secondary-action text-content-secondary"
                  htmlFor="invite-email"
                >
                  Email address
                </label>
                <input
                  id="invite-email"
                  type="email"
                  autoComplete="email"
                  autoFocus
                  value={email}
                  onChange={(event) => setEmail(event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter" && email.trim()) void createInvite();
                  }}
                  placeholder="name@company.com"
                  className="mt-2 h-11 w-full rounded-lg border border-border-default bg-surface-base px-3.5 font-main-ui-body text-content-primary outline-none placeholder:text-content-muted focus:border-focus-ring focus:ring-3 focus:ring-ring/30"
                />
              </div>
            )}

            {formError && (
              <p role="alert" className="mt-4 font-main-ui-body text-status-danger-content">
                {formError}
              </p>
            )}

            <div className="mt-7 flex justify-end gap-2">
              <Button
                variant="ghost"
                onClick={closeDialog}
                disabled={create.isPending || rotate.isPending}
              >
                {issued ? "Done" : "Cancel"}
              </Button>
              {!issued && (
                <Button
                  onClick={() => void createInvite()}
                  disabled={!email.trim() || create.isPending}
                >
                  {create.isPending ? "Creating…" : "Create invitation"}
                </Button>
              )}
            </div>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
    </AppShell>
  );
}

function invitationError(error: unknown) {
  if (error instanceof ApiError) {
    if (error.status === 409) return "An open invitation already exists for this email.";
    if (error.status === 403) return "Only an active Organization owner can manage invitations.";
    if (error.status === 410)
      return "This invitation is no longer available. Refresh and try again.";
    if (error.status === 400) return "Enter a valid email address.";
  }
  return "The invitation could not be updated. Try again.";
}

function statusLabel(status: Invitation["status"]) {
  return status.charAt(0) + status.slice(1).toLowerCase();
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value));
}
