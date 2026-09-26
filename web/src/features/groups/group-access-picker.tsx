import {
  keepPreviousData,
  useQuery,
  type QueryKey,
  type UseQueryOptions,
} from "@tanstack/react-query";
import { Search, Users } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { Alert, AlertAction, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia } from "@/components/ui/empty";
import { Field, FieldLabel } from "@/components/ui/field";
import { HelpPopover } from "@/components/ui/help-popover";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import { Spinner } from "@/components/ui/spinner";
import { TablePagination } from "@/components/ui/table-pagination";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";

/** Group option shape shared by every capability that associates Groups with a resource. */
export type GroupOption = { id: string; name: string; systemKey?: string | null };
export type GroupOptionPage = { items: GroupOption[]; totalPages: number };

type GroupAccessPickerProps<TPage extends GroupOptionPage, TError, TKey extends QueryKey> = {
  selected: ReadonlySet<string>;
  /** Paged options for the caller's capability, so authorization stays with the owning endpoint. */
  load: (query: {
    search: string;
    page: number;
    size: number;
  }) => UseQueryOptions<TPage, TError, TPage, TKey>;
  description: AppCopy;
  knownGroups?: readonly GroupOption[];
  required?: boolean;
  disabled?: boolean;
  className?: string;
  onChange: (groupIds: Set<string>) => void;
};

const noKnownGroups: readonly GroupOption[] = [];
/** The most Groups one resource may be associated with. */
const selectionLimit = 100;

