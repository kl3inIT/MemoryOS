import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useSearch } from "@tanstack/react-router";
import { Copy, Link2, MailPlus, UserRoundPlus } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Dialog } from "radix-ui";
import { Button } from "@/components/ui/button";
import { InvitationFilters } from "@/features/invitations/invitation-filters";
import {
  invitationListQuery,
  type InvitationListSearch,
  type InvitationSort,
} from "@/features/invitations/invitation-list-search";
import { formatInvitationDate } from "@/features/invitations/invitation-presentation";
import {
  InvitationTable,
  type InvitationPendingAction,
} from "@/features/invitations/invitation-table";
import { ApiError, sameOriginMutationHeaders } from "@/lib/api";
import {
  createInvitationMutation,
  listInvitationsOptions,
  listInvitationsQueryKey,
  revokeInvitationMutation,
  rotateInvitationMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { Invitation, IssuedInvitation } from "@/lib/hey-api/types.gen";

const emptyInvitations: Invitation[] = [];

export function OrganizationInvitationsPage() {
  const queryClient = useQueryClient();
  const search = useSearch({ from: "/_authenticated/admin/invitations" });
  const navigate = useNavigate({ from: "/admin/invitations" });
  const invitationsQuery = useQuery({
    ...listInvitationsOptions({ query: invitationListQuery(search) }),
    placeholderData: keepPreviousData,
    retry: false,
  });
  const [dialogOpen, setDialogOpen] = useState(false);
  const [inviteeEmail, setInviteeEmail] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const [issuedInvitation, setIssuedInvitation] = useState<IssuedInvitation | null>(null);
  const [invitationLinkCopied, setInvitationLinkCopied] = useState(false);
  const [pendingActions, setPendingActions] = useState<
    Partial<Record<string, InvitationPendingAction>>
  >({});
  const [rowErrors, setRowErrors] = useState<Partial<Record<string, string>>>({});
  const invitationCreationInFlight = useRef(false);

  const createInvitation = useMutation(createInvitationMutation());
  const rotateInvitation = useMutation(rotateInvitationMutation());
  const revokeInvitation = useMutation(revokeInvitationMutation());

  useEffect(() => {
    const totalPages = invitationsQuery.data?.totalPages;
    if (!totalPages || search.page < totalPages) return;
    void navigate({
      replace: true,
      search: (current) => ({ ...current, page: totalPages - 1 }),
    });
  }, [invitationsQuery.data?.totalPages, navigate, search.page]);

  async function refreshInvitations() {
    await queryClient.invalidateQueries({ queryKey: listInvitationsQueryKey() });
  }

  function updateView(
    update: Partial<InvitationListSearch>,
    options: { resetPage?: boolean } = {},
  ) {
    void navigate({
      search: (current) => ({
        ...current,
        ...update,
        page: options.resetPage ? 0 : (update.page ?? current.page),
      }),
    });
  }

  function updatePendingAction(invitationId: string, action?: InvitationPendingAction) {
    setPendingActions((current) => {
      const next = { ...current };
      if (action) next[invitationId] = action;
      else delete next[invitationId];
      return next;
    });
  }

  function updateRowError(invitationId: string, error?: string) {
    setRowErrors((current) => {
      const next = { ...current };
      if (error) next[invitationId] = error;
      else delete next[invitationId];
      return next;
    });
  }

  async function submitInvitation() {
    const email = inviteeEmail.trim();
    if (!email || invitationCreationInFlight.current) return;

    invitationCreationInFlight.current = true;
    setFormError(null);
    try {
      const result = await createInvitation.mutateAsync({
        body: { email },
        headers: sameOriginMutationHeaders,
      });
      setIssuedInvitation(result);
      setInviteeEmail("");
      await refreshInvitations();
    } catch (error) {
      setFormError(invitationError(error));
    } finally {
      invitationCreationInFlight.current = false;
    }
  }

  async function rotateInvitationLink(invitation: Invitation) {
    updateRowError(invitation.id);
    updatePendingAction(invitation.id, "rotate");
    try {
      const result = await rotateInvitation.mutateAsync({
        path: { invitationId: invitation.id },
        headers: sameOriginMutationHeaders,
      });
      setIssuedInvitation(result);
      setDialogOpen(true);
      await refreshInvitations();
    } catch (error) {
      updateRowError(invitation.id, invitationError(error));
    } finally {
      updatePendingAction(invitation.id);
    }
  }

  async function revokePendingInvitation(invitation: Invitation) {
    updateRowError(invitation.id);
    updatePendingAction(invitation.id, "revoke");
    try {
      await revokeInvitation.mutateAsync({
        path: { invitationId: invitation.id },
        headers: sameOriginMutationHeaders,
      });
      await refreshInvitations();
    } catch (error) {
      updateRowError(invitation.id, invitationError(error));
    } finally {
      updatePendingAction(invitation.id);
    }
  }

  function closeInvitationDialog() {
    if (
      invitationCreationInFlight.current ||
      createInvitation.isPending ||
      rotateInvitation.isPending
    ) {
      return;
    }
    setDialogOpen(false);
    setInviteeEmail("");
    setFormError(null);
    setIssuedInvitation(null);
    setInvitationLinkCopied(false);
  }

  async function copyInvitationLink() {
    if (!issuedInvitation) return;
    try {
      await navigator.clipboard.writeText(
        new URL(issuedInvitation.invitationUrl, window.location.origin).toString(),
      );
      setInvitationLinkCopied(true);
    } catch {
      setFormError("The invitation link could not be copied. Copy it from the field.");
    }
  }

  const invitationPage = invitationsQuery.data;
  const invitationRows = invitationPage?.items ?? emptyInvitations;
  const hasFilters = Boolean(search.status || search.email);

  return (
    <>
      <section className="mx-auto w-full max-w-6xl px-5 py-8 sm:px-8 sm:py-12">
        <header className="mb-8 flex flex-col gap-5 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <p className="mb-2 font-secondary-action text-content-muted">Organization</p>
            <h1 className="font-heading-h2 text-content-primary">Invitations</h1>
            <p className="mt-2 max-w-xl font-main-content-body text-content-secondary">
              Invite members and inspect the complete invitation lifecycle.
            </p>
          </div>
          <Button
            size="lg"
            onClick={() => {
              setIssuedInvitation(null);
              setFormError(null);
              setDialogOpen(true);
            }}
          >
            <UserRoundPlus />
            Invite member
          </Button>
        </header>

        <div className="overflow-hidden rounded-2xl border border-border-subtle bg-surface-raised">
          <div className="flex items-center justify-between border-b border-border-subtle px-4 py-3 sm:px-5">
            <h2 className="font-main-ui-action text-content-primary">Invitation history</h2>
            <span
              className="font-secondary-body text-content-muted"
              aria-live="polite"
              aria-atomic="true"
            >
              {invitationsQuery.isFetching && !invitationsQuery.isPending
                ? "Updating…"
                : `${invitationPage?.totalItems ?? 0} ${
                    invitationPage?.totalItems === 1 ? "record" : "records"
                  }`}
            </span>
          </div>

          <InvitationFilters
            key={`${search.status ?? "ALL"}:${search.email ?? ""}`}
            search={search}
            onApply={(filters) => updateView(filters, { resetPage: true })}
            onClear={() => updateView({ status: undefined, email: undefined }, { resetPage: true })}
          />

          {invitationsQuery.isPending ? (
            <div className="px-5 py-12 text-center font-main-ui-body text-content-muted">
              Loading invitations…
            </div>
          ) : invitationsQuery.isError && !invitationPage ? (
            <div className="px-5 py-12 text-center">
              <p className="font-main-ui-body text-content-secondary">
                Invitations could not be loaded.
              </p>
              <Button
                variant="outline"
                className="mt-4"
                onClick={() => void invitationsQuery.refetch()}
              >
                Try again
              </Button>
            </div>
          ) : invitationRows.length === 0 ? (
            <div className="px-6 py-14 text-center">
              <span className="mx-auto mb-5 grid size-11 place-items-center rounded-xl border border-border-subtle bg-surface-subtle text-content-secondary">
                <MailPlus className="size-5" aria-hidden="true" />
              </span>
              <h2 className="font-heading-h3 text-content-primary">
                {hasFilters ? "No invitations match these filters" : "No invitations yet"}
              </h2>
              <p className="mx-auto mt-2 max-w-md font-main-ui-body text-content-muted">
                {hasFilters
                  ? "Change or clear the current filters to see other invitation records."
                  : "Create a link when you are ready to bring someone into MemoryOS."}
              </p>
              {hasFilters && (
                <Button
                  variant="outline"
                  className="mt-5"
                  onClick={() =>
                    updateView({ status: undefined, email: undefined }, { resetPage: true })
                  }
                >
                  Clear filters
                </Button>
              )}
            </div>
          ) : (
            <InvitationTable
              invitations={invitationRows}
              sort={search.sort}
              page={search.page}
              size={search.size}
              totalItems={invitationPage?.totalItems ?? 0}
              pendingActions={pendingActions}
              rowErrors={rowErrors}
              onSortChange={(sort: InvitationSort) => updateView({ sort }, { resetPage: true })}
              onPageChange={(page) => updateView({ page })}
              onSizeChange={(size) => updateView({ size }, { resetPage: true })}
              onRotate={(invitation) => void rotateInvitationLink(invitation)}
              onRevoke={(invitation) => void revokePendingInvitation(invitation)}
            />
          )}
        </div>
      </section>

      <Dialog.Root
        open={dialogOpen}
        onOpenChange={(open) => (open ? setDialogOpen(true) : closeInvitationDialog())}
      >
        <Dialog.Portal>
          <Dialog.Overlay className="fixed inset-0 z-40 bg-content-primary/20 backdrop-blur-[2px] data-[state=closed]:animate-out data-[state=open]:animate-in data-[state=closed]:fade-out data-[state=open]:fade-in motion-reduce:animate-none" />
          <Dialog.Content className="fixed top-1/2 left-1/2 z-50 w-[min(30rem,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 rounded-2xl border border-border-default bg-surface-overlay p-5 shadow-md outline-none sm:p-6">
            <form
              onSubmit={(event) => {
                event.preventDefault();
                void submitInvitation();
              }}
              aria-busy={createInvitation.isPending}
            >
              <Dialog.Title className="font-heading-h3 text-content-primary">
                {issuedInvitation ? "Invitation link ready" : "Invite a member"}
              </Dialog.Title>
              <Dialog.Description className="mt-2 font-main-ui-body text-content-secondary">
                {issuedInvitation
                  ? "Share this link now. MemoryOS stores only its digest, so this exact link cannot be shown again."
                  : "They will join as a member of this Organization."}
              </Dialog.Description>

              {issuedInvitation ? (
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
                      value={new URL(
                        issuedInvitation.invitationUrl,
                        window.location.origin,
                      ).toString()}
                      className="h-10 min-w-0 flex-1 rounded-lg border border-border-default bg-surface-subtle px-3 font-main-ui-body text-content-primary outline-none focus:border-focus-ring focus:ring-3 focus:ring-ring/30"
                    />
                    <Button type="button" size="lg" onClick={() => void copyInvitationLink()}>
                      {invitationLinkCopied ? <Link2 /> : <Copy />}
                      {invitationLinkCopied ? "Copied" : "Copy"}
                    </Button>
                  </div>
                  <p className="mt-3 font-secondary-body text-content-muted">
                    Expires {formatInvitationDate(issuedInvitation.invitation.expiresAt)}.
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
                    required
                    value={inviteeEmail}
                    onChange={(event) => setInviteeEmail(event.target.value)}
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
                  type="button"
                  variant="ghost"
                  onClick={closeInvitationDialog}
                  disabled={createInvitation.isPending || rotateInvitation.isPending}
                >
                  {issuedInvitation ? "Done" : "Cancel"}
                </Button>
                {!issuedInvitation && (
                  <Button
                    type="submit"
                    disabled={!inviteeEmail.trim() || createInvitation.isPending}
                  >
                    {createInvitation.isPending ? "Creating…" : "Create invitation"}
                  </Button>
                )}
              </div>
            </form>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
    </>
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
