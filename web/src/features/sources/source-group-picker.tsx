import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Search, ShieldCheck, UsersRound } from "lucide-react";
import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { listSourceGroupOptionsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SourceGroup } from "@/lib/hey-api/types.gen";

type SourceGroupPickerProps = {
  selected: ReadonlySet<string>;
  knownGroups?: readonly SourceGroup[];
  required?: boolean;
  disabled?: boolean;
  onChange: (groupIds: Set<string>) => void;
};

export function SourceGroupPicker({
  selected,
  knownGroups = [],
  required = false,
  disabled = false,
  onChange,
}: SourceGroupPickerProps) {
  const [searchDraft, setSearchDraft] = useState("");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const options = useQuery({
    ...listSourceGroupOptionsOptions({ query: { search, page, size: 25 } }),
    placeholderData: keepPreviousData,
    retry: false,
  });

  const totalPages = options.data?.totalPages;
  if (!options.isPlaceholderData && totalPages !== undefined) {
    const lastPage = Math.max(totalPages - 1, 0);
    if (page > lastPage) setPage(lastPage);
  }

  const knownById = new Map(knownGroups.map((group) => [group.id, group]));
  for (const group of options.data?.items ?? []) knownById.set(group.id, group);
  const selectedGroups = [...selected]
    .map((groupId) => knownById.get(groupId))
    .filter((group): group is SourceGroup => Boolean(group));
  const rows = options.data?.items ?? [];

  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h3 className="font-secondary-action text-content-primary">Access groups</h3>
          <p className="mt-1 font-secondary-body text-content-muted">
            Selected groups can scope Source management; document-content access remains separate.
          </p>
        </div>
        <span className="font-secondary-body tabular-nums text-content-muted">
          {selected.size} selected
        </span>
      </div>

      {selectedGroups.length > 0 ? (
        <div className="mt-3 flex flex-wrap gap-1" aria-label="Selected Source groups">
          {selectedGroups.map((group) => (
            <Badge
              key={group.id}
              variant="secondary"
              className="bg-surface-subtle text-content-secondary"
            >
              {group.name}
            </Badge>
          ))}
          {selected.size > selectedGroups.length ? (
            <Badge variant="outline" className="text-content-muted">
              +{selected.size - selectedGroups.length}
            </Badge>
          ) : null}
        </div>
      ) : null}

      <form
        role="search"
        className="mt-3 flex gap-2"
        onSubmit={(event) => {
          event.preventDefault();
          setSearch(searchDraft.trim());
          setPage(0);
        }}
      >
        <label className="relative min-w-0 flex-1">
          <span className="sr-only">Search groups available to this Source</span>
          <Search
            className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-content-muted"
            aria-hidden="true"
          />
          <Input
            type="search"
            size="sm"
            disabled={disabled}
            value={searchDraft}
            maxLength={200}
            placeholder="Search groups…"
            className="bg-surface-sunken pl-9"
            onChange={(event) => setSearchDraft(event.target.value)}
          />
        </label>
        <Button type="submit" size="sm" prominence="secondary" disabled={disabled}>
          Search
        </Button>
      </form>

      {options.isPending ? (
        <p role="status" className="mt-4 px-2 py-5 font-main-ui-body text-content-muted">
          Loading groups
        </p>
      ) : options.isError ? (
        <div className="mt-4 rounded-xl border border-border-subtle p-4">
          <p role="alert" className="font-main-ui-body text-content-secondary">
            Available groups could not be loaded. Your selection is unchanged.
          </p>
          <Button
            size="sm"
            prominence="secondary"
            className="mt-3"
            disabled={disabled}
            onClick={() => void options.refetch()}
          >
            Try again
          </Button>
        </div>
      ) : rows.length === 0 ? (
        <div className="mt-4 rounded-xl border border-dashed border-border-default px-4 py-7 text-center">
          <UsersRound className="mx-auto size-5 text-content-muted" aria-hidden="true" />
          <p className="mt-2 font-main-ui-body text-content-muted">
            {search ? "No groups match your search." : "No groups are available."}
          </p>
        </div>
      ) : (
        <div className="mt-3 max-h-72 divide-y divide-border-subtle overflow-y-auto rounded-xl border border-border-subtle bg-surface-raised">
          {rows.map((group) => {
            const checked = selected.has(group.id);
            const limitReached = disabled || (selected.size >= 100 && !checked);
            return (
              <label
                key={group.id}
                className={`flex items-center gap-3 px-4 py-3 transition-colors has-[:focus-visible]:ring-3 has-[:focus-visible]:ring-inset has-[:focus-visible]:ring-focus-ring/30 ${limitReached ? "cursor-not-allowed text-content-disabled" : "cursor-pointer hover:bg-surface-subtle"}`}
              >
                <input
                  type="checkbox"
                  checked={checked}
                  disabled={limitReached}
                  className="size-4 shrink-0 accent-content-primary outline-none"
                  onChange={() => {
                    const next = new Set(selected);
                    if (checked) next.delete(group.id);
                    else next.add(group.id);
                    onChange(next);
                  }}
                />
                <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-surface-subtle text-content-muted">
                  {group.systemKey ? (
                    <ShieldCheck className="size-4" aria-hidden="true" />
                  ) : (
                    <UsersRound className="size-4" aria-hidden="true" />
                  )}
                </span>
                <span className="min-w-0 flex-1 truncate font-main-ui-body text-content-primary">
                  {group.name}
                </span>
                {group.systemKey ? (
                  <Badge
                    variant="outline"
                    className="shrink-0 bg-surface-raised text-content-muted"
                  >
                    System
                  </Badge>
                ) : null}
              </label>
            );
          })}
        </div>
      )}

      {required && selected.size === 0 ? (
        <p role="alert" className="mt-3 font-secondary-body text-status-danger-content">
          Select at least one group.
        </p>
      ) : null}

      {options.data && options.data.totalPages > 1 ? (
        <nav
          aria-label="Source group option pages"
          className="mt-3 flex items-center justify-end gap-2"
        >
          <Button
            size="sm"
            prominence="secondary"
            disabled={disabled || page === 0}
            onClick={() => setPage(page - 1)}
          >
            Previous
          </Button>
          <span className="min-w-24 text-center font-secondary-body tabular-nums text-content-muted">
            Page {page + 1} of {options.data.totalPages}
          </span>
          <Button
            size="sm"
            prominence="secondary"
            disabled={disabled || page + 1 >= options.data.totalPages}
            onClick={() => setPage(page + 1)}
          >
            Next
          </Button>
        </nav>
      ) : null}
    </div>
  );
}