export function GroupAccessPicker<TPage extends GroupOptionPage, TError, TKey extends QueryKey>({
  selected,
  load,
  description,
  knownGroups = noKnownGroups,
  required = false,
  disabled = false,
  className,
  onChange,
}: GroupAccessPickerProps<TPage, TError, TKey>) {
  const ui = useAppTranslation();

  const [searchDraft, setSearchDraft] = useState("");
  const search = useDebouncedValue(searchDraft.trim(), 250);
  // A new search starts again at its first page.
  const [paged, setPaged] = useState({ search, page: 0 });
  const page = paged.search === search ? paged.page : 0;
  const setPage = (next: number) => setPaged({ search, page: next });
  const options = useQuery({
    ...load({ search, page, size: 25 }),
    placeholderData: keepPreviousData,
    retry: false,
  });

  const totalPages = options.data?.totalPages;
  if (!options.isPlaceholderData && totalPages !== undefined) {
    const lastPage = Math.max(totalPages - 1, 0);
    if (page > lastPage) setPage(lastPage);
  }

  const [rememberedGroups, setRememberedGroups] = useState<Map<string, GroupOption>>(
    () => new Map(),
  );
  const knownById = useMemo(() => {
    const groups = new Map(rememberedGroups);
    for (const group of knownGroups) groups.set(group.id, group);
    for (const group of options.data?.items ?? []) groups.set(group.id, group);
    return groups;
  }, [knownGroups, options.data?.items, rememberedGroups]);
  const ordinarySelected = useMemo(
    () =>
      new Set(
        [...selected].filter((id) => {
          const group = knownById.get(id);
          return !group || !group.systemKey;
        }),
      ),
    [knownById, selected],
  );
  const selectedGroups = [...ordinarySelected]
    .map((groupId) => knownById.get(groupId))
    .filter((group): group is GroupOption => Boolean(group));
  const rows = (options.data?.items ?? []).filter((group) => !group.systemKey);

  // Keep selected metadata across pages without dropping IDs from unloaded pages.
  const rememberedSelection = new Map<string, GroupOption>();
  for (const id of ordinarySelected) {
    const group = knownById.get(id);
    if (group) rememberedSelection.set(id, group);
  }
  if (
    rememberedGroups.size !== rememberedSelection.size ||
    [...rememberedSelection].some(([id, group]) => rememberedGroups.get(id) !== group)
  ) {
    setRememberedGroups(rememberedSelection);
  }

  // Callers may hand in system Groups, which never associate; report the selection without them.
  useEffect(() => {
    if (ordinarySelected.size !== selected.size) onChange(ordinarySelected);
  }, [onChange, ordinarySelected, selected.size]);

  return (
    <div className={cn("flex flex-col gap-3", className)}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-1">
          <h3 className="font-secondary-action text-content-primary">{ui("Access groups")}</h3>
          <HelpPopover label={ui("Access groups")}>
            <p>{ui(description)}</p>
          </HelpPopover>
        </div>
        <span className="font-secondary-body tabular-nums text-content-muted">
          {ordinarySelected.size} {ui("selected")}
        </span>
      </div>

      {selectedGroups.length > 0 ? (
        <div className="flex flex-wrap gap-1" aria-label={ui("Selected groups")}>
          {selectedGroups.map((group) => (
            <Badge key={group.id} variant="secondary">
              {group.name}
            </Badge>
          ))}
          {ordinarySelected.size > selectedGroups.length ? (
            <Badge variant="outline">+{ordinarySelected.size - selectedGroups.length}</Badge>
          ) : null}
        </div>
      ) : null}

      <InputGroup>
        <InputGroupAddon>
          <Search aria-hidden="true" />
        </InputGroupAddon>
        <InputGroupInput
          type="search"
          disabled={disabled}
          value={searchDraft}
          maxLength={200}
          placeholder={ui("Search groups…")}
          aria-label={ui("Search available groups")}
          onChange={(event) => setSearchDraft(event.target.value)}
        />
      </InputGroup>

      {options.isPending ? (
        <p
          role="status"
          className="flex items-center gap-2 px-2 py-5 font-main-ui-body text-content-muted"
        >
          <Spinner aria-hidden="true" />
          {ui("Loading groups")}
        </p>
      ) : options.isError ? (
        <Alert variant="destructive">
          <AlertDescription>
            {ui("Available groups could not be loaded. Your selection is unchanged.")}
          </AlertDescription>
          <AlertAction>
            <Button
              size="sm"
              prominence="secondary"
              disabled={disabled}
              onClick={() => void options.refetch()}
            >
              {ui("Try again")}
            </Button>
          </AlertAction>
        </Alert>
      ) : rows.length === 0 ? (
        <Empty>
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <Users />
            </EmptyMedia>
            <EmptyDescription>
              {search ? ui("No groups match your search.") : ui("No groups are available.")}
            </EmptyDescription>
          </EmptyHeader>
        </Empty>
      ) : (
        <div
          data-slot="group-options"
          className="max-h-72 divide-y divide-border-subtle overflow-y-auto rounded-xl border border-border-subtle bg-surface-raised"
        >
          {rows.map((group) => {
            const checked = ordinarySelected.has(group.id);
            const limitReached = disabled || (ordinarySelected.size >= selectionLimit && !checked);
            const id = `group-option-${group.id}`;
            return (
              <div key={group.id} className="px-4 py-3">
                <Field orientation="horizontal" data-disabled={limitReached || undefined}>
                  <Checkbox
                    id={id}
                    checked={checked}
                    disabled={limitReached}
                    onCheckedChange={() => {
                      const next = new Set(ordinarySelected);
                      if (checked) next.delete(group.id);
                      else next.add(group.id);
                      onChange(next);
                    }}
                  />
                  <FieldLabel htmlFor={id} className="min-w-0 flex-1">
                    <Users className="size-4 shrink-0 text-content-muted" aria-hidden="true" />
                    <span className="truncate">{group.name}</span>
                  </FieldLabel>
                </Field>
              </div>
            );
          })}
        </div>
      )}

      {required && ordinarySelected.size === 0 ? (
        <p role="alert" className="font-secondary-body text-status-danger-content">
          {ui("Select at least one group.")}
        </p>
      ) : null}

      {options.data && options.data.totalPages > 1 ? (
        <TablePagination
          label={ui("Group option pages")}
          page={page}
          totalPages={options.data.totalPages}
          previousDisabled={disabled || page === 0}
          nextDisabled={disabled || page + 1 >= options.data.totalPages}
          onPrevious={() => setPage(page - 1)}
          onNext={() => setPage(page + 1)}
        />
      ) : null}
    </div>
  );
}
