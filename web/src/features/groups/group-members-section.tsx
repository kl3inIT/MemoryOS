import { useAppTranslation } from "@/i18n/use-app-translation";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { CircleMinus, CirclePlus, Search, ShieldUser } from "lucide-react";
import { useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import {
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Checkbox } from "@/components/ui/checkbox";
import {
  listGroupCandidatesOptions,
  listGroupMembersOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { GroupMember, GroupSummary } from "@/lib/hey-api/types.gen";
import { groupMutationError } from "./group-errors";
import {
  EmptyRows,
  InlineError,
  LoadingRows,
  MemberAccount,
  MemberIdentity,
  Pagination,
} from "./group-member-list-parts";
import type { GroupMembersDraft } from "./group-members-draft";

/** The Group's members; edits stay in the page's draft until Save Changes. */
export function GroupMembersSection({
  group,
  draft,
}: {
  group: GroupSummary;
  draft: GroupMembersDraft;
}) {
  const ui = useAppTranslation();

  const { canManageMembers, canManageManagers, currentActorId } = draft;
  const [search, setSearch] = useState("");
  const [searchDraft, setSearchDraft] = useState("");
  const [page, setPage] = useState(0);
  const [adding, setAdding] = useState(false);
  const [candidateSearch, setCandidateSearch] = useState("");
  const [candidateSearchDraft, setCandidateSearchDraft] = useState("");
  const [candidatePage, setCandidatePage] = useState(0);
  const [selectedCandidates, setSelectedCandidates] = useState<Set<string>>(() => new Set());
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
    enabled: adding && canManageMembers,
    placeholderData: keepPreviousData,
    retry: false,
  });

  const [previousState, setPreviousState] = useState(() => ({
    canManageMembers,
    generation: draft.generation,
  }));
  if (
    previousState.canManageMembers !== canManageMembers ||
    previousState.generation !== draft.generation
  ) {
    setPreviousState({ canManageMembers, generation: draft.generation });
    if (previousState.generation !== draft.generation) {
      setSelectedCandidates(new Set());
      setAdding(false);
    }
    if (!canManageMembers) {
      setAdding(false);
      setSelectedCandidates(new Set());
      setCandidateSearch("");
      setCandidateSearchDraft("");
      setCandidatePage(0);
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

  function addSelectedMembers() {
    if (!canManageMembers || selectedCandidates.size === 0 || busy) return;
    draft.add(candidateRows.filter((candidate) => selectedCandidates.has(candidate.actorId)));
    setSelectedCandidates(new Set());
    setAdding(false);
    addButtonRef.current?.focus();
  }

  async function removeSelectedMember(member: GroupMember) {
    draft.remove(member);
    addButtonRef.current?.focus();
  }

  const pageData = members.data;
  const baseRows = (pageData?.items ?? []).flatMap((member) => {
    const row = draft.staged(member);
    return row ? [row] : [];
  });
  const stagedRows =
    page === 0
      ? [...draft.added.values()]
          .filter(
            (member) =>
              !search || member.email?.toLocaleLowerCase().includes(search.toLocaleLowerCase()),
          )
          .flatMap((member) => {
            const row = draft.staged(member);
            return row ? [row] : [];
          })
      : [];
  const rows = [...stagedRows, ...baseRows];
  const candidatesPage = candidates.data;
  const candidateRows = (candidatesPage?.items ?? []).filter(
    (candidate) => !draft.added.has(candidate.actorId),
  );
  const busy = draft.pending;
  const actionError = draft.error;

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
            pending={false}
            onClick={addSelectedMembers}
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
              draft.clearError();
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
                    <Checkbox
                      checked={checked}
                      onCheckedChange={() => {
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
            <div className="mt-3 min-w-0 overflow-hidden rounded-lg border border-border-subtle bg-surface-sunken">
              <Table className="w-full table-fixed" aria-busy={members.isFetching}>
                <TableCaption className="sr-only">
                  {ui("Members of")} {group.name}
                </TableCaption>
                <colgroup>
                  <col />
                  <col className="w-28 sm:w-40" />
                  <col className={canManageManagers ? "w-16 sm:w-20" : "w-10 sm:w-12"} />
                </colgroup>
                <TableHeader className="text-left">
                  <TableRow>
                    <TableHead
                      scope="col"
                      className="h-8 px-3 font-secondary-action text-content-secondary"
                    >
                      {ui("Name")}
                    </TableHead>
                    <TableHead
                      scope="col"
                      className="h-8 px-2 font-secondary-action text-content-secondary"
                    >
                      {ui("Account Type")}
                    </TableHead>
                    <TableHead
                      scope="col"
                      className="h-8 px-2 font-secondary-action text-content-secondary"
                    >
                      <span className="sr-only">{ui("Actions")}</span>
                    </TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {rows.map((member) => {
                    const name = member.email?.trim() || ui("member without email");
                    const managerPending = draft.managerPendingFor(member.actorId);
                    const ownManager = member.isManager && member.actorId === currentActorId;
                    return (
                      <TableRow
                        key={member.actorId}
                        className="border-b border-border-subtle transition-colors last:border-b-0 hover:bg-surface-subtle"
                      >
                        <TableCell className="h-11 px-3 py-2">
                          <MemberIdentity member={member} />
                        </TableCell>
                        <TableCell className="px-2 py-2">
                          <MemberAccount member={member} />
                        </TableCell>
                        <TableCell className="px-1 py-2 sm:px-2">
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
                                    <ShieldUser />
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
                                onConfirm={async () => draft.toggleManager(member)}
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
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
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
