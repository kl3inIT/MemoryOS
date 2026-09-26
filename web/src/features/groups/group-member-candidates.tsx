import { useAppTranslation } from "@/i18n/use-app-translation";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Checkbox } from "@/components/ui/checkbox";
import { Field, FieldLabel } from "@/components/ui/field";
import { TablePagination } from "@/components/ui/table-pagination";
import { listGroupCandidatesOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { GroupMember, GroupSummary } from "@/lib/hey-api/types.gen";
import {
  EmptyRows,
  InlineError,
  LoadingRows,
  MemberAccount,
  MemberIdentity,
  PageSummary,
} from "./group-member-list-parts";

const pageSize = 10;

/** Tenant users who may join the Group, a page at a time, each chosen with a checkbox. */
export function GroupMemberCandidates({
  group,
  search,
  excluded,
  selected,
  onToggle,
}: {
  group: GroupSummary;
  /** The settled search text. */
  search: string;
  /** People the draft already adds. */
  excluded: ReadonlyMap<string, GroupMember>;
  selected: ReadonlyMap<string, GroupMember>;
  onToggle: (candidate: GroupMember) => void;
}) {
  const ui = useAppTranslation();
  // A new search starts again at its first page.
  const [paged, setPaged] = useState({ search, page: 0 });
  const page = paged.search === search ? paged.page : 0;
  const candidates = useQuery({
    ...listGroupCandidatesOptions({
      path: { groupId: group.id },
      query: { search: search || undefined, page, size: pageSize },
    }),
    placeholderData: keepPreviousData,
    retry: false,
  });
  const totalPages = candidates.data?.totalPages;
  if (
    !candidates.isPlaceholderData &&
    totalPages !== undefined &&
    page > Math.max(totalPages - 1, 0)
  )
    setPaged({ search, page: Math.max(totalPages - 1, 0) });
  const rows = (candidates.data?.items ?? []).filter(
    (candidate) => !excluded.has(candidate.actorId),
  );

  if (candidates.isPending) return <LoadingRows label={ui("Loading eligible users")} />;
  if (candidates.isError)
    return (
      <InlineError
        label={ui("Eligible users could not be loaded.")}
        onRetry={() => void candidates.refetch()}
      />
    );
  const data = candidates.data;
  return (
    <div className="flex min-w-0 flex-col gap-3">
      {rows.length === 0 ? (
        <EmptyRows
          title={
            search ? ui("No eligible users found") : ui("Everyone eligible is already a member")
          }
          detail={
            search
              ? ui("Try another name or email.")
              : ui("There are no more users to add to this group.")
          }
        />
      ) : (
        <div className="divide-y divide-border-subtle overflow-hidden rounded-lg border border-border-subtle bg-surface-sunken">
          {rows.map((candidate) => {
            const id = `group-candidate-${candidate.actorId}`;
            return (
              <div key={candidate.actorId} className="px-3 py-2">
                <Field orientation="horizontal">
                  <Checkbox
                    id={id}
                    checked={selected.has(candidate.actorId)}
                    onCheckedChange={() => onToggle(candidate)}
                  />
                  <FieldLabel htmlFor={id} className="min-w-0 flex-1">
                    <MemberIdentity member={candidate} />
                    <MemberAccount member={candidate} />
                  </FieldLabel>
                </Field>
              </div>
            );
          })}
        </div>
      )}
      <TablePagination
        label={ui("Candidate pages")}
        page={page}
        totalPages={data.totalPages}
        summary={<PageSummary page={data} shown={rows.length} />}
        previousDisabled={candidates.isFetching || page === 0}
        nextDisabled={candidates.isFetching || page + 1 >= data.totalPages}
        onPrevious={() => setPaged({ search, page: page - 1 })}
        onNext={() => setPaged({ search, page: page + 1 })}
      />
    </div>
  );
}
