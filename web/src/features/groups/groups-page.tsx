import { useAppTranslation } from "@/i18n/use-app-translation";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useSearch } from "@tanstack/react-router";
import { CirclePlus, ExternalLink, Info, Search, SearchX, WifiOff } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { OnyxUsersIcon } from "@/components/icons/identity-icons";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { TextButton } from "@/components/ui/text-button";
import { TablePagination } from "@/components/ui/table-pagination";
import { useGlobalCapability } from "@/features/identity/application-session-context";
import { listGroupsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import { GroupCard } from "./group-card";
import type { GroupsSearch } from "./groups-search";
import "./groups-list.css";

export function GroupsPage() {
  const ui = useAppTranslation();

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

  useEffect(() => {
    const nextSearch = searchDraft.trim();
    if (nextSearch === appliedSearch) return;
    const timeout = window.setTimeout(() => {
      void navigate({
        replace: true,
        search: (current) => ({ ...current, search: nextSearch || undefined, page: 0 }),
      });
    }, 250);
    return () => window.clearTimeout(timeout);
  }, [searchDraft, appliedSearch, navigate]);

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
    <section className="groups-list-page min-h-full px-5 py-12 sm:px-8">
      <div className="mx-auto w-full max-w-[840px]">
        <header className="border-b border-border-subtle pb-6">
          <OnyxUsersIcon className="size-8 text-content-secondary" aria-hidden="true" />
          <h1
            ref={headingRef}
            tabIndex={-1}
            className="mt-2 text-2xl font-semibold leading-8 text-content-primary"
          >
            {ui("Groups")}
          </h1>
          <div className="groups-list-info mt-7 flex flex-col gap-3 px-3 py-2 sm:flex-row sm:items-start">
            <Info
              className="mt-1 size-4 shrink-0 text-[var(--groups-info-icon)]"
              aria-hidden="true"
            />
            <div className="min-w-0 flex-1 py-1">
              <p className="text-sm font-semibold leading-5 text-content-primary">
                {ui("Permissions have changed")}
              </p>
              <p className="mt-0.5 text-xs leading-4 text-content-secondary">
                {ui(
                  "MemoryOS uses group-based permissions. Access is configured per group, so a user’s permissions are the combination of every group they belong to.",
                )}
              </p>
            </div>
            <Button asChild size="sm" className="groups-list-action shrink-0 self-start">
              <a
                href="https://github.com/kl3inIT/MemoryOS/blob/main/docs/specs/identity.md#account-classification-and-group-authority"
                target="_blank"
                rel="noopener noreferrer"
              >
                <ExternalLink className="size-4" aria-hidden="true" />
                {ui("Learn more")}
              </a>
            </Button>
          </div>
        </header>

        <form
          role="search"
          className="mt-6 flex items-center gap-3"
          onSubmit={(event) => {
            event.preventDefault();
            const nextSearch = searchDraft.trim();
            updateView({ search: nextSearch || undefined }, true);
          }}
        >
          <label className="relative min-w-0 flex-1">
            <span className="sr-only">{ui("Search groups")}</span>
            <Search
              className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-content-muted"
              aria-hidden="true"
            />
            <Input
              type="search"
              value={searchDraft}
              maxLength={200}
              placeholder={ui("Search groups…")}
              className="border-transparent bg-transparent pl-9 shadow-none hover:border-transparent focus-visible:border-transparent"
              onChange={(event) => setDraft({ applied: appliedSearch, value: event.target.value })}
            />
          </label>
          <button type="submit" className="sr-only">
            {ui("Search groups")}
          </button>
          {search.search ? (
            <TextButton
              onClick={() => {
                setDraft({ applied: appliedSearch, value: "" });
                updateView({ search: undefined }, true);
              }}
            >
              {ui("Clear")}
            </TextButton>
          ) : null}
          {canCreate ? (
            <Button asChild size="sm" className="groups-list-action shrink-0">
              <Link to="/admin/groups/new">
                {ui("New Group")}
                <CirclePlus className="size-4" aria-hidden="true" />
              </Link>
            </Button>
          ) : null}
        </form>

        <div className="mt-10" aria-busy={groups.isFetching}>
          <span className="sr-only" aria-live="polite">
            {groups.isFetching
              ? ui("Updating groups")
              : page
                ? ui("{{v1}} groups", { v1: page.totalItems })
                : ""}
          </span>
          {groups.isError && page ? (
            <div className="mb-4 flex flex-col gap-2 rounded-xl border border-status-warning-content/20 bg-status-warning-surface px-4 py-3 font-secondary-body text-status-warning-content sm:flex-row sm:items-center sm:justify-between">
              <span>{ui("Could not refresh groups. Showing previous results.")}</span>
              <TextButton size="sm" onClick={() => void groups.refetch()}>
                {ui("Retry refresh")}
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
            <div className="space-y-2">
              {systemGroups.map((group) => (
                <GroupCard
                  key={group.id}
                  group={group}
                  onAuthorityChanged={refreshAuthorityViews}
                />
              ))}
              {systemGroups.length > 0 && ordinaryGroups.length > 0 ? (
                <div role="separator" className="my-4 border-t border-border-subtle" />
              ) : null}
              {ordinaryGroups.map((group) => (
                <GroupCard
                  key={group.id}
                  group={group}
                  onAuthorityChanged={refreshAuthorityViews}
                />
              ))}
            </div>
          )}
        </div>

        {page &&
        page.totalItems > 0 &&
        (page.totalPages > 1 || search.page > 0 || search.size !== 20) ? (
          <TablePagination
            label={ui("Group pages")}
            className="mt-6 px-0 pt-4"
            page={search.page}
            totalPages={page.totalPages}
            summary={ui("Showing {{first}}–{{last}} of {{total}}", {
              first: page.totalItems === 0 ? 0 : search.page * search.size + 1,
              last: Math.min((search.page + 1) * search.size, page.totalItems),
              total: page.totalItems,
            })}
            previousDisabled={search.page === 0}
            nextDisabled={search.page + 1 >= page.totalPages}
            onPrevious={() => updateView({ page: search.page - 1 })}
            onNext={() => updateView({ page: search.page + 1 })}
          >
            <label className="flex items-center gap-2 font-secondary-body text-content-secondary">
              {ui("Rows")}
              <Select
                size="sm"
                value={search.size}
                className="w-auto px-2"
                aria-label={ui("Groups per page")}
                onChange={(event) =>
                  updateView({ size: Number(event.target.value) as GroupsSearch["size"] }, true)
                }
              >
                <option value={20}>20</option>
                <option value={50}>50</option>
                <option value={100}>100</option>
              </Select>
            </label>
          </TablePagination>
        ) : null}
      </div>
    </section>
  );
}

