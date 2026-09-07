import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useSearch } from "@tanstack/react-router";
import { Plus, Search, SearchX, ShieldCheck, UsersRound, WifiOff } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { TextButton } from "@/components/ui/text-button";
import { useGlobalCapability } from "@/features/identity/application-session-context";
import { listGroupsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import { GroupCard } from "./group-card";
import type { GroupsSearch } from "./groups-search";

export function GroupsPage() {
  const queryClient = useQueryClient();
  const headingRef = useRef<HTMLHeadingElement>(null);
  const search = useSearch({ from: "/_authenticated/admin/groups/" });
  const navigate = useNavigate({ from: "/admin/groups/" });
  const canCreate = useGlobalCapability("GROUPS_MANAGE");
  const appliedSearch = search.search ?? "";
  const [draft, setDraft] = useState({ applied: appliedSearch, value: appliedSearch });
  const groups = useQuery({
    ...listGroupsOptions({ query: search }),
    placeholderData: keepPreviousData,
    retry: false,
  });

  if (draft.applied !== appliedSearch) {
    setDraft({ applied: appliedSearch, value: appliedSearch });
  }
  const searchDraft = draft.applied === appliedSearch ? draft.value : appliedSearch;

  useEffect(() => {
    const totalPages = groups.data?.totalPages;
    if (groups.isPlaceholderData || totalPages === undefined) return;
    const lastPage = Math.max(totalPages - 1, 0);
    if (search.page <= lastPage) return;
    void navigate({ replace: true, search: (current) => ({ ...current, page: lastPage }) });
  }, [groups.data?.totalPages, groups.isPlaceholderData, navigate, search.page]);

  function updateView(update: Partial<GroupsSearch>, resetPage = false) {
    void navigate({
      search: (current) => ({
        ...current,
        ...update,
        page: resetPage ? 0 : (update.page ?? current.page),
      }),
    });
  }

  async function refreshAuthorityViews() {
    headingRef.current?.focus();
    await queryClient.invalidateQueries();
  }

  const page = groups.data;
  const items = page?.items ?? [];
  const systemGroups = items.filter((group) => group.systemKey !== null);
  const ordinaryGroups = items.filter((group) => group.systemKey === null);

  return (
    <section className="mx-auto w-full max-w-[var(--page-width-standard)] px-5 py-8 sm:px-8 sm:py-10">
      <header className="flex flex-col gap-4 border-b border-border-subtle pb-5 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex min-w-0 items-center gap-3">
          <ShieldCheck className="size-6 shrink-0 text-content-secondary" aria-hidden="true" />
          <div>
            <h1 ref={headingRef} tabIndex={-1} className="font-heading-h2 text-content-primary">
              Groups
            </h1>
            <p className="mt-1 font-main-ui-body text-content-muted">
              Memberships, delegated managers, and working capabilities.
            </p>
          </div>
        </div>
        {canCreate ? (
          <Button asChild size="sm">
            <Link to="/admin/groups/new">
              <Plus aria-hidden="true" />
              New group
            </Link>
          </Button>
        ) : null}
      </header>

      <form
        role="search"
        className="mt-6 flex flex-col gap-2 sm:flex-row"
        onSubmit={(event) => {
          event.preventDefault();
          const nextSearch = searchDraft.trim();
          updateView({ search: nextSearch || undefined }, true);
        }}
      >
        <label className="relative min-w-0 flex-1">
          <span className="sr-only">Search groups</span>
          <Search
            className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-content-muted"
            aria-hidden="true"
          />
          <Input
            type="search"
            value={searchDraft}
            maxLength={200}
            placeholder="Search groups…"
            className="bg-surface-sunken pl-9"
            onChange={(event) => setDraft({ applied: appliedSearch, value: event.target.value })}
          />
        </label>
        <Button type="submit" prominence="secondary">
          Search
        </Button>
        {search.search ? (
          <TextButton
            onClick={() => {
              setDraft({ applied: appliedSearch, value: "" });
              updateView({ search: undefined }, true);
            }}
          >
            Clear
          </TextButton>
        ) : null}
      </form>

      <div className="mt-6" aria-busy={groups.isFetching}>
        <span className="sr-only" aria-live="polite">
          {groups.isFetching ? "Updating groups" : page ? `${page.totalItems} groups` : ""}
        </span>
        {groups.isError && page ? (
          <div className="mb-4 flex flex-col gap-2 rounded-xl border border-status-warning-content/20 bg-status-warning-surface px-4 py-3 font-secondary-body text-status-warning-content sm:flex-row sm:items-center sm:justify-between">
            <span>Could not refresh groups. Showing previous results.</span>
            <TextButton size="sm" onClick={() => void groups.refetch()}>
              Retry refresh
            </TextButton>
          </div>
        ) : null}

        {groups.isPending ? (
          <GroupsLoading />
        ) : groups.isError && !page ? (
          <GroupsError onRetry={() => void groups.refetch()} />
        ) : items.length === 0 ? (
          <GroupsEmpty
            filtered={Boolean(search.search)}
            canCreate={canCreate}
            onClear={() => {
              setDraft({ applied: appliedSearch, value: "" });
              updateView({ search: undefined }, true);
            }}
          />
        ) : (
          <div className="space-y-3">
            {systemGroups.map((group) => (
              <GroupCard key={group.id} group={group} onAuthorityChanged={refreshAuthorityViews} />
            ))}
            {systemGroups.length > 0 && ordinaryGroups.length > 0 ? (
              <div className="my-5 border-t border-border-subtle" />
            ) : null}
            {ordinaryGroups.map((group) => (
              <GroupCard key={group.id} group={group} onAuthorityChanged={refreshAuthorityViews} />
            ))}
          </div>
        )}
      </div>

      {page && page.totalItems > 0 ? (
        <nav
          aria-label="Group pages"
          className="mt-6 flex flex-col gap-3 border-t border-border-subtle pt-4 sm:flex-row sm:items-center sm:justify-between"
        >
          <p className="font-secondary-body tabular-nums text-content-muted">
            Showing {page.totalItems === 0 ? 0 : search.page * search.size + 1}–
            {Math.min((search.page + 1) * search.size, page.totalItems)} of {page.totalItems}
          </p>
          <div className="flex flex-wrap items-center gap-2">
            <label className="flex items-center gap-2 font-secondary-body text-content-secondary">
              Rows
              <Select
                size="sm"
                value={search.size}
                className="w-auto px-2"
                aria-label="Groups per page"
                onChange={(event) =>
                  updateView({ size: Number(event.target.value) as GroupsSearch["size"] }, true)
                }
              >
                <option value={20}>20</option>
                <option value={50}>50</option>
                <option value={100}>100</option>
              </Select>
            </label>
            <span className="min-w-24 text-center font-secondary-body tabular-nums text-content-secondary">
              Page {search.page + 1} of {Math.max(page.totalPages, 1)}
            </span>
            <Button
              size="sm"
              prominence="secondary"
              disabled={search.page === 0}
              onClick={() => updateView({ page: search.page - 1 })}
            >
              Previous
            </Button>
            <Button
              size="sm"
              prominence="secondary"
              disabled={search.page + 1 >= page.totalPages}
              onClick={() => updateView({ page: search.page + 1 })}
            >
              Next
            </Button>
          </div>
        </nav>
      ) : null}
    </section>
  );
}

function GroupsLoading() {
  return (
    <div role="status" aria-label="Loading groups" className="space-y-3">
      {Array.from({ length: 4 }, (_, index) => (
        <div
          key={index}
          className="flex items-center gap-3 rounded-xl border border-border-subtle p-5"
        >
          <Skeleton className="size-10 shrink-0 rounded-xl" />
          <div className="min-w-0 flex-1 space-y-2">
            <Skeleton className="h-5 w-40" />
            <Skeleton className="h-3 w-28" />
          </div>
          <Skeleton className="size-8" />
        </div>
      ))}
    </div>
  );
}

function GroupsError({ onRetry }: { onRetry: () => void }) {
  return (
    <div className="rounded-xl border border-border-subtle px-6 py-16 text-center">
      <WifiOff className="mx-auto size-5 text-content-muted" aria-hidden="true" />
      <h2 className="mt-3 font-heading-h3 text-content-primary">Groups unavailable</h2>
      <p className="mt-2 font-main-ui-body text-content-muted">
        The authorized group list could not be loaded.
      </p>
      <Button size="sm" prominence="secondary" className="mt-5" onClick={onRetry}>
        Try again
      </Button>
    </div>
  );
}

function GroupsEmpty({
  filtered,
  canCreate,
  onClear,
}: {
  filtered: boolean;
  canCreate: boolean;
  onClear: () => void;
}) {
  return (
    <div className="rounded-xl border border-dashed border-border-default px-6 py-16 text-center">
      {filtered ? (
        <SearchX className="mx-auto size-5 text-content-muted" aria-hidden="true" />
      ) : (
        <UsersRound className="mx-auto size-5 text-content-muted" aria-hidden="true" />
      )}
      <h2 className="mt-3 font-heading-h3 text-content-primary">
        {filtered ? "No groups found" : "No groups yet"}
      </h2>
      <p className="mt-2 font-main-ui-body text-content-muted">
        {filtered
          ? "Try another name or clear the search."
          : "Create an ordinary group to organize members and access."}
      </p>
      {filtered ? (
        <Button size="sm" prominence="secondary" className="mt-5" onClick={onClear}>
          Clear search
        </Button>
      ) : canCreate ? (
        <Button asChild size="sm" className="mt-5">
          <Link to="/admin/groups/new">New group</Link>
        </Button>
      ) : null}
    </div>
  );
}
