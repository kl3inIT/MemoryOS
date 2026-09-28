import { useAppTranslation } from "@/i18n/use-app-translation";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useSearch } from "@tanstack/react-router";
import { SearchX, User, UserPlus, WifiOff } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { EmptyState } from "@/components/composites/empty-state";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { Alert, AlertAction, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
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
import { usersQuery, type UsersSearch } from "./users-search";

const emptyEntries: UserListItem[] = [];

export function UsersPage() {
  const ui = useAppTranslation();

  const queryClient = useQueryClient();
  const search = useSearch({ from: "/_authenticated/admin/users" });
  const navigate = useNavigate({ from: "/admin/users" });
  const canReadGroups = useCapabilityAuthority("GROUPS_READ") !== "none";
  const canEditUserGroups = useGlobalCapability("SYSTEM_ADMIN");
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

  function refreshUsers() {
    void queryClient.invalidateQueries({ queryKey: listUsersQueryKey() }).catch(() => undefined);
  }

  // Group membership changes what the person can read everywhere, so every private view refreshes.
  async function refreshPrivateViews() {
    await queryClient.invalidateQueries();
  }

  function showIssuedInvitation(invitation: IssuedInvitation) {
    setIssuedInvitation(invitation);
    setInvitationDialogOpen(true);
  }

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

  function clearFilters() {
    updateView(
      { search: undefined, status: undefined, role: undefined, groupId: undefined },
      { resetPage: true },
    );
  }

  const usersPage = users.data;
  const entries = usersPage?.items ?? emptyEntries;
  const hasFilters = Boolean(search.search || search.status || search.role || search.groupId);
  const groups = groupOptions.data?.items ?? [];

  return (
    <>
      <SettingsLayout wide className="gap-6">
        <PageHeader
          icon={<User />}
          title={ui("Users")}
          description={ui("Manage members, their roles and pending invitations.")}
          actions={
            <Button
              ref={inviteButtonRef}
              size="sm"
              disabled={actions.invitationPending}
              onClick={openInvitationDialog}
            >
              <UserPlus data-icon="inline-start" aria-hidden="true" />
              {ui("Invite member")}
            </Button>
          }
        />

        <UsersSummary
          counts={usersPage?.counts}
          selectedStatus={search.status}
          loading={users.isPending}
          onStatusChange={(status) => updateView({ status }, { resetPage: true })}
        />

        <UsersFilters
          search={search}
          groups={canReadGroups ? groups : undefined}
          groupsLoading={groupOptions.isPending}
          onSearchChange={(nextSearch) => updateView({ search: nextSearch }, { resetPage: true })}
          onRoleChange={(role) => updateView({ role }, { resetPage: true })}
          onGroupChange={(groupId) => updateView({ groupId }, { resetPage: true })}
          onClear={clearFilters}
        />

        <div className="flex flex-col gap-3" aria-busy={users.isFetching}>
          <span className="sr-only" aria-live="polite">
            {users.isPending
              ? ui("Loading…")
              : users.isFetching
                ? ui("Updating…")
                : usersPage
                  ? ui("{{v1}} {{v2}}", {
                      v1: usersPage.totalItems,
                      v2: ui(usersPage.totalItems === 1 ? "user" : "users"),
                    })
                  : ui("Count unavailable")}
          </span>

          {users.isError && usersPage ? (
            <Alert variant="warning">
              <AlertDescription>
                {ui("Could not refresh users. Showing previous results.")}
              </AlertDescription>
              <AlertAction>
                <Button size="sm" prominence="tertiary" onClick={() => void users.refetch()}>
                  {ui("Retry refresh")}
                </Button>
              </AlertAction>
            </Alert>
          ) : null}

          {users.isPending ? (
            <UsersLoading />
          ) : users.isError && !usersPage ? (
            <EmptyState
              role="alert"
              icon={<WifiOff />}
              title={ui("Could not load users")}
              action={
                <Button prominence="secondary" size="sm" onClick={() => void users.refetch()}>
                  {ui("Try again")}
                </Button>
              }
            />
          ) : entries.length === 0 ? (
            <UsersEmpty
              filtered={hasFilters}
              invitationPending={actions.invitationPending}
              onClear={clearFilters}
              onInvite={openInvitationDialog}
            />
          ) : (
            <UsersTable
              entries={entries}
              sort={search.sort}
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
      </SettingsLayout>

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
  const ui = useAppTranslation();

  return (
    <div role="status" aria-label={ui("Loading users")} className="flex flex-col gap-2">
      {Array.from({ length: 6 }, (_, index) => (
        <Skeleton key={index} className="h-18" />
      ))}
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
  const ui = useAppTranslation();

  return (
    <EmptyState
      icon={filtered ? <SearchX /> : <UserPlus />}
      title={filtered ? ui("No users found") : ui("No users yet")}
      action={
        <Button
          prominence="secondary"
          size="sm"
          disabled={!filtered && invitationPending}
          onClick={filtered ? onClear : onInvite}
        >
          {filtered ? ui("Clear filters") : ui("Invite member")}
        </Button>
      }
    />
  );
}
