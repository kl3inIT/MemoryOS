import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Command as CommandPrimitive } from "cmdk";
import { Search, Users, X } from "lucide-react";
import { useId, useState } from "react";
import { ClampedList } from "@/components/ui/clamped-list";
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandItem,
  CommandList,
} from "@/components/ui/command";
import { IconButton } from "@/components/ui/icon-button";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { listSourceGroupOptionsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SourceGroup } from "@/lib/hey-api/types.gen";

const optionPageSize = 25;
const searchDelayMs = 250;
const selectionLimit = 100;
/** Rows of chips kept in view before the rest move behind "+N". */
const visibleRows = 3;

/**
 * Groups for a new Source: a search field listing the groups not chosen yet, with the selection
 * as chips below, like the document set Source picker. System groups never associate with a
 * Source, so they are not offered.
 */
export function SourceGroupPicker({
  label,
  placeholder,
  selected,
  disabled = false,
  onChange,
}: {
  label: string;
  placeholder: string;
  selected: ReadonlySet<string>;
  disabled?: boolean;
  onChange: (groupIds: Set<string>) => void;
}) {
  const ui = useAppTranslation();
  const labelId = useId();
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const query = useDebouncedValue(search.trim(), searchDelayMs);
  const [names, setNames] = useState<ReadonlyMap<string, string>>(() => new Map());
  const options = useQuery({
    ...listSourceGroupOptionsOptions({ query: { search: query, page: 0, size: optionPageSize } }),
    placeholderData: keepPreviousData,
    retry: false,
  });
  const groups = (options.data?.items ?? []).filter((group) => !group.systemKey);
  const unselected = groups.filter((group) => !selected.has(group.id));
  const full = selected.size >= selectionLimit;
  const nameOf = (id: string) =>
    names.get(id) ?? groups.find((group) => group.id === id)?.name ?? id;

  function add(group: SourceGroup) {
    setNames((current) => new Map(current).set(group.id, group.name));
    onChange(new Set(selected).add(group.id));
    setSearch("");
  }

  function remove(id: string) {
    const next = new Set(selected);
    next.delete(id);
    onChange(next);
  }

  return (
    <div className="flex flex-col gap-3">
      <span id={labelId} className="text-sm font-medium text-content-primary">
        {label}
      </span>
      {/* cmdk labels its input from this text; without it the field is nameless whatever the placeholder says. */}
      <Command label={label} shouldFilter={false} className="relative overflow-visible">
        <div className="flex h-10 items-center gap-2 rounded-xl border border-border-default bg-surface-raised px-3 focus-within:border-border-strong">
          <Search aria-hidden="true" className="size-4 shrink-0 text-content-disabled" />
          <CommandPrimitive.Input
            value={search}
            disabled={disabled || full}
            aria-labelledby={labelId}
            onValueChange={(next) => {
              setSearch(next);
              setOpen(true);
            }}
            onFocus={() => setOpen(true)}
            onBlur={() => setOpen(false)}
            onKeyDown={(event) => {
              if (event.key === "Escape") setOpen(false);
            }}
            placeholder={full ? ui("Group limit reached.") : placeholder}
            className="h-full min-w-0 flex-1 bg-transparent font-main-ui-body outline-none placeholder:text-content-muted disabled:cursor-not-allowed"
          />
        </div>
        {open && !full ? (
          // The list floats under the field; a press inside it must not blur the field and close it.
          <div
            role="presentation"
            onMouseDown={(event) => event.preventDefault()}
            className="absolute top-full left-0 z-50 mt-1 w-full rounded-xl border border-border-subtle bg-surface-overlay p-1 shadow-md"
          >
            <CommandList>
              {options.isPending ? (
                <p role="status" className="py-6 text-center text-sm text-content-muted">
                  {ui("Loading groups")}
                </p>
              ) : options.isError ? (
                <p role="alert" className="px-3 py-6 text-center text-sm text-content-secondary">
                  {ui("Available groups could not be loaded. Your selection is unchanged.")}
                </p>
              ) : (
                <>
                  <CommandEmpty>
                    {query ? ui("No groups match your search.") : ui("No groups are available.")}
                  </CommandEmpty>
                  <CommandGroup>
                    {unselected.map((group) => (
                      <CommandItem key={group.id} value={group.id} onSelect={() => add(group)}>
                        <Users aria-hidden="true" className="size-4 shrink-0" />
                        <span className="truncate" title={group.name}>
                          {group.name}
                        </span>
                      </CommandItem>
                    ))}
                  </CommandGroup>
                  {options.data && options.data.totalPages > 1 ? (
                    <p className="border-t border-border-subtle px-3 py-2 text-xs text-content-muted">
                      {ui("Type to find more groups.")}
                    </p>
                  ) : null}
                </>
              )}
            </CommandList>
          </div>
        ) : null}
      </Command>
      {selected.size > 0 ? (
        <ClampedList
          maxRows={visibleRows}
          label={ui("Selected groups")}
          items={[...selected].map((id) => (
            <span
              key={id}
              className="flex max-w-full items-center gap-1.5 rounded-xl border border-border-subtle bg-surface-raised py-1 pr-1 pl-2.5 font-secondary-body"
            >
              <Users aria-hidden="true" className="size-4 shrink-0" />
              <span className="truncate" title={nameOf(id)}>
                {nameOf(id)}
              </span>
              <IconButton
                prominence="internal"
                size="sm"
                disabled={disabled}
                aria-label={ui("Remove {{v1}}", { v1: nameOf(id) })}
                onClick={() => remove(id)}
              >
                <X />
              </IconButton>
            </span>
          ))}
        />
      ) : null}
    </div>
  );
}
