import { useAppTranslation } from "@/i18n/use-app-translation";
import { Search, X } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { TextButton } from "@/components/ui/text-button";
import type { UserGroupOption } from "./user-groups-dialog";
import type { UserRoleFilter, UsersSearch } from "./users-search";

type UsersFiltersProps = {
  search: UsersSearch;
  groups?: readonly UserGroupOption[];
  groupsLoading?: boolean;
  onSearchChange: (search?: string) => void;
  onRoleChange: (role?: UserRoleFilter) => void;
  onGroupChange: (groupId?: string) => void;
  onClear: () => void;
};

export function UsersFilters({
  search,
  groups,
  groupsLoading = false,
  onSearchChange,
  onRoleChange,
  onGroupChange,
  onClear,
}: UsersFiltersProps) {
  const ui = useAppTranslation();

  const appliedSearch = search.search ?? "";
  const [draft, setDraft] = useState({ applied: appliedSearch, value: appliedSearch });
  if (draft.applied !== appliedSearch) {
    setDraft({ applied: appliedSearch, value: appliedSearch });
  }
  const searchValue = draft.applied === appliedSearch ? draft.value : appliedSearch;
  const hasFilters = Boolean(
    searchValue.trim() || search.search || search.status || search.role || search.groupId,
  );
  const selectedGroupAvailable = groups?.some((group) => group.id === search.groupId) ?? false;

  return (
    <form
      aria-label={ui("User filters")}
      className="grid gap-2 sm:grid-cols-2 sm:items-end lg:grid-cols-[minmax(14rem,1fr)_10rem_12rem_auto]"
      onSubmit={(event) => {
        event.preventDefault();
        const normalized = searchValue.trim();
        onSearchChange(normalized || undefined);
      }}
    >
      <label className="grid min-w-0 gap-1.5 font-secondary-action text-content-secondary">
        {ui("Search")}
        <span className="relative block">
          <Search
            className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-content-muted"
            aria-hidden="true"
          />
          <Input
            type="search"
            size="sm"
            value={searchValue}
            maxLength={200}
            placeholder={ui("Search users…")}
            aria-label={ui("Search users")}
            className="bg-surface-sunken pl-9"
            onChange={(event) => setDraft({ applied: appliedSearch, value: event.target.value })}
          />
        </span>
      </label>

      <label className="grid gap-1.5 font-secondary-action text-content-secondary">
        {ui("Role")}
        <Select
          size="sm"
          value={search.role ?? ""}
          aria-label={ui("Filter by role")}
          onChange={(event) =>
            onRoleChange((event.target.value || undefined) as UserRoleFilter | undefined)
          }
        >
          <option value="">{ui("All roles")}</option>
          <option value="OWNER">{ui("Owner")}</option>
          <option value="MEMBER">{ui("Member")}</option>
        </Select>
      </label>

      {groups ? (
        <label className="grid gap-1.5 font-secondary-action text-content-secondary">
          {ui("Group")}
          <Select
            size="sm"
            value={search.groupId ?? ""}
            disabled={groupsLoading}
            aria-label={ui("Filter by group")}
            onChange={(event) => onGroupChange(event.target.value || undefined)}
          >
            <option value="">{groupsLoading ? ui("Loading groups…") : ui("All groups")}</option>
            {search.groupId && !selectedGroupAvailable ? (
              <option value={search.groupId}>{ui("Selected group")}</option>
            ) : null}
            {groups.map((group) => (
              <option key={group.id} value={group.id}>
                {group.name}
              </option>
            ))}
          </Select>
        </label>
      ) : null}

      <div className="flex h-8 items-center gap-3 sm:justify-end">
        <Button type="submit" size="sm" prominence="secondary">
          {ui("Search")}
        </Button>
        {hasFilters ? (
          <TextButton
            type="button"
            size="sm"
            onClick={() => {
              setDraft({ applied: appliedSearch, value: "" });
              onClear();
            }}
          >
            <X aria-hidden="true" />
            {ui("Clear")}
          </TextButton>
        ) : null}
      </div>
    </form>
  );
}
