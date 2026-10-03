import { useAppTranslation } from "@/i18n/use-app-translation";
import { Search, X } from "lucide-react";
import { useEffect, useEffectEvent, useState } from "react";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { Field, FieldLabel } from "@/components/ui/field";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { TextButton } from "@/components/ui/text-button";
import type { UserGroupOption } from "./user-groups-dialog";
import type { UserRoleFilter, UsersSearch } from "./users-search";

type UsersFiltersProps = {
  search: UsersSearch;
  groups?: readonly UserGroupOption[];
  groupsLoading?: boolean;
  /** `settled` marks a search applied as typing paused, which replaces the history entry instead of adding one. */
  onSearchChange: (search: string | undefined, settled?: boolean) => void;
  onRoleChange: (role?: UserRoleFilter) => void;
  onGroupChange: (groupId?: string) => void;
  onClear: () => void;
};

/** The value of the entry that leaves a filter open; a registry select item cannot hold an empty one. */
const ANY = "any";

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

  // Typing applies the search once it pauses, as the Groups and Sources searches do. Only a settled draft applies:
  // Clear or history navigation changes the applied search at once, and must not be undone.
  const settledSearch = useDebouncedValue(searchValue.trim(), 250);
  const applySettledSearch = useEffectEvent((nextSearch: string) => {
    if (nextSearch === appliedSearch || nextSearch !== searchValue.trim()) return;
    onSearchChange(nextSearch || undefined, true);
  });
  useEffect(() => applySettledSearch(settledSearch), [settledSearch]);
  const hasFilters = Boolean(
    searchValue.trim() || search.search || search.status || search.role || search.groupId,
  );
  const selectedGroupAvailable = groups?.some((group) => group.id === search.groupId) ?? false;

  return (
    <form
      aria-label={ui("User filters")}
      className="flex flex-wrap items-end gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        const normalized = searchValue.trim();
        onSearchChange(normalized || undefined);
      }}
    >
      <Field className="min-w-56 flex-1">
        <FieldLabel htmlFor="users-search">{ui("Search users")}</FieldLabel>
        <InputGroup>
          <InputGroupAddon>
            <Search aria-hidden="true" />
          </InputGroupAddon>
          <InputGroupInput
            id="users-search"
            type="search"
            value={searchValue}
            maxLength={200}
            placeholder={ui("Search users…")}
            onChange={(event) => setDraft({ applied: appliedSearch, value: event.target.value })}
          />
        </InputGroup>
      </Field>

      <Field className="w-40">
        <FieldLabel htmlFor="users-role">{ui("Role")}</FieldLabel>
        <Select
          value={search.role ?? ANY}
          onValueChange={(next) =>
            onRoleChange(next === ANY ? undefined : (next as UserRoleFilter))
          }
        >
          <SelectTrigger id="users-role" aria-label={ui("Filter by role")} className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent position="popper" sideOffset={4}>
            <SelectItem value={ANY}>{ui("All roles")}</SelectItem>
            <SelectItem value="OWNER">{ui("Owner")}</SelectItem>
            <SelectItem value="MEMBER">{ui("Member")}</SelectItem>
          </SelectContent>
        </Select>
      </Field>

      {groups ? (
        <Field className="w-48">
          <FieldLabel htmlFor="users-group">{ui("Group")}</FieldLabel>
          <Select
            value={search.groupId ?? ANY}
            disabled={groupsLoading}
            onValueChange={(next) => onGroupChange(next === ANY ? undefined : next)}
          >
            <SelectTrigger id="users-group" aria-label={ui("Filter by group")} className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent position="popper" sideOffset={4}>
              <SelectItem value={ANY}>
                {groupsLoading ? ui("Loading groups…") : ui("All groups")}
              </SelectItem>
              {search.groupId && !selectedGroupAvailable ? (
                <SelectItem value={search.groupId}>{ui("Selected group")}</SelectItem>
              ) : null}
              {groups.map((group) => (
                <SelectItem key={group.id} value={group.id}>
                  {group.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </Field>
      ) : null}

      {hasFilters ? (
        <div className="flex items-center">
          <TextButton
            type="button"
            size="sm"
            onClick={() => {
              setDraft({ applied: appliedSearch, value: "" });
              onClear();
            }}
          >
            <X data-icon="inline-start" aria-hidden="true" />
            {ui("Clear")}
          </TextButton>
        </div>
      ) : null}
    </form>
  );
}
