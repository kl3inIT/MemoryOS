import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Check, ChevronDown, X } from "lucide-react";
import { useEffect, useId, useState } from "react";
import { Badge } from "@/components/ui/badge";
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from "@/components/ui/command";
import { Popover, PopoverAnchor, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { listSourceGroupOptionsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SourceGroup } from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";

const optionPageSize = 25;
const searchDelayMs = 250;
const selectionLimit = 100;

/**
 * Groups for a new Source: the chosen groups as chips in the field, searchable options in a
 * popover. System groups never associate with a Source, so they are not offered.
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
  const [query, setQuery] = useState("");
  const [names, setNames] = useState<ReadonlyMap<string, string>>(() => new Map());
  useEffect(() => {
    const timer = setTimeout(() => setQuery(search.trim()), searchDelayMs);
    return () => clearTimeout(timer);
  }, [search]);
  const options = useQuery({
    ...listSourceGroupOptionsOptions({ query: { search: query, page: 0, size: optionPageSize } }),
    placeholderData: keepPreviousData,
    retry: false,
  });
  const groups = (options.data?.items ?? []).filter((group) => !group.systemKey);
  const nameOf = (id: string) =>
    names.get(id) ?? groups.find((group) => group.id === id)?.name ?? id;

  function toggle(group: SourceGroup) {
    const next = new Set(selected);
    if (next.has(group.id)) next.delete(group.id);
    else next.add(group.id);
    setNames((current) => new Map(current).set(group.id, group.name));
    onChange(next);
  }

  function remove(id: string) {
    const next = new Set(selected);
    next.delete(id);
    onChange(next);
  }

  return (
    <div className="space-y-2">
      <span id={labelId} className="text-sm font-medium text-content-primary">
        {label}
      </span>
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverAnchor asChild>
          <div
            className={cn(
              "flex min-h-[var(--control-height-md)] w-full flex-wrap items-center gap-1.5 rounded-lg border border-border-subtle bg-surface-raised py-1 pr-1 pl-2 sm:max-w-md",
              disabled && "opacity-60",
            )}
          >
            {[...selected].map((id) => (
              <Badge key={id} variant="secondary" className="gap-1 pr-0.5">
                {nameOf(id)}
                <button
                  type="button"
                  disabled={disabled}
                  aria-label={ui("Remove {{v1}}", { v1: nameOf(id) })}
                  className="rounded-sm p-0.5 hover:bg-surface-subtle focus-visible:outline-2 focus-visible:outline-focus-ring disabled:pointer-events-none"
                  onClick={() => remove(id)}
                >
                  <X className="size-3" aria-hidden="true" />
                </button>
              </Badge>
            ))}
            <PopoverTrigger asChild>
              <button
                type="button"
                disabled={disabled}
                aria-labelledby={labelId}
                className="flex min-h-8 min-w-24 flex-1 items-center justify-between gap-2 rounded-md px-1 text-left text-sm text-content-muted outline-none focus-visible:ring-2 focus-visible:ring-focus-ring disabled:cursor-not-allowed"
              >
                {selected.size ? null : <span>{placeholder}</span>}
                <ChevronDown className="ml-auto size-4 shrink-0" aria-hidden="true" />
              </button>
            </PopoverTrigger>
          </div>
        </PopoverAnchor>
        <PopoverContent align="start" className="w-(--radix-popover-trigger-width) min-w-72 p-0">
          <Command shouldFilter={false}>
            <CommandInput
              value={search}
              onValueChange={setSearch}
              placeholder={ui("Search groups…")}
            />
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
                    {groups.map((group) => {
                      const checked = selected.has(group.id);
                      return (
                        <CommandItem
                          key={group.id}
                          value={group.id}
                          aria-checked={checked}
                          disabled={!checked && selected.size >= selectionLimit}
                          onSelect={() => toggle(group)}
                        >
                          <span
                            aria-hidden="true"
                            className={cn(
                              "grid size-4 shrink-0 place-items-center rounded-sm border",
                              checked
                                ? "border-primary bg-primary text-primary-foreground"
                                : "border-border-default",
                            )}
                          >
                            {checked ? <Check className="size-3" /> : null}
                          </span>
                          <span className="truncate">{group.name}</span>
                        </CommandItem>
                      );
                    })}
                  </CommandGroup>
                  {options.data && options.data.totalPages > 1 ? (
                    <p className="border-t border-border-subtle px-3 py-2 text-xs text-content-muted">
                      {ui("Type to find more groups.")}
                    </p>
                  ) : null}
                </>
              )}
            </CommandList>
          </Command>
        </PopoverContent>
      </Popover>
    </div>
  );
}
