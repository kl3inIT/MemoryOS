import { useAppTranslation } from "@/i18n/use-app-translation";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Command as CommandPrimitive } from "cmdk";
import { Search } from "lucide-react";
import { PersonAvatar } from "@/components/composites/person-avatar";
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandItem,
  CommandList,
} from "@/components/ui/command";
import { InputGroup, InputGroupAddon } from "@/components/ui/input-group";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { searchPrincipalsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import { personLabel, type Person, type NamedRef } from "@/features/identity/principals";

export type Principal = { kind: "person"; person: Person } | { kind: "group"; group: NamedRef };

/**
 * An invite field for active members and ordinary Groups; results appear only while typing (Perplexity, GitBook).
 * The server rechecks every selection.
 */
export function PrincipalPicker({
  onPick,
  exclude,
  groups = true,
}: {
  onPick: (principal: Principal) => void;
  exclude: Set<string>;
  groups?: boolean;
}) {
  const ui = useAppTranslation();
  const [search, setSearch] = useState("");
  const query = useDebouncedValue(search.trim(), 250);
  const options = useQuery({
    ...searchPrincipalsOptions({ query: { search: query, size: 20 } }),
    enabled: query !== "",
  });
  // Results of an older search stay hidden until the typed one arrives.
  const searching = options.isFetching || query !== search.trim();
  const people = options.data?.people.filter((person) => !exclude.has(person.actorId)) ?? [];
  const groupRows = groups
    ? (options.data?.groups.filter((group) => !exclude.has(group.id)) ?? [])
    : [];
  const pick = (principal: Principal) => {
    setSearch("");
    onPick(principal);
  };
  return (
    // cmdk labels its input from this text; without it the field is nameless whatever the placeholder says.
    <Command
      label={groups ? ui("Thêm người hoặc Group") : ui("Tìm người")}
      shouldFilter={false}
      className="relative overflow-visible"
    >
      <InputGroup>
        <InputGroupAddon>
          <Search aria-hidden="true" />
        </InputGroupAddon>
        <CommandPrimitive.Input
          data-slot="input-group-control"
          value={search}
          onValueChange={setSearch}
          placeholder={groups ? ui("Thêm người hoặc Group") : ui("Tìm người")}
          className="h-full min-w-0 flex-1 bg-transparent pr-2.5 font-main-ui-body outline-none placeholder:text-content-muted"
        />
      </InputGroup>
      {search.trim() !== "" && (
        <div className="absolute top-full right-0 left-0 z-50 mt-1 rounded-xl border border-border-subtle bg-surface-overlay p-1 shadow-md">
          <CommandList className="max-h-64">
            {options.isError && (
              <p role="alert" className="px-3 py-4 font-secondary-body text-status-danger-content">
                {ui("Không tải được danh sách người và Group.")}
              </p>
            )}
            {searching && (
              <p role="status" className="px-3 py-3 font-secondary-body text-content-muted">
                {ui("Đang tìm…")}
              </p>
            )}
            {options.isSuccess && !searching && (
              <CommandEmpty>{ui("Không tìm thấy kết quả")}</CommandEmpty>
            )}
            {!searching && people.length > 0 && (
              <CommandGroup heading={ui("Người")}>
                {people.map((person) => (
                  <CommandItem
                    key={person.actorId}
                    value={`person-${person.actorId}`}
                    onSelect={() => pick({ kind: "person", person })}
                  >
                    <PersonAvatar name={personLabel(person)} seed={person.actorId} size="sm" />
                    <span className="min-w-0 flex-1 truncate">{personLabel(person)}</span>
                    {person.name && person.email && (
                      <span className="truncate font-secondary-body text-content-muted">
                        {person.email}
                      </span>
                    )}
                  </CommandItem>
                ))}
              </CommandGroup>
            )}
            {!searching && groupRows.length > 0 && (
              <CommandGroup heading={ui("Group")}>
                {groupRows.map((group) => (
                  <CommandItem
                    key={group.id}
                    value={`group-${group.id}`}
                    onSelect={() => pick({ kind: "group", group })}
                  >
                    <PersonAvatar name={group.name} kind="group" size="sm" />
                    <span className="min-w-0 flex-1 truncate">{group.name}</span>
                  </CommandItem>
                ))}
              </CommandGroup>
            )}
          </CommandList>
        </div>
      )}
    </Command>
  );
}
