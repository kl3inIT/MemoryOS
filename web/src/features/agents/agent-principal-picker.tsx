import { useAppTranslation } from "@/i18n/use-app-translation";
import { useDeferredValue, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { User, Users } from "lucide-react";
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from "@/components/ui/command";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { listChatPersonaShareOptions } from "@/lib/hey-api/sdk.gen";
import {
  personLabel,
  shareOptionsSchema,
  type AgentPerson,
  type AgentRef,
} from "@/features/chat/chat-workspace-api";

export type Principal =
  | { kind: "person"; person: AgentPerson }
  | { kind: "group"; group: AgentRef };

/** Searches active members and ordinary Groups; the server rechecks every selection. */
export function AgentPrincipalPicker({
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
    queryKey: ["chat-persona-share-options", actorId, authorizationVersion, query],
    queryFn: async ({ signal }) =>
      shareOptionsSchema.parse(
        (
          await listChatPersonaShareOptions({
            query: { q: query || undefined, limit: 20 },
            signal,
            throwOnError: true,
          })
        ).data,
      ),
    placeholderData: (previous) => previous,
  });
  const people = options.data?.people.filter((person) => !exclude.has(person.actorId)) ?? [];
  const groupRows = groups
    ? (options.data?.groups.filter((group) => !exclude.has(group.id)) ?? [])
    : [];
  return (
    <Command shouldFilter={false} className="rounded-xl border border-border-default">
      <CommandInput
        value={search}
        onValueChange={setSearch}
        placeholder={groups ? ui("Tìm người hoặc Group") : ui("Tìm người")}
      />
      <CommandList className="max-h-56">
        {options.isError && (
          <p role="alert" className="px-3 py-4 text-sm text-status-danger-content">
            {ui("Không tải được danh sách người và Group.")}
          </p>
        )}
        {options.isSuccess && <CommandEmpty>{ui("Không tìm thấy kết quả")}</CommandEmpty>}
        {people.length > 0 && (
          <CommandGroup heading={ui("Người")}>
            {people.map((person) => (
              <CommandItem
                key={person.actorId}
                value={`person-${person.actorId}`}
                onSelect={() => onPick({ kind: "person", person })}
              >
                <User aria-hidden="true" className="size-4 text-content-muted" />
                <span className="min-w-0 flex-1 truncate">{personLabel(person)}</span>
                {person.name && person.email && (
                  <span className="truncate text-xs text-content-muted">{person.email}</span>
                )}
              </CommandItem>
            ))}
          </CommandGroup>
        )}
        {groupRows.length > 0 && (
          <CommandGroup heading={ui("Group")}>
            {groupRows.map((group) => (
              <CommandItem
                key={group.id}
                value={`group-${group.id}`}
                onSelect={() => onPick({ kind: "group", group })}
              >
                <Users aria-hidden="true" className="size-4 text-content-muted" />
                <span className="min-w-0 flex-1 truncate">{group.name}</span>
              </CommandItem>
            ))}
          </CommandGroup>
        )}
      </CommandList>
    </Command>
  );
}
