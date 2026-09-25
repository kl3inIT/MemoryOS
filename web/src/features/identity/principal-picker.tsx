import { useAppTranslation } from "@/i18n/use-app-translation";
import { useDeferredValue, useState } from "react";
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
import { useApplicationSession } from "@/features/identity/application-session-context";
import { searchPrincipals } from "@/lib/hey-api/sdk.gen";
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
  const { actorId, authorizationVersion } = useApplicationSession();
  const [search, setSearch] = useState("");
  const query = useDeferredValue(search.trim());
  const options = useQuery({
    queryKey: ["identity-principals", actorId, authorizationVersion, query],
    enabled: query !== "",
    queryFn: async ({ signal }) =>
      (await searchPrincipals({ query: { search: query, size: 20 }, signal })).data,
  });
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
      className="relative overflow-visible bg-transparent"
    >
      <div className="flex h-10 items-center gap-2 rounded-xl border border-border-default bg-surface-raised px-3 focus-within:border-border-strong">
        <Search aria-hidden="true" className="size-4 shrink-0 text-content-disabled" />
        <CommandPrimitive.Input
          value={search}
          onValueChange={setSearch}
          placeholder={groups ? ui("Thêm người hoặc Group") : ui("Tìm người")}
          className="h-full min-w-0 flex-1 bg-transparent font-main-ui-body outline-none placeholder:text-content-muted"
        />
      </div>
      {search.trim() !== "" && (
        <CommandList className="absolute top-full right-0 left-0 z-50 mt-1 max-h-64 rounded-xl border border-border-subtle bg-surface-overlay p-1 shadow-md">
          {options.isError && (
            <p role="alert" className="px-3 py-4 font-secondary-body text-status-danger-content">
              {ui("Không tải được danh sách người và Group.")}
            </p>
          )}
          {options.isFetching && (
            <p role="status" className="px-3 py-3 font-secondary-body text-content-muted">
              {ui("Đang tìm…")}
            </p>
          )}
          {options.isSuccess && !options.isFetching && (
            <CommandEmpty>{ui("Không tìm thấy kết quả")}</CommandEmpty>
          )}
          {!options.isFetching && people.length > 0 && (
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
          {!options.isFetching && groupRows.length > 0 && (
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
      )}
    </Command>
  );
}
