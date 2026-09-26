import { useAppTranslation } from "@/i18n/use-app-translation";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useSearch } from "@tanstack/react-router";
import { CirclePlus, Search, SearchX, Users, WifiOff } from "lucide-react";
import { useEffect, useEffectEvent, useRef, useState } from "react";
import { EmptyState } from "@/components/composites/empty-state";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { Alert, AlertAction, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Field, FieldLabel } from "@/components/ui/field";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import { NativeSelect } from "@/components/ui/native-select";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { TextButton } from "@/components/ui/text-button";
import { TablePagination } from "@/components/ui/table-pagination";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { useGlobalCapability } from "@/features/identity/application-session-context";
import { listGroupsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import { GroupCard } from "./group-card";
import type { GroupsSearch } from "./groups-search";

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

  // Typing applies the search once it pauses. Only a settled draft navigates: the Clear button
  // or history navigation changes the applied search at once, and must not be undone.
  const settledSearch = useDebouncedValue(searchDraft.trim(), 250);
  const applySettledSearch = useEffectEvent((nextSearch: string) => {
    if (nextSearch === appliedSearch || nextSearch !== searchDraft.trim()) return;
    void navigate({
      replace: true,
      search: (current) => ({ ...current, search: nextSearch || undefined, page: 0 }),
    });
  });
  useEffect(() => applySettledSearch(settledSearch), [settledSearch]);

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
    <SettingsLayout>
      <PageHeader
        icon={<Users />}
        title={ui("Groups")}
        description={ui("Groups carry permissions and the Sources their members may read.")}
        titleRef={headingRef}
      />

      <form
        role="search"
        className="flex items-center gap-3"
        onSubmit={(event) => {
          event.preventDefault();
          const nextSearch = searchDraft.trim();
          updateView({ search: nextSearch || undefined }, true);
        }}
      >
        <InputGroup className="min-w-0 flex-1">
          <InputGroupAddon>
            <Search aria-hidden="true" />
          </InputGroupAddon>
          <InputGroupInput
            type="search"
            value={searchDraft}
            maxLength={200}
            placeholder={ui("Search groups…")}
            aria-label={ui("Search groups")}
            onChange={(event) => setDraft({ applied: appliedSearch, value: event.target.value })}
          />
        </InputGroup>
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
          <Button asChild size="sm" className="shrink-0">
            <Link to="/admin/groups/new">
              {ui("New Group")}
              <CirclePlus data-icon="inline-end" aria-hidden="true" />
            </Link>
          </Button>
        ) : null}
      </form>

      <div className="flex flex-col gap-4" aria-busy={groups.isFetching}>
        <span className="sr-only" aria-live="polite">
          {groups.isFetching
            ? ui("Updating groups")
            : page
              ? ui("{{v1}} groups", { v1: page.totalItems })
              : ""}
        </span>
        {groups.isError && page ? (
          <Alert variant="warning">
            <AlertDescription>
              {ui("Could not refresh groups. Showing previous results.")}
            </AlertDescription>
            <AlertAction>
              <Button size="sm" prominence="tertiary" onClick={() => void groups.refetch()}>
                {ui("Retry refresh")}
              </Button>
            </AlertAction>
          </Alert>
        ) : null}

        {groups.isPending ? (
          <GroupsLoading />
        ) : groups.isError && !page ? (
          <EmptyState
            role="alert"
            icon={<WifiOff />}
            title={ui("Groups unavailable")}
            detail={ui("The authorized group list could not be loaded.")}
            action={
              <Button size="sm" prominence="secondary" onClick={() => void groups.refetch()}>
                {ui("Try again")}
              </Button>
            }
          />
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
          <div className="flex flex-col gap-2">
            {systemGroups.map((group) => (
              <GroupCard key={group.id} group={group} onAuthorityChanged={refreshAuthorityViews} />
            ))}
            {systemGroups.length > 0 && ordinaryGroups.length > 0 ? (
              <Separator className="my-2" />
            ) : null}
            {ordinaryGroups.map((group) => (
              <GroupCard key={group.id} group={group} onAuthorityChanged={refreshAuthorityViews} />
            ))}
          </div>
        )}
      </div>

      {page &&
      page.totalItems > 0 &&
      (page.totalPages > 1 || search.page > 0 || search.size !== 20) ? (
        <TablePagination
          label={ui("Group pages")}
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
          <Field orientation="horizontal" className="w-auto">
            <FieldLabel htmlFor="groups-page-size">{ui("Rows")}</FieldLabel>
            <NativeSelect
              id="groups-page-size"
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
            </NativeSelect>
          </Field>
        </TablePagination>
      ) : null}
    </SettingsLayout>
  );
}

function GroupsLoading() {
  const ui = useAppTranslation();

  return (
    <div role="status" aria-label={ui("Loading groups")} className="flex flex-col gap-3">
      {Array.from({ length: 4 }, (_, index) => (
        <Skeleton key={index} className="h-20" />
      ))}
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
    <EmptyState
      icon={filtered ? <SearchX /> : <Users />}
      title={filtered ? ui("No groups found") : ui("No groups yet")}
      detail={
        filtered
          ? ui("Try another name or clear the search.")
          : ui("Create an ordinary group to organize members and access.")
      }
      action={
        filtered ? (
          <Button size="sm" prominence="secondary" onClick={onClear}>
            {ui("Clear search")}
          </Button>
        ) : canCreate ? (
          <Button asChild size="sm">
            <Link to="/admin/groups/new">{ui("New group")}</Link>
          </Button>
        ) : undefined
      }
    />
  );
}