function GroupsLoading() {
  const ui = useAppTranslation();

  return (
    <div role="status" aria-label={ui("Loading groups")} className="space-y-3">
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
  const ui = useAppTranslation();

  return (
    <div className="rounded-xl border border-border-subtle px-6 py-16 text-center">
      <WifiOff className="mx-auto size-5 text-content-muted" aria-hidden="true" />
      <h2 className="mt-3 font-heading-h3 text-content-primary">{ui("Groups unavailable")}</h2>
      <p className="mt-2 font-main-ui-body text-content-muted">
        {ui("The authorized group list could not be loaded.")}
      </p>
      <Button size="sm" prominence="secondary" className="mt-5" onClick={onRetry}>
        {ui("Try again")}
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
  const ui = useAppTranslation();

  return (
    <div className="rounded-xl border border-dashed border-border-default px-6 py-16 text-center">
      {filtered ? (
        <SearchX className="mx-auto size-5 text-content-muted" aria-hidden="true" />
      ) : (
        <OnyxUsersIcon className="mx-auto size-5 text-content-muted" aria-hidden="true" />
      )}
      <h2 className="mt-3 font-heading-h3 text-content-primary">
        {filtered ? ui("No groups found") : ui("No groups yet")}
      </h2>
      <p className="mt-2 font-main-ui-body text-content-muted">
        {filtered
          ? ui("Try another name or clear the search.")
          : ui("Create an ordinary group to organize members and access.")}
      </p>
      {filtered ? (
        <Button size="sm" prominence="secondary" className="mt-5" onClick={onClear}>
          {ui("Clear search")}
        </Button>
      ) : canCreate ? (
        <Button asChild size="sm" className="mt-5">
          <Link to="/admin/groups/new">{ui("New group")}</Link>
        </Button>
      ) : null}
    </div>
  );
}
