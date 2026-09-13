import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { keepPreviousData, useMutation, useQuery } from "@tanstack/react-query";
import {
  ChevronLeft,
  ChevronRight,
  CircleMinus,
  CirclePlus,
  LoaderCircle,
  Search,
} from "lucide-react";
import { useRef, useState } from "react";
import { OnyxUserIcon, OnyxUsersIcon, OnyxUserShieldIcon } from "@/components/icons/identity-icons";
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
  const ui = useAppTranslation();

  const currentActorId = useApplicationSession().actorId;
  const [search, setSearch] = useState("");
  const [searchDraft, setSearchDraft] = useState("");
  const [page, setPage] = useState(0);
  const [adding, setAdding] = useState(false);
  const [candidateSearch, setCandidateSearch] = useState("");
  const [candidateSearchDraft, setCandidateSearchDraft] = useState("");
  const [candidatePage, setCandidatePage] = useState(0);
  const [selectedCandidates, setSelectedCandidates] = useState<Set<string>>(() => new Set());
  const [actionError, setActionError] = useState<AppCopy | null>(null);
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
    enabled: adding && group.actions.includes("manage_members"),
    placeholderData: keepPreviousData,
    retry: false,
  });
  const addMembers = useMutation(addGroupMembersMutation());
  const removeMember = useMutation(removeGroupMemberMutation());
  const assignManager = useMutation(assignGroupManagerMutation());
  const removeManager = useMutation(removeGroupManagerMutation());
  const canManageMembers = group.actions.includes("manage_members");
  const canManageManagers = group.actions.includes("manage_managers");
  const [previousAuthority, setPreviousAuthority] = useState(() => ({
    canManageMembers,
    canManageManagers,
  }));

  if (
    previousAuthority.canManageMembers !== canManageMembers ||
    previousAuthority.canManageManagers !== canManageManagers
  ) {
    setPreviousAuthority({ canManageMembers, canManageManagers });
    if (!canManageMembers) {
      setAdding(false);
      setSelectedCandidates(new Set());
      setCandidateSearch("");
      setCandidateSearchDraft("");
      setCandidatePage(0);
    }
    if (
      (previousAuthority.canManageMembers && !canManageMembers) ||
      (previousAuthority.canManageManagers && !canManageManagers)
    ) {
      setActionError(null);
    }
  }

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
    if (!canManageMembers || selectedCandidates.size === 0 || addMembers.isPending) return;
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
    if (
      !canManageMembers ||
      member.protectedOwner ||
      (member.isManager && (member.actorId === currentActorId || !canManageManagers))
    )
      return;
    setActionError(null);
    await removeMember.mutateAsync({
      path: { groupId: group.id, actorId: member.actorId },
      headers: sameOriginMutationHeaders,
    });
    addButtonRef.current?.focus();
    await onAuthorityChanged();
  }

  async function changeManager(member: GroupMember) {
    if (
      !canManageManagers ||
      member.protectedOwner ||
      (member.isManager && member.actorId === currentActorId)
    )
      return;
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
    <section
      aria-labelledby="group-members-heading"
      className="group-detail-members min-w-0 border-t border-border-subtle pt-5"
    >
      <h2 id="group-members-heading" className="mb-4 font-heading-h3 text-content-primary">
        {ui("Group Members")}
      </h2>
      <div className="flex min-w-0 items-center gap-2">
        <form
          role="search"
          className="relative min-w-0 flex-1"
          onSubmit={(event) => {
            event.preventDefault();
            if (adding) {
              setCandidateSearch(candidateSearchDraft.trim());
              setCandidatePage(0);
            } else {
              setSearch(searchDraft.trim());
              setPage(0);
            }
          }}
        >
          <Search
            className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-content-muted"
            aria-hidden="true"
          />
          <Input
            type="search"
            value={adding ? candidateSearchDraft : searchDraft}
            aria-label={adding ? ui("Search member candidates") : ui("Search group members")}
            maxLength={200}
            placeholder={adding ? ui("Search users…") : ui("Search members…")}
            className="h-9 bg-surface-sunken pl-9"
            onChange={(event) =>
              adding
                ? setCandidateSearchDraft(event.target.value)
                : setSearchDraft(event.target.value)
            }
          />
          <button type="submit" className="sr-only">
            {adding ? ui("Search candidates") : ui("Search members")}
          </button>
        </form>
        {adding && canManageMembers ? (
          <Button
            size="sm"
            disabled={
              selectedCandidates.size === 0 || candidates.isPending || candidates.isError || busy
            }
            pending={addMembers.isPending}
            onClick={() => void addSelectedMembers()}
          >
            {ui("Add")} {selectedCandidates.size > 0 ? selectedCandidates.size : ui("selected")}
          </Button>
        ) : null}
        {canManageMembers ? (
          <Button
            ref={addButtonRef}
            size="sm"
            prominence={adding ? "secondary" : "tertiary"}
            className="shrink-0"
            disabled={busy}
            onClick={() => {
              setAdding((current) => !current);
              setSelectedCandidates(new Set());
              setActionError(null);
            }}
          >
            <CirclePlus aria-hidden="true" />
            {adding ? ui("Done") : ui("Add")}
          </Button>
        ) : null}
      </div>

      {actionError ? (
        <p
          role="alert"
          className="mt-4 rounded-lg bg-status-danger-surface px-4 py-3 font-secondary-body text-status-danger-content"
        >
          {ui(actionError)}
        </p>
      ) : null}

      {adding && canManageMembers ? (
        <div className="mt-3 min-w-0">
          {candidates.isPending ? (
            <LoadingRows label={ui("Loading eligible users")} />
          ) : candidates.isError ? (
            <InlineError
              label={ui("Eligible users could not be loaded.")}
              onRetry={() => void candidates.refetch()}
            />
          ) : candidateRows.length === 0 ? (
            <EmptyRows
              title={
                candidateSearch
                  ? ui("No eligible users found")
                  : ui("Everyone eligible is already a member")
              }
              detail={
                candidateSearch
                  ? ui("Try another name or email.")
                  : ui("There are no more users to add to this group.")
              }
            />
          ) : (
            <div className="mt-3 divide-y divide-border-subtle overflow-hidden rounded-lg border border-border-subtle bg-surface-sunken">
              {candidateRows.map((candidate) => {
                const checked = selectedCandidates.has(candidate.actorId);
                return (
                  <label
                    key={candidate.actorId}
                    className="flex min-w-0 cursor-pointer items-center gap-2 px-3 py-2 transition-colors hover:bg-surface-subtle has-[:focus-visible]:ring-3 has-[:focus-visible]:ring-inset has-[:focus-visible]:ring-focus-ring/30"
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
          {candidatesPage && !candidates.isError ? (
            <Pagination
              label={ui("Candidate pages")}
              page={candidatesPage.page}
              pageSize={candidatesPage.size}
              itemCount={candidateRows.length}
              totalItems={candidatesPage.totalItems}
              totalPages={candidatesPage.totalPages}
              disabled={candidates.isFetching}
              onPageChange={setCandidatePage}
            />
          ) : null}
        </div>
      ) : (
        <>
          {members.isPending ? (
            <LoadingRows label={ui("Loading members")} />
          ) : members.isError ? (
            <InlineError
              label={ui("Members could not be loaded.")}
              onRetry={() => void members.refetch()}
            />
          ) : rows.length === 0 ? (
            <EmptyRows
              title={search ? ui("No members found") : ui("No members")}
              detail={
                search
                  ? ui("Try another name or email.")
                  : ui("Add an eligible Tenant user to this group.")
              }
            />
          ) : (
            <div className="mt-3 min-w-0">
              <table
                className="w-full table-fixed border-separate border-spacing-x-0 border-spacing-y-1"
                aria-busy={members.isFetching}
              >
                <caption className="sr-only">
                  {ui("Members of")} {group.name}
                </caption>
                <colgroup>
                  <col />
                  <col className="w-28 sm:w-40" />
                  <col className={canManageManagers ? "w-16 sm:w-20" : "w-10 sm:w-12"} />
                </colgroup>
                <thead className="text-left">
                  <tr>
                    <th
                      scope="col"
                      className="h-8 px-3 font-secondary-action text-content-secondary"
                    >
                      {ui("Name")}
                    </th>
                    <th
                      scope="col"
                      className="h-8 px-2 font-secondary-action text-content-secondary"
                    >
                      {ui("Account Type")}
                    </th>
                    <th
                      scope="col"
                      className="h-8 px-2 font-secondary-action text-content-secondary"
                    >
                      <span className="sr-only">{ui("Actions")}</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((member) => {
                    const name = member.email?.trim() || ui("member without email");
                    const managerPending =
                      (assignManager.isPending &&
                        assignManager.variables?.path.actorId === member.actorId) ||
                      (removeManager.isPending &&
                        removeManager.variables?.path.actorId === member.actorId);
                    const ownManager = member.isManager && member.actorId === currentActorId;
                    return (
                      <tr
                        key={member.actorId}
                        className="bg-surface-sunken transition-colors hover:bg-surface-subtle"
                      >
                        <td className="h-11 rounded-l-lg px-3 py-2">
                          <MemberIdentity member={member} />
                        </td>
                        <td className="px-2 py-2">
                          <MemberAccount member={member} />
                        </td>
                        <td className="rounded-r-lg px-1 py-2 sm:px-2">
                          <div className="flex flex-wrap items-center justify-end gap-0.5">
                            {canManageManagers ? (
                              <ConfirmDialog
                                trigger={
                                  <IconButton
                                    size="sm"
                                    disabled={busy || member.protectedOwner || ownManager}
                                    pending={managerPending}
                                    aria-label={ui("{{v1}} for {{v2}}", {
                                      v1: ui(member.isManager ? "Remove manager" : "Make manager"),
                                      v2: name,
                                    })}
                                  >
                                    <OnyxUserShieldIcon />
                                  </IconButton>
                                }
                                title={ui(
                                  member.isManager
                                    ? "Remove manager access from {{name}}?"
                                    : "Make {{name}} a manager?",
                                  { name },
                                )}
                                description={
                                  member.isManager
                                    ? ui(
                                        "Their group-scoped management access ends on the next authorized request.",
                                      )
                                    : ui(
                                        "They will be able to maintain this group’s ordinary membership and access associated Sources within their granted scope.",
                                      )
                                }
                                confirmLabel={
                                  member.isManager ? ui("Remove manager") : ui("Make manager")
                                }
                                pendingLabel={ui("Updating manager…")}
                                confirmTone={member.isManager ? "danger" : "default"}
                                onConfirm={() => changeManager(member)}
                                errorMessage={(cause) => groupMutationError(cause, "manager")}
                              />
                            ) : null}
                            {canManageMembers && (!member.isManager || canManageManagers) ? (
                              <ConfirmDialog
                                trigger={
                                  <IconButton
                                    size="sm"
                                    prominence="tertiary"
                                    disabled={busy || member.protectedOwner || ownManager}
                                    aria-label={ui("Remove {{v1}} from {{v2}}", {
                                      v1: name,
                                      v2: group.name,
                                    })}
                                  >
                                    <CircleMinus />
                                  </IconButton>
                                }
                                successFocusRef={addButtonRef}
                                fallbackFocusRef={addButtonRef}
                                title={ui("Remove {{v1}}?", { v1: name })}
                                description={ui(
                                  "They will leave “{{v1}}”. Other group memberships and their Tenant account stay unchanged.",
                                  { v1: group.name },
                                )}
                                confirmLabel={ui("Remove member")}
                                pendingLabel={ui("Removing member…")}
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

          {pageData && !members.isError ? (
            <Pagination
              label={ui("Member pages")}
              page={pageData.page}
              pageSize={pageData.size}
              itemCount={rows.length}
              totalItems={pageData.totalItems}
              totalPages={pageData.totalPages}
              disabled={members.isFetching}
              onPageChange={setPage}
            />
          ) : null}
        </>
      )}
    </section>
  );
}

function MemberIdentity({ member }: { member: GroupMember }) {
  const ui = useAppTranslation();
  const email = member.email?.trim() || ui("Email unavailable");
  return (
    <span
      className="block min-w-0 flex-1 truncate font-main-ui-body text-content-primary"
      title={email}
    >
      {email}
    </span>
  );
}
function MemberAccount({ member }: { member: GroupMember }) {
  const ui = useAppTranslation();

  return (
    <span className="inline-flex shrink-0 items-center gap-1.5 font-main-ui-body text-content-secondary">
      <OnyxUserIcon className="size-4 text-content-muted" aria-hidden="true" />
      {member.accountType === "STANDARD" ? ui("Standard") : member.accountType}
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
  const ui = useAppTranslation();

  return (
    <div className="mt-4 rounded-xl border border-border-subtle px-4 py-5">
      <p role="alert" className="font-main-ui-body text-content-secondary">
        {label}
      </p>
      <Button size="sm" prominence="secondary" className="mt-3" onClick={onRetry}>
        {ui("Try again")}
      </Button>
    </div>
  );
}

function EmptyRows({ title, detail }: { title: string; detail: string }) {
  return (
    <div className="mt-4 rounded-xl border border-dashed border-border-default px-4 py-8 text-center">
      <OnyxUsersIcon className="mx-auto size-5 text-content-muted" aria-hidden="true" />
      <p className="mt-2 font-main-ui-action text-content-primary">{title}</p>
      <p className="mt-1 font-secondary-body text-content-muted">{detail}</p>
    </div>
  );
}

function Pagination({
  label,
  page,
  pageSize,
  itemCount,
  totalItems,
  totalPages,
  disabled,
  onPageChange,
}: {
  label: string;
  page: number;
  pageSize: number;
  itemCount: number;
  totalItems: number;
  totalPages: number;
  disabled: boolean;
  onPageChange: (page: number) => void;
}) {
  const ui = useAppTranslation();
  const firstItem = itemCount === 0 ? 0 : page * pageSize + 1;
  const lastItem = itemCount === 0 ? 0 : page * pageSize + itemCount;
  return (
    <nav aria-label={label} className="mt-3 flex flex-wrap items-center justify-between gap-2">
      <span className="font-secondary-body tabular-nums text-content-secondary" aria-live="polite">
        {ui("Showing {{first}}–{{last}} of {{total}}", {
          first: firstItem,
          last: lastItem,
          total: totalItems,
        })}
      </span>
      <div className="flex items-center gap-1">
        <IconButton
          size="sm"
          aria-label={ui("Previous page")}
          disabled={disabled || page === 0}
          onClick={() => onPageChange(page - 1)}
        >
          <ChevronLeft />
        </IconButton>
        <span
          className="min-w-8 rounded-lg bg-surface-subtle px-2 py-1.5 text-center font-secondary-body tabular-nums text-content-secondary"
          aria-label={ui("Page {{v1}} of {{v2}}", { v1: page + 1, v2: Math.max(totalPages, 1) })}
          aria-current="page"
        >
          {page + 1}
        </span>
        <IconButton
          size="sm"
          aria-label={ui("Next page")}
          disabled={disabled || page + 1 >= totalPages}
          onClick={() => onPageChange(page + 1)}
        >
          <ChevronRight />
        </IconButton>
      </div>
    </nav>
  );
}
