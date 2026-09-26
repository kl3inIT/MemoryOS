import { useAppTranslation } from "@/i18n/use-app-translation";
import { Search, X } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Field, FieldLabel } from "@/components/ui/field";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import { NativeSelect } from "@/components/ui/native-select";
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
      className="flex flex-wrap items-end gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        const normalized = searchValue.trim();
        onSearchChange(normalized || undefined);
      }}
    >
      <Field className="min-w-56 flex-1">
        <FieldLabel htmlFor="users-search">{ui("Search")}</FieldLabel>
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
            aria-label={ui("Search users")}
            onChange={(event) => setDraft({ applied: appliedSearch, value: event.target.value })}
          />
        </InputGroup>
      </Field>

      <Field className="w-40">
        <FieldLabel htmlFor="users-role">{ui("Role")}</FieldLabel>
        <NativeSelect
          id="users-role"
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
        </NativeSelect>
      </Field>

      {groups ? (
        <Field className="w-48">
          <FieldLabel htmlFor="users-group">{ui("Group")}</FieldLabel>
          <NativeSelect
            id="users-group"
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
          </NativeSelect>
        </Field>
      ) : null}

      <div className="flex items-center gap-3">
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
            <X data-icon="inline-start" aria-hidden="true" />
            {ui("Clear")}
          </TextButton>
        ) : null}
      </div>
    </form>
  );
}
