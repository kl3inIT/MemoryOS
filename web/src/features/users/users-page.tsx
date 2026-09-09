import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useSearch } from "@tanstack/react-router";
import { SearchX, UserRoundPlus, UsersRound, WifiOff } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { TextButton } from "@/components/ui/text-button";
import {
  useCapabilityAuthority,
  useGlobalCapability,
} from "@/features/identity/application-session-context";
import {
  listGroupsOptions,
  listUsersOptions,
  listUsersQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { IssuedInvitation, UserListItem } from "@/lib/hey-api/types.gen";
import { InvitationDialog } from "./invitation-dialog";
import { useUserActions } from "./use-user-actions";
import { UsersFilters } from "./users-filters";
import { UsersSummary } from "./users-summary";
import { UsersTable } from "./users-table";
import { usersQuery, type UsersSearch, type UsersSort } from "./users-search";

const emptyEntries: UserListItem[] = [];

export function UsersPage() {
  const queryClient = useQueryClient();
  const search = useSearch({ from: "/_authenticated/admin/users" });
  const navigate = useNavigate({ from: "/admin/users" });
  const canReadGroups = useCapabilityAuthority("GROUPS_READ") !== "none";
  const canEditUserGroups = useGlobalCapability("IAM_ADMIN");
  const inviteButtonRef = useRef<HTMLButtonElement>(null);
  const invitationReturnFocusRef = useRef<HTMLElement | null>(null);
  const [invitationDialogOpen, setInvitationDialogOpen] = useState(false);
  const [issuedInvitation, setIssuedInvitation] = useState<IssuedInvitation | null>(null);
  const users = useQuery({
    ...listUsersOptions({ query: usersQuery(search) }),
    placeholderData: keepPreviousData,
    retry: false,
  });
  const groupOptions = useQuery({
    ...listGroupsOptions({ query: { page: 0, size: 100 } }),
    enabled: canReadGroups,
    retry: false,
  });

  const refreshUsers = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: listUsersQueryKey() }).catch(() => undefined);
  }, [queryClient]);

  const refreshPrivateViews = useCallback(async () => {
    await queryClient.invalidateQueries();
  }, [queryClient]);

  const showIssuedInvitation = useCallback((invitation: IssuedInvitation) => {
    setIssuedInvitation(invitation);
    setInvitationDialogOpen(true);
  }, []);

  const actions = useUserActions({
    onUsersChanged: refreshUsers,
    onInvitationIssued: showIssuedInvitation,
  });

  useEffect(() => {
    const totalPages = users.data?.totalPages;
    if (users.isPlaceholderData || totalPages === undefined) return;
    const lastPage = Math.max(totalPages - 1, 0);
    if (search.page <= lastPage) return;
    void navigate({
      replace: true,
      search: (current) => ({ ...current, page: lastPage }),
    });
  }, [navigate, search.page, users.data?.totalPages, users.isPlaceholderData]);

  function openInvitationDialog() {
    invitationReturnFocusRef.current = inviteButtonRef.current;
    setInvitationDialogOpen(true);
  }

  function updateView(update: Partial<UsersSearch>, options: { resetPage?: boolean } = {}) {
    void navigate({
      search: (current) => ({
        ...current,
        ...update,
        page: options.resetPage ? 0 : (update.page ?? current.page),
      }),
    });
  }

  const usersPage = users.data;
  const entries = usersPage?.items ?? emptyEntries;
  const hasFilters = Boolean(search.search || search.status || search.role || search.groupId);
  const groups = groupOptions.data?.items ?? [];

  return (
    <>
      <section className="mx-auto w-full max-w-[var(--page-width-wide)] px-5 pt-7 pb-12 sm:px-8 sm:pt-10 sm:pb-16">
        <header className="flex items-center justify-between gap-4 border-b border-border-subtle pb-5">
          <div className="flex min-w-0 items-center gap-3">
            <UsersRound className="size-6 shrink-0 text-content-secondary" aria-hidden="true" />
            <h1 className="font-heading-h2 text-content-primary">Users</h1>
          </div>
          <Button
            ref={inviteButtonRef}
            size="sm"
            disabled={actions.invitationPending}
            onClick={openInvitationDialog}
          >
            <UserRoundPlus aria-hidden="true" />
            Invite member
          </Button>
        </header>

        <div className="mt-6 max-w-xl">
          <UsersSummary
            counts={usersPage?.counts}
            selectedStatus={search.status}
            loading={users.isPending}
            onStatusChange={(status) => updateView({ status }, { resetPage: true })}
          />
        </div>

        <div className="mt-6">
          <UsersFilters
            search={search}
            groups={canReadGroups ? groups : undefined}
            groupsLoading={groupOptions.isPending}
            onSearchChange={(nextSearch) => updateView({ search: nextSearch }, { resetPage: true })}
            onRoleChange={(role) => updateView({ role }, { resetPage: true })}
            onGroupChange={(groupId) => updateView({ groupId }, { resetPage: true })}
            onClear={() =>
              updateView(
                { search: undefined, status: undefined, role: undefined, groupId: undefined },
                { resetPage: true },
              )
            }
          />
        </div>

        <div
          className="mt-5 overflow-hidden rounded-xl border border-border-subtle bg-surface-raised"
          aria-busy={users.isFetching}
        >
          <span className="sr-only" aria-live="polite">
            {users.isPending
              ? "Loading…"
              : users.isFetching
                ? "Updating…"
                : usersPage
                  ? `${usersPage.totalItems} ${usersPage.totalItems === 1 ? "user" : "users"}`
                  : "Count unavailable"}
          </span>

          {users.isError && usersPage ? (
            <div
              role="alert"
              className="flex flex-col gap-2 border-b border-status-warning-content/20 bg-status-warning-surface px-4 py-3 font-secondary-body text-status-warning-content sm:flex-row sm:items-center sm:justify-between"
            >
              <span>Could not refresh users. Showing previous results.</span>
              <TextButton size="sm" onClick={() => void users.refetch()}>
                Retry refresh
              </TextButton>
            </div>
          ) : null}

          {users.isPending ? (
            <UsersLoading />
          ) : users.isError && !usersPage ? (
            <UsersError onRetry={() => void users.refetch()} />
          ) : entries.length === 0 ? (
            <UsersEmpty
              filtered={hasFilters}
              invitationPending={actions.invitationPending}
              onClear={() =>
                updateView(
                  { search: undefined, status: undefined, role: undefined, groupId: undefined },
                  { resetPage: true },
                )
              }
              onInvite={openInvitationDialog}
            />
          ) : (
            <UsersTable
              entries={entries}
              sort={search.sort as UsersSort}
              statusFilter={search.status}
              page={search.page}
              size={search.size}
              totalItems={usersPage?.totalItems ?? 0}
              totalPages={usersPage?.totalPages ?? 0}
              pendingActions={actions.pendingActions}
              rowErrors={actions.rowErrors}
              invitationPending={actions.invitationPending}
              canEditGroups={canEditUserGroups}
              fallbackActionFocusRef={inviteButtonRef}
              onGroupsSaved={refreshPrivateViews}
              onSortChange={(sort) => updateView({ sort }, { resetPage: true })}
              onPageChange={(page) => updateView({ page })}
              onSizeChange={(size) => updateView({ size }, { resetPage: true })}
              onActivate={actions.activate}
              onDeactivate={actions.deactivate}
              onRotate={(entry, target) => {
                invitationReturnFocusRef.current = target;
                return actions.rotate(entry);
              }}
              onRevoke={actions.revoke}
            />
          )}
        </div>
      </section>

      <InvitationDialog
        open={invitationDialogOpen}
        pending={actions.invitationPending}
        issuedInvitation={issuedInvitation}
        returnFocusRef={invitationReturnFocusRef}
        fallbackFocusRef={inviteButtonRef}
        onOpenChange={(open) => {
          setInvitationDialogOpen(open);
          if (!open) setIssuedInvitation(null);
        }}
        onCreate={actions.create}
      />
    </>
  );
}

