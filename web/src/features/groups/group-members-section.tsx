import { keepPreviousData, useMutation, useQuery } from "@tanstack/react-query";
import {
  LoaderCircle,
  Plus,
  Search,
  ShieldMinus,
  ShieldPlus,
  Trash2,
  UserRound,
  UsersRound,
} from "lucide-react";
import { useRef, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  addGroupMembersMutation,
  assignGroupManagerMutation,
  listGroupCandidatesOptions,
  listGroupMembersOptions,
  removeGroupManagerMutation,
  removeGroupMemberMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { GroupMember, GroupSummary } from "@/lib/hey-api/types.gen";
import { groupMutationError } from "./group-errors";

type GroupMembersSectionProps = {
  group: GroupSummary;
  onAuthorityChanged: () => Promise<void>;
};

export function GroupMembersSection({ group, onAuthorityChanged }: GroupMembersSectionProps) {
  const currentActorId = useApplicationSession().actorId;
  const [search, setSearch] = useState("");
  const [searchDraft, setSearchDraft] = useState("");
  const [page, setPage] = useState(0);
  const [adding, setAdding] = useState(false);
  const [candidateSearch, setCandidateSearch] = useState("");
  const [candidateSearchDraft, setCandidateSearchDraft] = useState("");
  const [candidatePage, setCandidatePage] = useState(0);
  const [selectedCandidates, setSelectedCandidates] = useState<Set<string>>(() => new Set());
  const [actionError, setActionError] = useState<string | null>(null);
  const addButtonRef = useRef<HTMLButtonElement>(null);
  const members = useQuery({
    ...listGroupMembersOptions({
      path: { groupId: group.id },
      query: { search: search || undefined, page, size: 10 },
    }),
    placeholderData: keepPreviousData,
    retry: false,
  });
  const candidates = useQuery({
    ...listGroupCandidatesOptions({
      path: { groupId: group.id },
      query: { search: candidateSearch || undefined, page: candidatePage, size: 10 },
    }),
    enabled: adding,
    placeholderData: keepPreviousData,
    retry: false,
  });
  const addMembers = useMutation(addGroupMembersMutation());
  const removeMember = useMutation(removeGroupMemberMutation());
  const assignManager = useMutation(assignGroupManagerMutation());
  const removeManager = useMutation(removeGroupManagerMutation());
  const canManageMembers = group.actions.includes("manage_members");
  const canManageManagers = group.actions.includes("manage_managers");

  const memberTotalPages = members.data?.totalPages;
  if (!members.isPlaceholderData && memberTotalPages !== undefined) {
    const lastPage = Math.max(memberTotalPages - 1, 0);
    if (page > lastPage) setPage(lastPage);
  }

  const candidateTotalPages = candidates.data?.totalPages;
  if (!candidates.isPlaceholderData && candidateTotalPages !== undefined) {
    const lastPage = Math.max(candidateTotalPages - 1, 0);
    if (candidatePage > lastPage) setCandidatePage(lastPage);
  }

  async function addSelectedMembers() {
    if (selectedCandidates.size === 0 || addMembers.isPending) return;
    setActionError(null);
    try {
      await addMembers.mutateAsync({
        path: { groupId: group.id },
        headers: sameOriginMutationHeaders,
        body: { actorIds: [...selectedCandidates] },
      });
      setSelectedCandidates(new Set());
      setAdding(false);
      addButtonRef.current?.focus();
      await onAuthorityChanged();
    } catch (cause) {
      setActionError(groupMutationError(cause, "members"));
    }
  }

  async function removeSelectedMember(member: GroupMember) {
    setActionError(null);
    await removeMember.mutateAsync({
      path: { groupId: group.id, actorId: member.actorId },
      headers: sameOriginMutationHeaders,
    });
    addButtonRef.current?.focus();
    await onAuthorityChanged();
  }

  async function changeManager(member: GroupMember) {
    setActionError(null);
    const mutation = member.isManager ? removeManager : assignManager;
    try {
      await mutation.mutateAsync({
        path: { groupId: group.id, actorId: member.actorId },
        headers: sameOriginMutationHeaders,
      });
      await onAuthorityChanged();
    } catch (cause) {
      setActionError(groupMutationError(cause, "manager"));
      throw cause;
    }
  }

  const pageData = members.data;
  const rows = pageData?.items ?? [];
  const candidatesPage = candidates.data;
  const candidateRows = candidatesPage?.items ?? [];
  const busy =
    addMembers.isPending ||
    removeMember.isPending ||
    assignManager.isPending ||
    removeManager.isPending;

  return (
    <section aria-labelledby="group-members-heading" className="border-t border-border-subtle pt-7">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex items-start gap-3">
          <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-surface-subtle text-content-secondary">
            <UsersRound className="size-4" aria-hidden="true" />
          </span>
          <div>
            <h2 id="group-members-heading" className="font-heading-h3 text-content-primary">
              Members
            </h2>
            <p className="mt-1 font-main-ui-body text-content-muted">
              Group managers can maintain ordinary membership without changing manager status.
            </p>
          </div>
        </div>
        {canManageMembers ? (
          <Button
            ref={addButtonRef}
            size="sm"
            prominence={adding ? "secondary" : "tertiary"}
            disabled={busy}
            onClick={() => {
              setAdding((current) => !current);
              setSelectedCandidates(new Set());
              setActionError(null);
            }}
          >
            <Plus aria-hidden="true" />
            {adding ? "Done adding" : "Add members"}
          </Button>
        ) : null}
      </div>

      {actionError ? (
        <p
          role="alert"
          className="mt-4 rounded-lg bg-status-danger-surface px-4 py-3 font-secondary-body text-status-danger-content"
        >
          {actionError}
        </p>
      ) : null}

      {adding ? (
        <div className="mt-4 rounded-xl border border-border-default bg-surface-subtle p-4 sm:p-5">
          <div className="flex flex-col gap-2 sm:flex-row">
            <form
              role="search"
              className="relative min-w-0 flex-1"
              onSubmit={(event) => {
                event.preventDefault();
                setCandidateSearch(candidateSearchDraft.trim());
                setCandidatePage(0);
              }}
            >
              <Search
                className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-content-muted"
                aria-hidden="true"
              />
              <Input
                type="search"
                value={candidateSearchDraft}
                aria-label="Search member candidates"
                maxLength={200}
                placeholder="Search users…"
                className="bg-surface-raised pl-9"
                onChange={(event) => setCandidateSearchDraft(event.target.value)}
              />
              <button type="submit" className="sr-only">
                Search candidates
              </button>
            </form>
            <Button
              disabled={selectedCandidates.size === 0 || candidates.isPending}
              pending={addMembers.isPending}
              onClick={() => void addSelectedMembers()}
            >
              Add {selectedCandidates.size > 0 ? selectedCandidates.size : "selected"}
            </Button>
          </div>

          {candidates.isPending ? (
            <LoadingRows label="Loading eligible users" />
          ) : candidates.isError ? (
            <InlineError
              label="Eligible users could not be loaded."
              onRetry={() => void candidates.refetch()}
            />
          ) : candidateRows.length === 0 ? (
            <EmptyRows
              title={
                candidateSearch
                  ? "No eligible users found"
                  : "Everyone eligible is already a member"
              }
              detail={
                candidateSearch
                  ? "Try another name or email."
                  : "There are no more users to add to this group."
              }
            />
          ) : (
            <div className="mt-4 divide-y divide-border-subtle overflow-hidden rounded-xl border border-border-subtle bg-surface-raised">
              {candidateRows.map((candidate) => {
                const checked = selectedCandidates.has(candidate.actorId);
                return (
                  <label
                    key={candidate.actorId}
                    className="flex cursor-pointer items-center gap-3 px-4 py-3 transition-colors hover:bg-surface-subtle has-[:focus-visible]:ring-3 has-[:focus-visible]:ring-inset has-[:focus-visible]:ring-focus-ring/30"
                  >
                    <input
                      type="checkbox"
                      checked={checked}
                      className="size-4 shrink-0 accent-content-primary outline-none"
                      onChange={() => {
                        setSelectedCandidates((current) => {
                          const next = new Set(current);
                          if (checked) next.delete(candidate.actorId);
                          else next.add(candidate.actorId);
                          return next;
                        });
                      }}
                    />
                    <MemberIdentity member={candidate} />
                    <MemberAccount member={candidate} />
                  </label>
                );
              })}
            </div>
          )}
          {candidatesPage && candidatesPage.totalPages > 1 ? (
            <Pagination
              label="Candidate pages"
              page={candidatePage}
              totalPages={candidatesPage.totalPages}
              onPageChange={setCandidatePage}
            />
          ) : null}
        </div>
      ) : null}

      <form
        role="search"
        className="relative mt-4"
        onSubmit={(event) => {
          event.preventDefault();
          setSearch(searchDraft.trim());
          setPage(0);
        }}
      >
        <Search
          className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-content-muted"
          aria-hidden="true"
        />
        <Input
          type="search"
          value={searchDraft}
          aria-label="Search group members"
          maxLength={200}
          placeholder="Search members…"
          className="bg-surface-sunken pl-9"
          onChange={(event) => setSearchDraft(event.target.value)}
        />
        <button type="submit" className="sr-only">
          Search members
        </button>
      </form>

      {members.isPending ? (
        <LoadingRows label="Loading members" />
      ) : members.isError ? (
        <InlineError label="Members could not be loaded." onRetry={() => void members.refetch()} />
      ) : rows.length === 0 ? (
        <EmptyRows
          title={search ? "No members found" : "No members"}
          detail={
            search ? "Try another name or email." : "Add an eligible Tenant user to this group."
          }
        />
      ) : (
        <div className="mt-4 overflow-x-auto rounded-xl border border-border-subtle">
          <table className="w-full min-w-[42rem] table-fixed border-collapse">
            <caption className="sr-only">Members of {group.name}</caption>
            <colgroup>
              <col />
              <col className="w-32" />
              <col className="w-28" />
              <col className="w-28" />
            </colgroup>
            <thead className="border-b border-border-subtle bg-surface-subtle text-left">
              <tr>
                <th className="h-10 px-4 font-secondary-action text-content-secondary">Name</th>
                <th className="h-10 px-4 font-secondary-action text-content-secondary">
                  Account type
                </th>
                <th className="h-10 px-4 font-secondary-action text-content-secondary">Status</th>
                <th className="h-10 px-4 text-center font-secondary-action text-content-secondary">
                  <span className="sr-only">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border-subtle">
              {rows.map((member) => {
                const name =
                  member.displayName?.trim() || member.email?.trim() || `user ${member.actorId}`;
                const managerPending =
                  (assignManager.isPending &&
                    assignManager.variables?.path.actorId === member.actorId) ||
                  (removeManager.isPending &&
                    removeManager.variables?.path.actorId === member.actorId);
                const ownManager = member.isManager && member.actorId === currentActorId;
                return (
                  <tr
                    key={member.actorId}
                    className="bg-surface-raised transition-colors hover:bg-surface-subtle"
                  >
                    <td className="h-16 px-4 py-3">
                      <MemberIdentity member={member} />
                    </td>
                    <td className="px-4 py-3">
                      <MemberAccount member={member} />
                    </td>
                    <td className="px-4 py-3">
                      <Badge
                        variant="outline"
                        className={
                          member.status === "ACTIVE"
                            ? "border-status-success-content/25 bg-status-success-surface text-status-success-content"
                            : "bg-surface-subtle text-content-muted"
                        }
                      >
                        {member.status === "ACTIVE" ? "Active" : "Inactive"}
                      </Badge>
                    </td>
                    <td className="px-3 py-3">
                      <div className="flex items-center justify-end gap-1">
                        {member.protectedOwner ? (
                          <Badge
                            variant="outline"
                            className="mr-1 bg-surface-raised text-content-muted"
                          >
                            Protected
                          </Badge>
                        ) : null}
                        {canManageManagers ? (
                          <ConfirmDialog
                            trigger={
                              <IconButton
                                size="sm"
                                disabled={busy || member.protectedOwner || ownManager}
                                pending={managerPending}
                                aria-label={`${member.isManager ? "Remove manager" : "Make manager"} for ${name}`}
                              >
                                {member.isManager ? <ShieldMinus /> : <ShieldPlus />}
                              </IconButton>
                            }
                            title={`${member.isManager ? "Remove manager access from" : "Make"} ${name}${member.isManager ? "" : " a manager"}?`}
                            description={
                              member.isManager
                                ? "Their group-scoped management access ends on the next authorized request."
                                : "They will be able to maintain this group’s ordinary membership and access associated Sources within their granted scope."
                            }
                            confirmLabel={member.isManager ? "Remove manager" : "Make manager"}
                            pendingLabel="Updating manager…"
                            confirmTone={member.isManager ? "danger" : "default"}
                            onConfirm={() => changeManager(member)}
                            errorMessage={(cause) => groupMutationError(cause, "manager")}
                          />
                        ) : member.isManager ? (
                          <Badge
                            variant="secondary"
                            className="bg-surface-subtle text-content-secondary"
                          >
                            Manager
                          </Badge>
                        ) : null}
                        {canManageMembers && (!member.isManager || canManageManagers) ? (
                          <ConfirmDialog
                            trigger={
                              <IconButton
                                size="sm"
                                tone="danger"
                                prominence="tertiary"
                                disabled={busy || member.protectedOwner || ownManager}
                                aria-label={`Remove ${name} from ${group.name}`}
                              >
                                <Trash2 />
                              </IconButton>
                            }
                            successFocusRef={addButtonRef}
                            fallbackFocusRef={addButtonRef}
                            title={`Remove ${name}?`}
                            description={`They will leave “${group.name}”. Other group memberships and their Tenant account stay unchanged.`}
                            confirmLabel="Remove member"
                            pendingLabel="Removing member…"
                            onConfirm={() => removeSelectedMember(member)}
                            errorMessage={(cause) => groupMutationError(cause, "members")}
                          />
                        ) : null}
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {pageData && pageData.totalPages > 1 ? (
        <Pagination
          label="Member pages"
          page={page}
          totalPages={pageData.totalPages}
          onPageChange={setPage}
        />
      ) : null}
    </section>
  );
}

function MemberIdentity({ member }: { member: GroupMember }) {
  const name = member.displayName?.trim() || member.email?.trim() || `user ${member.actorId}`;
  return (
    <span className="flex min-w-0 flex-1 items-center gap-3">
      <span className="grid size-8 shrink-0 place-items-center rounded-full bg-surface-subtle text-content-muted">
        <UserRound className="size-4" aria-hidden="true" />
      </span>
      <span className="min-w-0 flex-1">
        <span className="flex items-center gap-2">
          <span className="truncate font-main-ui-action text-content-primary">{name}</span>
          {member.isManager ? (
            <Badge
              variant="secondary"
              className="shrink-0 bg-surface-subtle text-content-secondary"
            >
              Manager
            </Badge>
          ) : null}
        </span>
        {member.displayName && member.email ? (
          <span className="block truncate font-secondary-body text-content-muted">
            {member.email}
          </span>
        ) : null}
      </span>
    </span>
  );
}
function MemberAccount({ member }: { member: GroupMember }) {
  return (
    <span className="inline-flex shrink-0 items-center gap-1.5 font-main-ui-body text-content-secondary">
      <UserRound className="size-4 text-content-muted" aria-hidden="true" />
      {member.accountType === "STANDARD" ? "Standard" : member.accountType}
    </span>
  );
}

function LoadingRows({ label }: { label: string }) {
  return (
    <p
      role="status"
      className="mt-5 flex items-center gap-2 px-2 py-6 font-main-ui-body text-content-muted"
    >
      <LoaderCircle className="size-4 animate-spin motion-reduce:animate-none" aria-hidden="true" />
      {label}
    </p>
  );
}

function InlineError({ label, onRetry }: { label: string; onRetry: () => void }) {
  return (
    <div className="mt-4 rounded-xl border border-border-subtle px-4 py-5">
      <p role="alert" className="font-main-ui-body text-content-secondary">
        {label}
      </p>
      <Button size="sm" prominence="secondary" className="mt-3" onClick={onRetry}>
        Try again
      </Button>
    </div>
  );
}

function EmptyRows({ title, detail }: { title: string; detail: string }) {
  return (
    <div className="mt-4 rounded-xl border border-dashed border-border-default px-4 py-8 text-center">
      <UsersRound className="mx-auto size-5 text-content-muted" aria-hidden="true" />
      <p className="mt-2 font-main-ui-action text-content-primary">{title}</p>
      <p className="mt-1 font-secondary-body text-content-muted">{detail}</p>
    </div>
  );
}

function Pagination({
  label,
  page,
  totalPages,
  onPageChange,
}: {
  label: string;
  page: number;
  totalPages: number;
  onPageChange: (page: number) => void;
}) {
  return (
    <nav aria-label={label} className="mt-4 flex items-center justify-end gap-2">
      <Button
        size="sm"
        prominence="secondary"
        disabled={page === 0}
        onClick={() => onPageChange(page - 1)}
      >
        Previous
      </Button>
      <span className="min-w-24 text-center font-secondary-body tabular-nums text-content-muted">
        Page {page + 1} of {Math.max(totalPages, 1)}
      </span>
      <Button
        size="sm"
        prominence="secondary"
        disabled={page + 1 >= totalPages}
        onClick={() => onPageChange(page + 1)}
      >
        Next
      </Button>
    </nav>
  );
}
