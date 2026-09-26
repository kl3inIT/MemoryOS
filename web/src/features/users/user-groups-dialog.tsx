import { useAppTranslation } from "@/i18n/use-app-translation";
import { keepPreviousData, useMutation, useQuery } from "@tanstack/react-query";
import { Search, UserCog, Users } from "lucide-react";
import { useMemo, useRef, useState, type RefObject } from "react";
import { Alert, AlertAction, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia } from "@/components/ui/empty";
import { Field, FieldLabel } from "@/components/ui/field";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import { Spinner } from "@/components/ui/spinner";
import { TablePagination } from "@/components/ui/table-pagination";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import {
  listGroupsOptions,
  replaceUserGroupsMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { UserListItem } from "@/lib/hey-api/types.gen";
import { useProblemMessage } from "@/lib/use-problem-message";
import { membershipActionError } from "./user-action-errors";

export type UserGroupOption = {
  id: string;
  name: string;
  systemKey: "ADMIN" | "BASIC" | null;
};

/** The most ordinary Groups one person may belong to. */
const selectionLimit = 100;

type UserGroupsDialogProps = {
  entry: UserListItem;
  restoreFocusRef: RefObject<HTMLElement | null>;
  fallbackFocusRef?: RefObject<HTMLElement | null>;
  onOpenChange: (open: boolean) => void;
  onSaved: () => Promise<void>;
};

export function UserGroupsDialog({
  entry,
  restoreFocusRef,
  fallbackFocusRef,
  onOpenChange,
  onSaved,
}: UserGroupsDialogProps) {
  const ui = useAppTranslation();
  const errorMessage = useProblemMessage();
  const replaceGroups = useMutation(replaceUserGroupsMutation());
  const initialIds = useMemo(
    () => entry.groups.filter((group) => group.systemKey === null).map((group) => group.id),
    [entry.groups],
  );
  const [selectedIds, setSelectedIds] = useState(() => new Set(initialIds));
  const [searchDraft, setSearchDraft] = useState("");
  const search = useDebouncedValue(searchDraft.trim(), 250);
  const [paged, setPaged] = useState({ search, page: 0 });
  // A new search starts again at its first page.
  const page = paged.search === search ? paged.page : 0;
  const searchRef = useRef<HTMLInputElement>(null);
  const actorId = entry.actorId;
  const label = entry.displayName?.trim() || entry.email?.trim() || ui("this user");
  const groupOptions = useQuery({
    ...listGroupsOptions({ query: { search: search || undefined, page, size: 20 } }),
    placeholderData: keepPreviousData,
    retry: false,
  });
  const normalizedSearch = search.toLocaleLowerCase();
  const matchingCurrentGroups = entry.groups.filter(
    (group) =>
      group.systemKey === null &&
      (!normalizedSearch || group.name.toLocaleLowerCase().includes(normalizedSearch)),
  );
  const ordinaryGroups = Array.from(
    new Map(
      [...(groupOptions.data?.items ?? []), ...matchingCurrentGroups]
        .filter((group) => group.systemKey === null)
        .map((group) => [group.id, group]),
    ).values(),
  ).sort((left, right) => left.name.localeCompare(right.name));
  const systemGroups = entry.groups.filter((group) => group.systemKey !== null);
  const initialKey = [...initialIds].sort().join("\u0000");
  const selectedKey = [...selectedIds].sort().join("\u0000");
  const dirty = initialKey !== selectedKey;
  const saving = replaceGroups.isPending;
  const totalPages = groupOptions.data?.totalPages ?? 0;

  function toggle(groupId: string) {
    setSelectedIds((current) => {
      const next = new Set(current);
      if (next.has(groupId)) next.delete(groupId);
      else next.add(groupId);
      return next;
    });
  }

  function save() {
    if (!actorId || !dirty || saving) return;
    replaceGroups.mutate(
      { path: { actorId }, body: { groupIds: [...selectedIds] } },
      {
        onSuccess: async () => {
          onOpenChange(false);
          fallbackFocusRef?.current?.focus();
          await onSaved();
        },
      },
    );
  }

  return (
    <Dialog open onOpenChange={(open) => !saving && onOpenChange(open)}>
      <DialogContent
        className="sm:max-w-xl"
        onOpenAutoFocus={(event) => {
          event.preventDefault();
          searchRef.current?.focus();
        }}
        onCloseAutoFocus={(event) => {
          const target = restoreFocusRef.current?.isConnected
            ? restoreFocusRef.current
            : fallbackFocusRef?.current;
          if (!target?.isConnected) return;
          event.preventDefault();
          target.focus();
        }}
        onEscapeKeyDown={(event) => {
          if (saving) event.preventDefault();
        }}
        onPointerDownOutside={(event) => {
          if (saving) event.preventDefault();
        }}
      >
        <DialogHeader>
          <DialogTitle>{ui("Edit groups")}</DialogTitle>
          <DialogDescription>
            {ui("Choose ordinary group memberships for")} {label}
            {ui(". System memberships are preserved.")}
          </DialogDescription>
        </DialogHeader>

        {systemGroups.length > 0 ? (
          <section aria-labelledby="protected-user-groups" className="flex flex-col gap-2">
            <h2 id="protected-user-groups" className="font-secondary-action text-content-primary">
              {ui("System groups")}
            </h2>
            <div className="divide-y divide-border-subtle overflow-hidden rounded-xl border border-border-subtle">
              {systemGroups.map((group) => (
                <div key={group.id} className="flex items-center gap-3 px-4 py-3">
                  {group.systemKey === "ADMIN" ? (
                    <UserCog className="size-4 shrink-0 text-content-muted" aria-hidden="true" />
                  ) : (
                    <Users className="size-4 shrink-0 text-content-muted" aria-hidden="true" />
                  )}
                  <span className="min-w-0 flex-1 truncate font-main-ui-body text-content-primary">
                    {group.name}
                  </span>
                  <span className="font-secondary-body text-content-muted">{ui("Protected")}</span>
                </div>
              ))}
            </div>
          </section>
        ) : null}

        <section aria-labelledby="ordinary-user-groups" className="flex flex-col gap-3">
          <div className="flex items-center justify-between gap-3">
            <h2 id="ordinary-user-groups" className="font-secondary-action text-content-primary">
              {ui("Ordinary groups")}
            </h2>
            <span className="font-secondary-body tabular-nums text-content-muted">
              {selectedIds.size} {ui("selected")}
            </span>
          </div>
          <InputGroup>
            <InputGroupAddon>
              <Search aria-hidden="true" />
            </InputGroupAddon>
            <InputGroupInput
              ref={searchRef}
              type="search"
              value={searchDraft}
              maxLength={200}
              placeholder={ui("Search groups…")}
              aria-label={ui("Search ordinary groups")}
              onChange={(event) => setSearchDraft(event.target.value)}
            />
          </InputGroup>

          {groupOptions.isPending ? (
            <p
              role="status"
              className="flex items-center gap-2 font-main-ui-body text-content-muted"
            >
              <Spinner aria-hidden="true" />
              {ui("Loading groups")}
            </p>
          ) : groupOptions.isError ? (
            <Alert variant="destructive">
              <AlertDescription>
                {ui("Groups could not be loaded. Existing memberships have not been changed.")}
              </AlertDescription>
              <AlertAction>
                <Button
                  size="sm"
                  prominence="secondary"
                  onClick={() => void groupOptions.refetch()}
                >
                  {ui("Try again")}
                </Button>
              </AlertAction>
            </Alert>
          ) : ordinaryGroups.length === 0 ? (
            <Empty>
              <EmptyHeader>
                <EmptyMedia variant="icon">
                  <Users />
                </EmptyMedia>
                <EmptyDescription>
                  {search
                    ? ui("No groups match your search.")
                    : ui("No ordinary groups are available.")}
                </EmptyDescription>
              </EmptyHeader>
            </Empty>
          ) : (
            <div className="divide-y divide-border-subtle overflow-hidden rounded-xl border border-border-subtle">
              {ordinaryGroups.map((group) => {
                const checked = selectedIds.has(group.id);
                const limitReached = selectedIds.size >= selectionLimit && !checked;
                const id = `user-group-${group.id}`;
                return (
                  <div key={group.id} className="px-4 py-3">
                    <Field orientation="horizontal" data-disabled={limitReached || undefined}>
                      <Checkbox
                        id={id}
                        checked={checked}
                        disabled={limitReached}
                        onCheckedChange={() => toggle(group.id)}
                      />
                      <FieldLabel htmlFor={id} className="min-w-0">
                        <span className="truncate">{group.name}</span>
                      </FieldLabel>
                    </Field>
                  </div>
                );
              })}
            </div>
          )}

          {totalPages > 1 ? (
            <TablePagination
              label={ui("User group option pages")}
              page={page}
              totalPages={totalPages}
              previousDisabled={page === 0 || groupOptions.isPlaceholderData}
              nextDisabled={page + 1 >= totalPages || groupOptions.isPlaceholderData}
              onPrevious={() => setPaged({ search, page: page - 1 })}
              onNext={() => setPaged({ search, page: page + 1 })}
            />
          ) : null}
        </section>

        {replaceGroups.isError ? (
          <Alert variant="destructive">
            <AlertDescription>
              {errorMessage(membershipActionError(replaceGroups.error))}
            </AlertDescription>
          </Alert>
        ) : null}

        <DialogFooter>
          <Button prominence="secondary" disabled={saving} onClick={() => onOpenChange(false)}>
            {ui("Cancel")}
          </Button>
          <Button pending={saving} disabled={!dirty || !actorId} onClick={save}>
            {saving ? ui("Saving groups…") : ui("Save groups")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