function UsersLoading() {
  return (
    <div role="status" aria-label="Loading users" className="p-4">
      <span className="sr-only">Loading users</span>
      <div className="grid grid-cols-[minmax(12rem,2fr)_1.4fr_0.8fr_1fr_2rem] gap-4 border-b border-border-subtle px-1 pb-3">
        {Array.from({ length: 5 }, (_, index) => (
          <Skeleton key={index} className="h-3 w-16" />
        ))}
      </div>
      <div className="divide-y divide-border-subtle">
        {Array.from({ length: 6 }, (_, index) => (
          <div
            key={index}
            className="grid h-[4.5rem] grid-cols-[minmax(12rem,2fr)_1.4fr_0.8fr_1fr_2rem] items-center gap-4 px-1"
          >
            <div className="space-y-2">
              <Skeleton className="h-4 w-32" />
              <Skeleton className="h-3 w-44" />
            </div>
            <Skeleton className="h-5 w-24 rounded-full" />
            <Skeleton className="h-4 w-20" />
            <Skeleton className="h-5 w-16 rounded-full" />
            <Skeleton className="size-8" />
          </div>
        ))}
      </div>
    </div>
  );
}

function UsersError({ onRetry }: { onRetry: () => void }) {
  return (
    <div className="px-6 py-16 text-center">
      <span className="mx-auto grid size-10 place-items-center rounded-xl border border-border-subtle bg-surface-subtle text-content-secondary">
        <WifiOff className="size-4.5" aria-hidden="true" />
      </span>
      <h2 className="mt-4 font-heading-h3 text-content-primary">Could not load users</h2>
      <Button prominence="secondary" size="sm" className="mt-5" onClick={onRetry}>
        Try again
      </Button>
    </div>
  );
}

function UsersEmpty({
  filtered,
  invitationPending,
  onClear,
  onInvite,
}: {
  filtered: boolean;
  invitationPending: boolean;
  onClear: () => void;
  onInvite: () => void;
}) {
  return (
    <div className="px-6 py-16 text-center">
      <span className="mx-auto grid size-10 place-items-center rounded-xl border border-border-subtle bg-surface-subtle text-content-secondary">
        {filtered ? (
          <SearchX className="size-4.5" aria-hidden="true" />
        ) : (
          <UserRoundPlus className="size-4.5" aria-hidden="true" />
        )}
      </span>
      <h2 className="mt-4 font-heading-h3 text-content-primary">
        {filtered ? "No users found" : "No users yet"}
      </h2>
      <Button
        prominence="secondary"
        size="sm"
        className="mt-5"
        disabled={!filtered && invitationPending}
        onClick={filtered ? onClear : onInvite}
      >
        {filtered ? "Clear filters" : "Invite member"}
      </Button>
    </div>
  );
}
